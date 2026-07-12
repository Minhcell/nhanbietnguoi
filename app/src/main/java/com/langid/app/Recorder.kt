package com.langid.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * Ghi âm ra WAV 16 kHz / mono / 16-bit PCM.
 *
 * Vì sao WAV chứ không phải .m4a:
 *  - Whisper (OpenAI) nhận WAV  ✓
 *  - Gemini (Google) nhận audio/wav  ✓
 *  - .m4a thì Gemini không chắc parse được → dùng WAV cho cả hai nhà cung cấp.
 *
 * Dung lượng: 32 KB/giây → 15 giây ≈ 480 KB. Thoải mái cho cả 2 API.
 */
class Recorder(private val context: Context) {

    companion object {
        private const val RATE = 16000
        private const val CHANNELS = 1
        private const val BITS = 16
    }

    private var record: AudioRecord? = null
    private var writer: Thread? = null
    @Volatile private var running = false
    private var outFile: File? = null

    /** Mức âm thanh 0.0 – 1.0, dùng để vẽ thanh báo đang nghe được tiếng. */
    @Volatile var level: Float = 0f
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        stop()

        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val bufSize = minBuf * 2

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: Exception) { return false }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return false
        }

        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.wav")
        outFile = file
        record = rec
        running = true

        rec.startRecording()

        writer = thread(start = true) {
            file.outputStream().use { out ->
                out.write(ByteArray(44))            // chỗ trống cho header, ghi đè lúc kết thúc

                val buf = ByteArray(bufSize)
                var total = 0L

                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        out.write(buf, 0, n)
                        total += n
                        level = peak(buf, n)
                    }
                }
                out.flush()

                // Quay lại ghi header WAV với đúng độ dài dữ liệu
                runCatching { writeWavHeader(file, total) }
            }
        }
        return true
    }

    /** Dừng ghi. Trả về file WAV, hoặc null nếu quá ngắn / lỗi. */
    fun stop(): File? {
        if (!running) return null
        running = false
        level = 0f

        runCatching {
            record?.stop()
            record?.release()
        }
        record = null

        runCatching { writer?.join(2000) }
        writer = null

        val f = outFile
        outFile = null

        // dưới ~0.5 giây (44 + 16000 byte) coi như hỏng
        return f?.takeIf { it.exists() && it.length() > 16_000 }
    }

    fun isRecording(): Boolean = running

    // ── Header WAV chuẩn 44 byte ──────────────────────────────
    private fun writeWavHeader(file: File, dataLen: Long) {
        val byteRate = RATE * CHANNELS * BITS / 8
        val blockAlign = CHANNELS * BITS / 8
        val riffLen = dataLen + 36

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write("RIFF".toByteArray())
            raf.write(le32(riffLen.toInt()))
            raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray())
            raf.write(le32(16))                 // kích thước khối fmt
            raf.write(le16(1))                  // 1 = PCM
            raf.write(le16(CHANNELS))
            raf.write(le32(RATE))
            raf.write(le32(byteRate))
            raf.write(le16(blockAlign))
            raf.write(le16(BITS))
            raf.write("data".toByteArray())
            raf.write(le32(dataLen.toInt()))
        }
    }

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(),
        ((v shr 24) and 0xff).toByte()
    )

    private fun le16(v: Int) = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte()
    )

    /** Biên độ lớn nhất trong buffer, chuẩn hoá về 0..1 */
    private fun peak(buf: ByteArray, n: Int): Float {
        var max = 0
        var i = 0
        while (i < n - 1) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
            val a = if (s < 0) -s else s
            if (a > max) max = a
            i += 2
        }
        return (max / 32768f).coerceIn(0f, 1f)
    }
}

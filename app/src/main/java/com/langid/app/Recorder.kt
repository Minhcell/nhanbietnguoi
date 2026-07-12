package com.langid.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Ghi âm giọng nói ra file .m4a (AAC trong container MP4).
 * Whisper API chấp nhận trực tiếp định dạng này nên không cần convert.
 */
class Recorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start(): Boolean {
        stop() // dọn phiên cũ nếu còn

        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        outputFile = file

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // 16 kHz mono là chuẩn tối ưu cho nhận dạng giọng nói
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setAudioEncodingBitRate(64000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            true
        } catch (e: Exception) {
            runCatching { rec.release() }
            recorder = null
            outputFile = null
            false
        }
    }

    /** Dừng ghi âm, trả về file kết quả (null nếu lỗi hoặc chưa ghi). */
    fun stop(): File? {
        val rec = recorder ?: return null
        recorder = null
        return try {
            rec.stop()
            rec.release()
            outputFile?.takeIf { it.exists() && it.length() > 1000 }
        } catch (e: Exception) {
            // stop() ném lỗi khi đoạn ghi quá ngắn -> file hỏng
            runCatching { rec.release() }
            outputFile?.delete()
            null
        }
    }

    fun isRecording(): Boolean = recorder != null
}

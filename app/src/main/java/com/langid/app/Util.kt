package com.langid.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import okhttp3.Request
import okhttp3.OkHttpClient
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * Nén ảnh về tối đa 1600px cạnh dài và xoay đúng chiều theo EXIF,
 * rồi mã hoá base64 để gửi lên Claude Vision.
 * Ảnh giấy tờ cần đủ nét để đọc chữ nên không nén quá mạnh (quality 88).
 */
fun uriToBase64(ctx: Context, uri: Uri): ImageEvidence? = try {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw Exception("không đọc được ảnh")

    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw Exception("không giải mã được ảnh")

    // xoay theo EXIF (ảnh chụp dọc hay bị nằm ngang)
    val orientation = ctx.contentResolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
    } ?: ExifInterface.ORIENTATION_NORMAL

    val deg = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (deg != 0f) {
        bmp = Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height,
            Matrix().apply { postRotate(deg) }, true
        )
    }

    val max = 1600
    val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
    if (scale < 1f) {
        bmp = Bitmap.createScaledBitmap(
            bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
        )
    }

    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
    ImageEvidence(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), "image/jpeg")
} catch (e: Exception) {
    null
}

fun newImageFile(ctx: Context): File =
    File(ctx.cacheDir, "shot_${System.currentTimeMillis()}.jpg")

/**
 * Phát âm HOẠT ĐỘNG NHƯ GOOGLE DỊCH.
 *
 * Không dùng giọng đọc cài trên máy (Xiaomi hay câm với Bengali/Urdu),
 * mà tải thẳng audio MP3 từ endpoint TTS của Google — đúng thứ Google Dịch dùng —
 * rồi phát bằng MediaPlayer. Nhờ vậy MỌI ngôn ngữ đều phát được, KHÔNG cần cài gì.
 *
 * Cần mạng (app vốn đã cần mạng để dịch nên không phát sinh yêu cầu mới).
 * Nếu mạng lỗi -> tự lùi về giọng đọc của máy.
 */
class Speaker(context: Context) {

    private val appCtx = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var player: MediaPlayer? = null

    // Giọng đọc máy — chỉ dùng dự phòng khi mạng lỗi
    @Volatile private var ttsReady = false
    private val tts = TextToSpeech(appCtx) { ttsReady = it == TextToSpeech.SUCCESS }

    private val http = OkHttpClient.Builder()
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * @param langTag BCP-47 (vd "bn-BD", "vi-VN", "ur-PK")
     * @param onResult null = đang/đã phát; chuỗi = lý do không phát được
     */
    fun speak(text: String, langTag: String, onResult: (String?) -> Unit = {}) {
        if (text.isBlank()) { onResult("Không có nội dung để đọc."); return }

        // Âm lượng đa phương tiện = 0 là nguyên nhân câm hay gặp nhất
        val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            onResult("Âm lượng đa phương tiện đang ở 0 — tăng âm lượng (nút volume) rồi bấm lại.")
            return
        }

        val tl = googleCode(langTag)

        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { fetchGoogleTts(text, tl) }
                playFile(file, onResult)
            } catch (e: Exception) {
                // Mạng lỗi -> thử giọng đọc của máy
                deviceFallback(text, langTag, onResult)
            }
        }
    }

    // ── Tải MP3 từ Google (giống Google Dịch) ─────────────────
    private fun fetchGoogleTts(text: String, tl: String): File {
        val out = File(appCtx.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
        out.outputStream().use { os ->
            // endpoint giới hạn ~200 ký tự/lần -> cắt nhỏ rồi nối lại
            for (chunk in chunk(text, 190)) {
                val url = "https://translate.google.com/translate_tts" +
                        "?ie=UTF-8&client=tw-ob&tl=$tl" +
                        "&q=" + java.net.URLEncoder.encode(chunk, "UTF-8")
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .header("Referer", "https://translate.google.com/")
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("TTS ${r.code}")
                    r.body?.byteStream()?.copyTo(os) ?: throw RuntimeException("rỗng")
                }
            }
        }
        if (out.length() < 500) throw RuntimeException("audio rỗng")
        return out
    }

    private fun playFile(file: File, onResult: (String?) -> Unit) {
        release()
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        try {
            mp.setDataSource(file.path)
            mp.setOnPreparedListener { it.start(); onResult(null) }
            mp.setOnCompletionListener { runCatching { file.delete() }; release() }
            mp.setOnErrorListener { _, _, _ ->
                onResult("Không phát được audio."); release(); true
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            release()
            onResult("Lỗi phát audio: ${e.message}")
        }
    }

    // ── Dự phòng: giọng đọc của máy ───────────────────────────
    private fun deviceFallback(text: String, langTag: String, onResult: (String?) -> Unit) {
        if (!ttsReady) {
            onResult("Mất mạng và máy chưa có bộ đọc offline. Bật mạng rồi thử lại.")
            return
        }
        val loc = Locale.forLanguageTag(langTag.ifBlank { "en-US" })
        val avail = tts.isLanguageAvailable(loc)
        if (avail < TextToSpeech.LANG_AVAILABLE) {
            onResult("Mất mạng, và máy chưa cài giọng đọc offline cho ngôn ngữ này.")
            return
        }
        tts.language = loc
        tts.setSpeechRate(0.9f)
        val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fb")
        onResult(if (r == TextToSpeech.SUCCESS) null else "Không phát được (offline).")
    }

    // ── Tiện ích ──────────────────────────────────────────────
    /** Cắt văn bản thành đoạn <= max ký tự, không cắt giữa từ. */
    private fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (word in text.split(" ")) {
            if (sb.length + word.length + 1 > max) {
                if (sb.isNotEmpty()) { out.add(sb.toString().trim()); sb.clear() }
            }
            sb.append(word).append(" ")
        }
        if (sb.isNotEmpty()) out.add(sb.toString().trim())
        return out
    }

    /** BCP-47 -> mã ngôn ngữ Google Dịch (tham số tl). */
    private fun googleCode(tag: String): String {
        val base = tag.substringBefore('-').lowercase()
        return when (base) {
            "zh" -> "zh-CN"
            "yue" -> "zh-TW"
            "fil" -> "tl"
            "nb", "nn" -> "no"
            "he" -> "iw"
            "jv" -> "jw"
            else -> base
        }
    }

    private fun release() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    fun stop() { release(); runCatching { tts.stop() } }

    fun shutdown() {
        release()
        runCatching { tts.stop(); tts.shutdown() }
        scope.cancel()
    }
}

/** Mở màn hình cài thêm gói giọng đọc offline của Android (dùng khi mất mạng). */
fun openInstallVoice(ctx: Context) {
    val i = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(i) }
    catch (e: Exception) {
        runCatching {
            ctx.startActivity(
                Intent("com.android.settings.TTS_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

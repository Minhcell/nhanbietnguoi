package com.langid.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
 * Phát âm bằng TextToSpeech.
 *
 * Bản cũ bị câm vì 2 lý do:
 *  1. TTS khởi tạo BẤT ĐỒNG BỘ — bấm loa sớm quá thì lệnh rơi mất.
 *  2. Máy không có giọng đọc cho ngôn ngữ đó (Bengali, Urdu... Xiaomi không cài sẵn)
 *     -> setLanguage() trả LANG_NOT_SUPPORTED và TTS im lặng, KHÔNG báo lỗi.
 *
 * Bản này: xếp hàng chờ khởi tạo xong, dò locale theo nhiều bậc,
 * và luôn báo lại kết quả qua onResult để giao diện hiển thị được.
 */
class Speaker(context: Context) {

    private val appCtx = context.applicationContext

    @Volatile private var ready = false
    private var tts: TextToSpeech? = null

    // Lệnh bấm trước khi TTS kịp khởi tạo -> giữ lại, chạy sau
    private var queued: Job? = null

    private data class Job(
        val text: String,
        val langTag: String,
        val onResult: (String?) -> Unit   // null = phát được; khác null = thông báo lỗi
    )

    init {
        tts = TextToSpeech(appCtx) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setSpeechRate(0.9f)   // chậm hơn chút cho người nghe kịp
                tts?.setPitch(1.0f)
            }
            val j = queued
            queued = null
            if (j != null) {
                if (ready) doSpeak(j)
                else j.onResult("Máy không khởi động được bộ đọc (TTS). Vào CH Play cài \"Google Text-to-Speech\".")
            }
        }
    }

    /**
     * @param onResult null = đã phát; chuỗi = lý do không phát được (hiển thị cho người dùng).
     */
    fun speak(text: String, langTag: String, onResult: (String?) -> Unit = {}) {
        if (text.isBlank()) { onResult("Không có nội dung để đọc."); return }

        val job = Job(text, langTag.ifBlank { "en-US" }, onResult)

        if (!ready) {
            queued = job            // TTS chưa sẵn sàng -> chờ init xong sẽ chạy
            return
        }
        doSpeak(job)
    }

    private fun doSpeak(job: Job) {
        val engine = tts
        if (engine == null) {
            job.onResult("Bộ đọc chưa sẵn sàng.")
            return
        }

        val locale = resolveLocale(engine, job.langTag)
        if (locale == null) {
            val base = job.langTag.substringBefore('-')
            job.onResult(
                "Máy chưa có giọng đọc cho ngôn ngữ này ($base). " +
                "Bấm \"CÀI GIỌNG ĐỌC\" để tải thêm."
            )
            return
        }

        engine.language = locale
        engine.stop()

        val r = engine.speak(
            job.text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "utt_${System.currentTimeMillis()}"
        )

        if (r == TextToSpeech.SUCCESS) job.onResult(null)
        else job.onResult("Bộ đọc từ chối phát. Thử lại hoặc kiểm tra âm lượng.")
    }

    /**
     * Dò locale theo 3 bậc, lấy cái đầu tiên máy hỗ trợ:
     *   1. Đúng thẻ đầy đủ   (bn-BD)
     *   2. Chỉ mã ngôn ngữ   (bn)      -> bắt được bn-IN nếu có
     *   3. Quét toàn bộ voice đã cài, tìm voice cùng mã ngôn ngữ
     */
    private fun resolveLocale(engine: TextToSpeech, tag: String): Locale? {
        val full = Locale.forLanguageTag(tag)
        if (isOk(engine, full)) return full

        val base = tag.substringBefore('-')
        if (base.isNotBlank()) {
            val onlyLang = Locale.forLanguageTag(base)
            if (isOk(engine, onlyLang)) return onlyLang

            // quét voice đã cài trên máy
            val v = runCatching {
                engine.voices?.firstOrNull { it.locale?.language == base }
            }.getOrNull()
            if (v?.locale != null && isOk(engine, v.locale)) return v.locale
        }
        return null
    }

    private fun isOk(engine: TextToSpeech, loc: Locale): Boolean = try {
        val r = engine.isLanguageAvailable(loc)
        r == TextToSpeech.LANG_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    } catch (e: Exception) { false }

    fun stop() { runCatching { tts?.stop() } }

    fun shutdown() { runCatching { tts?.stop(); tts?.shutdown() } }
}

/** Mở màn hình cài thêm gói giọng đọc của Android. */
fun openInstallVoice(ctx: Context) {
    val i = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(i)
    } catch (e: Exception) {
        // Máy không có Activity này -> mở thẳng cài đặt TTS
        runCatching {
            ctx.startActivity(
                Intent("com.android.settings.TTS_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

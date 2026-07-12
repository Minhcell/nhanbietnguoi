package com.langid.app

import android.content.Context
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
 * Phát âm bằng TTS. langTag là BCP-47 (vd "bn-IN", "hi-IN", "vi-VN").
 * Nhiều máy không cài sẵn giọng Bengali/Urdu -> onMissing() được gọi để báo người dùng.
 */
class Speaker(ctx: Context) {
    private var ready = false
    private var pending: Pair<String, String>? = null

    private val tts = TextToSpeech(ctx) { status ->
        ready = status == TextToSpeech.SUCCESS
        pending?.let { (t, l) -> if (ready) speak(t, l) }
        pending = null
    }

    fun speak(text: String, langTag: String, onMissing: (() -> Unit)? = null) {
        if (text.isBlank()) return
        if (!ready) { pending = text to langTag; return }

        val loc = Locale.forLanguageTag(langTag.ifBlank { "en-US" })
        val res = tts.setLanguage(loc)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            onMissing?.invoke()
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt")
    }

    fun shutdown() = runCatching { tts.stop(); tts.shutdown() }
}

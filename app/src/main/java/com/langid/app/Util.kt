package com.langid.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Looper
import android.os.Handler
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
 * Phát âm bằng TextToSpeech — bản chống câm.
 *
 * Vì sao bản trước bấm loa vẫn im mà KHÔNG báo lỗi:
 *   isLanguageAvailable() trả "có" ngay cả khi giọng đọc là loại tải qua mạng
 *   CHƯA cài. speak() khi đó trả SUCCESS nhưng thực tế không ra tiếng.
 *
 * Bản này lắng nghe KẾT QUẢ TỔNG HỢP THẬT qua UtteranceProgressListener:
 *   - onStart  -> đang phát (ổn)
 *   - onDone   -> phát xong (ổn)
 *   - onError  -> câm thật -> báo cho người dùng cài giọng đọc
 * Mọi callback được đẩy về luồng chính để hiện Toast an toàn.
 */
class Speaker(context: Context) {

    private val appCtx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ready = false
    private var tts: TextToSpeech? = null

    // map utteranceId -> callback báo kết quả
    private val callbacks = java.util.concurrent.ConcurrentHashMap<String, (String?) -> Unit>()
    @Volatile private var startedIds = java.util.Collections.synchronizedSet(HashSet<String>())

    private var queued: Triple<String, String, (String?) -> Unit>? = null

    init {
        tts = TextToSpeech(appCtx) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setSpeechRate(0.9f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        if (id != null) startedIds.add(id)
                    }
                    override fun onDone(id: String?) {
                        val cb = callbacks.remove(id)
                        startedIds.remove(id)
                        // onDone mà chưa từng onStart => nhiều máy = câm
                        main.post { cb?.invoke(null) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        val cb = callbacks.remove(id)
                        startedIds.remove(id)
                        main.post { cb?.invoke("Máy không phát được giọng này. Bấm CÀI GIỌNG ĐỌC để tải.") }
                    }
                    override fun onError(id: String?, code: Int) {
                        val cb = callbacks.remove(id)
                        startedIds.remove(id)
                        main.post { cb?.invoke("Máy không phát được giọng này (mã $code). Bấm CÀI GIỌNG ĐỌC để tải.") }
                    }
                })
            }
            queued?.let { (t, l, cb) ->
                queued = null
                if (ready) doSpeak(t, l, cb)
                else main.post { cb("Không khởi động được bộ đọc (TTS). Cài 'Speech Services by Google' từ CH Play.") }
            }
        }
    }

    fun speak(text: String, langTag: String, onResult: (String?) -> Unit = {}) {
        if (text.isBlank()) { onResult("Không có nội dung để đọc."); return }
        val tag = langTag.ifBlank { "en-US" }
        if (!ready) { queued = Triple(text, tag, onResult); return }
        doSpeak(text, tag, onResult)
    }

    private fun doSpeak(text: String, langTag: String, onResult: (String?) -> Unit) {
        val engine = tts ?: run { onResult("Bộ đọc chưa sẵn sàng."); return }

        val locale = resolveLocale(engine, langTag)
        if (locale == null) {
            val base = langTag.substringBefore('-')
            onResult("Máy chưa có giọng đọc cho ngôn ngữ này ($base). Bấm CÀI GIỌNG ĐỌC để tải.")
            return
        }

        // Kiểm tra âm lượng đa phương tiện — TTS phát qua kênh MUSIC.
        // Âm lượng 0 là nguyên nhân "câm" rất hay gặp mà không ai để ý.
        val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            onResult("Âm lượng đa phương tiện đang ở 0 — hãy tăng âm lượng (nút volume) rồi bấm lại.")
            return
        }

        engine.language = locale
        engine.stop()

        val id = "utt_${System.currentTimeMillis()}"
        callbacks[id] = onResult

        val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            onResult("Bộ đọc từ chối phát. Kiểm tra âm lượng nhạc/đa phương tiện.")
            return
        }

        // Nếu sau 4 giây mà chưa hề onStart -> coi như câm ngầm, báo người dùng
        main.postDelayed({
            if (callbacks.containsKey(id) && id !in startedIds) {
                callbacks.remove(id)
                onResult("Không nghe thấy tiếng? Giọng đọc cho ngôn ngữ này có thể chưa cài — bấm CÀI GIỌNG ĐỌC, hoặc tăng âm lượng đa phương tiện.")
            }
        }, 4000)
    }

    private fun resolveLocale(engine: TextToSpeech, tag: String): Locale? {
        val full = Locale.forLanguageTag(tag)
        if (isOk(engine, full)) return full

        val base = tag.substringBefore('-')
        if (base.isNotBlank()) {
            val onlyLang = Locale.forLanguageTag(base)
            if (isOk(engine, onlyLang)) return onlyLang
            val v = runCatching { engine.voices?.firstOrNull { it.locale?.language == base } }.getOrNull()
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

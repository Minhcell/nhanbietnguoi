package com.langid.app

import android.util.Base64
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

enum class Provider { GEMINI, OPENAI_CLAUDE }

data class Config(
    val provider: Provider,
    val geminiKey: String = "",
    val openAiKey: String = "",
    val claudeKey: String = ""
) {
    val ready: Boolean
        get() = when (provider) {
            Provider.GEMINI -> geminiKey.isNotBlank()
            Provider.OPENAI_CLAUDE -> openAiKey.isNotBlank() && claudeKey.isNotBlank()
        }
}

/**
 * Lớp trung gian: giao diện gọi giống nhau, bên dưới tự chọn nhà cung cấp.
 *
 *  GEMINI        — 1 key miễn phí, gửi audio + ảnh trong CÙNG một request.
 *  OPENAI_CLAUDE — 2 key trả phí, Whisper nghe giọng rồi Claude phân tích.
 */
object Engine {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val GEMINI_MODEL = "gemini-2.5-flash"

    // ═══════════════════════════════════════════════════════════
    // PHÂN TÍCH — luồng chính
    // ═══════════════════════════════════════════════════════════
    suspend fun analyze(
        cfg: Config,
        audio: File?,
        images: List<ImageEvidence>,
        onStep: (String) -> Unit = {}
    ): JSONObject = when (cfg.provider) {

        Provider.OPENAI_CLAUDE -> {
            var voice: Transcription? = null
            var offline: String? = null
            if (audio != null) {
                onStep("Đang nhận diện ngôn ngữ (Whisper)…")
                val t = Api.transcribe(audio, cfg.openAiKey)
                if (t.text.isNotBlank()) {
                    voice = t
                    offline = identifyOfflineSafe(t.text)
                    onStep("Nghe ra: ${Languages.lookup(t.language)?.vi ?: t.language}. Đang phân tích…")
                }
            }
            Api.analyze(voice, offline, images, cfg.claudeKey)
        }

        Provider.GEMINI -> {
            onStep(
                if (audio != null && images.isNotEmpty()) "Đang phân tích giọng nói + hình ảnh…"
                else if (audio != null) "Đang nghe và phân tích giọng nói…"
                else "Đang đọc chữ trong ảnh…"
            )
            geminiAnalyze(cfg.geminiKey, audio, images)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // HỘI THOẠI 2 CHIỀU — 1 lượt nói
    // ═══════════════════════════════════════════════════════════
    /** @return Pair(câu gốc, bản dịch) */
    suspend fun chatTurn(
        cfg: Config,
        audio: File,
        foreignerSpeaking: Boolean,
        theirLanguage: String
    ): Pair<String, String> = when (cfg.provider) {

        Provider.OPENAI_CLAUDE -> {
            val t = Api.transcribe(
                audio, cfg.openAiKey,
                forceLanguage = if (foreignerSpeaking) null else "vi"
            )
            if (t.text.isBlank()) throw RuntimeException("Không nghe rõ.")
            val tr = Api.translate(t.text, theirLanguage, foreignerSpeaking, cfg.claudeKey)
            t.text to tr
        }

        Provider.GEMINI -> {
            val instruction = if (foreignerSpeaking)
                "Đoạn ghi âm là một người nước ngoài (đang nói $theirLanguage) đang nói. " +
                        "Hãy chép lại nguyên văn lời họ nói, rồi dịch sang tiếng Việt."
            else
                "Đoạn ghi âm là một người Việt đang nói tiếng Việt. " +
                        "Hãy chép lại nguyên văn, rồi dịch sang $theirLanguage " +
                        "(dùng chữ viết gốc của ngôn ngữ đó, không phiên âm)."

            val prompt = """
$instruction

Trả về DUY NHẤT một object JSON:
{"gốc": "nguyên văn lời nói", "dịch": "bản dịch"}
            """.trimIndent()

            val r = geminiCall(cfg.geminiKey, prompt, audio, emptyList())
            r.optString("gốc", "") to r.optString("dịch", "")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // GEMINI — gửi audio + ảnh + prompt trong 1 request duy nhất
    // ═══════════════════════════════════════════════════════════
    private suspend fun geminiAnalyze(
        key: String,
        audio: File?,
        images: List<ImageEvidence>
    ): JSONObject = withContext(Dispatchers.IO) {

        val hasVoice = audio != null
        val hasImage = images.isNotEmpty()

        val mode = when {
            hasVoice && hasImage -> "KẾT HỢP — có cả ghi âm và ảnh. Phân tích cả hai rồi ĐỐI CHIẾU CHÉO."
            hasVoice -> "CHỈ GIỌNG NÓI — phân tích hoàn toàn dựa trên ngôn ngữ nghe được."
            else -> "CHỈ HÌNH ẢNH — phân tích hoàn toàn dựa trên chữ viết / giấy tờ trong ảnh."
        }

        val prompt = """
Bạn là trợ lý phân tích tài liệu & ngôn ngữ, giúp người Việt xác định một người nước ngoài đến từ đâu và giao tiếp được với họ.

>>> CHẾ ĐỘ PHÂN TÍCH: $mode

QUY TẮC PHÂN TÍCH GIỌNG NÓI (nếu có file âm thanh đính kèm):
- Tự dò xem đó là ngôn ngữ gì. Phải xử lý được MỌI ngôn ngữ trên thế giới, kể cả hiếm: Bengali, Pashto, Sindhi, Sinhala, Amhara, Somali, Wolof, Hausa, Uzbek, Tajik...
- Chép lại nội dung họ nói.
- Nếu ngôn ngữ đó có nhiều nước dùng (Ả Rập, Anh, Pháp, Tây Ban Nha, Bồ Đào Nha), dùng PHƯƠNG NGỮ / GIỌNG / TỪ VỰNG để thu hẹp nước, và nói rõ mức độ chắc chắn.
- Ghi âm dưới 5 giây thì độ tin cậy phải để "thấp".

QUY TẮC PHÂN TÍCH ẢNH — BẮT BUỘC TUYỆT ĐỐI:
- CHỈ đọc: CHỮ VIẾT (viết tay hoặc đánh máy), giấy tờ, hộ chiếu, visa, thẻ cư trú, giấy phép lao động, con dấu, biển hiệu, tin nhắn trên màn hình, tiền tệ, quốc kỳ, logo.
- Chú ý HỆ CHỮ vì nó phân biệt rất mạnh: Bengali (বাংলা) vs Devanagari/Hindi (हिन्दी) vs Urdu (اردو) vs Ả Rập vs Thái vs Hán vs Kirin vs Ethiopic.
- Trên hộ chiếu/visa tìm: MÃ QUỐC GIA 3 CHỮ (BGD=Bangladesh, IND=Ấn Độ, PAK=Pakistan, PSE=Palestine, NPL=Nepal, LKA=Sri Lanka, MMR=Myanmar, PHL=Philippines, NGA=Nigeria...), DÒNG MRZ ở đáy trang, tên nước phát hành, con dấu.
- TUYỆT ĐỐI KHÔNG phân tích khuôn mặt, màu da, dáng người, tóc hay đặc điểm cơ thể. Nếu ảnh CHỈ có mặt người mà không có chữ hay giấy tờ, trả ket_luan = "Không đủ bằng chứng" và nói rõ khuôn mặt KHÔNG cho biết quốc tịch.

QUY TẮC ĐỐI CHIẾU (khi có cả hai):
- Giấy tờ ghi rõ quốc tịch -> GIẤY TỜ THẮNG, tin cậy cao.
- Nếu mâu thuẫn (vd giấy tờ Ấn Độ nhưng nói tiếng Bengali) -> nói rõ và giải thích (có thể là người Bengal ở Tây Bengal, Ấn Độ).
- Hai nguồn khớp nhau -> nâng tin cậy lên "cao".

Trả về DUY NHẤT một object JSON:

{
  "ket_luan": "Tên nước (nếu đủ chắc) HOẶC 'Chưa đủ chắc chắn' HOẶC 'Không đủ bằng chứng'",
  "do_tin_cay": "cao | trung bình | thấp",
  "nguon_phan_tich": "giọng nói | hình ảnh | cả hai",
  "ly_do": "1-2 câu: dựa vào bằng chứng cụ thể nào",
  "loi_noi": "Nguyên văn lời họ nói (chuỗi rỗng nếu không có ghi âm)",
  "bang_chung_anh": ["Từng thứ đọc được từ ảnh. Mảng rỗng nếu không có ảnh."],
  "bang_chung_giong": "Ngôn ngữ nghe được và nó gợi ý nước nào. 'Không có' nếu không ghi âm.",
  "doi_chieu": "Hai nguồn có khớp không? 'Chỉ có 1 nguồn' nếu chỉ có một loại bằng chứng.",
  "cac_nuoc": [{"nuoc": "...", "kha_nang": "cao|trung bình|thấp", "ghi_chu": "vì sao"}],
  "ngon_ngu": "Ngôn ngữ họ dùng (tiếng Việt)",
  "ma_ngon_ngu": "Mã BCP-47 để đọc thành tiếng, vd bn-BD, hi-IN, ur-PK, ar-SA, en-US",
  "dich_tieng_viet": "Dịch lời họ nói / chữ trong ảnh sang tiếng Việt",
  "cau_hoi_goi_y": [{"tieng_viet": "...", "ban_dich": "...", "phien_am": "cách đọc cho người Việt"}],
  "buoc_tiep_theo": "Cần thêm bằng chứng gì để chắc hơn",
  "luu_y": "Nhắc rằng ngôn ngữ không đồng nghĩa quốc tịch, giấy tờ là bằng chứng chắc nhất"
}

- "cac_nuoc" xếp theo khả năng giảm dần, tối đa 4 nước.
- 3-4 câu hỏi gợi ý thực dụng để hỏi thẳng họ đến từ đâu.
- Bằng chứng yếu thì nói thẳng là yếu. KHÔNG bịa ra sự chắc chắn.
        """.trimIndent()

        geminiCall(key, prompt, audio, images)
    }

    private suspend fun geminiCall(
        key: String,
        prompt: String,
        audio: File?,
        images: List<ImageEvidence>
    ): JSONObject = withContext(Dispatchers.IO) {

        val parts = JSONArray()

        audio?.let { f ->
            val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            parts.put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "audio/wav")     // Recorder ghi ra WAV
                    put("data", b64)
                })
            })
        }

        images.forEach { img ->
            parts.put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", img.mediaType)
                    put("data", img.base64)
                })
            })
        }

        parts.put(JSONObject().put("text", prompt))

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")   // ép trả JSON thuần
                put("temperature", 0.2)
                put("maxOutputTokens", 2500)
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                "$GEMINI_MODEL:generateContent?key=$key"

        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        http.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val hint = when (res.code) {
                    429 -> "Hết hạn mức miễn phí hôm nay (250 lượt/ngày). Chờ sang ngày mới hoặc đổi sang OpenAI+Claude."
                    400 -> "Key sai hoặc file quá lớn. Thử ghi âm ngắn hơn."
                    403 -> "Key không hợp lệ."
                    else -> raw.take(200)
                }
                throw RuntimeException("Gemini lỗi ${res.code}: $hint")
            }

            val text = JSONObject(raw)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text")
                .replace("```json", "").replace("```", "").trim()

            JSONObject(text)
        }
    }

    private suspend fun identifyOfflineSafe(text: String): String? = try {
        val code = com.google.mlkit.nl.languageid.LanguageIdentification
            .getClient()
            .identifyLanguage(text)
            .await()
        if (code == "und") null else code
    } catch (e: Exception) { null }
}

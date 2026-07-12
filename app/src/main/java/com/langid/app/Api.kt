package com.langid.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class Transcription(
    val language: String,      // ngôn ngữ Whisper tự phát hiện
    val text: String,          // nội dung nói
    val durationSec: Double
)

/** Ảnh đưa vào phân tích: base64 + loại media. */
data class ImageEvidence(val base64: String, val mediaType: String = "image/jpeg")

object Api {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val MODEL = "claude-sonnet-4-6"

    // ─────────────────────────────────────────────────────────────
    // WHISPER — chuyển lời nói thành chữ + tự dò ngôn ngữ
    // ─────────────────────────────────────────────────────────────
    suspend fun transcribe(
        audio: File,
        openAiKey: String,
        forceLanguage: String? = null   // "vi" khi chính anh nói; null = để máy tự dò
    ): Transcription = withContext(Dispatchers.IO) {

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/m4a".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("response_format", "verbose_json")

        if (forceLanguage != null) builder.addFormDataPart("language", forceLanguage)

        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $openAiKey")
            .post(builder.build())
            .build()

        http.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw RuntimeException("Whisper lỗi ${res.code}: ${raw.take(300)}")
            val j = JSONObject(raw)
            Transcription(
                language = j.optString("language", "unknown"),
                text = j.optString("text", "").trim(),
                durationSec = j.optDouble("duration", 0.0)
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    // CLAUDE — tổng hợp bằng chứng (giọng nói + ảnh giấy tờ/chữ viết)
    // ─────────────────────────────────────────────────────────────
    suspend fun analyze(
        voice: Transcription?,
        offlineGuess: String?,
        images: List<ImageEvidence>,
        claudeKey: String
    ): JSONObject = withContext(Dispatchers.IO) {

        val voiceBlock = if (voice != null && voice.text.isNotBlank()) {
            """
            === BẰNG CHỨNG GIỌNG NÓI ===
            - Ngôn ngữ Whisper phát hiện: ${voice.language}
            - Đối chiếu ngoại tuyến (ML Kit): ${offlineGuess ?: "không có"}
            - Nội dung nói: "${voice.text}"
            - Độ dài: ${"%.1f".format(voice.durationSec)} giây
            """.trimIndent()
        } else {
            "=== BẰNG CHỨNG GIỌNG NÓI ===\n(Không có)"
        }

        val imageBlock = if (images.isNotEmpty())
            "=== BẰNG CHỨNG HÌNH ẢNH ===\nCó ${images.size} ảnh đính kèm bên dưới."
        else
            "=== BẰNG CHỨNG HÌNH ẢNH ===\n(Không có)"

        val prompt = """
Bạn là trợ lý phân tích tài liệu & ngôn ngữ, giúp người Việt xác định một người nước ngoài đến từ đâu và giao tiếp được với họ.

$voiceBlock

$imageBlock

QUY TẮC PHÂN TÍCH ẢNH — BẮT BUỘC TUYỆT ĐỐI:
- CHỈ đọc và phân tích: CHỮ VIẾT (viết tay hoặc đánh máy), giấy tờ, hộ chiếu, visa, thẻ cư trú, vé, con dấu, biển hiệu, tin nhắn trên màn hình, tiền tệ, quốc kỳ, logo, tên cơ quan cấp.
- Chú ý HỆ CHỮ vì nó phân biệt rất mạnh: Bengali (বাংলা) vs Devanagari/Hindi (हिन्दी) vs Urdu (اردو) vs Ả Rập vs Thái vs Hán vs Kirin...
- Trên hộ chiếu/visa hãy tìm: mã quốc gia 3 chữ (BGD=Bangladesh, IND=Ấn Độ, PAK=Pakistan, PSE=Palestine, NPL=Nepal, LKA=Sri Lanka...), dòng MRZ ở đáy trang, tên nước phát hành, con dấu.
- TUYỆT ĐỐI KHÔNG phân tích khuôn mặt, màu da, dáng người, tóc, hay bất kỳ đặc điểm cơ thể nào của người trong ảnh. Nếu ảnh chỉ có mặt người mà không có chữ hay giấy tờ, hãy trả về ket_luan = "Không đủ bằng chứng" và nói rõ rằng khuôn mặt KHÔNG cho biết quốc tịch, cần chụp giấy tờ hoặc ghi âm giọng nói.

TRẢ VỀ DUY NHẤT một object JSON, không markdown, không lời dẫn:

{
  "ket_luan": "Tên nước (nếu bằng chứng đủ chắc) HOẶC 'Chưa đủ chắc chắn' HOẶC 'Không đủ bằng chứng'",
  "do_tin_cay": "cao | trung bình | thấp",
  "ly_do": "1-2 câu: dựa vào bằng chứng cụ thể nào (mã BGD trên visa, chữ Bengali, Whisper nhận ra tiếng Bengali...)",
  "bang_chung_anh": ["Từng thứ đọc được từ ảnh: chữ gì, hệ chữ gì, giấy tờ gì, mã nước gì. Mảng rỗng nếu không có ảnh."],
  "bang_chung_giong": "Ngôn ngữ nghe được và nó gợi ý nước nào. Ghi 'Không có' nếu không ghi âm.",
  "doi_chieu": "Hai nguồn có khớp nhau không? Nếu mâu thuẫn thì nói rõ.",
  "cac_nuoc": [
    {"nuoc": "Tên nước", "kha_nang": "cao | trung bình | thấp", "ghi_chu": "vì sao"}
  ],
  "ngon_ngu": "Ngôn ngữ họ dùng (tiếng Việt)",
  "ma_ngon_ngu": "Mã BCP-47 để đọc thành tiếng, vd bn-IN cho Bengali, hi-IN, ur-PK, ar-SA, en-US",
  "dich_tieng_viet": "Dịch nội dung họ nói / chữ trong ảnh sang tiếng Việt",
  "cau_hoi_goi_y": [
    {"tieng_viet": "Bạn đến từ nước nào?", "ban_dich": "dịch sang ngôn ngữ của họ", "phien_am": "cách đọc gần đúng cho người Việt"}
  ],
  "buoc_tiep_theo": "Cần thêm bằng chứng gì để chắc chắn hơn (vd: chụp trang thông tin hộ chiếu, ghi âm dài hơn 10 giây)",
  "luu_y": "Nhắc rằng ngôn ngữ không đồng nghĩa quốc tịch, và giấy tờ là bằng chứng chắc chắn nhất"
}

- "cac_nuoc" xếp theo khả năng giảm dần, tối đa 4 nước.
- Cho 3-4 câu hỏi gợi ý thực dụng để bắt chuyện và hỏi thẳng họ đến từ đâu.
- Nếu bằng chứng yếu, hãy nói thẳng là yếu. Không bịa ra sự chắc chắn.
        """.trimIndent()

        // content = [ảnh..., text]
        val content = JSONArray()
        images.forEach { img ->
            content.put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", img.mediaType)
                    put("data", img.base64)
                })
            })
        }
        content.put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })

        val payload = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 2000)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            }))
        }

        JSONObject(callClaude(payload, claudeKey))
    }

    // ─────────────────────────────────────────────────────────────
    // CLAUDE — dịch cho chế độ hội thoại 2 chiều
    // ─────────────────────────────────────────────────────────────
    /**
     * @param toVietnamese true = họ nói -> dịch ra tiếng Việt
     *                     false = anh nói tiếng Việt -> dịch sang tiếng họ
     */
    suspend fun translate(
        text: String,
        theirLanguage: String,
        toVietnamese: Boolean,
        claudeKey: String
    ): String = withContext(Dispatchers.IO) {

        val prompt = if (toVietnamese) {
            "Dịch câu sau từ $theirLanguage sang tiếng Việt. Chỉ trả về bản dịch, không giải thích, không dấu ngoặc kép.\n\n$text"
        } else {
            "Dịch câu sau từ tiếng Việt sang $theirLanguage. Chỉ trả về bản dịch bằng chữ viết gốc của ngôn ngữ đó, không giải thích, không phiên âm, không dấu ngoặc kép.\n\n$text"
        }

        val payload = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 800)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
        }
        callClaude(payload, claudeKey).trim()
    }

    // ─────────────────────────────────────────────────────────────
    private fun callClaude(payload: JSONObject, key: String): String {
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        http.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw RuntimeException("Claude lỗi ${res.code}: ${raw.take(300)}")

            val blocks = JSONObject(raw).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until blocks.length()) {
                val b = blocks.getJSONObject(i)
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            return sb.toString()
                .replace("```json", "")
                .replace("```", "")
                .trim()
        }
    }
}

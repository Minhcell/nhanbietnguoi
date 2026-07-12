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
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/wav".toMediaType()))
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

        val hasVoice = voice != null && voice.text.isNotBlank()
        val hasImage = images.isNotEmpty()

        // Xác định chế độ phân tích theo bằng chứng đang có
        val mode = when {
            hasVoice && hasImage -> "KẾT HỢP — có cả giọng nói và ảnh. Phân tích cả hai rồi ĐỐI CHIẾU CHÉO."
            hasVoice             -> "CHỈ GIỌNG NÓI — không có ảnh. Phân tích hoàn toàn dựa trên ngôn ngữ nghe được."
            hasImage             -> "CHỈ HÌNH ẢNH — không có ghi âm. Phân tích hoàn toàn dựa trên chữ viết / giấy tờ trong ảnh."
            else                 -> "KHÔNG CÓ BẰNG CHỨNG"
        }

        val voiceBlock = if (hasVoice) {
            """
            === BẰNG CHỨNG GIỌNG NÓI ===
            - Ngôn ngữ Whisper tự phát hiện: ${voice!!.language}
            - ${Languages.hint(voice.language)}
            - Đối chiếu ngoại tuyến (ML Kit): ${offlineGuess ?: "không có"}
            - Nội dung nói: "${voice.text}"
            - Độ dài: ${"%.1f".format(voice.durationSec)} giây
            """.trimIndent()
        } else {
            "=== BẰNG CHỨNG GIỌNG NÓI ===\n(Không có — bỏ qua phần này)"
        }

        val imageBlock = if (hasImage)
            "=== BẰNG CHỨNG HÌNH ẢNH ===\nCó ${images.size} ảnh đính kèm bên dưới. Hãy đọc kỹ mọi chữ viết trong đó."
        else
            "=== BẰNG CHỨNG HÌNH ẢNH ===\n(Không có — bỏ qua phần này)"

        val prompt = """
Bạn là trợ lý phân tích tài liệu & ngôn ngữ, giúp người Việt xác định một người nước ngoài đến từ đâu và giao tiếp được với họ.

>>> CHẾ ĐỘ PHÂN TÍCH: $mode

$voiceBlock

$imageBlock

QUY TẮC PHÂN TÍCH GIỌNG NÓI:
- Ngôn ngữ nào cũng phải xử lý được, kể cả ngôn ngữ hiếm (Bengali, Pashto, Amhara, Wolof, Uzbek, Sinhala...).
- Nếu ngôn ngữ đó có nhiều nước dùng (Ả Rập, Anh, Pháp, Tây Ban Nha, Bồ Đào Nha), hãy dùng PHƯƠNG NGỮ / GIỌNG / TỪ VỰNG trong câu nói để thu hẹp nước, và nói rõ mức độ chắc chắn.
- Đoạn ghi dưới 5 giây thì độ tin cậy phải để "thấp" và nói rõ là cần ghi dài hơn.

QUY TẮC PHÂN TÍCH ẢNH — BẮT BUỘC TUYỆT ĐỐI:
- CHỈ đọc và phân tích: CHỮ VIẾT (viết tay hoặc đánh máy), giấy tờ, hộ chiếu, visa, thẻ cư trú, giấy phép lao động, vé, con dấu, biển hiệu, tin nhắn trên màn hình, tiền tệ, quốc kỳ, logo, tên cơ quan cấp.
- Chú ý HỆ CHỮ vì nó phân biệt rất mạnh: Bengali (বাংলা) vs Devanagari/Hindi (हिन्दी) vs Urdu (اردو) vs Ả Rập vs Thái vs Hán vs Kirin vs Ethiopic...
- Trên hộ chiếu/visa hãy tìm: MÃ QUỐC GIA 3 CHỮ (BGD=Bangladesh, IND=Ấn Độ, PAK=Pakistan, PSE=Palestine, NPL=Nepal, LKA=Sri Lanka, MMR=Myanmar, PHL=Philippines, NGA=Nigeria, EGY=Ai Cập...), DÒNG MRZ ở đáy trang, tên nước phát hành, con dấu, số hộ chiếu.
- TUYỆT ĐỐI KHÔNG phân tích khuôn mặt, màu da, dáng người, tóc hay bất kỳ đặc điểm cơ thể nào. Nếu ảnh CHỈ có mặt người mà không có chữ hay giấy tờ, trả về ket_luan = "Không đủ bằng chứng" và nói rõ khuôn mặt KHÔNG cho biết quốc tịch.

QUY TẮC ĐỐI CHIẾU (khi có cả hai):
- Nếu ảnh có giấy tờ ghi rõ quốc tịch -> GIẤY TỜ THẮNG, độ tin cậy cao.
- Nếu giọng nói và giấy tờ mâu thuẫn (vd giấy tờ Ấn Độ nhưng nói tiếng Bengali) -> nói rõ mâu thuẫn và giải thích khả năng (vd người Bengal ở Tây Bengal, Ấn Độ).
- Nếu hai nguồn khớp nhau -> nâng độ tin cậy lên "cao".

TRẢ VỀ DUY NHẤT một object JSON, không markdown, không lời dẫn:

{
  "ket_luan": "Tên nước (nếu bằng chứng đủ chắc) HOẶC 'Chưa đủ chắc chắn' HOẶC 'Không đủ bằng chứng'",
  "do_tin_cay": "cao | trung bình | thấp",
  "nguon_phan_tich": "giọng nói | hình ảnh | cả hai",
  "ly_do": "1-2 câu: dựa vào bằng chứng cụ thể nào",
  "bang_chung_anh": ["Từng thứ đọc được từ ảnh: chữ gì, hệ chữ gì, giấy tờ gì, mã nước gì. Mảng rỗng nếu không có ảnh."],
  "bang_chung_giong": "Ngôn ngữ nghe được và nó gợi ý nước nào. Ghi 'Không có' nếu không ghi âm.",
  "doi_chieu": "Hai nguồn có khớp không? Ghi 'Chỉ có 1 nguồn' nếu chỉ có một loại bằng chứng.",
  "cac_nuoc": [
    {"nuoc": "Tên nước", "kha_nang": "cao | trung bình | thấp", "ghi_chu": "vì sao"}
  ],
  "ngon_ngu": "Ngôn ngữ họ dùng (tiếng Việt)",
  "ma_ngon_ngu": "Mã BCP-47 để đọc thành tiếng, vd bn-BD, hi-IN, ur-PK, ar-SA, en-US",
  "dich_tieng_viet": "Dịch nội dung họ nói / chữ trong ảnh sang tiếng Việt",
  "cau_hoi_goi_y": [
    {"tieng_viet": "Bạn đến từ nước nào?", "ban_dich": "dịch sang ngôn ngữ của họ", "phien_am": "cách đọc gần đúng cho người Việt"}
  ],
  "buoc_tiep_theo": "Cần thêm bằng chứng gì để chắc hơn",
  "luu_y": "Nhắc rằng ngôn ngữ không đồng nghĩa quốc tịch, giấy tờ là bằng chứng chắc nhất"
}

- "cac_nuoc" xếp theo khả năng giảm dần, tối đa 4 nước.
- Cho 3-4 câu hỏi gợi ý thực dụng để hỏi thẳng họ đến từ đâu.
- Bằng chứng yếu thì nói thẳng là yếu. KHÔNG bịa ra sự chắc chắn.
        """.trimIndent()

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

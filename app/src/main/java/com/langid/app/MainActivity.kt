package com.langid.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Root() }
            }
        }
    }
}

private const val PREFS = "langid_prefs"

@Composable
fun Root() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val speaker = remember { Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    var openAiKey by remember { mutableStateOf(prefs.getString("openai", "") ?: "") }
    var claudeKey by remember { mutableStateOf(prefs.getString("claude", "") ?: "") }
    var tab by remember { mutableIntStateOf(0) }

    // ngôn ngữ đối phương -> dùng chung cho tab hội thoại
    var theirLang by remember { mutableStateOf("") }
    var theirLangTag by remember { mutableStateOf("en-US") }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Nhận diện") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Hội thoại") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Cấu hình") })
        }

        when (tab) {
            0 -> IdentifyTab(
                openAiKey = openAiKey, claudeKey = claudeKey, speaker = speaker,
                onLangDetected = { name, tag -> theirLang = name; theirLangTag = tag }
            )
            1 -> ChatTab(
                openAiKey = openAiKey, claudeKey = claudeKey, speaker = speaker,
                theirLang = theirLang, theirLangTag = theirLangTag
            )
            2 -> ConfigTab(
                openAiKey, claudeKey,
                onSave = { o, c ->
                    openAiKey = o; claudeKey = c
                    prefs.edit().putString("openai", o).putString("claude", c).apply()
                }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// TAB 1 — NHẬN DIỆN (giọng nói + ảnh giấy tờ/chữ viết)
// ═══════════════════════════════════════════════════════════════
@Composable
fun IdentifyTab(
    openAiKey: String,
    claudeKey: String,
    speaker: Speaker,
    onLangDetected: (String, String) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { Recorder(ctx) }

    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    var voice by remember { mutableStateOf<Transcription?>(null) }
    var images by remember { mutableStateOf<List<ImageEvidence>>(emptyList()) }
    var result by remember { mutableStateOf<JSONObject?>(null) }

    var camUri by remember { mutableStateOf<Uri?>(null) }

    fun addImage(uri: Uri) {
        val ev = uriToBase64(ctx, uri)
        if (ev == null) { status = "Không đọc được ảnh này."; return }
        if (images.size >= 4) { status = "Tối đa 4 ảnh."; return }
        images = images + ev
        status = "Đã thêm ảnh (${images.size})."
    }

    val micPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (!it) status = "Cần quyền micro." }

    val camPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (!it) status = "Cần quyền camera." }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) camUri?.let { addImage(it) } }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { addImage(it) } }

    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true; status = "Đang nghe file…"
            try {
                val f = File(ctx.cacheDir, "in_${System.currentTimeMillis()}.m4a")
                ctx.contentResolver.openInputStream(uri)?.use { i ->
                    f.outputStream().use { i.copyTo(it) }
                }
                voice = Api.transcribe(f, openAiKey.trim())
                status = "Đã nhận giọng nói."
            } catch (e: Exception) { status = "Lỗi: ${e.message}" }
            busy = false
        }
    }

    fun runAnalysis() {
        if (voice == null && images.isEmpty()) {
            status = "Cần ít nhất 1 bằng chứng: ghi âm hoặc ảnh giấy tờ/chữ viết."
            return
        }
        scope.launch {
            busy = true; result = null; status = "Đang phân tích bằng chứng…"
            try {
                val offline = voice?.text?.takeIf { it.isNotBlank() }?.let { identifyOffline(it) }
                val r = Api.analyze(voice, offline, images, claudeKey.trim())
                result = r
                onLangDetected(
                    r.optString("ngon_ngu", ""),
                    r.optString("ma_ngon_ngu", "en-US").ifBlank { "en-US" }
                )
                status = ""
            } catch (e: Exception) { status = "Lỗi: ${e.message}" }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Thu thập bằng chứng", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Ghi âm giọng nói và/hoặc chụp giấy tờ, hộ chiếu, visa, chữ viết. Càng nhiều bằng chứng, kết quả càng chắc.",
            fontSize = 13.sp, color = Color.Gray
        )

        // ── Ghi âm ──
        Button(
            onClick = {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) { micPerm.launch(Manifest.permission.RECORD_AUDIO); return@Button }
                if (openAiKey.isBlank()) { status = "Chưa có OpenAI key (tab Cấu hình)."; return@Button }

                if (!recording) {
                    if (recorder.start()) {
                        recording = true; status = "Đang ghi… bấm lần nữa để dừng (nên ≥ 8 giây)."
                    } else status = "Không mở được micro."
                } else {
                    recording = false
                    val f = recorder.stop()
                    if (f == null) { status = "Đoạn ghi quá ngắn."; return@Button }
                    scope.launch {
                        busy = true; status = "Đang nghe…"
                        try {
                            voice = Api.transcribe(f, openAiKey.trim())
                            status = "Đã nhận giọng nói."
                        } catch (e: Exception) { status = "Lỗi: ${e.message}" }
                        busy = false
                    }
                }
            },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (recording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth().height(58.dp)
        ) {
            Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, null)
            Spacer(Modifier.width(8.dp))
            Text(if (recording) "DỪNG GHI ÂM" else "GHI ÂM TRỰC TIẾP")
        }

        OutlinedButton(
            onClick = { pickAudio.launch("audio/*") },
            enabled = !busy && !recording, modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.AudioFile, null); Spacer(Modifier.width(8.dp))
            Text("CHỌN FILE GHI ÂM CÓ SẴN")
        }

        HorizontalDivider()

        // ── Ảnh ──
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED
                    ) { camPerm.launch(Manifest.permission.CAMERA); return@Button }
                    val f = newImageFile(ctx)
                    val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                    camUri = u
                    takePhoto.launch(u)
                },
                enabled = !busy, modifier = Modifier.weight(1f).height(54.dp)
            ) {
                Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("CHỤP")
            }
            OutlinedButton(
                onClick = { pickImage.launch("image/*") },
                enabled = !busy, modifier = Modifier.weight(1f).height(54.dp)
            ) {
                Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("THÊM ẢNH")
            }
        }

        Surface(color = Color(0xFFE8F4FD), shape = MaterialTheme.shapes.small) {
            Text(
                "📄 Chụp GIẤY TỜ, HỘ CHIẾU, VISA, CHỮ VIẾT — không phải khuôn mặt. " +
                        "Trang thông tin hộ chiếu (có mã nước 3 chữ + dòng MRZ ở đáy) là bằng chứng mạnh nhất.",
                Modifier.padding(10.dp), fontSize = 12.sp, color = Color(0xFF0B4A6F)
            )
        }

        // ── Trạng thái bằng chứng ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Bằng chứng hiện có", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    if (voice != null) "🎤 Giọng nói: ${voice!!.language} — \"${voice!!.text.take(60)}…\""
                    else "🎤 Giọng nói: chưa có",
                    fontSize = 13.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🖼 Ảnh: ${images.size}", fontSize = 13.sp)
                    if (images.isNotEmpty()) {
                        TextButton(onClick = { images = emptyList() }) { Text("Xoá hết", fontSize = 12.sp) }
                    }
                }
            }
        }

        Button(
            onClick = { runAnalysis() },
            enabled = !busy && !recording && claudeKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(58.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
        ) {
            Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp))
            Text("PHÂN TÍCH", fontSize = 16.sp)
        }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp)); Text(status, fontSize = 13.sp)
            }
        } else if (status.isNotBlank()) {
            Text(status, fontSize = 13.sp, color = Color(0xFF9C4221))
        }

        result?.let { ResultCard(it, speaker) }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ResultCard(r: JSONObject, speaker: Speaker) {
    val ctx = LocalContext.current
    val langTag = r.optString("ma_ngon_ngu", "en-US").ifBlank { "en-US" }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Text(
                r.optString("ket_luan", "?"),
                fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text("Độ tin cậy: ${r.optString("do_tin_cay")}", fontSize = 13.sp, color = Color.Gray)
            Text(r.optString("ly_do"), fontSize = 14.sp)

            HorizontalDivider()

            val ba = r.optJSONArray("bang_chung_anh")
            if (ba != null && ba.length() > 0) {
                Text("Đọc được từ ảnh", fontWeight = FontWeight.SemiBold)
                for (i in 0 until ba.length()) Text("• ${ba.getString(i)}", fontSize = 14.sp)
            }
            Text("Giọng nói: ${r.optString("bang_chung_giong")}", fontSize = 14.sp)
            Text("Đối chiếu: ${r.optString("doi_chieu")}", fontSize = 13.sp, color = Color.Gray)

            HorizontalDivider()

            Text("Các nước khả dĩ", fontWeight = FontWeight.SemiBold)
            r.optJSONArray("cac_nuoc")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    Column {
                        Text("• ${c.optString("nuoc")} (${c.optString("kha_nang")})",
                            fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text("   ${c.optString("ghi_chu")}", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }

            val dich = r.optString("dich_tieng_viet")
            if (dich.isNotBlank() && dich != "null") {
                HorizontalDivider()
                Text("Dịch sang tiếng Việt", fontWeight = FontWeight.SemiBold)
                Text(dich, fontSize = 15.sp)
            }

            HorizontalDivider()
            Text("Câu hỏi để hỏi thẳng họ (bấm loa để phát)", fontWeight = FontWeight.SemiBold)
            r.optJSONArray("cau_hoi_goi_y")?.let { qs ->
                for (i in 0 until qs.length()) {
                    val q = qs.getJSONObject(i)
                    val target = q.optString("ban_dich")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("VN: ${q.optString("tieng_viet")}", fontSize = 13.sp, color = Color.Gray)
                            Text(target, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            val pa = q.optString("phien_am")
                            if (pa.isNotBlank()) Text("[$pa]", fontSize = 12.sp, color = Color.Gray)
                        }
                        IconButton(onClick = {
                            speaker.speak(target, langTag) {
                                android.widget.Toast.makeText(
                                    ctx, "Máy chưa cài giọng đọc cho ngôn ngữ này", 
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }) { Icon(Icons.Default.VolumeUp, "Phát") }
                    }
                }
            }

            Surface(color = Color(0xFFE8F5E9), shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()) {
                Text("➡ ${r.optString("buoc_tiep_theo")}", Modifier.padding(10.dp),
                    fontSize = 13.sp, color = Color(0xFF1B5E20))
            }
            Surface(color = Color(0xFFFFF3CD), shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()) {
                Text("⚠ ${r.optString("luu_y")}", Modifier.padding(10.dp),
                    fontSize = 12.sp, color = Color(0xFF664D03))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// TAB 2 — HỘI THOẠI 2 CHIỀU
// ═══════════════════════════════════════════════════════════════
data class Turn(val whoIsForeigner: Boolean, val original: String, val translated: String)

@Composable
fun ChatTab(
    openAiKey: String, claudeKey: String, speaker: Speaker,
    theirLang: String, theirLangTag: String
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { Recorder(ctx) }

    var turns by remember { mutableStateOf<List<Turn>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var recordingForeigner by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf("") }

    val lang = theirLang.ifBlank { "tiếng Anh" }

    fun handle(isForeigner: Boolean) {
        if (recordingForeigner == null) {
            if (recorder.start()) {
                recordingForeigner = isForeigner
                status = if (isForeigner) "Đang nghe họ nói…" else "Đang nghe anh nói…"
            } else status = "Không mở được micro."
            return
        }
        val f = recorder.stop()
        val wasForeigner = recordingForeigner!!
        recordingForeigner = null
        if (f == null) { status = "Đoạn ghi quá ngắn."; return }

        scope.launch {
            busy = true; status = "Đang dịch…"
            try {
                // anh nói -> ép nhận tiếng Việt cho chính xác; họ nói -> để máy tự dò
                val t = Api.transcribe(f, openAiKey.trim(), if (wasForeigner) null else "vi")
                if (t.text.isBlank()) { status = "Không nghe rõ."; busy = false; return@launch }

                val translated = Api.translate(
                    t.text, lang, toVietnamese = wasForeigner, claudeKey = claudeKey.trim()
                )
                turns = turns + Turn(wasForeigner, t.text, translated)

                // đọc bản dịch lên: họ nói -> đọc tiếng Việt; anh nói -> đọc tiếng họ
                speaker.speak(translated, if (wasForeigner) "vi-VN" else theirLangTag)
                status = ""
            } catch (e: Exception) { status = "Lỗi: ${e.message}" }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Hội thoại 2 chiều", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Ngôn ngữ đối phương: $lang" +
                if (theirLang.isBlank()) " (chạy tab Nhận diện trước để tự nhận)" else "",
            fontSize = 13.sp, color = Color.Gray)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { handle(true) },
                enabled = !busy && recordingForeigner != false,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (recordingForeigner == true) Color(0xFFD32F2F) else Color(0xFF1565C0)
                ),
                modifier = Modifier.weight(1f).height(72.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (recordingForeigner == true) Icons.Default.Stop else Icons.Default.RecordVoiceOver, null)
                    Text(if (recordingForeigner == true) "DỪNG" else "HỌ NÓI", fontSize = 13.sp)
                }
            }
            Button(
                onClick = { handle(false) },
                enabled = !busy && recordingForeigner != true,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (recordingForeigner == false) Color(0xFFD32F2F) else Color(0xFF2E7D32)
                ),
                modifier = Modifier.weight(1f).height(72.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (recordingForeigner == false) Icons.Default.Stop else Icons.Default.Mic, null)
                    Text(if (recordingForeigner == false) "DỪNG" else "TÔI NÓI", fontSize = 13.sp)
                }
            }
        }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text(status, fontSize = 13.sp)
            }
        } else if (status.isNotBlank()) {
            Text(status, fontSize = 13.sp, color = Color(0xFF9C4221))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            turns.reversed().forEach { t ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (t.whoIsForeigner) Color(0xFFE3F2FD) else Color(0xFFE8F5E9)
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (t.whoIsForeigner) "HỌ" else "TÔI",
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Text(t.original, fontSize = 14.sp, color = Color.DarkGray)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.translated, fontSize = 16.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                speaker.speak(
                                    t.translated,
                                    if (t.whoIsForeigner) "vi-VN" else theirLangTag
                                )
                            }) { Icon(Icons.Default.VolumeUp, null) }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// TAB 3 — CẤU HÌNH
// ═══════════════════════════════════════════════════════════════
@Composable
fun ConfigTab(openAi: String, claude: String, onSave: (String, String) -> Unit) {
    var o by remember { mutableStateOf(openAi) }
    var c by remember { mutableStateOf(claude) }
    var msg by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cấu hình API", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(o, { o = it }, label = { Text("OpenAI key (Whisper)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(c, { c = it }, label = { Text("Anthropic key (Claude)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(o.trim(), c.trim()); msg = "Đã lưu." },
            modifier = Modifier.fillMaxWidth()) { Text("LƯU") }
        if (msg.isNotBlank()) Text(msg, color = Color(0xFF2E7D32))

        Spacer(Modifier.height(10.dp))
        Text("Mẹo dùng", fontWeight = FontWeight.SemiBold)
        Text(
            "• Ghi âm ít nhất 8 giây thì máy mới dò ngôn ngữ chắc.\n" +
            "• Ảnh mạnh nhất: trang thông tin hộ chiếu (có mã nước 3 chữ như BGD/IND/PAK và dòng MRZ ở đáy), visa, thẻ cư trú.\n" +
            "• Ảnh chữ viết tay hay tin nhắn trên điện thoại cũng rất tốt — hệ chữ Bengali khác hẳn Hindi/Urdu.\n" +
            "• Kết hợp cả giọng nói và ảnh sẽ cho kết quả chắc nhất.\n" +
            "• Giọng đọc TTS cho Bengali/Urdu có thể chưa cài sẵn — vào Cài đặt Android > Ngôn ngữ > Đầu ra văn bản thành giọng nói để tải thêm.",
            fontSize = 13.sp, color = Color.Gray
        )
    }
}

private suspend fun identifyOffline(text: String): String = try {
    val code = LanguageIdentification.getClient().identifyLanguage(text).await()
    if (code == "und") "không xác định" else code
} catch (e: Exception) { "không chạy được" }

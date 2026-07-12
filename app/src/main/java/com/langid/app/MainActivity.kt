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
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.launch
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

    // Đọc cấu hình đã lưu — lần sau mở app không hỏi lại
    var cfg by remember {
        mutableStateOf(
            Config(
                provider = if (prefs.getString("provider", "GEMINI") == "GEMINI")
                    Provider.GEMINI else Provider.OPENAI_CLAUDE,
                geminiKey = prefs.getString("gemini", "") ?: "",
                openAiKey = prefs.getString("openai", "") ?: "",
                claudeKey = prefs.getString("claude", "") ?: ""
            )
        )
    }

    var tab by remember { mutableIntStateOf(if (cfg.ready) 0 else 2) }
    var theirLang by remember { mutableStateOf("") }
    var theirLangTag by remember { mutableStateOf("en-US") }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Nhận diện") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Hội thoại") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Cấu hình") })
        }

        when (tab) {
            0 -> IdentifyTab(cfg, speaker) { name, tag ->
                theirLang = name; theirLangTag = tag
            }
            1 -> ChatTab(cfg, speaker, theirLang, theirLangTag)
            2 -> ConfigTab(cfg) { newCfg ->
                cfg = newCfg
                prefs.edit()
                    .putString("provider", newCfg.provider.name)
                    .putString("gemini", newCfg.geminiKey)
                    .putString("openai", newCfg.openAiKey)
                    .putString("claude", newCfg.claudeKey)
                    .apply()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// TAB 1 — NHẬN DIỆN
// ═══════════════════════════════════════════════════════════════
@Composable
fun IdentifyTab(cfg: Config, speaker: Speaker, onLangDetected: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { Recorder(ctx) }

    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var seconds by remember { mutableIntStateOf(0) }

    var audio by remember { mutableStateOf<File?>(null) }
    var images by remember { mutableStateOf<List<ImageEvidence>>(emptyList()) }
    var result by remember { mutableStateOf<JSONObject?>(null) }

    var camUri by remember { mutableStateOf<Uri?>(null) }
    var trigger by remember { mutableIntStateOf(0) }   // tăng lên để tự chạy phân tích

    // Đếm giây khi đang ghi
    LaunchedEffect(recording) {
        seconds = 0
        while (recording) {
            kotlinx.coroutines.delay(1000)
            seconds++
        }
    }

    fun addImage(uri: Uri) {
        val ev = uriToBase64(ctx, uri)
        when {
            ev == null -> status = "Không đọc được ảnh này."
            images.size >= 4 -> status = "Tối đa 4 ảnh."
            else -> { images = images + ev; status = "Đã thêm ảnh (${images.size})." }
        }
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
        if (uri != null) {
            val f = File(ctx.cacheDir, "in_${System.currentTimeMillis()}.wav")
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { i ->
                    f.outputStream().use { i.copyTo(it) }
                }
            }
            if (f.length() > 1000) { audio = f; trigger++ }
            else status = "Không đọc được file."
        }
    }

    // Tự động phân tích mỗi khi có bằng chứng mới từ ghi âm
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        if (audio == null && images.isEmpty()) return@LaunchedEffect

        busy = true; result = null
        try {
            val r = Engine.analyze(cfg, audio, images) { s -> status = s }
            result = r
            onLangDetected(
                r.optString("ngon_ngu", ""),
                r.optString("ma_ngon_ngu", "en-US").ifBlank { "en-US" }
            )
            status = ""
        } catch (e: Exception) {
            status = "Lỗi: ${e.message}"
        }
        busy = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Thu thập bằng chứng", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (cfg.provider == Provider.GEMINI) "Gemini (free)" else "OpenAI + Claude",
                fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp)
            )
        }
        Text(
            "Ghi âm giọng nói và/hoặc chụp giấy tờ, chữ viết. App tự dò ngôn ngữ và tự phân tích.",
            fontSize = 13.sp, color = Color.Gray
        )

        if (!cfg.ready) {
            Surface(color = Color(0xFFFFEBEE), shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()) {
                Text("⚠ Chưa cấu hình API key — vào tab Cấu hình.",
                    Modifier.padding(10.dp), fontSize = 13.sp, color = Color(0xFFB71C1C))
            }
        }

        // ── Ghi âm ──
        Button(
            onClick = {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) { micPerm.launch(Manifest.permission.RECORD_AUDIO); return@Button }
                if (!cfg.ready) { status = "Chưa cấu hình key."; return@Button }

                if (!recording) {
                    if (recorder.start()) { recording = true; status = "" }
                    else status = "Không mở được micro."
                } else {
                    recording = false
                    val f = recorder.stop()
                    if (f == null) { status = "Đoạn ghi quá ngắn."; return@Button }
                    audio = f
                    trigger++          // tự dò ngôn ngữ + tự phân tích
                }
            },
            enabled = !busy && cfg.ready,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (recording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth().height(62.dp)
        ) {
            Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (recording) "DỪNG  •  ${seconds}s" else "GHI ÂM TRỰC TIẾP",
                fontSize = 16.sp
            )
        }

        if (recording) {
            LinearProgressIndicator(
                progress = { recorder.level.coerceIn(0.02f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (seconds < 8) "Nói tiếp… nên ghi ít nhất 8 giây để dò ngôn ngữ chắc"
                else "✓ Đủ dài rồi, có thể dừng",
                fontSize = 12.sp,
                color = if (seconds < 8) Color(0xFF9C4221) else Color(0xFF2E7D32)
            )
        }

        OutlinedButton(
            onClick = { pickAudio.launch("audio/*") },
            enabled = !busy && !recording && cfg.ready,
            modifier = Modifier.fillMaxWidth()
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
                enabled = !busy && !recording,
                modifier = Modifier.weight(1f).height(54.dp)
            ) {
                Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("CHỤP")
            }
            OutlinedButton(
                onClick = { pickImage.launch("image/*") },
                enabled = !busy && !recording,
                modifier = Modifier.weight(1f).height(54.dp)
            ) {
                Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("THÊM ẢNH")
            }
        }

        Surface(color = Color(0xFFE8F4FD), shape = MaterialTheme.shapes.small) {
            Text(
                "📄 Chụp GIẤY TỜ, HỘ CHIẾU, VISA, CHỮ VIẾT — không phải khuôn mặt. " +
                        "Trang thông tin hộ chiếu (mã nước 3 chữ + dòng MRZ ở đáy) là bằng chứng mạnh nhất.",
                Modifier.padding(10.dp), fontSize = 12.sp, color = Color(0xFF0B4A6F)
            )
        }

        // ── Bằng chứng ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Bằng chứng hiện có", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(if (audio != null) "🎤 Có ghi âm" else "🎤 Chưa có ghi âm", fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🖼 Ảnh: ${images.size}", fontSize = 13.sp)
                    if (images.isNotEmpty()) {
                        TextButton(onClick = { images = emptyList() }) {
                            Text("Xoá hết", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Button(
            onClick = { trigger++ },
            enabled = !busy && !recording && cfg.ready && (audio != null || images.isNotEmpty()),
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

            Text(r.optString("ket_luan", "?"), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text("Độ tin cậy: ${r.optString("do_tin_cay")}  •  Nguồn: ${r.optString("nguon_phan_tich")}",
                fontSize = 13.sp, color = Color.Gray)
            Text(r.optString("ly_do"), fontSize = 14.sp)

            val loi = r.optString("loi_noi")
            if (loi.isNotBlank() && loi != "null") {
                Surface(color = Color(0xFFF5F5F5), shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("\"$loi\"", Modifier.padding(10.dp), fontSize = 14.sp)
                }
            }

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
                            speaker.speak(target, langTag) { err ->
                                if (err != null) android.widget.Toast.makeText(
                                    ctx, err, android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }) { Icon(Icons.Default.VolumeUp, "Phát") }
                    }
                }
            }

            OutlinedButton(
                onClick = { openInstallVoice(ctx) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.RecordVoiceOver, null); Spacer(Modifier.width(6.dp))
                Text("CÀI GIỌNG ĐỌC (nếu loa không phát)")
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
data class Turn(val foreigner: Boolean, val original: String, val translated: String)

@Composable
fun ChatTab(cfg: Config, speaker: Speaker, theirLang: String, theirLangTag: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { Recorder(ctx) }

    // Ngôn ngữ 2 bên — mặc định: TÔI = tiếng Việt, HỌ = tự động phát hiện.
    // Nếu tab Nhận diện đã ra kết quả, tự điền sẵn ngôn ngữ đối phương cho nhanh.
    var myLang by remember { mutableStateOf(LangOptions.vietnamese) }
    var theirOpt by remember {
        mutableStateOf(
            if (theirLang.isNotBlank())
                LangOptions.list(false).firstOrNull { it.display == theirLang }
                    ?: LangOptions.autoDetect
            else LangOptions.autoDetect
        )
    }

    var pickerFor by remember { mutableStateOf<Int?>(null) }   // 1 = họ, 2 = tôi

    var turns by remember { mutableStateOf<List<Turn>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var who by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf("") }

    fun handle(isForeigner: Boolean) {
        if (who == null) {
            if (recorder.start()) {
                who = isForeigner
                status = if (isForeigner) "Đang nghe họ nói…" else "Đang nghe anh nói…"
            } else status = "Không mở được micro."
            return
        }
        val f = recorder.stop()
        val wasForeigner = who!!
        who = null
        if (f == null) { status = "Đoạn ghi quá ngắn."; return }

        scope.launch {
            busy = true; status = "Đang dịch…"
            try {
                // Ai nói -> from là ngôn ngữ người đó; to là ngôn ngữ người kia
                val from = if (wasForeigner) theirOpt else myLang
                val to = if (wasForeigner) myLang else theirOpt
                val (orig, trans) = Engine.chatTurn(cfg, f, from, to)
                turns = turns + Turn(wasForeigner, orig, trans)
                // đọc bản dịch bằng ngôn ngữ ĐÍCH để người kia nghe
                speaker.speak(trans, to.bcp47) { err ->
                    if (err != null) android.widget.Toast.makeText(
                        ctx, err, android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                status = ""
            } catch (e: Exception) { status = "Lỗi: ${e.message}" }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Hội thoại 2 chiều", fontSize = 20.sp, fontWeight = FontWeight.Bold)

        // ── 2 ô chọn ngôn ngữ ──
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LangField("HỌ nói", theirOpt.display, Modifier.weight(1f)) { pickerFor = 1 }
            LangField("TÔI nói", myLang.display, Modifier.weight(1f)) { pickerFor = 2 }
        }
        if (theirOpt.isAuto) {
            Text("Bên HỌ để 'Tự động' — app tự dò. Chọn sẵn ngôn ngữ sẽ nhanh và chính xác hơn.",
                fontSize = 12.sp, color = Color.Gray)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { handle(true) },
                enabled = !busy && who != false && cfg.ready,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (who == true) Color(0xFFD32F2F) else Color(0xFF1565C0)
                ),
                modifier = Modifier.weight(1f).height(76.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (who == true) Icons.Default.Stop else Icons.Default.RecordVoiceOver, null)
                    Text(if (who == true) "DỪNG" else "HỌ NÓI", fontSize = 13.sp)
                }
            }
            Button(
                onClick = { handle(false) },
                enabled = !busy && who != true && cfg.ready,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (who == false) Color(0xFFD32F2F) else Color(0xFF2E7D32)
                ),
                modifier = Modifier.weight(1f).height(76.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (who == false) Icons.Default.Stop else Icons.Default.Mic, null)
                    Text(if (who == false) "DỪNG" else "TÔI NÓI", fontSize = 13.sp)
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

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            turns.reversed().forEach { t ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (t.foreigner) Color(0xFFE3F2FD) else Color(0xFFE8F5E9)
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (t.foreigner) "HỌ" else "TÔI",
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Text(t.original, fontSize = 14.sp, color = Color.DarkGray)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.translated, fontSize = 16.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                val tag = if (t.foreigner) myLang.bcp47 else theirOpt.bcp47
                                speaker.speak(t.translated, tag) { err ->
                                    if (err != null) android.widget.Toast.makeText(
                                        ctx, err, android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }) { Icon(Icons.Default.VolumeUp, null) }
                        }
                    }
                }
            }
        }
    }

    // ── Dialog chọn ngôn ngữ ──
    pickerFor?.let { target ->
        LanguagePickerDialog(
            withAuto = target == 1,             // chỉ bên HỌ mới có "Tự động"
            onPick = { opt ->
                if (target == 1) theirOpt = opt else myLang = opt
                pickerFor = null
            },
            onDismiss = { pickerFor = null }
        )
    }
}

/** Ô hiển thị ngôn ngữ đang chọn, bấm để mở dialog. */
@Composable
private fun LangField(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(56.dp)) {
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 10.sp, color = Color.Gray)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Icon(Icons.Default.ArrowDropDown, null)
    }
}

/** Dialog danh sách ngôn ngữ + ô tìm kiếm. */
@Composable
private fun LanguagePickerDialog(
    withAuto: Boolean,
    onPick: (LangOption) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val all = remember(withAuto) { LangOptions.list(withAuto) }
    val filtered = all.filter {
        query.isBlank() || it.display.lowercase().contains(query.lowercase())
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, color = Color.White) {
            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                Text("Chọn ngôn ngữ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Tìm (vd: Bengali, Anh, Ả Rập…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())
                ) {
                    if (filtered.isEmpty()) {
                        Text("Không tìm thấy.", Modifier.padding(12.dp), color = Color.Gray)
                    }
                    filtered.forEach { opt ->
                        Text(
                            opt.display,
                            Modifier.fillMaxWidth()
                                .clickable { onPick(opt) }
                                .padding(vertical = 12.dp),
                            fontSize = 15.sp
                        )
                        HorizontalDivider()
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
fun ConfigTab(cfg: Config, onSave: (Config) -> Unit) {
    var provider by remember { mutableStateOf(cfg.provider) }
    var gk by remember { mutableStateOf(cfg.geminiKey) }
    var ok by remember { mutableStateOf(cfg.openAiKey) }
    var ck by remember { mutableStateOf(cfg.claudeKey) }
    var msg by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(!cfg.ready) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Cấu hình", fontSize = 20.sp, fontWeight = FontWeight.Bold)

        // ── Chọn nhà cung cấp ──
        Text("Chọn nhà cung cấp AI", fontWeight = FontWeight.SemiBold)

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (provider == Provider.GEMINI) Color(0xFFE8F5E9) else Color(0xFFF5F5F5)
            )
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(provider == Provider.GEMINI, { provider = Provider.GEMINI; editing = true })
                Column(Modifier.weight(1f)) {
                    Text("Gemini — MIỄN PHÍ", fontWeight = FontWeight.SemiBold)
                    Text("1 key duy nhất, không cần thẻ. 250 lượt/ngày.",
                        fontSize = 12.sp, color = Color.Gray)
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (provider == Provider.OPENAI_CLAUDE) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
            )
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(provider == Provider.OPENAI_CLAUDE, {
                    provider = Provider.OPENAI_CLAUDE; editing = true
                })
                Column(Modifier.weight(1f)) {
                    Text("OpenAI + Claude — TRẢ PHÍ", fontWeight = FontWeight.SemiBold)
                    Text("2 key, nạp ~5 USD. Không dùng dữ liệu để huấn luyện.",
                        fontSize = 12.sp, color = Color.Gray)
                }
            }
        }

        // ── Cảnh báo riêng cho Gemini free ──
        if (provider == Provider.GEMINI) {
            Surface(color = Color(0xFFFFEBEE), shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()) {
                Text(
                    "🔴 QUAN TRỌNG — Gemini bản miễn phí:\n" +
                    "Google được phép dùng dữ liệu anh gửi lên để huấn luyện model. " +
                    "Nghĩa là ẢNH HỘ CHIẾU / VISA của người khác cũng có thể bị dùng để huấn luyện.\n\n" +
                    "Nếu chỉ dùng phần GIỌNG NÓI thì rủi ro thấp. " +
                    "Nếu chụp GIẤY TỜ của người khác, nên chuyển sang OpenAI + Claude (trả phí, không train trên dữ liệu API).",
                    Modifier.padding(12.dp), fontSize = 12.sp, color = Color(0xFFB71C1C)
                )
            }
        }

        HorizontalDivider()

        val saved = cfg.ready
        if (saved && !editing) {
            Surface(color = Color(0xFFE8F5E9), shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("✓ Đã lưu key — không cần nhập lại",
                        fontWeight = FontWeight.SemiBold, color = Color(0xFF1B5E20))
                    when (cfg.provider) {
                        Provider.GEMINI -> {
                            Text("Gemini: ${mask(cfg.geminiKey)}", fontSize = 13.sp, color = Color(0xFF33691E))
                            Text("Model: ${Engine.currentModel}", fontSize = 12.sp, color = Color(0xFF558B2F))
                        }
                        Provider.OPENAI_CLAUDE -> {
                            Text("OpenAI:    ${mask(cfg.openAiKey)}", fontSize = 13.sp, color = Color(0xFF33691E))
                            Text("Anthropic: ${mask(cfg.claudeKey)}", fontSize = 13.sp, color = Color(0xFF33691E))
                        }
                    }
                }
            }
            OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                Text("ĐỔI KEY")
            }
        } else {
            if (provider == Provider.GEMINI) {
                OutlinedTextField(gk, { gk = it },
                    label = { Text("Gemini API key (bắt đầu bằng AIza…)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(ok, { ok = it },
                    label = { Text("OpenAI key (sk-…)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ck, { ck = it },
                    label = { Text("Anthropic key (sk-ant-…)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
            }

            Button(
                onClick = {
                    val c = Config(provider, gk.trim(), ok.trim(), ck.trim())
                    if (!c.ready) {
                        msg = if (provider == Provider.GEMINI) "Cần nhập Gemini key."
                        else "Cần đủ cả 2 key."
                        return@Button
                    }
                    onSave(c)
                    editing = false
                    msg = "Đã lưu. Lần sau mở app không cần nhập lại."
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("LƯU") }
        }

        if (msg.isNotBlank()) Text(msg, color = Color(0xFF2E7D32), fontSize = 13.sp)

        HorizontalDivider()

        // ── Hướng dẫn lấy key ──
        Text("Lấy key ở đâu", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Gemini (MIỄN PHÍ — 1 key)", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "• Vào aistudio.google.com\n" +
                    "• Đăng nhập bằng tài khoản Google\n" +
                    "• Bấm \"Get API key\" → \"Create API key\"\n" +
                    "• Copy chuỗi bắt đầu bằng AIza…\n" +
                    "• KHÔNG cần thẻ tín dụng, KHÔNG hết hạn\n" +
                    "• Hạn mức: 250 lượt/ngày, 10 lượt/phút",
                    fontSize = 13.sp, color = Color.Gray
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("OpenAI + Anthropic (TRẢ PHÍ — 2 key)",
                    fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "OpenAI (Whisper — nghe giọng nói):\n" +
                    "• platform.openai.com → API keys → Create new secret key\n" +
                    "• Copy chuỗi sk-… (chỉ hiện MỘT LẦN)\n" +
                    "• Billing → nạp tối thiểu 5 USD\n" +
                    "• Giá ~0,006 USD/phút → 5 USD ≈ 800 phút ghi âm\n\n" +
                    "Anthropic (Claude — đọc giấy tờ, phân tích, dịch):\n" +
                    "• console.anthropic.com → Settings → API Keys → Create Key\n" +
                    "• Copy chuỗi sk-ant-… (chỉ hiện MỘT LẦN)\n" +
                    "• Plans & Billing → nạp tiền",
                    fontSize = 13.sp, color = Color.Gray
                )
            }
        }

        Surface(color = Color(0xFFFFF3CD), shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()) {
            Text("🔒 Key lưu trong bộ nhớ riêng của app, không gửi đi đâu ngoài chính nhà cung cấp. " +
                    "Gỡ app là mất, cài lại phải nhập lại.",
                Modifier.padding(10.dp), fontSize = 12.sp, color = Color(0xFF664D03))
        }

        HorizontalDivider()

        Text("Mẹo dùng", fontWeight = FontWeight.SemiBold)
        Text(
            "• Ghi âm ít nhất 8 giây — app TỰ dò ngôn ngữ và TỰ phân tích ngay sau khi dừng.\n" +
            "• Hỗ trợ mọi ngôn ngữ trên thế giới (Bengali, Pashto, Amhara, Wolof, Sinhala…).\n" +
            "• Chỉ ảnh → đọc chữ/giấy tờ. Chỉ giọng → phân tích ngôn ngữ. Cả hai → đối chiếu chéo.\n" +
            "• Ảnh mạnh nhất: trang thông tin hộ chiếu (mã nước 3 chữ + dòng MRZ ở đáy).\n" +
            "• Giọng đọc TTS Bengali/Urdu có thể chưa có — vào Cài đặt Android > Ngôn ngữ > Text-to-speech để tải thêm.",
            fontSize = 13.sp, color = Color.Gray
        )
        Spacer(Modifier.height(30.dp))
    }
}

private fun mask(k: String): String =
    if (k.length <= 12) "••••••••"
    else "${k.take(6)}••••••••${k.takeLast(4)}"

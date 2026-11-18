package app.lecturenotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }
}

sealed interface Screen {
    data object Home : Screen
    data class Detail(val id: String) : Screen
    data object Config : Screen
    data object GlossaryEdit : Screen
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val live by Live.state.collectAsState()
    var screen by remember { mutableStateOf<Screen>(live.sessionId?.let { Screen.Detail(it) } ?: Screen.Home) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())
        withContext(Dispatchers.IO) { Updater.check() }
    }

    BackHandler(enabled = screen != Screen.Home) {
        screen = if (screen == Screen.GlossaryEdit) Screen.Config else Screen.Home
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(onOpen = { screen = Screen.Detail(it) }, onSettings = { screen = Screen.Config })
            is Screen.Detail -> DetailScreen(s.id, onBack = { screen = Screen.Home })
            Screen.Config -> SettingsScreen(onBack = { screen = Screen.Home }, onGlossary = { screen = Screen.GlossaryEdit })
            Screen.GlossaryEdit -> GlossaryScreen(onBack = { screen = Screen.Config })
        }
    }
}

@Composable
private fun Header(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text("← 戻る") }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            maxLines = 1,
        )
        actions()
    }
}

// ---------------- 一覧 ----------------

@Composable
fun HomeScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val sessions by Repo.sessions.collectAsState()
    val live by Live.state.collectAsState()
    var showNew by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Header("講演メモ", null) { TextButton(onClick = onSettings) { Text("設定") } }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("版 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall)
            UpdateBanner()
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showNew = true },
                enabled = live.sessionId == null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("新しく録音する", style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(sessions, key = { it.id }) { s ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(s.id) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        val rec = if (live.sessionId == s.id) "・● 録音中" else ""
                        Text("${fmtDate(s.startedAt)}・${fmtClock(s.durationMs)}$rec", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showNew) {
        var title by remember { mutableStateOf("${fmtDate(System.currentTimeMillis())} 講演") }
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("新しく録音する") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("名前") }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    val s = Repo.create(title.ifBlank { "講演" })
                    showNew = false
                    RecorderService.start(context, s.id)
                    onOpen(s.id)
                }) { Text("録音を始める") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("やめる") } },
        )
    }
}

@Composable
private fun UpdateBanner() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by Updater.available.collectAsState()
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf("") }
    val r = update ?: return
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("新しい版 ${r.tag} があります", fontWeight = FontWeight.Bold)
            if (r.notes.isNotBlank()) Text(r.notes.take(300), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            val p = progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            } else {
                Button(onClick = {
                    scope.launch {
                        progress = 0f
                        try {
                            val f = withContext(Dispatchers.IO) { Updater.download(context, r) { progress = it } }
                            if (!Updater.install(context, f)) message = "「この提供元のアプリを許可」をオンにしてから、もう一度押してください"
                        } catch (e: Exception) {
                            message = "失敗しました: ${e.message}"
                        }
                        progress = null
                    }
                }) { Text("更新する") }
            }
            if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------- 記録 ----------------

private sealed interface Row_ {
    data class Seg(val seg: Segment) : Row_
    data class Mk(val mark: Mark) : Row_
}

@Composable
fun DetailScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by Repo.sessions.collectAsState()
    val live by Live.state.collectAsState()
    val status by Processor.status.collectAsState()
    val s = sessions.firstOrNull { it.id == id }
    if (s == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val isRec = live.sessionId == id
    val otherRec = live.sessionId != null && !isRec

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRec) {
        while (isRec) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Segment?>(null) }
    var markTime by remember { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }

    val rows = remember(s) {
        val marks = s.marks.sortedBy { it.timeMs }
        val out = ArrayList<Row_>()
        var mi = 0
        for (g in s.segments) {
            while (mi < marks.size && marks[mi].timeMs < g.startMs) out.add(Row_.Mk(marks[mi++]))
            out.add(Row_.Seg(g))
        }
        while (mi < marks.size) out.add(Row_.Mk(marks[mi++]))
        out
    }

    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(rows.size, live.partial) {
        if (isRec && atBottom && listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(s.title, onBack) {
            Box {
                TextButton(onClick = { menu = true }) { Text("…") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("文章を書き出す") }, onClick = {
                        menu = false
                        Export.shareText(context, s)
                    })
                    DropdownMenuItem(text = { Text("音声を書き出す") }, onClick = {
                        menu = false
                        scope.launch {
                            busy = "音声をまとめています…"
                            try {
                                val f = withContext(Dispatchers.IO) {
                                    Export.buildAudio(context, s) { p -> busy = "音声をまとめています… ${(p * 100).toInt()}%" }
                                }
                                Export.shareFile(context, f, "audio/wav")
                            } catch (e: Exception) {
                                busy = null
                                Processor.status.value = "音声を書き出せません: ${e.message}"
                            }
                            busy = null
                        }
                    })
                    DropdownMenuItem(text = { Text("整形をやり直す（全部）") }, onClick = {
                        menu = false
                        Repo.update(id) { ss -> ss.copy(segments = ss.segments.map { it.copy(formatted = null) }) }
                        Processor.wake()
                    })
                    DropdownMenuItem(text = { Text("文字起こしからやり直す（全部）") }, onClick = {
                        menu = false
                        Repo.update(id) { ss ->
                            ss.copy(segments = ss.segments.map { it.copy(raw = null, rawEngine = null, formatted = null, error = null) })
                        }
                        Processor.wake()
                    })
                    DropdownMenuItem(text = { Text("名前を変える") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("削除する") }, enabled = !isRec, onClick = { menu = false; confirmDelete = true })
                }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = listState) {
            items(rows) { row ->
                when (row) {
                    is Row_.Mk -> Text(
                        "★ ${fmtClock(row.mark.timeMs)}  ${row.mark.note}",
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    is Row_.Seg -> SegmentRow(row.seg) { selected = row.seg }
                }
            }
            if (isRec && live.partial.isNotBlank()) {
                item { Text(live.partial, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp)) }
            }
            if (rows.isEmpty() && !isRec) {
                item { Text("まだ録音がありません", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
            }
        }

        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            val (sttWait, fmtWait) = Processor.pendingCounts(s)
            Text(
                "文字起こし待ち $sttWait・整形待ち $fmtWait　$status",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
            )
            busy?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            if (isRec) {
                live.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("● ${fmtClock(live.elapsedMs(now))}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    LinearProgressIndicator(progress = { live.level }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(live.engine, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { markTime = live.elapsedMs() },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("★ 印を付ける") }
                    Button(
                        onClick = { RecorderService.stop(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("停止") }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { RecorderService.start(context, id) },
                    enabled = !otherRec,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(if (s.segments.isEmpty()) "録音を始める" else "続きを録音する") }
                if (otherRec) Text("ほかの記録を録音中です", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    markTime?.let { t ->
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { markTime = null },
            title = { Text("★ ${fmtClock(t)} に印") },
            text = { OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("メモ（なくてもよい）") }) },
            confirmButton = {
                Button(onClick = {
                    Repo.update(id) { it.copy(marks = it.marks + Mark(t, note.trim())) }
                    markTime = null
                }) { Text("付ける") }
            },
            dismissButton = { TextButton(onClick = { markTime = null }) { Text("やめる") } },
        )
    }

    selected?.let { g0 ->
        val g = s.segments.firstOrNull { it.index == g0.index } ?: g0
        var edit by remember(g.index) { mutableStateOf(g.formatted ?: g.raw ?: "") }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("${fmtClock(g.startMs)}〜${fmtClock(g.endMs)}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("文字起こし（${engineLabel(g.rawEngine)}）", style = MaterialTheme.typography.labelMedium)
                    Text(g.raw ?: "（まだ）", style = MaterialTheme.typography.bodySmall)
                    g.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = edit, onValueChange = { edit = it }, label = { Text("整形後（直せます）") })
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        Repo.updateSegment(id, g.index) { it.copy(raw = null, rawEngine = null, formatted = null, error = null, silent = false) }
                        Processor.wake()
                        selected = null
                    }) { Text("文字起こしからやり直す") }
                    TextButton(onClick = {
                        Repo.updateSegment(id, g.index) { it.copy(formatted = null) }
                        Processor.wake()
                        selected = null
                    }) { Text("整形だけやり直す") }
                }
            },
            confirmButton = {
                Button(onClick = {
                    Repo.updateSegment(id, g.index) { it.copy(formatted = edit) }
                    selected = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("閉じる") } },
        )
    }

    if (renaming) {
        var title by remember { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("名前を変える") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    Repo.update(id) { it.copy(title = title.ifBlank { it.title }) }
                    renaming = false
                }) { Text("変える") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("やめる") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("「${s.title}」を削除しますか？") },
            text = { Text("録音と文字がすべて消えます。元に戻せません。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        Repo.delete(id)
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("削除する") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("やめる") } },
        )
    }
}

@Composable
private fun SegmentRow(g: Segment, onClick: () -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        val tag = when {
            g.formatted != null -> "整形済み"
            g.raw != null -> engineLabel(g.rawEngine)
            else -> ""
        }
        Text("${fmtClock(g.startMs)}  $tag", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        val text = g.formatted ?: g.raw
        when {
            g.silent -> Text("（無音）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            g.rawEngine == "error" -> Text("文字起こしできませんでした（押すとやり直せます）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            text == null -> Text("文字起こし待ち…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            text.isBlank() -> Text("（文字なし）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            else -> Text(
                Scripture.annotate(text, linkColor),
                style = MaterialTheme.typography.bodyLarge,
                color = if (g.formatted == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ---------------- 設定 ----------------

@Composable
fun SettingsScreen(onBack: () -> Unit, onGlossary: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val st = remember { Settings(context) }

    var mode by remember { mutableStateOf(st.mode) }
    var groqKey by remember { mutableStateOf(st.groqKey) }
    var geminiKey by remember { mutableStateOf(st.geminiKey) }
    var groqStt by remember { mutableStateOf(st.groqSttModel) }
    var groqChat by remember { mutableStateOf(st.groqChatModel) }
    var gemini by remember { mutableStateOf(st.geminiModels) }
    var chunk by remember { mutableStateOf(st.chunkSeconds.toString()) }
    var batch by remember { mutableStateOf(st.formatBatch.toString()) }
    var formatOn by remember { mutableStateOf(st.formatEnabled) }
    var showKeys by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    var updateResult by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Header("設定", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Section("文字起こしの方法")
            Mode.entries.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = mode == m, onClick = {
                        mode = m; st.mode = m; Processor.wake()
                    }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == m, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(m.label)
                        Text(m.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { testResult = LocalRecognizer.prepareModel(context) }) { Text("端末内の日本語モデルを準備") }

            Section("API キー（無料で取れます）")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = showKeys, onCheckedChange = { showKeys = it })
                Text("キーを表示", Modifier.padding(start = 8.dp))
            }
            KeyField("Groq の API キー", groqKey, showKeys) { groqKey = it; st.groqKey = it.trim() }
            TextButton(onClick = { uri.openUri("https://console.groq.com/keys") }) { Text("Groq のキーを取る →") }
            KeyField("Gemini の API キー", geminiKey, showKeys) { geminiKey = it; st.geminiKey = it.trim() }
            TextButton(onClick = { uri.openUri("https://aistudio.google.com/apikey") }) { Text("Gemini のキーを取る →") }
            Text(
                "※ 無料枠では、送った音声や文章がサービスの改善に使われることがあります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    testResult = "確かめています…"
                    testResult = withContext(Dispatchers.IO) { testKeys(st) }
                    Cooldown.clear()
                    Processor.wake()
                }
            }) { Text("つながるか確かめる（使えるモデルも表示）") }
            if (testResult.isNotEmpty()) Text(testResult, style = MaterialTheme.typography.bodySmall)

            Section("モデル")
            Field("Gemini のモデル（カンマ区切り。前から順に使う）", gemini) { gemini = it; st.geminiModels = it }
            Field("Groq の文字起こしモデル", groqStt) { groqStt = it; st.groqSttModel = it.trim() }
            Field("Groq の整形モデル", groqChat) { groqChat = it; st.groqChatModel = it.trim() }

            Section("整形")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = formatOn, onCheckedChange = { formatOn = it; st.formatEnabled = it; Processor.wake() })
                Text("AI で整形する", Modifier.padding(start = 8.dp))
            }
            Field("何区間ずつまとめて整形するか", batch, KeyboardType.Number) {
                batch = it; it.toIntOrNull()?.let { v -> st.formatBatch = v.coerceIn(1, 20) }
            }
            Field("音声を区切る長さ（秒・10〜120）", chunk, KeyboardType.Number) {
                chunk = it; it.toIntOrNull()?.let { v -> st.chunkSeconds = v.coerceIn(10, 120) }
            }
            OutlinedButton(onClick = onGlossary) { Text("用語集を編集する") }

            Section("その他")
            OutlinedButton(onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }) { Text("電池の最適化の設定を開く") }
            Text(
                "長時間の録音が止まらないよう、「講演メモ」を最適化しない（制限なし）にしておくと安心です。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    updateResult = "確かめています…"
                    updateResult = withContext(Dispatchers.IO) { Updater.check() }
                }
            }) { Text("更新を確かめる（いま ${BuildConfig.VERSION_NAME}）") }
            if (updateResult.isNotEmpty()) Text(updateResult, style = MaterialTheme.typography.bodySmall)
            UpdateBanner()
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun testKeys(st: Settings): String = buildString {
    if (st.groqKey.isBlank()) {
        append("Groq：キーがありません\n")
    } else {
        try {
            val models = Groq.listModels(st.groqKey)
            append("Groq：OK\n  ${models.joinToString(", ")}\n")
        } catch (e: Exception) {
            append("Groq：失敗 ${e.message?.take(150)}\n")
        }
    }
    if (st.geminiKey.isBlank()) {
        append("Gemini：キーがありません")
    } else {
        try {
            val models = Gemini.listModels(st.geminiKey)
            append("Gemini：OK\n  ${models.joinToString(", ")}")
        } catch (e: Exception) {
            append("Gemini：失敗 ${e.message?.take(150)}")
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun KeyField(label: String, value: String, show: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

// ---------------- 用語集 ----------------

@Composable
fun GlossaryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(Glossary.load(context)) }
    var saved by remember { mutableStateOf(true) }
    var confirmReset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Header("用語集", onBack) {
            TextButton(onClick = { Glossary.save(context, text); saved = true }, enabled = !saved) { Text("保存") }
        }
        Text(
            "1行に1語。「誤→正」と書くとその直し方を AI に伝えます。#で始まる行は説明です。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; saved = false },
            modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 200.dp).padding(16.dp),
        )
        TextButton(onClick = { confirmReset = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("最初の内容に戻す") }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("最初の内容に戻しますか？") },
            text = { Text("書き足した語は消えます。") },
            confirmButton = {
                Button(onClick = {
                    Glossary.reset(context)
                    text = Glossary.load(context)
                    saved = true
                    confirmReset = false
                }) { Text("戻す") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("やめる") } },
        )
    }
}

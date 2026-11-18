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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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
import androidx.compose.runtime.key
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

@Composable
fun DetailScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by Repo.sessions.collectAsState()
    val live by Live.state.collectAsState()
    val status by Processor.status.collectAsState()
    val polishStatus by Processor.polishStatus.collectAsState()
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

    var learned by remember { mutableStateOf<Pair<Int, List<Corrections.Fix>>?>(null) }
    val rows = remember(s) { Transcript.paragraphs(s) }

    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(rows.size, live.partial, live.liveText) {
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
                                Export.shareFile(context, f, "audio/mp4")
                            } catch (e: Exception) {
                                busy = null
                                Processor.status.value = "音声を書き出せません: ${e.message}"
                            }
                            busy = null
                        }
                    })
                    DropdownMenuItem(text = { Text("整形をやり直す（全部）") }, onClick = {
                        menu = false
                        Repo.update(id) { ss -> ss.copy(segments = ss.segments.map { it.copy(formatted = null, polished = false) }) }
                        Processor.wake()
                    })
                    DropdownMenuItem(text = { Text("文字起こしからやり直す（全部）") }, onClick = {
                        menu = false
                        Repo.update(id) { ss ->
                            ss.copy(segments = ss.segments.map { it.copy(raw = null, rawEngine = null, formatted = null, polished = false, error = null) })
                        }
                        Processor.wake()
                    })
                    DropdownMenuItem(text = { Text("名前を変える") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("削除する") }, enabled = !isRec, onClick = { menu = false; confirmDelete = true })
                }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = listState) {
            items(rows) { para ->
                ParaView(para) { selected = it }
            }
            if (isRec) {
                item { LiveBlock(live, now) }
            }
            if (rows.isEmpty() && !isRec) {
                item { Text("まだ録音がありません", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
            }
        }

        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            val p = Processor.pendingCounts(s)
            Text(
                "清書待ち ${p.stt}・整形待ち ${p.fast}・仕上げ待ち ${p.polish}　$status" +
                    (if (polishStatus.isNotEmpty()) "　$polishStatus" else ""),
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
        val before = displayText(g) ?: ""
        var edit by remember(g.index) { mutableStateOf(before) }
        val pos = s.segments.indexOfFirst { it.index == g.index }
        val prev = s.segments.getOrNull(pos - 1)?.let { displayText(it) }?.takeLast(60)
        val next = s.segments.getOrNull(pos + 1)?.let { displayText(it) }?.take(60)
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("直す（${fmtClock(g.startMs)}〜${fmtClock(g.endMs)}）") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (!prev.isNullOrBlank()) Text("…$prev", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    OutlinedTextField(
                        value = edit, onValueChange = { edit = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    if (!next.isNullOrBlank()) Text("$next…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text(
                        "空行を入れると、そこで段落が分かれます。直したところは AI が上書きせず、直し方を覚えてこの先の文字起こし・整形に使います。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("文字起こし（${engineLabel(g.rawEngine, g.rawModel)}）", style = MaterialTheme.typography.labelMedium)
                    Text(g.raw ?: "（まだ）", style = MaterialTheme.typography.bodySmall)
                    g.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (g.edited != null) {
                        TextButton(onClick = {
                            Repo.updateSegment(id, g.index) { it.copy(edited = null) }
                            Processor.wake()
                            selected = null
                        }) { Text("自分の直しをやめて AI の文に戻す") }
                    }
                    TextButton(onClick = {
                        Repo.updateSegment(id, g.index) { it.copy(raw = null, rawEngine = null, formatted = null, polished = false, edited = null, error = null, silent = false) }
                        Processor.wake()
                        selected = null
                    }) { Text("文字起こしからやり直す") }
                    TextButton(onClick = {
                        Repo.updateSegment(id, g.index) { it.copy(formatted = null, polished = false, edited = null) }
                        Processor.wake()
                        selected = null
                    }) { Text("整形だけやり直す") }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (edit != before) {
                        val fixes = Corrections.extract(before, edit)
                        Repo.updateSegment(id, g.index) { it.copy(edited = edit) }
                        if (fixes.isNotEmpty()) {
                            Repo.update(id) { ss -> ss.copy(corrections = (ss.corrections + fixes.map { it.toString() }).distinct()) }
                            learned = g.index to fixes
                        }
                        Processor.wake()
                    }
                    selected = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("閉じる") } },
        )
    }

    learned?.let { (segIndex, fixes) ->
        val others = remember(fixes) { Corrections.countOthers(s, segIndex, fixes) }
        var toGlossary by remember(fixes) { mutableStateOf(true) }
        var toOthers by remember(fixes) { mutableStateOf(others > 0) }
        AlertDialog(
            onDismissRequest = { learned = null },
            title = { Text("直し方を覚えました") },
            text = {
                Column {
                    fixes.forEach { Text("・${it.from} → ${it.to}") }
                    Text(
                        "この記録のこの先の文字起こし・整形で、このとおりに直すよう AI に伝えます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = toGlossary, onCheckedChange = { toGlossary = it })
                        Text("用語集にも足す（ほかの記録でも使う）", Modifier.padding(start = 8.dp))
                    }
                    if (others > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = toOthers, onCheckedChange = { toOthers = it })
                            Text("すでにある同じ誤り（${others}か所）も直す", Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (toGlossary) Glossary.append(context, fixes.map { it.toString() }, "直したところから")
                    if (toOthers) Corrections.applyToOthers(id, segIndex, fixes)
                    learned = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { learned = null }) { Text("この記録だけ") } },
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

/** 録音中の、まだ区切られていない部分 */
@Composable
private fun LiveBlock(live: Live.State, now: Long) {
    val sec = if (live.chunkStartedAt > 0) ((now - live.chunkStartedAt) / 1000).coerceAtLeast(0) else 0
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "● 聞き取り中（${sec}秒）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        val text = (live.liveText + " " + live.partial).trim()
        if (text.isNotEmpty()) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline, fontStyle = FontStyle.Italic)
        } else {
            Text(
                "…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 1つの段落。押したところの区間を直せる。聖句の参照を押すと wol.jw.org を開く */
@Composable
private fun ParaView(para: Transcript.Para, onEdit: (Segment) -> Unit) {
    val uri = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme
    val built = remember(para, cs) {
        val segRanges = ArrayList<Triple<Int, Int, Segment>>()
        val urlRanges = ArrayList<Triple<Int, Int, String>>()
        val text = buildAnnotatedString {
            for (p in para.pieces) {
                val start = length
                val style = when (p.kind) {
                    Transcript.Kind.FINAL -> SpanStyle(color = cs.onSurface)
                    Transcript.Kind.RAW -> SpanStyle(color = cs.onSurfaceVariant)
                    Transcript.Kind.DRAFT -> SpanStyle(color = cs.outline, fontStyle = FontStyle.Italic)
                    Transcript.Kind.PENDING -> SpanStyle(color = cs.outline)
                    Transcript.Kind.MARK -> SpanStyle(color = cs.tertiary, fontWeight = FontWeight.Bold)
                }
                withStyle(style) { append(if (p.kind == Transcript.Kind.MARK) " ${p.text} " else p.text) }
                if (p.kind != Transcript.Kind.MARK) {
                    for (r in Scripture.find(p.text)) {
                        addStyle(SpanStyle(color = cs.primary, textDecoration = TextDecoration.Underline), start + r.start, start + r.end)
                        urlRanges.add(Triple(start + r.start, start + r.end, Scripture.url(r.book, r.chapter)))
                    }
                }
                p.seg?.let { segRanges.add(Triple(start, length, it)) }
            }
        }
        Triple(text, segRanges, urlRanges)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(fmtClock(para.startMs), style = MaterialTheme.typography.labelSmall, color = cs.outline)
        Text(
            built.first,
            style = MaterialTheme.typography.bodyLarge,
            onTextLayout = { layout = it },
            modifier = Modifier.pointerInput(built) {
                detectTapGestures { pos ->
                    val off = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
                    built.third.firstOrNull { off in it.first until it.second }?.let {
                        uri.openUri(it.third)
                        return@detectTapGestures
                    }
                    (built.second.firstOrNull { off in it.first until it.second } ?: built.second.lastOrNull { it.first <= off })
                        ?.let { onEdit(it.third) }
                }
            },
        )
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
    var autoModels by remember { mutableStateOf(st.autoModels) }
    var manualStt by remember { mutableStateOf(st.manualStt) }
    var manualFormat by remember { mutableStateOf(st.manualFormat) }
    var manualPolish by remember { mutableStateOf(st.manualPolish) }
    var polishOn by remember { mutableStateOf(st.polishEnabled) }
    var liveOn by remember { mutableStateOf(st.livePreview) }
    var catalogMsg by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoModels, onCheckedChange = { autoModels = it; st.autoModels = it; Processor.wake() })
                Text("自動で選ぶ（おすすめ）", Modifier.padding(start = 8.dp))
            }
            Text(
                "使えるモデルの一覧を取ってきて、聞き取りは「速くて回数が多いもの」、整形は「頭の良いもの」から順に使います。" +
                    "制限にかかったら次のモデルへ、制限が戻ればまた上のモデルへ戻ります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            OutlinedButton(onClick = {
                scope.launch {
                    catalogMsg = "取っています…"
                    catalogMsg = withContext(Dispatchers.IO) { Catalog.refresh(context, st) }
                    tick++
                }
            }) { Text("モデルの一覧を取り直す") }
            if (catalogMsg.isNotEmpty()) Text(catalogMsg, style = MaterialTheme.typography.bodySmall)
            if (!autoModels) {
                Text("1行に「groq:モデル名」か「gemini:モデル名」。上から使います。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = manualStt, onValueChange = { manualStt = it; st.manualStt = it },
                    label = { Text(Role.STT.label) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
                OutlinedTextField(
                    value = manualFormat, onValueChange = { manualFormat = it; st.manualFormat = it },
                    label = { Text(Role.FAST.label) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
                OutlinedTextField(
                    value = manualPolish, onValueChange = { manualPolish = it; st.manualPolish = it },
                    label = { Text(Role.POLISH.label) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }
            key(tick, autoModels, mode, groqKey, geminiKey) {
                ModelOrder(st)
            }

            Section("整形")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = formatOn, onCheckedChange = { formatOn = it; st.formatEnabled = it; Processor.wake() })
                Text("AI で整形する", Modifier.padding(start = 8.dp))
            }
            Text(
                "清書（クラウドの文字起こし）が来たら、速いモデルですぐ整形し、あとから頭の良いモデルで仕上げ直します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = polishOn, onCheckedChange = { polishOn = it; st.polishEnabled = it; Processor.wake() })
                Text("頭の良いモデルで仕上げ直す", Modifier.padding(start = 8.dp))
            }
            Field("すぐの整形を何区間ずつまとめるか", batch, KeyboardType.Number) {
                batch = it; it.toIntOrNull()?.let { v -> st.formatBatch = v.coerceIn(1, 20) }
            }

            Section("その場の表示")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = liveOn, onCheckedChange = { liveOn = it; st.livePreview = it })
                Text("端末内の音声認識で、その場で文字を出す", Modifier.padding(start = 8.dp))
            }
            Text(
                "話した直後に文字が出ます（Android 13 以上）。区切りごとにクラウドの清書で置き換わります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Field("清書に送る区切りの目安（秒・5〜120）", chunk, KeyboardType.Number) {
                chunk = it; it.toIntOrNull()?.let { v -> st.chunkSeconds = v.coerceIn(5, 120) }
            }
            Text(
                "短いほど清書が早く出ますが、無料枠の回数を多く使います。目安を過ぎたら話の切れ目で区切ります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
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

@Composable
private fun ModelOrder(st: Settings) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            now = System.currentTimeMillis()
        }
    }
    Role.entries.forEach { role ->
        Spacer(Modifier.height(8.dp))
        Text("${role.label}の順番", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        val list = remember(now) { Catalog.candidates(context, st, role) }
        if (list.isEmpty()) {
            Text("（使えるモデルがありません。キーを入れてください）", style = MaterialTheme.typography.bodySmall)
        }
        list.forEachIndexed { i, c ->
            val wait = Cooldown.remainingMs(c.key)
            val state = if (wait > 0) {
                val m = wait / 60000
                if (m >= 60) "休み（あと${m / 60}時間${m % 60}分）" else "休み（あと${m + 1}分）"
            } else "使える"
            Text(
                "${i + 1}. ${c.model}（${if (c.provider == "groq") "Groq" else "Gemini"}）　$state・今日 ${Usage.get(c.key)} 回",
                style = MaterialTheme.typography.bodySmall,
                color = if (wait > 0) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
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
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        Header("用語集", onBack) {
            TextButton(onClick = { Glossary.save(context, text); saved = true }, enabled = !saved) { Text("保存") }
        }
        Text(
            "1行に1語。「誤→正」と書くとその直し方を AI に伝えます。#で始まる行は説明です。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; saved = false },
            modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 200.dp).padding(16.dp),
        )
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { importing = true }) { Text("貼り付けて取り込む") }
            TextButton(onClick = { uri.openUri(WOL_GLOSSARY_SEARCH) }) { Text("wol.jw.org を開く") }
        }
        TextButton(onClick = { confirmReset = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("最初の内容に戻す") }
    }
    if (importing) {
        ImportDialog(
            onDismiss = { importing = false },
            onAdd = { words ->
                if (!saved) Glossary.save(context, text)
                val n = Glossary.append(context, words, "wol.jw.org から取り込み")
                text = Glossary.load(context)
                saved = true
                importing = false
                message = "${n}語を足しました"
            },
        )
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

private const val WOL_GLOSSARY_SEARCH = "https://wol.jw.org/ja/wol/s/r7/lp-j?q=%E7%94%A8%E8%AA%9E%E9%9B%86"

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    var pasted by remember { mutableStateOf("") }
    var words by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (words == null) "貼り付けて取り込む" else "取り出した語（直せます）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val w = words
                if (w == null) {
                    Text(
                        "wol.jw.org で聖書の付録の「用語集」などを開き、本文をコピーしてここに貼り付けてください。見出し語だけを取り出します。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = pasted, onValueChange = { pasted = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                    )
                } else {
                    Text(
                        "${w.lines().count { it.isNotBlank() }}語。余計な語は消してください。すでに用語集にある語は足しません。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = w, onValueChange = { words = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                    )
                }
            }
        },
        confirmButton = {
            val w = words
            if (w == null) {
                Button(onClick = { words = Glossary.extractHeadwords(pasted).joinToString("\n") }, enabled = pasted.isNotBlank()) { Text("取り出す") }
            } else {
                Button(onClick = { onAdd(w.lines().filter { it.isNotBlank() }) }) { Text("用語集に足す") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

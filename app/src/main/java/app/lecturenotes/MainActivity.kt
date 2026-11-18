@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import androidx.compose.ui.unit.sp
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
    /** 設定。back は戻る先（録音中の画面から開いたら、そこへ戻る） */
    data class Config(val back: Screen) : Screen
    data class GlossaryEdit(val back: Config) : Screen
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Live.state.value.sessionId?.let { Screen.Detail(it) } ?: Screen.Home) }

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
        screen = when (val s = screen) {
            is Screen.GlossaryEdit -> s.back
            is Screen.Config -> s.back
            else -> Screen.Home
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(onOpen = { screen = Screen.Detail(it) }, onSettings = { screen = Screen.Config(Screen.Home) })
            is Screen.Detail -> DetailScreen(s.id, onBack = { screen = Screen.Home }, onSettings = { screen = Screen.Config(s) })
            is Screen.Config -> SettingsScreen(onBack = { screen = s.back }, onGlossary = { screen = Screen.GlossaryEdit(s) })
            is Screen.GlossaryEdit -> GlossaryScreen(onBack = { screen = s.back })
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
        Header("たっぴぃず録音", null) { TextButton(onClick = onSettings) { Text("設定") } }
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
fun DetailScreen(id: String, onBack: () -> Unit, onSettings: () -> Unit) {
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
    var editing by remember { mutableStateOf<Int?>(null) }
    var noteFor by remember { mutableStateOf<Long?>(null) }
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
        if (isRec && atBottom && editing == null && listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(s.title, onBack) {
            TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("⚙", fontSize = 22.sp) }
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

        RecordControls(s, isRec, otherRec, live, now, onMark = {
            val t = live.elapsedMs()
            Repo.update(id) { it.copy(marks = it.marks + Mark(t, "")) }
            noteFor = t
        })
        HorizontalDivider()

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = listState) {
            items(rows) { para ->
                ParaView(
                    para, editing,
                    onEdit = { editing = it.index },
                    onMark = { Marker.toggleWhole(id, it.index) },
                ) { g ->
                    InlineEditor(
                        s, g,
                        onClose = { editing = null },
                        onLearned = { fixes -> learned = g.index to fixes },
                    )
                }
            }
            if (isRec) {
                item { LiveBlock(live, now) }
            }
            if (rows.isEmpty() && !isRec) {
                item { Text("まだ録音がありません", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
            }
        }

        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            val p = Processor.pendingCounts(s)
            Text(
                "清書待ち ${p.stt}・整形待ち ${p.fast}・仕上げ待ち ${p.polish}　$status" +
                    (if (polishStatus.isNotEmpty()) "　$polishStatus" else ""),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
            )
            busy?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            learned?.let { (segIndex, fixes) ->
                LearnedBar(s, segIndex, fixes) { learned = null }
            }
            noteFor?.let { t ->
                NoteBar(t, onSave = { note ->
                    Repo.update(id) { ss -> ss.copy(marks = ss.marks.map { if (it.timeMs == t) it.copy(note = note) else it }) }
                    noteFor = null
                }, onClose = { noteFor = null })
            }
        }
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

/** 画面の上の録音の操作。停止は押し間違えないよう、2回押して止める */
@Composable
private fun RecordControls(s: Session, isRec: Boolean, otherRec: Boolean, live: Live.State, now: Long, onMark: () -> Unit) {
    val context = LocalContext.current
    var stopArmed by remember { mutableStateOf(false) }
    LaunchedEffect(stopArmed) {
        if (stopArmed) {
            delay(3000)
            stopArmed = false
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (isRec) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("● ${fmtClock(live.elapsedMs(now))}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                LevelBar(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onMark, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("★") }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = {
                        if (stopArmed) {
                            stopArmed = false
                            RecorderService.stop(context)
                        } else stopArmed = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (stopArmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.errorContainer,
                        contentColor = if (stopArmed) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { Text(if (stopArmed) "もう一度で停止" else "停止") }
            }
            Text(live.engine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            live.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } else {
            Button(
                onClick = { RecorderService.start(context, s.id) },
                enabled = !otherRec,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (s.segments.isEmpty()) "● 録音を始める" else "● 続きを録音する") }
            if (otherRec) Text("ほかの記録を録音中です", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 音の大きさ。ここだけが 0.1 秒ごとに描き直される */
@Composable
private fun LevelBar(modifier: Modifier) {
    val level by Live.level.collectAsState()
    LinearProgressIndicator(progress = { level }, modifier = modifier)
}

/** 録音中の、まだ区切られていない部分（端末内の仮の文字を、文章の続きとして薄く出す） */
@Composable
private fun LiveBlock(live: Live.State, @Suppress("UNUSED_PARAMETER") now: Long) {
    val text = (live.liveText + " " + live.partial).trim()
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.error, fontSize = 10.sp)) { append("● ") }
            append(text.ifEmpty { "…" })
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.outline,
        fontStyle = FontStyle.Italic,
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
    )
}

private val MARKER_COLOR = androidx.compose.ui.graphics.Color(0x80FFE14D)

/** 1つの段落。押したところの区間をその場で直せる。長押しでマーカー。聖句の参照を押すと wol.jw.org を開く */
@Composable
private fun ParaView(
    para: Transcript.Para,
    editingIndex: Int?,
    onEdit: (Segment) -> Unit,
    onMark: (Segment) -> Unit,
    editor: @Composable (Segment) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        // 直している区間のところで文を分け、その区間だけ入力欄にする
        val groups = ArrayList<Any>() // List<Piece> か Segment（入力欄）
        var run = ArrayList<Transcript.Piece>()
        for (p in para.pieces) {
            val g = p.seg
            if (g != null && g.index == editingIndex) {
                val dt = displayText(g)
                val first = dt.isNullOrBlank() || dt.trimStart().startsWith(p.text.trimStart())
                if (run.isNotEmpty()) { groups.add(run); run = ArrayList() }
                if (first) groups.add(g)
            } else run.add(p)
        }
        if (run.isNotEmpty()) groups.add(run)
        for (gr in groups) {
            if (gr is Segment) editor(gr)
            else {
                @Suppress("UNCHECKED_CAST")
                PiecesText(gr as List<Transcript.Piece>, onEdit, onMark)
            }
        }
    }
}

@Composable
private fun PiecesText(pieces: List<Transcript.Piece>, onEdit: (Segment) -> Unit, onMark: (Segment) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val uri = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme
    val built = remember(pieces, cs) {
        val segRanges = ArrayList<Triple<Int, Int, Segment>>()
        val urlRanges = ArrayList<Triple<Int, Int, String>>()
        val text = buildAnnotatedString {
            for (p in pieces) {
                val start = length
                val style = when (p.kind) {
                    Transcript.Kind.FINAL -> SpanStyle(color = cs.onSurface)
                    Transcript.Kind.RAW -> SpanStyle(color = cs.onSurfaceVariant)
                    Transcript.Kind.DRAFT -> SpanStyle(color = cs.outline, fontStyle = FontStyle.Italic)
                    Transcript.Kind.PENDING -> SpanStyle(color = cs.outline)
                    Transcript.Kind.MARK -> SpanStyle(color = cs.tertiary, fontWeight = FontWeight.Bold)
                    Transcript.Kind.TIME -> SpanStyle(color = cs.outline.copy(alpha = 0.6f), fontSize = 10.sp)
                }
                withStyle(style) {
                    append(
                        when (p.kind) {
                            Transcript.Kind.MARK -> " ${p.text} "
                            Transcript.Kind.TIME -> "\u2009${p.text}\u2009"
                            else -> p.text
                        },
                    )
                }
                p.seg?.let { g ->
                    val bg = SpanStyle(background = MARKER_COLOR)
                    if (Marker.WHOLE in g.highlights) addStyle(bg, start, length)
                    val full = displayText(g) ?: ""
                    for (h in g.highlights) {
                        if (h == Marker.WHOLE) continue
                        for (r in Marker.find(p.text, h)) addStyle(bg, start + r.first, start + r.last + 1)
                        // AI が書き換えて見つからなくなった語句は、区間の最後に残す
                        if (Marker.find(full, h).isEmpty() && full.trimEnd().endsWith(p.text.trimEnd())) {
                            withStyle(bg) { append(" 🖍$h") }
                        }
                    }
                }
                if (p.kind != Transcript.Kind.MARK && p.kind != Transcript.Kind.TIME) {
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
    Text(
        built.first,
        style = MaterialTheme.typography.bodyLarge,
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().pointerInput(built) {
            fun segAt(pos: androidx.compose.ui.geometry.Offset): Segment? {
                val off = layout?.getOffsetForPosition(pos) ?: return null
                return (built.second.firstOrNull { off in it.first until it.second } ?: built.second.lastOrNull { it.first <= off })?.third
            }
            detectTapGestures(
                onLongPress = { pos ->
                    segAt(pos)?.let {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMark(it)
                    }
                },
                onTap = { pos ->
                    val off = layout?.getOffsetForPosition(pos)
                    val url = off?.let { o -> built.third.firstOrNull { o in it.first until it.second } }
                    if (url != null) uri.openUri(url.third) else segAt(pos)?.let { onEdit(it) }
                },
            )
        },
    )
}

/** 文章の中でそのまま直す入力欄 */
@Composable
private fun InlineEditor(s: Session, g: Segment, onClose: () -> Unit, onLearned: (List<Corrections.Fix>) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val before = remember(g.index) { displayText(g) ?: "" }
    var field by remember(g.index) { mutableStateOf(TextFieldValue(before, androidx.compose.ui.text.TextRange(before.length))) }
    var more by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(g.index) { runCatching { focus.requestFocus() } }

    fun save() {
        val edit = field.text
        if (edit != before) {
            val fixes = Corrections.extract(before, edit)
            Repo.updateSegment(s.id, g.index) { it.copy(edited = edit) }
            if (fixes.isNotEmpty()) {
                Repo.update(s.id) { ss -> ss.copy(corrections = (ss.corrections + fixes.map { it.toString() }).distinct()) }
                onLearned(fixes)
            }
            Processor.wake()
        }
        onClose()
    }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .background(cs.secondaryContainer.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        BasicTextField(
            value = field,
            onValueChange = { field = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val sel = field.selection
            SmallBtn("確定", primary = true) { save() }
            SmallBtn("やめる") { onClose() }
            SmallBtn("🖍 選んだ所", enabled = !sel.collapsed) {
                Marker.add(s.id, g.index, field.text.substring(sel.min, sel.max))
                field = field.copy(selection = androidx.compose.ui.text.TextRange(sel.max))
            }
            SmallBtn(if (Marker.WHOLE in g.highlights) "🖍 全体を消す" else "🖍 全体") { Marker.toggleWhole(s.id, g.index) }
            Box {
                SmallBtn("…") { more = true }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(if (showRaw) "元の文字起こしを隠す" else "元の文字起こしを見る") }, onClick = { more = false; showRaw = !showRaw })
                    if (g.edited != null) {
                        DropdownMenuItem(text = { Text("自分の直しをやめて AI の文に戻す") }, onClick = {
                            more = false
                            Repo.updateSegment(s.id, g.index) { it.copy(edited = null) }
                            Processor.wake(); onClose()
                        })
                    }
                    DropdownMenuItem(text = { Text("整形だけやり直す") }, onClick = {
                        more = false
                        Repo.updateSegment(s.id, g.index) { it.copy(formatted = null, polished = false, edited = null) }
                        Processor.wake(); onClose()
                    })
                    DropdownMenuItem(text = { Text("文字起こしからやり直す") }, onClick = {
                        more = false
                        Repo.updateSegment(s.id, g.index) { it.copy(raw = null, rawEngine = null, formatted = null, polished = false, edited = null, error = null, silent = false) }
                        Processor.wake(); onClose()
                    })
                }
            }
        }
        g.highlights.filter { it != Marker.WHOLE }.forEach { h ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🖍 $h", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                SmallBtn("×") { Marker.remove(s.id, g.index, h) }
            }
        }
        if (showRaw) {
            Text(
                "元（${engineLabel(g.rawEngine, g.rawModel)}・${fmtClock(g.startMs)}〜${fmtClock(g.endMs)}）：${g.raw ?: "（まだ）"}",
                style = MaterialTheme.typography.bodySmall,
                color = cs.outline,
            )
        }
    }
}

@Composable
private fun SmallBtn(label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.heightIn(min = 36.dp),
    ) { Text(label, fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal) }
}

/** 「直し方を覚えました」を、画面の下に帯で出す */
@Composable
private fun LearnedBar(s: Session, segIndex: Int, fixes: List<Corrections.Fix>, onClose: () -> Unit) {
    val context = LocalContext.current
    val others = remember(fixes) { Corrections.countOthers(s, segIndex, fixes) }
    var addedGlossary by remember(fixes) { mutableStateOf(false) }
    var fixedOthers by remember(fixes) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text("覚えた直し：${fixes.joinToString("、")}（この先の文字起こし・整形に使います）", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallBtn(if (addedGlossary) "用語集に足しました" else "用語集にも足す", enabled = !addedGlossary) {
                Glossary.append(context, fixes.map { it.toString() }, "直したところから")
                addedGlossary = true
            }
            if (others > 0) {
                SmallBtn(if (fixedOthers) "直しました" else "ほかの${others}か所も直す", enabled = !fixedOthers) {
                    Corrections.applyToOthers(s.id, segIndex, fixes)
                    fixedOthers = true
                }
            }
            SmallBtn("閉じる") { onClose() }
        }
    }
}

/** ★ を付けたあと、メモをその場で書く帯 */
@Composable
private fun NoteBar(timeMs: Long, onSave: (String) -> Unit, onClose: () -> Unit) {
    var note by remember(timeMs) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("★ ${fmtClock(timeMs)}", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            placeholder = { Text("メモ（なくてもよい）") },
            singleLine = true,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        SmallBtn("保存", primary = true, enabled = note.isNotBlank()) { onSave(note.trim()) }
        SmallBtn("×") { onClose() }
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
    var fastMin by remember { mutableStateOf(st.fastMinSec.toString()) }
    var fastMax by remember { mutableStateOf(st.fastMaxSec.toString()) }
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
            Text(
                "すぐの整形は、話に長めの間（2秒ほど）があったときに、たまった分をまとめて行います。間がなくても決まった秒数たてば行います。" +
                    "まとめるほど呼び出しの回数とトークンが減り、無料枠が長持ちします。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Field("間で整形するのは、たまった音声が何秒以上のとき", fastMin, KeyboardType.Number) {
                fastMin = it; it.toIntOrNull()?.let { v -> st.fastMinSec = v.coerceIn(5, 120) }
            }
            Field("間がなくても整形するまでの秒数", fastMax, KeyboardType.Number) {
                fastMax = it; it.toIntOrNull()?.let { v -> st.fastMaxSec = v.coerceIn(15, 180) }
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
            Field("清書に送る区切りの目安（秒・10〜120）", chunk, KeyboardType.Number) {
                chunk = it; it.toIntOrNull()?.let { v -> st.chunkSeconds = v.coerceIn(10, 120) }
            }
            Text(
                "10秒を過ぎたら文の切れ目（0.6秒ほどの間）で区切り、目安を過ぎたら少しでも静かなところで区切ります。短いほど清書が早く出ますが、無料枠の回数を多く使います。録音中に変えても、次の区切りから効きます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            OutlinedButton(onClick = onGlossary) { Text("用語集を編集する") }

            Section("その他")
            OutlinedButton(onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }) { Text("電池の最適化の設定を開く") }
            Text(
                "長時間の録音が止まらないよう、「たっぴぃず録音」を最適化しない（制限なし）にしておくと安心です。",
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
            val wait = maxOf(Cooldown.remainingMs(c.key), Cooldown.remainingMs("${c.key}#${role.name}"))
            val state = if (wait > 0) {
                val m = wait / 60000
                if (m >= 60) "休み（あと${m / 60}時間${m % 60}分）" else "休み（あと${m + 1}分）"
            } else "使える"
            Text(
                "${i + 1}. ${c.model}（${if (c.provider == "groq") "Groq" else "Gemini"}）　$state・今日 ${Usage.get(c.key)} 回" +
                    (Latency.avgMs(context, role, c)?.let { "・平均 %.1f 秒".format(it / 1000.0) + if (Latency.slow(context, role, c)) "（遅いので後回し）" else "" } ?: ""),
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

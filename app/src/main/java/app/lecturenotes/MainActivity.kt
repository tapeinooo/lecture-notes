@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.animateFloat
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
    /** openInfo = 情報欄を開いた状態で表示する（新しく録音したとき） */
    data class Detail(val id: String, val openInfo: Boolean = false) : Screen
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
            Screen.Home -> HomeScreen(
                onOpen = { id, info -> screen = Screen.Detail(id, info) },
                onSettings = { screen = Screen.Config(Screen.Home) },
            )
            is Screen.Detail -> DetailScreen(
                s.id, s.openInfo,
                onBack = { screen = Screen.Home },
                onSettings = { screen = Screen.Config(s.copy(openInfo = false)) },
            )
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
fun HomeScreen(onOpen: (String, Boolean) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val sessions by Repo.sessions.collectAsState()
    val live by Live.state.collectAsState()
    val labels by Meta.labels.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val shown = sessions.filter { filter == null || filter in it.labels }

    Column(Modifier.fillMaxSize()) {
        Header("たっぴぃず録音", null) {
            TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("⚙", fontSize = 22.sp) }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("版 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall)
            UpdateBanner()
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    // すぐ録音を始める（名前・話し手・ラベルは録音しながら入れられる）
                    val s = Repo.create(fmtDate(System.currentTimeMillis()))
                    RecorderService.start(context, s.id)
                    onOpen(s.id, true)
                },
                enabled = live.sessionId == null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("● 新しく録音する", style = MaterialTheme.typography.titleMedium) }
            // ラベルで絞り込む
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("すべて") })
                labels.forEach { l ->
                    FilterChip(selected = filter == l, onClick = { filter = if (filter == l) null else l }, label = { Text(l) })
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(shown, key = { it.id }) { s ->
                val recording = live.sessionId == s.id
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(s.id, false) },
                ) {
                    Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    listOfNotNull(
                                        fmtDate(s.startedAt),
                                        fmtClock(s.durationMs),
                                        s.speaker.ifBlank { null },
                                        if (recording) "● 録音中" else null,
                                    ).joinToString("・"),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (s.labels.isNotEmpty()) {
                                    Text(
                                        s.labels.joinToString("　") { "#$it" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            if (!recording) {
                                TextButton(onClick = { confirmDelete = if (confirmDelete == s.id) null else s.id }) { Text("🗑") }
                            }
                        }
                        if (confirmDelete == s.id) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "録音と文字がすべて消えます。元に戻せません。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { confirmDelete = null }) { Text("やめる") }
                                Button(
                                    onClick = { confirmDelete = null; Repo.delete(s.id) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                ) { Text("削除") }
                            }
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (filter == null) "まだ記録がありません" else "「$filter」の記録はありません",
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/** 記録の情報（タイトル・話し手・ラベル・録音日時・録音時間）をその場で直す欄 */
@Composable
private fun InfoPanel(s: Session, liveMs: Long?, onClose: () -> Unit) {
    val labels by Meta.labels.collectAsState()
    Meta.speakers.collectAsState().value // 候補が増えたら描き直す
    var title by remember(s.id) { mutableStateOf(s.title) }
    var speaker by remember(s.id) { mutableStateOf(s.speaker) }
    var newLabel by remember { mutableStateOf("") }
    // 画面を離れるときも、話し手を候補に残す
    val latestSpeaker = androidx.compose.runtime.rememberUpdatedState(speaker)
    androidx.compose.runtime.DisposableEffect(s.id) {
        onDispose { Meta.addSpeaker(latestSpeaker.value) }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { v -> title = v; Repo.update(s.id) { it.copy(title = v) } },
            label = { Text("タイトル") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = speaker,
            onValueChange = { v -> speaker = v; Repo.update(s.id) { it.copy(speaker = v.trim()) } },
            label = { Text("話し手") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        val sug = Meta.suggest(speaker)
        if (sug.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sug.forEach { name ->
                    AssistChip(onClick = { speaker = name; Repo.update(s.id) { it.copy(speaker = name) } }, label = { Text(name) })
                }
            }
        }
        Text("ラベル", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (labels + s.labels.filter { it !in labels }).forEach { l ->
                val on = l in s.labels
                FilterChip(
                    selected = on,
                    onClick = { Repo.update(s.id) { it.copy(labels = if (on) it.labels - l else it.labels + l) } },
                    label = { Text(l) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newLabel, onValueChange = { newLabel = it },
                placeholder = { Text("新しいラベル") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            SmallBtn("＋ 足す", enabled = newLabel.isNotBlank()) {
                val l = newLabel.trim()
                Meta.addLabel(l)
                Repo.update(s.id) { it.copy(labels = (it.labels + l).distinct()) }
                newLabel = ""
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "録音日時 ${fmtDate(s.startedAt)}・録音時間 ${fmtClock(liveMs ?: s.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            SmallBtn("閉じる", primary = true) {
                Meta.addSpeaker(speaker)
                onClose()
            }
        }
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
fun DetailScreen(id: String, openInfo: Boolean, onBack: () -> Unit, onSettings: () -> Unit) {
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
    currentSessionId = id

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRec) {
        while (isRec) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    var menu by remember { mutableStateOf(false) }
    /** 直している区間と、最初のカーソルの位置 */
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    /** 直している欄のカーソルの高さ（画面の中の位置） */
    var editCursorY by remember { mutableStateOf<Float?>(null) }
    var listTop by remember { mutableStateOf(0f) }
    var listHeight by remember { mutableStateOf(0f) }
    var noteFor by remember { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showInfo by remember(id) { mutableStateOf(openInfo) }
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
                    DropdownMenuItem(text = { Text("情報（タイトル・話し手・ラベル）") }, onClick = { menu = false; showInfo = true })
                    DropdownMenuItem(text = { Text("削除する") }, enabled = !isRec, onClick = { menu = false; confirmDelete = true })
                }
            }
        }

        if (showInfo) {
            InfoPanel(s, if (isRec) live.elapsedMs(now) else null) { showInfo = false }
        } else {
            // タイトルの下に、話し手・ラベルを小さく。押すと情報欄を開く
            Text(
                listOfNotNull(
                    s.speaker.ifBlank { null },
                    s.labels.joinToString(" ") { "#$it" }.ifBlank { null },
                ).joinToString("・").ifBlank { "＋ 話し手・ラベルを入れる" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth().clickable { showInfo = true }.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        RecordControls(s, isRec, otherRec, live, now, onMark = {
            val t = live.elapsedMs()
            Repo.update(id) { it.copy(marks = it.marks + Mark(t, "")) }
            noteFor = t
        })
        HorizontalDivider()

        // キーボードが出て見える範囲が変わったら、カーソルの行を真ん中へ
        LaunchedEffect(editing?.first, listHeight) {
            if (editing == null) return@LaunchedEffect
            delay(200)
            val y = editCursorY ?: return@LaunchedEffect
            listState.animateScrollBy(y - (listTop + listHeight / 2))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp).onGloballyPositioned {
                val b = it.boundsInWindow()
                listTop = b.top
                listHeight = b.height
            },
            state = listState,
        ) {
            items(rows) { para ->
                ParaView(
                    para, editing?.first,
                    onEdit = { g, cursor -> editCursorY = null; editing = g.index to cursor },
                    onMark = { Marker.toggleWhole(id, it.index) },
                ) { g ->
                    InlineEditor(
                        s, g, editing?.second ?: 0,
                        onCursorY = { editCursorY = it },
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
        Scrollbar(listState, Modifier.align(Alignment.CenterEnd))
        }

        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            val p = Processor.pendingCounts(s)
            val act by Processor.activity.collectAsState()
            val pact by Processor.polishActivity.collectAsState()
            var detail by remember { mutableStateOf(false) }
            val working = listOfNotNull(act, pact)
            // ふだんは短く。押すと詳しい状態を出す
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { detail = !detail },
            ) {
                if (working.isNotEmpty()) {
                    WritingIcon()
                    Spacer(Modifier.width(6.dp))
                    Text(working.joinToString("・"), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                }
                val waits = listOfNotNull(
                    if (p.stt > 0) "清書${p.stt}" else null,
                    if (p.fast > 0) "整形${p.fast}" else null,
                    if (p.polish > 0) "仕上げ${p.polish}" else null,
                )
                Text(
                    when {
                        waits.isNotEmpty() -> "待ち：" + waits.joinToString("・")
                        working.isEmpty() -> "✓ すべて済み"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                Text(if (detail) "▾" else "▸", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (detail) {
                Text(
                    status + (if (polishStatus.isNotEmpty()) "\n$polishStatus" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
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

/** 作業中に動く、ペンで書いているような小さなアイコン */
@Composable
private fun WritingIcon() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "writing")
    val x by t.animateFloat(
        initialValue = -3f, targetValue = 3f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "x",
    )
    val r by t.animateFloat(
        initialValue = -10f, targetValue = 10f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "r",
    )
    Text(
        "✍",
        fontSize = 16.sp,
        modifier = Modifier.offset(x = x.dp).graphicsLayer { rotationZ = r },
    )
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
        modifier = Modifier.fillMaxWidth().padding(end = GUTTER, top = 7.dp, bottom = 7.dp),
    )
}

/** 文章の右の余白（スクロールバーのすぐ左に、分の打刻を出す） */
private val GUTTER = 34.dp

/** 右端のスクロールバー。つまんで動かすと、その位置へ飛ぶ */
@Composable
private fun Scrollbar(state: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val color = MaterialTheme.colorScheme.outline
    var heightPx by remember { mutableStateOf(1f) }
    val info = state.layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return
    val visible = info.visibleItemsInfo
    val firstSize = visible.firstOrNull()?.size?.coerceAtLeast(1) ?: 1
    val pos = state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset.toFloat() / firstSize
    val shown = visible.size.coerceAtLeast(1)
    val thumbFrac = (shown.toFloat() / total).coerceIn(0.06f, 1f)
    val posFrac = if (total <= shown) 0f else (pos / (total - shown)).coerceIn(0f, 1f)
    fun jump(y: Float) {
        val f = (y / heightPx).coerceIn(0f, 1f)
        scope.launch { state.scrollToItem((f * (total - 1)).toInt()) }
    }
    androidx.compose.foundation.Canvas(
        modifier.fillMaxHeight().width(16.dp)
            .onGloballyPositioned { heightPx = it.size.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(total) {
                detectVerticalDragGestures(onDragStart = { jump(it.y) }) { change, _ -> jump(change.position.y) }
            }
            .pointerInput(total) { detectTapGestures { jump(it.y) } },
    ) {
        val w = 4.dp.toPx()
        val x = size.width - w - 3.dp.toPx()
        drawRoundRect(color.copy(alpha = 0.12f), topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
            size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2))
        val th = size.height * thumbFrac
        val ty = (size.height - th) * posFrac
        drawRoundRect(color.copy(alpha = 0.55f), topLeft = androidx.compose.ui.geometry.Offset(x, ty),
            size = androidx.compose.ui.geometry.Size(w, th), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2))
    }
}

private val MARKER_COLOR = androidx.compose.ui.graphics.Color(0x80FFE14D)

/** 1つの段落。押したところの区間をその場で直せる。長押しでマーカー。聖句の参照を押すと wol.jw.org を開く */
@Composable
private fun ParaView(
    para: Transcript.Para,
    editingIndex: Int?,
    onEdit: (Segment, Int) -> Unit,
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
            if (gr is Segment) Box(Modifier.padding(end = GUTTER)) { editor(gr) }
            else {
                @Suppress("UNCHECKED_CAST")
                PiecesText(gr as List<Transcript.Piece>, onEdit, onMark)
            }
        }
    }
}

private data class SegRange(val start: Int, val end: Int, val seg: Segment, val text: String)

/** すでにあるマーカーの位置（文字の範囲・区間・語句） */
private data class HlRange(val start: Int, val end: Int, val seg: Segment, val phrase: String)

/** 日本語の単語の切れ目 */
private fun wordRange(text: String, off: Int): IntRange {
    if (text.isEmpty()) return 0 until 0
    val bi = android.icu.text.BreakIterator.getWordInstance(android.icu.util.ULocale.JAPANESE)
    bi.setText(text)
    val o = off.coerceIn(0, text.length - 1)
    var st = if (bi.isBoundary(o)) o else bi.preceding(o)
    var en = bi.following(o)
    if (st == android.icu.text.BreakIterator.DONE) st = 0
    if (en == android.icu.text.BreakIterator.DONE) en = text.length
    return st until en
}

/**
 * 段落の文。
 *  - タップ：その位置にカーソルを置いて直す（マーカーをタップすると、そのマーカーを選ぶ）
 *  - 横になぞる：なぞった単語にマーカー。離すと保存し、両端の ● で長さを調整できる
 *  - 縦に動かす：ふつうにスクロール
 */
@Composable
private fun PiecesText(pieces: List<Transcript.Piece>, onEdit: (Segment, Int) -> Unit, @Suppress("UNUSED_PARAMETER") onMark: (Segment) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val uri = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val built = remember(pieces, cs) {
        val segRanges = ArrayList<SegRange>()
        val urlRanges = ArrayList<Triple<Int, Int, String>>()
        val hlRanges = ArrayList<HlRange>()
        val stamps = ArrayList<Pair<Int, String>>() // 文字の位置と、分の打刻
        val text = buildAnnotatedString {
            for (p in pieces) {
                val start = length
                if (p.kind == Transcript.Kind.TIME) {
                    stamps.add(start to p.text)
                    continue
                }
                val style = when (p.kind) {
                    Transcript.Kind.RAW -> SpanStyle(color = cs.onSurfaceVariant)
                    Transcript.Kind.DRAFT -> SpanStyle(color = cs.outline, fontStyle = FontStyle.Italic)
                    Transcript.Kind.PENDING -> SpanStyle(color = cs.outline)
                    Transcript.Kind.MARK -> SpanStyle(color = cs.tertiary, fontWeight = FontWeight.Bold)
                    else -> SpanStyle(color = cs.onSurface)
                }
                withStyle(style) { append(if (p.kind == Transcript.Kind.MARK) " ${p.text} " else p.text) }
                p.seg?.let { g ->
                    val bg = SpanStyle(background = MARKER_COLOR)
                    if (Marker.WHOLE in g.highlights) {
                        addStyle(bg, start, start + p.text.length)
                        hlRanges.add(HlRange(start, start + p.text.length, g, Marker.WHOLE))
                    }
                    val full = displayText(g) ?: ""
                    for (h in g.highlights) {
                        if (h == Marker.WHOLE) continue
                        for (r in Marker.find(p.text, h)) {
                            addStyle(bg, start + r.first, start + r.last + 1)
                            hlRanges.add(HlRange(start + r.first, start + r.last + 1, g, h))
                        }
                        // AI が書き換えて見つからなくなった語句は、区間の最後に残す
                        if (Marker.find(full, h).isEmpty() && full.trimEnd().endsWith(p.text.trimEnd())) {
                            val ns = length
                            withStyle(bg) { append(" 🖍$h") }
                            hlRanges.add(HlRange(ns, length, g, h))
                        }
                    }
                }
                if (p.kind != Transcript.Kind.MARK) {
                    for (r in Scripture.find(p.text)) {
                        addStyle(SpanStyle(color = cs.primary, textDecoration = TextDecoration.Underline), start + r.start, start + r.end)
                        urlRanges.add(Triple(start + r.start, start + r.end, Scripture.url(r.book, r.chapter)))
                    }
                }
                p.seg?.let { segRanges.add(SegRange(start, length, it, p.text)) }
            }
        }
        Built(text, segRanges, urlRanges, hlRanges, stamps)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // マーカーを引いている・選んでいるところ（文字の範囲）と、保存してある語句（区間の番号・語句）
    var selRange by remember { mutableStateOf<IntRange?>(null) }
    var selSaved by remember { mutableStateOf<List<Pair<Segment, String>>>(emptyList()) }

    fun segAt(off: Int): SegRange? =
        built.segs.firstOrNull { off in it.start until it.end } ?: built.segs.lastOrNull { it.start <= off }

    /** 範囲を区間ごとの語句にして保存する（前に保存した語句は外す） */
    fun commit(range: IntRange) {
        val sid = currentSessionId ?: return
        selSaved.forEach { (g, ph) -> Marker.remove(sid, g.index, ph) }
        val saved = ArrayList<Pair<Segment, String>>()
        for (sr in built.segs) {
            val textEnd = sr.start + sr.text.length
            val a = maxOf(range.first, sr.start)
            val b = minOf(range.last + 1, textEnd)
            if (b > a) {
                var s0 = a - sr.start
                var e0 = b - sr.start
                // 1文字だけで、同じ字がほかにもあると全部に付いてしまうので、隣の字まで含める
                if (e0 - s0 == 1 && sr.text.count { it == sr.text[s0] } > 1) {
                    if (e0 < sr.text.length) e0++ else if (s0 > 0) s0--
                }
                val ph = sr.text.substring(s0, e0).trim()
                if (ph.isNotEmpty()) {
                    Marker.add(sid, sr.seg.index, ph)
                    saved.add(sr.seg to ph)
                }
            }
        }
        selSaved = saved
    }

    fun removeSelected() {
        val sid = currentSessionId ?: return
        selSaved.forEach { (g, ph) -> Marker.remove(sid, g.index, ph) }
        selSaved = emptyList()
        selRange = null
    }

    val handleR = with(density) { 8.dp.toPx() }
    val hitR = with(density) { 28.dp.toPx() }

    fun handlePoints(l: TextLayoutResult, r: IntRange): Pair<androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset> {
        val n = built.text.length
        val a = l.getBoundingBox(r.first.coerceIn(0, n - 1))
        val b = l.getBoundingBox(r.last.coerceIn(0, n - 1))
        return androidx.compose.ui.geometry.Offset(a.left, a.bottom) to androidx.compose.ui.geometry.Offset(b.right, b.bottom)
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                built.text,
                style = MaterialTheme.typography.bodyLarge,
                onTextLayout = { layout = it },
                modifier = Modifier.weight(1f)
                    .drawWithContent {
                        val l = layout
                        val r = selRange
                        if (l != null && r != null && !r.isEmpty()) {
                            drawPath(l.getPathForRange(r.first, r.last + 1), SELECT_COLOR)
                        }
                        drawContent()
                        if (l != null && r != null && !r.isEmpty()) {
                            val (p1, p2) = handlePoints(l, r)
                            drawLine(cs.primary, p1.copy(y = p1.y - handleR * 2), p1, strokeWidth = 2.dp.toPx())
                            drawLine(cs.primary, p2.copy(y = p2.y - handleR * 2), p2, strokeWidth = 2.dp.toPx())
                            drawCircle(cs.primary, handleR, p1.copy(y = p1.y + handleR * 0.6f))
                            drawCircle(cs.primary, handleR, p2.copy(y = p2.y + handleR * 0.6f))
                        }
                    }
                    .pointerInput(built) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val l = layout ?: return@awaitEachGesture
                            val startPos = down.position
                            val startOff = l.getOffsetForPosition(startPos)
                            // ● をつまんだか
                            var handle = -1
                            selRange?.let { r ->
                                val (p1, p2) = handlePoints(l, r)
                                if ((startPos - p1).getDistance() < hitR) handle = 0
                                else if ((startPos - p2).getDistance() < hitR) handle = 1
                            }
                            if (handle >= 0) down.consume()
                            var swiping = false
                            var anchor: IntRange? = null
                            var dx = 0f
                            var dy = 0f
                            var moved = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val d = ch.positionChange()
                                dx += d.x
                                dy += d.y
                                if (handle < 0 && !swiping) {
                                    if (kotlin.math.abs(dx) > viewConfiguration.touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.3f) {
                                        swiping = true
                                        anchor = wordRange(built.text.text, startOff)
                                        selSaved = emptyList()
                                        selRange = anchor
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    } else if (kotlin.math.abs(dy) > viewConfiguration.touchSlop) {
                                        return@awaitEachGesture // 縦はスクロールに任せる
                                    }
                                }
                                if (kotlin.math.abs(dx) > viewConfiguration.touchSlop || kotlin.math.abs(dy) > viewConfiguration.touchSlop) moved = true
                                val off = l.getOffsetForPosition(ch.position).coerceIn(0, built.text.length)
                                if (swiping) {
                                    ch.consume()
                                    val an = anchor ?: continue
                                    // なぞった単語まで広げる
                                    val w = wordRange(built.text.text, off.coerceAtMost(built.text.length - 1))
                                    selRange = if (off >= an.first) an.first..maxOf(an.last, w.last) else w.first..an.last
                                } else if (handle >= 0) {
                                    ch.consume()
                                    val r = selRange ?: continue
                                    // ● は1文字ずつ動かせる
                                    selRange = if (handle == 0) off.coerceAtMost(r.last)..r.last else r.first..(off - 1).coerceAtLeast(r.first)
                                }
                            }
                            when {
                                swiping || handle >= 0 -> selRange?.let { commit(it) }
                                !moved -> {
                                    // タップ
                                    val url = built.urls.firstOrNull { startOff in it.first until it.second }
                                    val hl = built.hls.firstOrNull { startOff in it.start until it.end }
                                    when {
                                        selRange != null && hl == null -> { selRange = null; selSaved = emptyList() }
                                        url != null -> uri.openUri(url.third)
                                        hl != null -> {
                                            // マーカーを選ぶ（同じ語句の範囲をまとめる）
                                            selRange = hl.start until hl.end
                                            selSaved = listOf(hl.seg to hl.phrase)
                                        }
                                        else -> segAt(startOff)?.let { r ->
                                            // 押した文字の位置 → 区間の文の中の位置（カーソルをそこに置く）
                                            val full = displayText(r.seg) ?: ""
                                            val base = full.indexOf(r.text).coerceAtLeast(0)
                                            val inPiece = (startOff - r.start).coerceIn(0, r.text.length)
                                            onEdit(r.seg, (base + inPiece).coerceIn(0, full.length))
                                        }
                                    }
                                }
                            }
                        }
                    },
            )
            // 右の余白：分の打刻を、その分が始まる行の高さに（スクロールバーのすぐ左）
            Box(Modifier.width(GUTTER), contentAlignment = Alignment.TopEnd) {
                val l = layout
                if (l != null) {
                    for ((off, label) in built.stamps) {
                        val line = l.getLineForOffset(off.coerceIn(0, (built.text.length - 1).coerceAtLeast(0)))
                        val top = with(density) { l.getLineTop(line).toDp() }
                        Text(
                            label,
                            fontSize = 10.sp,
                            color = cs.outline.copy(alpha = 0.7f),
                            modifier = Modifier.offset(y = top + 4.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        // マーカーを選んでいるときの小さな操作
        selRange?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = GUTTER)) {
                Text(
                    "🖍 両端の●で長さを調整",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.outline,
                    modifier = Modifier.weight(1f),
                )
                SmallBtn("消す") { removeSelected() }
                SmallBtn("完了", primary = true) { selRange = null; selSaved = emptyList() }
            }
        }
    }
}

private class Built(
    val text: androidx.compose.ui.text.AnnotatedString,
    val segs: List<SegRange>,
    val urls: List<Triple<Int, Int, String>>,
    val hls: List<HlRange>,
    val stamps: List<Pair<Int, String>>,
)

/** いま開いている記録（マーカーの保存先） */
private var currentSessionId: String? = null

private val SELECT_COLOR = androidx.compose.ui.graphics.Color(0x66FFB300)

/** 文章の中でそのまま直す入力欄 */
@Composable
private fun InlineEditor(
    s: Session, g: Segment, cursor: Int,
    onCursorY: (Float) -> Unit,
    onClose: () -> Unit, onLearned: (List<Corrections.Fix>) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val before = remember(g.index) { displayText(g) ?: "" }
    var field by remember(g.index) {
        mutableStateOf(TextFieldValue(before, androidx.compose.ui.text.TextRange(cursor.coerceIn(0, before.length))))
    }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var fieldTop by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(field.selection, textLayout, fieldTop) {
        val tl = textLayout ?: return@LaunchedEffect
        val top = fieldTop ?: return@LaunchedEffect
        val c = field.selection.end.coerceIn(0, field.text.length)
        onCursorY(top + tl.getCursorRect(c).center.y)
    }
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
            onTextLayout = { textLayout = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                .onGloballyPositioned { fieldTop = it.positionInWindow().y },
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
    var voiceOn by remember { mutableStateOf(st.voiceChangeHints) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = voiceOn, onCheckedChange = { voiceOn = it; st.voiceChangeHints = it })
                Text("声の変わり目を、段落を区切る参考にする", Modifier.padding(start = 8.dp))
            }
            Text(
                "端末の中で声の高さと響きを比べ、話し手が替わったらしいところを AI に伝えます（ネット・無料枠は使いません）。",
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

            Section("ラベルと話し手")
            LabelSpeakerSettings()

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
private fun LabelSpeakerSettings() {
    val labels by Meta.labels.collectAsState()
    val speakers by Meta.speakers.collectAsState()
    var newLabel by remember { mutableStateOf("") }
    Text("ラベル（× で一覧から外す。付けてある記録はそのまま）", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { l -> AssistChip(onClick = { Meta.removeLabel(l) }, label = { Text("$l ×") }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newLabel, onValueChange = { newLabel = it },
            placeholder = { Text("新しいラベル") }, singleLine = true, modifier = Modifier.weight(1f),
        )
        SmallBtn("＋ 足す", enabled = newLabel.isNotBlank()) { Meta.addLabel(newLabel); newLabel = "" }
    }
    SmallBtn("最初のラベル（集会・講演・大会・その他）に戻す") { Meta.resetLabels() }
    Text("話し手の候補（× で消す）", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    if (speakers.isEmpty()) Text("（まだありません。記録の情報に話し手を入れると増えます）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        speakers.forEach { n -> AssistChip(onClick = { Meta.removeSpeaker(n) }, label = { Text("$n ×") }) }
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

package app.lecturenotes

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Segment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val audio: String,
    /** 文字起こしの結果（null = まだ） */
    val raw: String? = null,
    /** groq / gemini / local / error */
    val rawEngine: String? = null,
    /** 整形後（null = まだ） */
    val formatted: String? = null,
    /** 文字起こし・整形に使ったモデル */
    val rawModel: String? = null,
    val formatModel: String? = null,
    /** 頭の良いモデルで仕上げ直したか */
    val polished: Boolean = false,
    /** 利用者が直した文（あれば AI は上書きしない） */
    val edited: String? = null,
    /** マーカーを引いた語句。"*" はこの区間全体 */
    val highlights: List<String> = emptyList(),
    val silent: Boolean = false,
    val error: String? = null,
)

data class Mark(val timeMs: Long, val note: String)

data class Session(
    val id: String,
    val title: String,
    val startedAt: Long,
    val recording: Boolean,
    val segments: List<Segment>,
    val marks: List<Mark>,
    /** この記録で利用者が直した「誤→正」（文字起こし・整形の参考にする） */
    val corrections: List<String> = emptyList(),
    /** 話し手 */
    val speaker: String = "",
    /** ラベル（集会・講演・大会・その他 など） */
    val labels: List<String> = emptyList(),
    /** タイトルを文字起こしの出だしから自動で付けるか（自分で入れたら false） */
    val titleAuto: Boolean = false,
    /** 完了（この話にもう録音を足さない）。完了して仕上げが済んだら要約を作る */
    val completed: Boolean = false,
    /** 要約（頭の良い AI が作る）と、作ったモデル・形 */
    val summary: String? = null,
    val summaryModel: String? = null,
    /** 要約を作ったときの録音の長さ（そのあと録音が増えたかを見るため） */
    val summaryDurationMs: Long = 0,
    /** 声が変わったらしい時刻（記録の中のミリ秒）。段落を区切る参考 */
    val voiceChanges: List<Long> = emptyList(),
    /** 会話：話し手の印（Ａ など）→ 付けた名前 */
    val speakerMap: Map<String, String> = emptyMap(),
) {
    val durationMs: Long get() = segments.lastOrNull()?.endMs ?: 0L

    /** ラベル「会話」が付いていれば会話モード */
    val isTalk: Boolean get() = Talk.LABEL in labels

    /** 画面に出すタイトル（まだなければ録音日時） */
    val shownTitle: String get() = title.ifBlank { fmtDate(startedAt) }
}

/** 文字起こしの出だし（最初の一文、長ければ 20 字まで。一覧で1行に収まる長さ）。端末内の仮の文字は使わない */
fun autoTitle(s: Session): String? {
    val text = s.segments.asSequence()
        .filter { !it.silent && it.rawEngine != "local" && it.rawEngine != "error" }
        .mapNotNull { displayText(it)?.let { t -> if (s.isTalk) Talk.SPEAKER.replace(t, "") else t }?.trim() }
        .filter { it.isNotEmpty() }
        .take(3).joinToString("")
        .replace(Regex("\\s+"), " ").trim()
    if (text.length < 4) return null
    val end = text.indexOfFirst { it == '。' || it == '？' || it == '！' || it == '?' || it == '!' }
    val first = if (end in 4..19) text.substring(0, end + 1) else text.take(20)
    return first.trim().trimEnd('、', ',')
}

/** 記録の保存場所。files/sessions/<id>/session.json と audio/ */
object Repo {
    private lateinit var root: File
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions

    fun init(context: Context) {
        root = File(context.filesDir, "sessions").apply { mkdirs() }
        val list = root.listFiles()?.mapNotNull { dir ->
            val f = File(dir, "session.json")
            if (!f.exists()) null else runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
        } ?: emptyList()
        // 録音中のままアプリが終わっていたものを直す。「\n」の文字で保存された改行も直す
        val fixed = list.map { s0 ->
            var s = s0
            if (s.segments.any { g -> (g.formatted?.contains("\\n") == true) || (g.edited?.contains("\\n") == true) }) {
                s = s.copy(segments = s.segments.map { g -> g.copy(formatted = g.formatted?.let(::fixNewlines), edited = g.edited?.let(::fixNewlines)) })
            }
            // 聖句を「コリント第一9:10」の形にそろえる（自分で直した文も。「◯章◯節」は残さない）
            val refFixed = s.segments.map { g ->
                g.copy(
                    raw = g.raw?.let(Scripture::normalizeRefs),
                    formatted = g.formatted?.let(Scripture::normalizeRefs),
                    edited = g.edited?.let(Scripture::normalizeRefs),
                )
            }
            if (refFixed != s.segments) s = s.copy(segments = refFixed)
            if (s.recording) s = s.copy(recording = false)
            if (s !== s0) save(s)
            s
        }
        _sessions.value = fixed.sortedByDescending { it.startedAt }
    }

    fun dir(id: String) = File(root, id)
    fun audioDir(id: String) = File(dir(id), "audio").apply { mkdirs() }
    fun audioFile(id: String, seg: Segment) = File(audioDir(id), seg.audio)

    fun get(id: String): Session? = _sessions.value.firstOrNull { it.id == id }

    @Synchronized
    fun create(title: String): Session {
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val s = Session(id, title, now, false, emptyList(), emptyList(), titleAuto = title.isBlank())
        dir(id).mkdirs()
        save(s)
        _sessions.value = (listOf(s) + _sessions.value).sortedByDescending { it.startedAt }
        return s
    }

    /** 予定の枠（タイトルだけの空の記録）を作る。startedAt は予定の日時 */
    @Synchronized
    fun createPlanned(title: String, startedAt: Long, labels: List<String>): Session {
        var id = "plan-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(startedAt))
        var n = 1
        while (get(id) != null || dir(id).exists()) id = "plan-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(startedAt)) + "-" + (++n)
        val s = Session(id, title, startedAt, false, emptyList(), emptyList(), labels = labels, titleAuto = false)
        dir(id).mkdirs()
        save(s)
        _sessions.value = (listOf(s) + _sessions.value).sortedByDescending { it.startedAt }
        return s
    }

    @Synchronized
    fun update(id: String, f: (Session) -> Session): Session? {
        val old = get(id) ?: return null
        val new = f(old)
        save(new)
        _sessions.value = _sessions.value.map { if (it.id == id) new else it }
        return new
    }

    @Synchronized
    fun updateSegment(id: String, index: Int, f: (Segment) -> Segment) {
        update(id) { s -> s.copy(segments = s.segments.map { if (it.index == index) f(it) else it }) }
    }

    @Synchronized
    fun delete(id: String) {
        dir(id).deleteRecursively()
        _sessions.value = _sessions.value.filter { it.id != id }
    }

    private fun save(s: Session) {
        val d = dir(s.id).apply { mkdirs() }
        val tmp = File(d, "session.json.tmp")
        tmp.writeText(toJson(s).toString())
        tmp.renameTo(File(d, "session.json"))
    }

    private fun toJson(s: Session) = JSONObject().apply {
        put("id", s.id)
        put("title", s.title)
        // 録音日時・録音時間・話し手・ラベルは、文字とは別の情報として保存する
        put("startedAt", s.startedAt)
        put("recordedAt", fmtDate(s.startedAt))
        put("durationMs", s.durationMs)
        put("speaker", s.speaker)
        put("titleAuto", s.titleAuto)
        put("completed", s.completed)
        s.summary?.let { put("summary", it) }
        s.summaryModel?.let { put("summaryModel", it) }
        put("summaryDurationMs", s.summaryDurationMs)
        put("labels", JSONArray(s.labels))
        put("voiceChanges", JSONArray(s.voiceChanges))
        put("speakerMap", JSONObject().apply { s.speakerMap.forEach { (k, v) -> put(k, v) } })
        put("recording", s.recording)
        put("segments", JSONArray().apply {
            s.segments.forEach { g ->
                put(JSONObject().apply {
                    put("index", g.index)
                    put("startMs", g.startMs)
                    put("endMs", g.endMs)
                    put("audio", g.audio)
                    g.raw?.let { put("raw", it) }
                    g.rawEngine?.let { put("rawEngine", it) }
                    g.formatted?.let { put("formatted", it) }
                    g.rawModel?.let { put("rawModel", it) }
                    g.formatModel?.let { put("formatModel", it) }
                    put("polished", g.polished)
                    g.edited?.let { put("edited", it) }
                    if (g.highlights.isNotEmpty()) put("highlights", JSONArray(g.highlights))
                    put("silent", g.silent)
                    g.error?.let { put("error", it) }
                })
            }
        })
        put("corrections", JSONArray(s.corrections))
        put("marks", JSONArray().apply {
            s.marks.forEach { m -> put(JSONObject().put("timeMs", m.timeMs).put("note", m.note)) }
        })
    }

    private fun JSONObject.optStr(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun fromJson(j: JSONObject): Session {
        val segs = j.optJSONArray("segments") ?: JSONArray()
        val marks = j.optJSONArray("marks") ?: JSONArray()
        return Session(
            id = j.getString("id"),
            title = j.optString("title"),
            startedAt = j.optLong("startedAt"),
            recording = j.optBoolean("recording"),
            segments = (0 until segs.length()).map { i ->
                val g = segs.getJSONObject(i)
                Segment(
                    index = g.getInt("index"),
                    startMs = g.getLong("startMs"),
                    endMs = g.getLong("endMs"),
                    audio = g.getString("audio"),
                    raw = g.optStr("raw"),
                    rawEngine = g.optStr("rawEngine"),
                    formatted = g.optStr("formatted"),
                    rawModel = g.optStr("rawModel"),
                    formatModel = g.optStr("formatModel"),
                    polished = g.optBoolean("polished"),
                    edited = g.optStr("edited"),
                    highlights = g.optJSONArray("highlights")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    silent = g.optBoolean("silent"),
                    error = g.optStr("error"),
                )
            },
            marks = (0 until marks.length()).map { i ->
                val m = marks.getJSONObject(i)
                Mark(m.getLong("timeMs"), m.optString("note"))
            },
            corrections = j.optJSONArray("corrections")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            speaker = j.optString("speaker"),
            titleAuto = j.optBoolean("titleAuto"),
            completed = j.optBoolean("completed"),
            summary = if (j.has("summary")) j.getString("summary") else null,
            summaryModel = if (j.has("summaryModel")) j.getString("summaryModel") else null,
            summaryDurationMs = j.optLong("summaryDurationMs"),
            labels = j.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            voiceChanges = j.optJSONArray("voiceChanges")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList(),
            speakerMap = j.optJSONObject("speakerMap")?.let { o -> o.keys().asSequence().associateWith { k -> o.getString(k) } } ?: emptyMap(),
        )
    }
}

/** 録音中の画面表示用 */
object Live {
    data class State(
        val sessionId: String? = null,
        /** 録音を始めた時刻（端末の時計） */
        val startedAt: Long = 0,
        /** 記録の中での、録音を始めた位置（続きから録るとき 0 より大きい） */
        val baseMs: Long = 0,
        /** 端末内の文字起こしの途中結果 */
        val partial: String = "",
        /** いまの区間で確定した文字（端末内） */
        val liveText: String = "",
        /** いまの区間を始めた時刻（端末の時計） */
        val chunkStartedAt: Long = 0,
        /** いま使っている方法 */
        val engine: String = "",
        val error: String? = null,
        /** 通話中などでマイクが使えず、無音になっているとき */
        val warning: String? = null,
    ) {
        fun elapsedMs(now: Long = System.currentTimeMillis()) = if (sessionId == null) 0 else baseMs + (now - startedAt)
    }

    val state = MutableStateFlow(State())

    /** 音の大きさ（0〜1）。0.1 秒ごとに変わるので、画面全体を描き直さないよう別にしている */
    val level = MutableStateFlow(0f)

    /** 最後に声がした時刻（端末の時計）。長めの間を整形のきっかけにする */
    @Volatile var lastVoiceAt: Long = 0L
}

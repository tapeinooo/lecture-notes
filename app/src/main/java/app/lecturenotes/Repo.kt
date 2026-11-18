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
) {
    val durationMs: Long get() = segments.lastOrNull()?.endMs ?: 0L
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
        // 録音中のままアプリが終わっていたものを直す
        val fixed = list.map { if (it.recording) it.copy(recording = false).also(::save) else it }
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
        val s = Session(id, title, now, false, emptyList(), emptyList())
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
        put("labels", JSONArray(s.labels))
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
            labels = j.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
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
    ) {
        fun elapsedMs(now: Long = System.currentTimeMillis()) = if (sessionId == null) 0 else baseMs + (now - startedAt)
    }

    val state = MutableStateFlow(State())

    /** 音の大きさ（0〜1）。0.1 秒ごとに変わるので、画面全体を描き直さないよう別にしている */
    val level = MutableStateFlow(0f)

    /** 最後に声がした時刻（端末の時計）。長めの間を整形のきっかけにする */
    @Volatile var lastVoiceAt: Long = 0L
}

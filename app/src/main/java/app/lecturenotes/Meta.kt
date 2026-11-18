package app.lecturenotes

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray

/** 話し手の候補とラベルの一覧（すべての記録で共通） */
object Meta {
    val DEFAULT_LABELS = listOf("集会", "講演", "大会", "その他")

    private const val PREF = "meta"

    /** 使った順（新しいものが先） */
    val speakers = MutableStateFlow<List<String>>(emptyList())
    val labels = MutableStateFlow(DEFAULT_LABELS)

    private lateinit var app: Context

    private fun read(key: String): List<String>? {
        val s = app.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key, null) ?: return null
        return runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
    }

    private fun write(key: String, list: List<String>) {
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(key, JSONArray(list).toString()).apply()
    }

    fun init(context: Context) {
        app = context.applicationContext
        // 前からある記録の話し手も候補に入れる
        val fromSessions = Repo.sessions.value.map { it.speaker.trim() }.filter { it.isNotEmpty() }
        speakers.value = ((read("speakers") ?: emptyList()) + fromSessions).distinct()
        labels.value = read("labels") ?: DEFAULT_LABELS
    }

    fun addSpeaker(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val list = listOf(n) + speakers.value.filter { it != n }
        speakers.value = list
        write("speakers", list)
    }

    fun removeSpeaker(name: String) {
        val list = speakers.value - name
        speakers.value = list
        write("speakers", list)
    }

    /** 入力中の文字に合う候補（入力が空なら最近のもの） */
    fun suggest(typed: String, max: Int = 8): List<String> {
        val t = typed.trim()
        return speakers.value.filter { t.isEmpty() || (it.contains(t) && it != t) }.take(max)
    }

    fun addLabel(label: String) {
        val l = label.trim()
        if (l.isEmpty() || l in labels.value) return
        labels.value = labels.value + l
        write("labels", labels.value)
    }

    fun removeLabel(label: String) {
        labels.value = labels.value - label
        write("labels", labels.value)
    }

    fun resetLabels() {
        labels.value = DEFAULT_LABELS
        write("labels", labels.value)
    }
}

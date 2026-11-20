package app.lecturenotes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** 使う候補（サービス＋モデル） */
data class Cand(val provider: String, val model: String) {
    val key get() = "$provider:$model"
    override fun toString() = key

    companion object {
        fun parse(line: String): Cand? {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return null
            val i = t.indexOf(':')
            if (i <= 0) return null
            val p = t.substring(0, i).trim().lowercase()
            val m = t.substring(i + 1).trim()
            if (m.isEmpty() || (p != "groq" && p != "gemini")) return null
            return Cand(p, m)
        }
    }
}

enum class Role(val label: String) {
    STT("聞き取り（文字起こし）"),
    FAST("すぐの整形"),
    POLISH("仕上げ"),
}

/**
 * 使えるモデルの一覧を取ってきて、役割ごとに順番を付ける。
 *  - 聞き取り：音声を聞けて、速く、1日の回数が多いもの（Groq の Whisper → Gemini の Flash 系）。
 *  - すぐの整形：速いもの（考える時間を切った Gemini Flash-Lite・Flash、Groq）。数秒で返す。
 *  - 仕上げ：頭の良いもの（Gemini Pro・新しい Flash）。裏でゆっくり直し直す。
 * 上から使い、制限にかかったら次へ。制限が戻ればまた上を使う。
 */
object Catalog {
    private const val PREF = "catalog"

    private val DEFAULT_GEMINI = listOf(
        "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite",
        "gemini-2.0-flash", "gemini-2.0-flash-lite", "gemma-3-27b-it", "gemma-3-12b-it",
    )
    private val DEFAULT_GROQ = listOf(
        "whisper-large-v3", "whisper-large-v3-turbo",
        "moonshotai/kimi-k2-instruct", "openai/gpt-oss-120b", "llama-3.3-70b-versatile",
    )

    val DEFAULT_MANUAL_STT = """
groq:whisper-large-v3
groq:whisper-large-v3-turbo
gemini:gemini-2.5-flash
gemini:gemini-2.5-flash-lite
""".trim()

    val DEFAULT_MANUAL_FAST = """
gemini:gemini-2.5-flash-lite
groq:moonshotai/kimi-k2-instruct
groq:openai/gpt-oss-120b
gemini:gemini-2.5-flash
gemini:gemma-3-27b-it
""".trim()

    val DEFAULT_MANUAL_POLISH = """
gemini:gemini-2.5-pro
gemini:gemini-2.5-flash
""".trim()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun fetchedAt(ctx: Context) = prefs(ctx).getLong("at", 0)

    private fun list(ctx: Context, key: String, def: List<String>): List<String> {
        val s = prefs(ctx).getString(key, null) ?: return def
        return runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(def)
            .ifEmpty { def }
    }

    fun geminiModels(ctx: Context) = list(ctx, "gemini", DEFAULT_GEMINI)
    fun groqModels(ctx: Context) = list(ctx, "groq", DEFAULT_GROQ)

    private fun keysHash(st: Settings) = (st.geminiKey + "|" + st.groqKey).hashCode()

    /** 半日たったか、キーが変わったら取り直す */
    fun needsRefresh(ctx: Context, st: Settings): Boolean {
        if (st.geminiKey.isBlank() && st.groqKey.isBlank()) return false
        val p = prefs(ctx)
        return System.currentTimeMillis() - p.getLong("at", 0) > 12 * 3600_000L || p.getInt("keys", 0) != keysHash(st)
    }

    /** 一覧を取り直す（通信する）。戻り値は画面に出す文 */
    fun refresh(ctx: Context, st: Settings): String {
        val msgs = ArrayList<String>()
        val e = prefs(ctx).edit()
        if (st.geminiKey.isNotBlank()) {
            try {
                val g = Gemini.listModels(st.geminiKey)
                e.putString("gemini", JSONArray(g).toString())
                msgs.add("Gemini：${g.size}個")
            } catch (ex: Exception) {
                msgs.add("Gemini：取れません ${ex.message?.take(100)}")
            }
        }
        if (st.groqKey.isNotBlank()) {
            try {
                val g = Groq.listModels(st.groqKey)
                e.putString("groq", JSONArray(g).toString())
                msgs.add("Groq：${g.size}個")
            } catch (ex: Exception) {
                msgs.add("Groq：取れません ${ex.message?.take(100)}")
            }
        }
        e.putLong("at", System.currentTimeMillis()).putInt("keys", keysHash(st)).apply()
        return msgs.joinToString("\n")
    }

    // ---------- 順番の付け方 ----------

    private val EXCLUDE = listOf(
        "embedding", "tts", "image", "live", "native-audio", "computer-use", "robotics",
        "aqa", "veo", "imagen", "learnlm", "-latest", "nano",
    )

    private enum class Tier { PRO, FLASH, LITE, GEMMA }

    private data class GInfo(val name: String, val version: Double, val tier: Tier, val unstable: Boolean, val sizeB: Int)

    private fun gInfo(name: String): GInfo? {
        val n = name.lowercase()
        if (EXCLUDE.any { n.contains(it) }) return null
        val version = Regex("^(?:gemini|gemma)-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val tier = when {
            n.startsWith("gemma") -> Tier.GEMMA
            n.contains("flash-lite") -> Tier.LITE
            n.contains("flash") -> Tier.FLASH
            n.contains("pro") -> Tier.PRO
            else -> return null
        }
        val size = Regex("(\\d+)b").find(n.substringAfter("gemma-"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val unstable = n.contains("preview") || n.contains("exp")
        return GInfo(name, version, tier, unstable, size)
    }

    // 聞き取りの予備（Groq が使えないとき）：1日の回数が多い Flash-Lite を先に。
    // Flash は無料枠の1日の回数がとても少ない（数回〜20回ほど）ので、仕上げに残す
    private fun sttScore(g: GInfo): Double = when (g.tier) {
        Tier.GEMMA -> -1.0 // 音声を聞けない
        Tier.LITE -> 100 + g.version
        Tier.FLASH -> 50 + g.version
        Tier.PRO -> g.version
    } - if (g.unstable) 0.5 else 0.0

    /**
     * すぐの整形：速さ優先。Flash-Lite（速さのためのモデル）を先に、Flash は後に。Pro は使わない。
     * 新しい Flash は頭は良いが、考える時間を短くできなかったり（3.8 Flash は minimal 不可）、混んで遅いことがあるため。
     */
    private fun fastScore(g: GInfo): Double = when (g.tier) {
        Tier.GEMMA -> 10.0 + g.sizeB / 4.0
        Tier.PRO -> -1.0
        Tier.LITE -> 200 + g.version
        Tier.FLASH -> 100 + g.version
    } - if (g.unstable) 0.5 else 0.0

    /** 仕上げ：頭の良さ優先 */
    private fun polishScore(g: GInfo): Double = when (g.tier) {
        Tier.LITE -> -1.0
        // Gemma：1日に使える回数がとても多い。大きいもの（20B 以上）だけ、Flash のあとに
        Tier.GEMMA -> if (g.sizeB >= 20) 5.0 + g.version + g.sizeB / 100.0 else -1.0
        Tier.PRO -> g.version * 10 + 6
        Tier.FLASH -> g.version * 10 + 3
    } - if (g.unstable) 0.5 else 0.0

    private val GROQ_CHAT_PREF = listOf("kimi-k2", "gpt-oss-120b", "llama-4-maverick", "qwen3", "llama-3.3-70b", "gpt-oss-20b")
    private val GROQ_EXCLUDE = listOf("whisper", "guard", "tts", "playai", "compound", "allam", "orpheus", "distil")

    fun ranked(ctx: Context, role: Role): List<Cand> {
        val gem = geminiModels(ctx).mapNotNull { gInfo(it) }
        val groq = groqModels(ctx)
        return when (role) {
            Role.STT -> {
                val g = groq.filter { it.contains("whisper") && !it.contains("distil") }
                    .sortedBy { if (it == "whisper-large-v3") 0 else if (it.startsWith("whisper-large-v3")) 1 else 2 }
                    .map { Cand("groq", it) }
                val m = gem.filter { sttScore(it) > 0 }.sortedByDescending { sttScore(it) }.take(6)
                    .map { Cand("gemini", it.name) }
                g + m
            }
            Role.FAST -> {
                val gm = gem.filter { it.tier != Tier.GEMMA && fastScore(it) > 0 }.sortedByDescending { fastScore(it) }
                    .map { Cand("gemini", it.name) }
                val gq = groq.filter { id -> GROQ_EXCLUDE.none { id.contains(it) } }
                    .sortedBy { id -> GROQ_CHAT_PREF.indexOfFirst { id.contains(it) }.let { if (it < 0) 99 else it } }
                    .take(3).map { Cand("groq", it) }
                val gemma = gem.filter { it.tier == Tier.GEMMA }.sortedByDescending { fastScore(it) }.take(2)
                    .map { Cand("gemini", it.name) }
                // Flash-Lite（最大2つ）→ Groq（とても速い）→ Gemma。
                // Flash（Lite でないもの）は1日の回数がとても少ないので、すぐの整形には使わず仕上げに残す
                val lite = gm.filter { c -> gem.first { it.name == c.model }.tier == Tier.LITE }.take(2)
                lite + gq + gemma
            }
            // 仕上げ：頭の良い Gemini から。Gemini が今日の上限に達しても止まらないよう、Groq の大きなモデルを後ろに
            Role.POLISH -> gem.filter { polishScore(it) > 0 }.sortedByDescending { polishScore(it) }.take(7)
                .map { Cand("gemini", it.name) } +
                groq.filter { id -> GROQ_EXCLUDE.none { id.contains(it) } }
                    .filter { id -> listOf("kimi-k2", "gpt-oss-120b").any { id.contains(it) } }
                    .sortedBy { id -> GROQ_CHAT_PREF.indexOfFirst { id.contains(it) }.let { if (it < 0) 99 else it } }
                    .take(2).map { Cand("groq", it) }
        }
    }

    /** 実際に使う順番（設定の「自動で選ぶ」・文字起こしの方法・キーの有無を反映） */
    fun candidates(ctx: Context, st: Settings, role: Role): List<Cand> {
        val base = if (st.autoModels) ranked(ctx, role) else {
            when (role) {
                Role.STT -> st.manualStt
                Role.FAST -> st.manualFormat
                Role.POLISH -> st.manualPolish
            }.lines().mapNotNull { Cand.parse(it) }
        }
        return base.filter { c ->
            when (c.provider) {
                "groq" -> st.groqKey.isNotBlank()
                "gemini" -> st.geminiKey.isNotBlank()
                else -> false
            }
        }.filter { c ->
            true
        }.filter { c ->
            if (role != Role.STT) true else when (st.mode) {
                Mode.AUTO -> true
                Mode.GROQ -> c.provider == "groq"
                Mode.GEMINI -> c.provider == "gemini"
                Mode.LOCAL -> false
            }
        }.sortedBy { if (Latency.slow(ctx, role, it)) 1 else 0 } // 遅かったモデルは後ろへ（順番は保つ）
    }
}

/**
 * モデルごと・役割ごとの、実際にかかった時間（移動平均）。遅いモデルは自動で後回しにする。
 * 6時間たったら測り直す（混雑が直っていれば、また前に戻る）。
 */
object Latency {
    private const val PREF = "latency"
    private const val FORGET_MS = 6 * 3600_000L

    fun limitMs(role: Role) = when (role) {
        Role.STT -> 12_000L
        Role.FAST -> 10_000L
        Role.POLISH -> 90_000L
    }

    /** 1回の呼び出しの打ち切り時間 */
    fun timeoutMs(role: Role) = when (role) {
        Role.STT -> 40_000
        Role.FAST -> 25_000
        Role.POLISH -> 150_000
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun k(role: Role, c: Cand) = "${role.name}|${c.key}"

    @Synchronized
    fun record(ctx: Context, role: Role, c: Cand, ms: Long) {
        val p = prefs(ctx)
        val key = k(role, c)
        val fresh = System.currentTimeMillis() - p.getLong("$key@t", 0) < FORGET_MS
        val old = if (fresh) p.getFloat(key, -1f) else -1f
        val avg = if (old < 0) ms.toFloat() else old * 0.6f + ms * 0.4f
        p.edit().putFloat(key, avg).putLong("$key@t", System.currentTimeMillis()).apply()
    }

    fun avgMs(ctx: Context, role: Role, c: Cand): Long? {
        val p = prefs(ctx)
        val key = k(role, c)
        if (System.currentTimeMillis() - p.getLong("$key@t", 0) > FORGET_MS) return null
        val v = p.getFloat(key, -1f)
        return if (v < 0) null else v.toLong()
    }

    fun slow(ctx: Context, role: Role, c: Cand) = (avgMs(ctx, role, c) ?: 0L) > limitMs(role)
}

/** 今日モデルごとに何回使ったか（画面に出す。アプリを閉じると消える） */
/**
 * 今日モデルごとに使った量（回数・送った音声の秒数）。日をまたぐと 0 に戻る。アプリを閉じても残す。
 * 無料枠の残りの目安を出すのに使う。
 */
object Usage {
    private const val PREF = "usage"
    private var app: Context? = null
    private var day = ""
    private val counts = ConcurrentHashMap<String, Int>()
    private val audioSec = ConcurrentHashMap<String, Double>()

    /** サービスが教えてくれた「今日あと何回」（Groq） */
    val remainingRequests = ConcurrentHashMap<String, Int>()

    private fun today() = LocalDate.now(ZoneId.systemDefault()).toString()

    @Synchronized
    fun init(ctx: Context) {
        app = ctx.applicationContext
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        day = p.getString("day", "") ?: ""
        if (day == today()) {
            runCatching {
                val j = org.json.JSONObject(p.getString("data", "{}") ?: "{}")
                j.optJSONObject("counts")?.let { o -> o.keys().forEach { k -> counts[k] = o.getInt(k) } }
                j.optJSONObject("audio")?.let { o -> o.keys().forEach { k -> audioSec[k] = o.getDouble(k) } }
            }
        }
    }

    @Synchronized
    private fun rollDay() {
        val t = today()
        if (t != day) {
            counts.clear()
            audioSec.clear()
            remainingRequests.clear()
            day = t
        }
    }

    @Synchronized
    fun add(key: String, audioSeconds: Double = 0.0) {
        rollDay()
        counts[key] = (counts[key] ?: 0) + 1
        if (audioSeconds > 0) audioSec[key] = (audioSec[key] ?: 0.0) + audioSeconds
        save()
    }

    private fun save() {
        val c = app ?: return
        val j = org.json.JSONObject()
            .put("counts", org.json.JSONObject(counts as Map<*, *>))
            .put("audio", org.json.JSONObject(audioSec as Map<*, *>))
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("day", day).putString("data", j.toString()).apply()
    }

    fun get(key: String): Int {
        rollDay()
        return counts[key] ?: 0
    }

    fun audioSeconds(key: String): Double {
        rollDay()
        return audioSec[key] ?: 0.0
    }

    /** モデルが1日の上限に達したときの回数（＝1日の上限の目安）。日をまたいでも覚えておく */
    @Synchronized
    fun noteDailyCap(key: String) {
        val c = app ?: return
        val n = get(key)
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("cap:$key", n).apply()
    }

    fun dailyCap(key: String): Int? {
        val c = app ?: return null
        val p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return if (p.contains("cap:$key")) p.getInt("cap:$key", 0) else null
    }

    /** Groq の Whisper の、1日に文字起こしできる音声の長さの目安（秒）。無料枠の目安 */
    const val GROQ_WHISPER_DAILY_SEC = 28_800.0

    /** 今日あと何時間ぶん、Groq で文字起こしできそうか（モデルごとの合計） */
    fun groqHoursLeft(models: List<String>): Double =
        models.sumOf { m -> (GROQ_WHISPER_DAILY_SEC - audioSeconds("groq:$m")).coerceAtLeast(0.0) } / 3600.0
}

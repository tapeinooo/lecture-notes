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

enum class Role(val label: String) { STT("聞き取り（文字起こし）"), FORMAT("整形") }

/**
 * 使えるモデルの一覧を取ってきて、役割ごとに順番を付ける。
 *  - 聞き取り：音声を聞けて、速く、1日の回数が多いもの（Groq の Whisper → Gemini の Flash 系）。
 *  - 整形：頭の良いものから（Gemini Pro → Flash → Groq の大きなモデル → Gemma）。
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

    val DEFAULT_MANUAL_FORMAT = """
gemini:gemini-2.5-pro
gemini:gemini-2.5-flash
groq:moonshotai/kimi-k2-instruct
groq:openai/gpt-oss-120b
gemini:gemma-3-27b-it
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

    private fun sttScore(g: GInfo): Double = when (g.tier) {
        Tier.GEMMA -> -1.0 // 音声を聞けない
        Tier.FLASH -> g.version * 10 + 5
        Tier.LITE -> g.version * 10 + 3
        Tier.PRO -> g.version * 10 + 1
    } - if (g.unstable) 0.5 else 0.0

    private fun formatScore(g: GInfo): Double = when (g.tier) {
        Tier.GEMMA -> 10.0 + g.sizeB / 4.0
        Tier.PRO -> g.version * 10 + 6
        Tier.FLASH -> g.version * 10 + 3
        Tier.LITE -> g.version * 10
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
            Role.FORMAT -> {
                val gm = gem.filter { it.tier != Tier.GEMMA }.sortedByDescending { formatScore(it) }.take(6)
                    .map { Cand("gemini", it.name) }
                val gq = groq.filter { id -> GROQ_EXCLUDE.none { id.contains(it) } }
                    .sortedBy { id -> GROQ_CHAT_PREF.indexOfFirst { id.contains(it) }.let { if (it < 0) 99 else it } }
                    .take(3).map { Cand("groq", it) }
                val gemma = gem.filter { it.tier == Tier.GEMMA }.sortedByDescending { formatScore(it) }.take(2)
                    .map { Cand("gemini", it.name) }
                gm + gq + gemma
            }
        }
    }

    /** 実際に使う順番（設定の「自動で選ぶ」・文字起こしの方法・キーの有無を反映） */
    fun candidates(ctx: Context, st: Settings, role: Role): List<Cand> {
        val base = if (st.autoModels) ranked(ctx, role) else {
            (if (role == Role.STT) st.manualStt else st.manualFormat).lines().mapNotNull { Cand.parse(it) }
        }
        return base.filter { c ->
            when (c.provider) {
                "groq" -> st.groqKey.isNotBlank()
                "gemini" -> st.geminiKey.isNotBlank()
                else -> false
            }
        }.filter { c ->
            if (role != Role.STT) true else when (st.mode) {
                Mode.AUTO -> true
                Mode.GROQ -> c.provider == "groq"
                Mode.GEMINI -> c.provider == "gemini"
                Mode.LOCAL -> false
            }
        }
    }
}

/** 今日モデルごとに何回使ったか（画面に出す。アプリを閉じると消える） */
object Usage {
    private var day = LocalDate.now(ZoneId.systemDefault())
    private val counts = ConcurrentHashMap<String, Int>()

    @Synchronized
    fun add(key: String) {
        val today = LocalDate.now(ZoneId.systemDefault())
        if (today != day) {
            counts.clear()
            day = today
        }
        counts[key] = (counts[key] ?: 0) + 1
    }

    fun get(key: String) = counts[key] ?: 0
}

package app.lecturenotes

import android.content.Context
import java.io.File

/**
 * 用語集。1行に1語。「誤→正」と書くと直し方の例になる。# で始まる行は説明。
 */
object Glossary {
    private fun file(context: Context) = File(context.filesDir, "glossary.txt")

    fun default(context: Context): String =
        context.assets.open("glossary_default.txt").bufferedReader().use { it.readText() }

    fun load(context: Context): String {
        val f = file(context)
        if (!f.exists()) f.writeText(default(context))
        return f.readText()
    }

    fun save(context: Context, text: String) = file(context).writeText(text)

    fun reset(context: Context) = save(context, default(context))

    private fun entries(text: String) =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** 正しい表記だけの一覧 */
    fun terms(text: String): List<String> = entries(text).map { line ->
        val i = line.indexOfAny(charArrayOf('→', '＞'))
        if (i >= 0) line.substring(i + 1).trim() else line
    }.filter { it.isNotEmpty() }.distinct()

    /** AI に渡す形 */
    fun forPrompt(text: String): String = entries(text).joinToString("\n")

    /** Whisper の prompt（語彙のヒント。長すぎると効かないので短く） */
    fun whisperPrompt(text: String, maxChars: Int = 180): String {
        val sb = StringBuilder()
        for (t in terms(text)) {
            if (sb.length + t.length + 1 > maxChars) break
            if (sb.isNotEmpty()) sb.append('、')
            sb.append(t)
        }
        if (sb.isNotEmpty()) sb.append('。')
        return sb.toString()
    }

    /**
     * wol.jw.org の「用語集」などから貼り付けた文から、見出し語を取り出す。
     * 「見出し語＋説明」が1行のものと、見出し語の行の次に説明の行が来るものの両方に対応する。
     * 取り出した結果は、画面で確かめて直してから足す。
     */
    fun extractHeadwords(pasted: String): List<String> {
        val lines = pasted.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val stops = charArrayOf(' ', '　', '「', '（', '(', '：', ':', '，', ',', '、', '。', '—', '－', '\t')
        val out = LinkedHashSet<String>()
        fun add(w: String) {
            val t = w.trim().trimStart('・', '•', '*', '■', '◆', '●').trim().trimEnd('：', ':', '—', '－')
            if (t.length in 1..20 && t.any { !it.isDigit() } && !t.contains('。')) out.add(t)
        }
        lines.forEachIndexed { i, line ->
            val next = lines.getOrNull(i + 1)
            if (line.length <= 20 && !line.contains('。') && next != null && next.length > 20) {
                add(line)
            } else if (line.length > 20) {
                val cut = line.indexOfAny(stops)
                if (cut in 1..20) add(line.substring(0, cut))
            }
        }
        return out.toList()
    }

    /** 用語集にない語だけを、見出しを付けて末尾に足す */
    fun append(context: Context, words: List<String>, heading: String): Int {
        val cur = load(context)
        val have = terms(cur).toSet() + entries(cur).toSet()
        val add = words.map { it.trim() }.filter { it.isNotEmpty() && it !in have }.distinct()
        if (add.isEmpty()) return 0
        save(context, cur.trimEnd() + "\n\n# $heading\n" + add.joinToString("\n") + "\n")
        return add.size
    }
}

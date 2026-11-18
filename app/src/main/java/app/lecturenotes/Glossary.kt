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
}

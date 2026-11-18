package app.lecturenotes

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtClock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}

fun fmtDate(ms: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(ms))

fun engineLabel(e: String?) = when (e) {
    "groq" -> "Groq"
    "gemini" -> "Gemini"
    "local" -> "端末内"
    "error" -> "失敗"
    else -> ""
}

object Export {
    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(60).ifEmpty { "lecture" }

    fun markdown(s: Session): String = buildString {
        append("# ${s.title}\n\n")
        append("${fmtDate(s.startedAt)}　録音 ${fmtClock(s.durationMs)}\n\n")
        val marks = s.marks.sortedBy { it.timeMs }.toMutableList()
        for (g in s.segments) {
            while (marks.isNotEmpty() && marks.first().timeMs < g.startMs) {
                val m = marks.removeAt(0)
                append("> ★ [${fmtClock(m.timeMs)}] ${m.note}\n\n")
            }
            val text = (g.formatted ?: g.raw)?.trim().orEmpty()
            if (g.silent || text.isEmpty()) continue
            append("[${fmtClock(g.startMs)}] $text\n\n")
        }
        marks.forEach { m -> append("> ★ [${fmtClock(m.timeMs)}] ${m.note}\n\n") }
        // 聖句の参照の一覧
        val refs = s.segments.flatMap { g -> Scripture.find(g.formatted ?: g.raw ?: "").map { r -> (g.formatted ?: g.raw ?: "").substring(r.start, r.end) to Scripture.url(r.book, r.chapter) } }
            .distinctBy { it.first }
        if (refs.isNotEmpty()) {
            append("---\n\n## 出てきた聖句\n\n")
            refs.forEach { (label, url) -> append("- [$label]($url)\n") }
        }
    }

    private fun shareDir(context: Context) = File(context.cacheDir, "export").apply {
        mkdirs()
        listFiles()?.forEach { it.delete() }
    }

    fun shareText(context: Context, s: Session) {
        val text = markdown(s)
        val f = File(shareDir(context), "${safeName(s.title)}.md")
        f.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/markdown")
            .putExtra(Intent.EXTRA_SUBJECT, s.title)
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "書き出す").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 区間の音声を1つの WAV にまとめて共有する（時間がかかるので裏で呼ぶ） */
    fun buildAudio(context: Context, s: Session, onProgress: (Float) -> Unit): File {
        val files = s.segments.map { Repo.audioFile(s.id, it) }.filter { it.exists() }
        val out = File(shareDir(context), "${safeName(s.title)}.wav")
        Wav.merge(files, out, onProgress)
        return out
    }

    fun shareFile(context: Context, f: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "音声を書き出す").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

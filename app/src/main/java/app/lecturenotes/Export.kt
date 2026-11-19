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

/** 「2026/11/20（金）」 */
fun fmtDay(ms: Long): String = SimpleDateFormat("yyyy/MM/dd（E）", Locale.JAPAN).format(Date(ms))

fun fmtTime(ms: Long): String = SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(ms))

fun engineLabel(e: String?, model: String? = null): String = model ?: when (e) {
    "groq" -> "Groq"
    "gemini" -> "Gemini"
    "local" -> "端末内"
    "error" -> "失敗"
    else -> ""
}

object Export {
    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(60).ifEmpty { "lecture" }

    fun markdown(s: Session): String = buildString {
        append("# ${s.shownTitle}\n\n")
        append("- 録音日時：${fmtDate(s.startedAt)}\n")
        append("- 録音時間：${fmtClock(s.durationMs)}\n")
        if (s.speaker.isNotBlank()) append("- 話し手：${s.speaker}\n")
        if (s.labels.isNotEmpty()) append("- ラベル：${s.labels.joinToString("、")}\n")
        append("\n")
        for (p in Transcript.paragraphs(s)) {
            val body = p.pieces.joinToString("") { pc ->
                when (pc.kind) {
                    Transcript.Kind.MARK -> " **【★ ${fmtClock(pc.mark?.timeMs ?: 0)} ${pc.mark?.note ?: ""}】** "
                    Transcript.Kind.PENDING -> "…"
                    Transcript.Kind.TIME -> ""
                    else -> {
                        val g = pc.seg
                        val ph = g?.let { Marker.phrases(it.highlights) } ?: emptyList()
                        if (ph.isEmpty()) pc.text
                        else if (Marker.WHOLE in ph) "**${pc.text}**"
                        else ph.fold(pc.text) { acc, h -> if (acc.contains(h)) acc.replaceFirst(h, "**$h**") else acc }
                    }
                }
            }.trim()
            if (body.isEmpty()) continue
            append("`${fmtClock(p.startMs)}` $body\n\n")
        }
        // マーカーの一覧
        val marked = s.segments.flatMap { g ->
            g.highlights.map { e ->
                val (c, h) = Marker.parse(e)
                val dot = when (c) { 'r' -> "🔴"; 'y' -> "🟡"; else -> "🟢" }
                fmtClock(g.startMs) to "$dot " + (if (h == Marker.WHOLE) (displayText(g) ?: "").trim() else h)
            }
        }.filter { it.second.length > 2 }
        if (marked.isNotEmpty()) {
            append("---\n\n## マーカーを引いたところ\n\n")
            marked.forEach { (t, h) -> append("- `$t` $h\n") }
            append("\n")
        }
        // 聖句の参照の一覧
        val refs = s.segments.flatMap { g ->
            val t = displayText(g) ?: ""
            Scripture.find(t).map { r -> t.substring(r.start, r.end) to Scripture.webUrl(r) }
        }.distinctBy { it.first }
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
            // 長い記録の文を Intent にそのまま入れると大きすぎて落ちるので、短いときだけ本文も付ける（いつもファイルは付ける）
            .apply { if (text.length < 50_000) putExtra(Intent.EXTRA_TEXT, text) }
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "書き出す").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 区間の音声を1つの m4a にまとめる（時間がかかるので裏で呼ぶ） */
    fun buildAudio(context: Context, s: Session, onProgress: (Float) -> Unit): File {
        val dir = shareDir(context)
        val tmp = File(dir, "tmp").apply { mkdirs() }
        // 古い WAV の区間は、先に m4a に変える
        val files = s.segments.map { Repo.audioFile(s.id, it) }.filter { it.exists() }.map { f ->
            if (f.name.endsWith(".wav")) File(tmp, f.name.removeSuffix(".wav") + ".m4a").also { Audio.wavToM4a(f, it) } else f
        }
        val out = File(dir, "${safeName(s.title)}.m4a")
        Audio.concatM4a(files, out, onProgress)
        tmp.deleteRecursively()
        return out
    }

    fun shareFile(context: Context, f: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "音声を書き出す").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

package app.lecturenotes

/**
 * 区間の文字をつないで段落にする。段落の切れ目は、整形の AI（または利用者）が入れた空行。
 */
object Transcript {
    enum class Kind {
        /** 自分で直したか、整形済み */
        FINAL,
        /** クラウドの文字起こし（整形前） */
        RAW,
        /** 端末内の文字起こし（仮） */
        DRAFT,
        /** まだ文字になっていない */
        PENDING,
        MARK,
    }

    data class Piece(val seg: Segment?, val text: String, val kind: Kind, val mark: Mark? = null)
    data class Para(val startMs: Long, val pieces: List<Piece>) {
        val text get() = pieces.filter { it.kind != Kind.MARK }.joinToString("") { it.text }
    }

    private val BREAK = Regex("\\n[ \\t　]*\\n+")

    /** 整形が段落を入れないときのため、長くなりすぎたら区間の境目で区切る */
    private const val MAX_PARA = 700

    fun paragraphs(s: Session): List<Para> {
        val out = ArrayList<Para>()
        var cur = ArrayList<Piece>()
        var curStart = -1L
        var curLen = 0
        fun close() {
            if (cur.any { it.kind != Kind.MARK }) out.add(Para(curStart, cur))
            else if (cur.isNotEmpty() && out.isNotEmpty()) out[out.size - 1] = out.last().copy(pieces = out.last().pieces + cur)
            else if (cur.isNotEmpty()) out.add(Para(curStart.coerceAtLeast(0), cur))
            cur = ArrayList()
            curStart = -1
            curLen = 0
        }

        val marks = s.marks.sortedBy { it.timeMs }
        var mi = 0
        for (g in s.segments) {
            while (mi < marks.size && marks[mi].timeMs < g.startMs) {
                cur.add(Piece(null, "★${marks[mi].note}", Kind.MARK, marks[mi]))
                mi++
            }
            if (g.silent) continue
            val text = displayText(g)
            val kind = when {
                g.edited != null || g.formatted != null -> Kind.FINAL
                g.rawEngine == "error" -> Kind.PENDING
                g.rawEngine == "local" && !text.isNullOrBlank() -> Kind.DRAFT
                !text.isNullOrBlank() -> Kind.RAW
                else -> Kind.PENDING
            }
            if (kind == Kind.PENDING) {
                if (curStart < 0) curStart = g.startMs
                cur.add(Piece(g, if (g.rawEngine == "error") "（文字にできませんでした）" else "…", Kind.PENDING))
                continue
            }
            if (text.isNullOrBlank()) continue
            if (curLen > MAX_PARA) close()
            val parts = text.split(BREAK)
            parts.forEachIndexed { i, part ->
                if (i > 0) close()
                val t = part.trim('\n', '\r')
                if (t.isEmpty()) return@forEachIndexed
                if (curStart < 0) curStart = g.startMs
                cur.add(Piece(g, t, kind))
                curLen += t.length
            }
        }
        while (mi < marks.size) {
            cur.add(Piece(null, "★${marks[mi].note}", Kind.MARK, marks[mi]))
            mi++
        }
        close()
        return out
    }
}

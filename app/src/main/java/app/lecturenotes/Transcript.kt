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
        /** 1分ごとの控えめな打刻 */
        TIME,
    }

    private fun isContent(k: Kind) = k != Kind.MARK && k != Kind.TIME

    data class Piece(val seg: Segment?, val text: String, val kind: Kind, val mark: Mark? = null)
    data class Para(val startMs: Long, val pieces: List<Piece>) {
        val text get() = pieces.filter { isContent(it.kind) }.joinToString("") { it.text }
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
            if (cur.any { isContent(it.kind) }) out.add(Para(curStart, cur))
            else if (cur.isNotEmpty() && out.isNotEmpty()) out[out.size - 1] = out.last().copy(pieces = out.last().pieces + cur)
            else if (cur.isNotEmpty()) out.add(Para(curStart.coerceAtLeast(0), cur))
            cur = ArrayList()
            curStart = -1
            curLen = 0
        }

        val marks = s.marks.sortedBy { it.timeMs }
        var mi = 0
        var lastMinute = 0L
        var stamp: Long? = null // 次の文の前に入れる打刻
        fun addPiece(p: Piece) {
            stamp?.let { m -> cur.add(Piece(null, fmtMinute(m), Kind.TIME)); stamp = null }
            cur.add(p)
        }
        for (g in s.segments) {
            while (mi < marks.size && marks[mi].timeMs < g.startMs) {
                cur.add(Piece(null, "★${marks[mi].note}", Kind.MARK, marks[mi]))
                mi++
            }
            if (g.silent) continue
            val minute = g.startMs / 60_000
            if (minute > lastMinute) {
                stamp = minute
                lastMinute = minute
            }
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
                addPiece(Piece(g, if (g.rawEngine == "error") "（文字にできませんでした）" else "…", Kind.PENDING))
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
                addPiece(Piece(g, t, kind))
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

    /** 「12分」「1時間5分」 */
    fun fmtMinute(m: Long): String = if (m >= 60) "${m / 60}時間${m % 60}分" else "${m}分"
}

package app.lecturenotes

/** マーカー（蛍光ペン） */
object Marker {
    const val WHOLE = "*"

    private val PUNCT = Regex("[、。，．,.!！?？「」『』（）()・…\\s　]")

    /** 整形の AI に「変えない」と伝える語句 */
    fun keep(batch: List<Segment>): List<String> =
        batch.flatMap { g -> g.highlights.filter { it != WHOLE } }.distinct()

    fun toggleWhole(id: String, index: Int) {
        Repo.updateSegment(id, index) { g ->
            g.copy(highlights = if (WHOLE in g.highlights) g.highlights - WHOLE else g.highlights + WHOLE)
        }
    }

    fun add(id: String, index: Int, phrase: String) {
        val p = phrase.trim()
        if (p.isEmpty()) return
        Repo.updateSegment(id, index) { g -> g.copy(highlights = (g.highlights + p).distinct()) }
    }

    fun remove(id: String, index: Int, phrase: String) {
        Repo.updateSegment(id, index) { g -> g.copy(highlights = g.highlights - phrase) }
    }

    /**
     * 文の中で語句がある範囲。そのままで見つからなければ、句読点・空白を無視して探す
     * （AI が句読点を足しても、マーカーが消えないように）。
     */
    fun find(text: String, phrase: String): List<IntRange> {
        if (phrase.isEmpty()) return emptyList()
        val direct = Regex(Regex.escape(phrase)).findAll(text).map { it.range }.toList()
        if (direct.isNotEmpty()) return direct
        val target = phrase.replace(PUNCT, "")
        if (target.length < 2) return emptyList()
        // 句読点を除いた文字の位置 → 元の位置
        val idx = ArrayList<Int>()
        val sb = StringBuilder()
        text.forEachIndexed { i, ch -> if (!PUNCT.matches(ch.toString())) { sb.append(ch); idx.add(i) } }
        val stripped = sb.toString()
        val out = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val k = stripped.indexOf(target, from)
            if (k < 0) break
            out.add(idx[k]..idx[k + target.length - 1])
            from = k + target.length
        }
        return out
    }
}

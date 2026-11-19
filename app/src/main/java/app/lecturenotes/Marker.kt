package app.lecturenotes

/** マーカー（蛍光ペン） */
object Marker {
    const val WHOLE = "*"

    private val PUNCT = Regex("[、。，．,.!！?？「」『』（）()・…\\s　]")

    /** 色：g 緑（最初の色）・y 黄・r 赤 */
    val COLORS = listOf('g', 'y', 'r')

    /**
     * 保存の形は「色|語句」（例「g|王国会館」）。
     * 色の付いていない古いものは黄（前は黄色だけだったため）。
     */
    fun parse(entry: String): Pair<Char, String> =
        if (entry.length >= 2 && entry[1] == '|' && entry[0] in COLORS) entry[0] to entry.substring(2) else 'y' to entry

    fun entry(color: Char, phrase: String) = "$color|$phrase"

    fun phrases(highlights: List<String>) = highlights.map { parse(it).second }

    /** 整形の AI に「変えない」と伝える語句 */
    fun keep(batch: List<Segment>): List<String> =
        batch.flatMap { g -> phrases(g.highlights).filter { it != WHOLE } }.distinct()

    fun toggleWhole(id: String, index: Int) {
        Repo.updateSegment(id, index) { g ->
            val has = g.highlights.firstOrNull { parse(it).second == WHOLE }
            g.copy(highlights = if (has != null) g.highlights - has else g.highlights + entry('g', WHOLE))
        }
    }

    /** 語句にマーカーを付ける（同じ語句がほかの色で付いていたら、この色にする）。保存した形を返す */
    fun add(id: String, index: Int, phrase: String, color: Char = 'g'): String? {
        val p = phrase.trim()
        if (p.isEmpty()) return null
        val e = entry(color, p)
        Repo.updateSegment(id, index) { g ->
            g.copy(highlights = (g.highlights.filter { parse(it).second != p } + e).distinct())
        }
        return e
    }

    /** 保存した形（または語句だけ）で外す */
    fun remove(id: String, index: Int, entryOrPhrase: String) {
        val p = parse(entryOrPhrase).second
        Repo.updateSegment(id, index) { g -> g.copy(highlights = g.highlights.filter { it != entryOrPhrase && parse(it).second != p }) }
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

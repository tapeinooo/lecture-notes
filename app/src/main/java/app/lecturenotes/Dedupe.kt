package app.lecturenotes

/**
 * 端末内の音声認識の重複を取り除く。
 * 端末内の認識は、同じ文を2回返したり（区切りの確定と認識の終わり）、やり直したときに直前の部分を返したりする。
 */
object Dedupe {
    private val IGNORE = Regex("[\\s　、。，．,.!！?？]")

    private fun norm(s: String) = s.replace(IGNORE, "")

    /**
     * これまでの文（recent）に続けて、incoming のうちまだない部分だけを返す。
     *  - すでに最近の文の中にあれば ""（足さない）
     *  - 最近の文の終わりと incoming の始めが重なっていれば、重なっていない後ろの部分だけ
     */
    fun newPart(recent: String, incoming: String): String {
        val inc = incoming.trim()
        if (inc.isEmpty()) return ""
        val r = norm(recent)
        val i = norm(inc)
        if (i.isEmpty()) return ""
        // 最近の文に丸ごと含まれる → 重複（「はい」「そうです」のような短い言葉は、本当に繰り返すことがあるので消さない）
        if (i.length >= 6 && r.takeLast(i.length * 2 + 20).contains(i)) return ""
        // 終わりと始めの重なり（4 文字以上。2〜3 文字は「ます」「して」などで偶然重なりやすい）
        for (k in minOf(i.length - 1, r.length) downTo 4) {
            if (r.endsWith(i.substring(0, k))) {
                return cutAfterNormalized(inc, k).trim()
            }
        }
        return inc
    }

    /** 句読点・空白を数えずに、先頭から k 文字ぶんを取り除いた残り */
    private fun cutAfterNormalized(s: String, k: Int): String {
        var count = 0
        for (idx in s.indices) {
            if (!IGNORE.matches(s[idx].toString())) count++
            if (count == k) return s.substring(idx + 1)
        }
        return ""
    }

    /**
     * text のうち、reference（すでに表示している直前の文章）に含まれている文を取り除く。
     * 端末内と清書で言い回しが少し違っても（漢字・かな）、7 割以上同じなら同じ文とみなす。
     */
    fun dropCovered(text: String, reference: String): String {
        val ref = norm(reference).takeLast(500)
        if (ref.isEmpty() || text.isBlank()) return text
        val pieces = text.split(Regex("(?<=[。！？!?])|\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        return pieces.filter { p ->
            val n = norm(p)
            n.length < 6 || !coveredLocally(n, ref)
        }.joinToString(" ")
    }

    /**
     * n が ref のどこか一か所に、ほぼそのまま（8 割以上）あるか。
     * 長い ref 全体と比べると、よくあるかな文字が偶然そろって「同じ」と見なしてしまうので、
     * n の 1.5 倍くらいの長さの範囲ごとに比べる。
     */
    private fun coveredLocally(n: String, ref: String): Boolean {
        val w = (n.length * 3 / 2).coerceAtLeast(n.length + 2)
        if (ref.length <= w) return lcs(n, ref).toDouble() / n.length >= 0.8
        val step = (n.length / 3).coerceAtLeast(1)
        var st = 0
        while (true) {
            val en = minOf(ref.length, st + w)
            if (lcs(n, ref.substring(st, en)).toDouble() / n.length >= 0.8) return true
            if (en == ref.length) return false
            st += step
        }
    }

    private fun lcs(a: String, b: String): Int {
        var prev = IntArray(b.length + 1)
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            val t = prev; prev = cur; cur = t
            cur.fill(0)
        }
        return prev[b.length]
    }
}

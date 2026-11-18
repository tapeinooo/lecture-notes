package app.lecturenotes

/**
 * 利用者が直した文から「誤→正」を取り出す。
 * 例：「王国界館に集まり」→「王国会館に集まり」から「界館→会館」ではなく、前後を少し含めた「王国界館→王国会館」を取る。
 */
object Corrections {
    data class Fix(val from: String, val to: String) {
        override fun toString() = "$from→$to"
    }

    private val PUNCT = "、。，．,.!！?？「」『』（）()・…ー〜~ 　\n\t"

    private fun isPunctOnly(s: String) = s.all { PUNCT.indexOf(it) >= 0 }

    /** 文字単位の差分から、変わったところを取り出す */
    fun extract(before: String, after: String): List<Fix> {
        if (before == after) return emptyList()
        val a = before.take(1500)
        val b = after.take(1500)
        val n = a.length
        val m = b.length
        // 最長共通部分列
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val fixes = ArrayList<Fix>()
        var i = 0
        var j = 0
        while (i < n || j < m) {
            if (i < n && j < m && a[i] == b[j]) {
                i++; j++
                continue
            }
            // 違うところのかたまり
            val si = i
            val sj = j
            while ((i < n || j < m) && !(i < n && j < m && a[i] == b[j] && runSame(a, b, i, j) >= 2)) {
                if (j >= m || (i < n && dp[i + 1][j] >= dp[i][j + 1])) i++ else j++
            }
            var from = a.substring(si, i)
            var to = b.substring(sj, j)
            if (to.isEmpty() || isPunctOnly(from + to)) continue // 消しただけ・句読点だけの直しは覚えない
            // 1〜2文字の直しは、前後を足して語として覚える
            var l = si
            var r = i
            var lb = sj
            var rb = j
            while (from.length < 3 && l > 0 && lb > 0 && PUNCT.indexOf(a[l - 1]) < 0) {
                l--; lb--
                from = a.substring(l, r); to = b.substring(lb, rb)
            }
            while (from.length < 3 && r < n && rb < m && PUNCT.indexOf(a[r]) < 0) {
                r++; rb++
                from = a.substring(l, r); to = b.substring(lb, rb)
            }
            if (from.length in 1..20 && to.length in 1..20) fixes.add(Fix(from.trim(), to.trim()))
        }
        return fixes.filter { it.from.isNotEmpty() && it.to.isNotEmpty() && it.from != it.to }.distinct()
    }

    private fun runSame(a: String, b: String, i: Int, j: Int): Int {
        var k = 0
        while (i + k < a.length && j + k < b.length && a[i + k] == b[j + k] && k < 2) k++
        return k
    }

    /** ほかの区間で同じ誤りが何か所あるか */
    fun countOthers(s: Session, exceptIndex: Int, fixes: List<Fix>): Int =
        s.segments.filter { it.index != exceptIndex && it.edited == null }.sumOf { g ->
            val t = displayText(g) ?: ""
            fixes.sumOf { f -> t.windowed(f.from.length, 1).count { it == f.from } }
        }

    /** ほかの区間の文字（文字起こし・整形の両方）も同じように直す。利用者が直した区間は触らない */
    fun applyToOthers(id: String, exceptIndex: Int, fixes: List<Fix>) {
        Repo.update(id) { s ->
            s.copy(segments = s.segments.map { g ->
                if (g.index == exceptIndex || g.edited != null) g else {
                    fun fix(t: String?) = t?.let { fixes.fold(it) { acc, f -> acc.replace(f.from, f.to) } }
                    g.copy(raw = fix(g.raw), formatted = fix(g.formatted))
                }
            })
        }
    }
}

/** 画面に出す文字：自分で直したもの → 整形 → 文字起こし */
fun displayText(g: Segment): String? = g.edited ?: g.formatted ?: g.raw

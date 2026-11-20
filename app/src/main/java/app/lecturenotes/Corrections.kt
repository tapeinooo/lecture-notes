package app.lecturenotes

/**
 * 利用者が直した文から「誤→正」を取り出す。
 * 例：「王国界館に集まり」→「王国会館に集まり」から「界館→会館」ではなく、前後を少し含めた「王国界館→王国会館」を取る。
 */
object Corrections {
    /** start / end：直した後の文の中での、to の位置 */
    data class Fix(val from: String, val to: String, val start: Int = -1, val end: Int = -1) {
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
            if (from.length in 1..20 && to.length in 1..20) fixes.add(Fix(from.trim(), to.trim(), lb, rb))
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

/**
 * 直したところから、用語集に足すとよさそうな「単語」を取り出す（「誤→正」の組ではなく、正しい単語だけ）。
 * 直した部分の前後を、漢字・カタカナ・英数字が続く間だけ広げて1語にし、端の助詞（の・を・に…）を落とす。
 * すでに用語集にある単語は出さない。
 */
object WordSuggest {
    private fun isWordChar(c: Char) =
        c in '一'..'龥' || c == '々' || c in 'ァ'..'ヶ' || c == 'ー' || c.isLetterOrDigit() && c.code < 0x3000

    private val PARTICLES = "のをにはがでとへもやね"

    fun words(context: android.content.Context, after: String, fixes: List<Corrections.Fix>): List<String> {
        val have = Glossary.terms(Glossary.load(context)).toSet()
        val out = LinkedHashSet<String>()
        for (f in fixes) {
            if (f.start < 0 || f.end > after.length || f.start >= f.end) continue
            var a = f.start
            var b = f.end
            // 先に、直した部分の端の助詞を落としてから広げる（「聖なる力の」→「聖なる力」）
            while (a < b && PARTICLES.indexOf(after[b - 1]) >= 0) b--
            while (a < b && PARTICLES.indexOf(after[a]) >= 0) a++
            if (a >= b) continue
            while (a > 0 && isWordChar(after[a - 1])) a--
            while (b < after.length && isWordChar(after[b])) b++
            var w = after.substring(a, b).trim()
            while (w.isNotEmpty() && PARTICLES.indexOf(w.last()) >= 0) w = w.dropLast(1)
            while (w.isNotEmpty() && PARTICLES.indexOf(w.first()) >= 0) w = w.drop(1)
            w = w.trim('、', '。', '「', '」', ' ', '　')
            // ひらがなだけの語（言い回し）や、長すぎる・短すぎるものは出さない
            if (w.length !in 2..16 || w.all { it in 'ぁ'..'ゖ' }) continue
            if (w in have) continue
            out.add(w)
        }
        return out.toList()
    }
}

/** 画面に出す文字：自分で直したもの → 整形 → 文字起こし */
fun displayText(g: Segment): String? = g.edited ?: g.formatted ?: g.raw

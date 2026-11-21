package app.lecturenotes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/**
 * 聖句の参照（例「ヨハネ 3:16」「コリント人への第一の手紙9章10節」「十章の五節」）を見つけて、
 * JW Library で開くリンクにし、「コリント第一9:10」の形にそろえる。
 * 本文は取りに行かない（jw.org の利用規約で、サイトから自動でデータを取り出すことは禁じられているため）。
 *
 * * 書名の見つけ方（書き直すときは wol.jw.org の言い方。聞き取りは前の言い方なども広く読む）：
 *  1. 「◯章◯節」「◯:◯」の直前の語を書名とみなす（いろいろな言い方を登録してある）
 *  2. 登録にない言い方でも、聞き間違いで少し違う程度なら近い書名に当てはめる（ひらがなも読む）
 *  3. 用語集の「誤→正」で、正しい側が書名のものは、書名の言い換えとして覚える
 *  4. 書名がなく「◯章◯節」だけのときは、同じ文の中で前に出てきた書名を使う（文には書名を書き足さない）
 */
object Scripture {
    /** 新世界訳の書名（wol.jw.org「聖書の各書の一覧」の言い方。並び＝書の番号） */
    val books = listOf(
        "創世記", "出エジプト記", "レビ記", "民数記", "申命記", "ヨシュア", "裁き人", "ルツ",
        "サムエル第一", "サムエル第二", "列王第一", "列王第二", "歴代第一", "歴代第二",
        "エズラ", "ネヘミヤ", "エステル", "ヨブ", "詩編", "格言", "伝道の書", "ソロモンの歌",
        "イザヤ", "エレミヤ", "哀歌", "エゼキエル", "ダニエル", "ホセア", "ヨエル", "アモス",
        "オバデヤ", "ヨナ", "ミカ", "ナホム", "ハバクク", "ゼパニヤ", "ハガイ", "ゼカリヤ", "マラキ",
        "マタイ", "マルコ", "ルカ", "ヨハネ", "使徒", "ローマ", "コリント第一", "コリント第二", "ガラテア",
        "エフェソス", "フィリピ", "コロサイ", "テサロニケ第一", "テサロニケ第二", "テモテ第一", "テモテ第二",
        "テトス", "フィレモン", "ヘブライ", "ヤコブ", "ペテロ第一", "ペテロ第二", "ヨハネ第一", "ヨハネ第二",
        "ヨハネ第三", "ユダ", "啓示",
    )

    /** 書き直すときの書名は wol.jw.org の言い方（books）。聞き取りは、前の言い方やほかの言い方も広く読む */
    private val builtIn: Map<String, Int> = buildMap {
        books.forEachIndexed { i, b -> put(b, i + 1) }
        // 前の言い方（「記」「書」の付く書名）
        val older = listOf(
            "ヨシュア記" to 6, "ルツ記" to 8, "サムエル記第一" to 9, "サムエル記第二" to 10, "列王記第一" to 11, "列王記第二" to 12,
            "歴代誌第一" to 13, "歴代誌第二" to 14, "エズラ記" to 15, "ネヘミヤ記" to 16, "エステル記" to 17, "ヨブ記" to 18,
            "イザヤ書" to 23, "エレミヤ書" to 24, "エゼキエル書" to 26, "ダニエル書" to 27, "ホセア書" to 28, "ヨエル書" to 29,
            "アモス書" to 30, "オバデヤ書" to 31, "ヨナ書" to 32, "ミカ書" to 33, "ナホム書" to 34, "ハバクク書" to 35,
            "ゼパニヤ書" to 36, "ハガイ書" to 37, "ゼカリヤ書" to 38, "マラキ書" to 39,
            "創世" to 1, "出エジプト" to 2, "レビ" to 3, "民数" to 4, "申命" to 5,
        )
        older.forEach { (n, b) -> put(n, b) }
        // 第一・第二…の付く書（「コリント人への第一の手紙」「第1コリント」「コリント1」など）
        val ordinal = listOf(
            "サムエル" to 9, "サムエル記" to 9, "列王" to 11, "列王記" to 11, "歴代" to 13, "歴代誌" to 13,
            "コリント" to 46, "テサロニケ" to 52, "テモテ" to 54, "ペテロ" to 60, "ヨハネ" to 62,
        )
        for ((n, first) in ordinal) {
            val count = if (n == "ヨハネ") 3 else 2
            for (k in 0 until count) {
                val b = first + k
                for (o in listOf(listOf("一", "二", "三")[k], (k + 1).toString())) {
                    put("${n}第$o", b); put("第$o$n", b); put("$n$o", b)
                    put("${n}の第$o", b); put("${n}第${o}の手紙", b); put("${n}の第${o}の手紙", b)
                    put("${n}人への第${o}の手紙", b); put("${n}人への第$o", b); put("${n}人への手紙第$o", b)
                    put("${n}への第${o}の手紙", b); put("第$o${n}人への手紙", b)
                }
            }
        }
        // 手紙
        listOf(
            "ローマ" to 45, "ガラテア" to 48, "エフェソス" to 49, "エペソ" to 49, "フィリピ" to 50, "ピリピ" to 50,
            "コロサイ" to 51, "ヘブライ" to 58, "ヘブル" to 58,
        ).forEach { (n, b) -> put(n, b); put("${n}人への手紙", b); put("${n}人への", b); put("${n}の手紙", b) }
        listOf("テトス" to 56, "フィレモン" to 57).forEach { (n, b) -> put("${n}への手紙", b); put("${n}の手紙", b) }
        listOf("ヤコブ" to 59, "ユダ" to 65).forEach { (n, b) -> put("${n}の手紙", b); put("${n}書", b) }
        // 福音書
        listOf("マタイ" to 40, "マルコ" to 41, "ルカ" to 42, "ヨハネ" to 43).forEach { (n, b) ->
            put("${n}による福音書", b); put("${n}の福音書", b); put("${n}福音書", b); put("${n}による", b)
        }
        // そのほか
        put("裁き人の書", 7); put("士師記", 7); put("士師", 7)
        put("詩篇", 19); put("格言の書", 20); put("箴言", 20); put("伝道者の書", 21); put("伝道", 21); put("雅歌", 22)
        put("使徒行伝", 44); put("使徒の活動", 44); put("使徒たちの活動", 44)
        put("黙示録", 66); put("ヨハネの黙示録", 66); put("ヨハネへの啓示", 66); put("啓示の書", 66)
    }

    /** 用語集から覚えた言い方 → 書の番号 */
    @Volatile private var user: Map<String, Int> = emptyMap()

    private val aliases: Map<String, Int> get() = builtIn + user

    /** 長い言い方から順に（「ヨハネ第一」を「ヨハネ」より先に見る） */
    @Volatile private var sortedNames: List<Pair<String, Int>> = emptyList()

    /** sortedNames をカタカナにしたもの */
    @Volatile private var sortedKana: List<Pair<String, Int>> = emptyList()

    private fun rebuild() {
        sortedNames = aliases.entries.map { it.key to it.value }.sortedByDescending { it.first.length }
        sortedKana = sortedNames.map { toKatakana(it.first) to it.second }
    }

    /** 用語集の「誤→正」のうち、正しい側が書名のものを、書名の言い換えとして覚える */
    fun setUserAliases(glossary: String) {
        val m = HashMap<String, Int>()
        glossary.lines().map { it.trim() }.filter { !it.startsWith("#") }.forEach { line ->
            val i = line.indexOfAny(charArrayOf('→', '＞'))
            if (i <= 0) return@forEach
            val wrong = line.substring(0, i).trim()
            val right = line.substring(i + 1).trim().replace(" ", "")
            val b = builtIn[right] ?: return@forEach
            if (wrong.isNotEmpty() && wrong.length <= 20) m[wrong] = b
        }
        user = m
        rebuild()
    }

    init {
        rebuild()
    }

    // ---- 章・節の形（書名は別に探す）
    private const val END = "(?:\\s*(?:[-‐－–〜~～]|から)\\s*(\\d{1,3})(?:\\s*節)?|\\s*[,，、]\\s*(\\d{1,3})(?!\\d|\\s*[人年月日時分歳回個つ件冊])(?:\\s*節)?)?"
    private val cv = Regex("(\\d{1,3})\\s*(?:[章編篇]\\s*(?:の)?\\s*(\\d{1,3})\\s*(節)?|[:：]\\s*(\\d{1,3}))$END")

    /**
     * endVerse は「1-5」「9,10」の後ろの数（なければ null）。list = 「9,10」の形。
     * valid = 実在する章・節か。bookless = 文の中に書名がなく、前に出てきた書名を使ったもの
     */
    data class Ref(
        val start: Int, val end: Int, val book: Int, val chapter: Int, val verse: Int,
        val endVerse: Int? = null, val valid: Boolean = true, val list: Boolean = false, val bookless: Boolean = false,
    )

    // ---- 章ごとの節の数（assets/verse_counts.txt。実在しない「10:90」などを見分ける）
    @Volatile private var counts: List<IntArray>? = null

    fun init(context: android.content.Context) {
        counts = runCatching {
            context.assets.open("verse_counts.txt").bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line -> line.split(',').map { it.trim().toInt() }.toIntArray() }
        }.getOrNull()
        runCatching { setUserAliases(Glossary.load(context)) }
    }

    /** 実在する章・節か（表がなければ true）。訳による節の数の違いを考えて 1 節まで余裕を見る */
    fun exists(book: Int, chapter: Int, verse: Int): Boolean {
        val c = counts ?: return true
        val chapters = c.getOrNull(book - 1) ?: return false
        val max = chapters.getOrNull(chapter - 1) ?: return false
        return verse in 1..(max + 1)
    }

    // ---- 漢数字・全角数字を半角数字に（元の位置との対応を残す）
    private const val KANJI_DIGITS = "〇一二三四五六七八九"

    private class Normalized(val text: String, val start: IntArray, val end: IntArray)

    private fun kanjiValue(seq: String): Int? {
        var total = 0
        var cur = 0
        for (ch in seq) {
            val d = KANJI_DIGITS.indexOf(ch)
            when {
                d >= 0 -> cur = cur * 10 + d
                ch == '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
                ch == '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
                else -> return null
            }
        }
        return total + cur
    }

    private fun normalize(s: String): Normalized {
        val sb = StringBuilder()
        val st = ArrayList<Int>()
        val en = ArrayList<Int>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (KANJI_DIGITS.indexOf(c) >= 0 || c == '十' || c == '百') {
                var j = i
                while (j < s.length && (KANJI_DIGITS.indexOf(s[j]) >= 0 || s[j] == '十' || s[j] == '百')) j++
                val v = kanjiValue(s.substring(i, j))
                // 「章」「節」の前の漢数字だけ変える（「コリント第一」などの書名はそのまま）
                var k2 = j
                while (k2 < s.length && (s[k2] == ' ' || s[k2] == '　')) k2++
                val beforeUnit = k2 < s.length && (s[k2] == '章' || s[k2] == '節' || s[k2] == '編' || s[k2] == '篇')
                if (v != null && v in 1..999 && beforeUnit) {
                    v.toString().forEach { d -> sb.append(d); st.add(i); en.add(j) }
                } else {
                    for (k in i until j) { sb.append(s[k]); st.add(k); en.add(k + 1) }
                }
                i = j
                continue
            }
            sb.append(if (c in '０'..'９') ('0' + (c - '０')) else c)
            st.add(i)
            en.add(i + 1)
            i++
        }
        return Normalized(sb.toString(), st.toIntArray(), en.toIntArray())
    }

    // ---- 書名を探す
    /** 比べるための形：ひらがな→カタカナ、「第1」→「第一」 */
    private fun toKatakana(s: String) = buildString { s.forEach { c -> append(if (c in 'ぁ'..'ゖ') c + 0x60 else c) } }
        .replace("第1", "第一").replace("第2", "第二").replace("第3", "第三")

    private fun isNameChar(c: Char) =
        c in 'ァ'..'ヶ' || c == 'ー' || c in 'ぁ'..'ゖ' || c in '一'..'龥' || c == '々' || c.isDigit() || c == '第'

    private fun distance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private val HEN = Regex("^\\d{1,3}\\s*[編篇]")

    /** 書名と章の間に入っていても飛ばす文字（空白・「の」・句読点・改行など） */
    private val SEP = charArrayOf(' ', '　', 'の', 'ノ', '、', '。', '，', '．', ',', '.', '・', '\n', '\t', '「', '」', '『', '』', '（', '）', '(', ')')

    /** 登録してある言い方にぴったり合う書名（ひらがなはカタカナにして比べる） */
    private fun exactBookBefore(prefix: String): Pair<Int, Int>? {
        val pk = toKatakana(prefix)
        for ((name, b) in sortedKana) {
            if (pk.endsWith(name)) return name.length to b
        }
        return null
    }

    /** 文の終わり（章・節の直前）にある書名。見つかれば（書名の長さ, 書の番号） */
    private fun bookBefore(prefix: String): Pair<Int, Int>? {
        // 1) 登録してある言い方
        exactBookBefore(prefix)?.let { return it }
        // 2) 聞き間違い：直前の語（書名に使う文字の続き）の終わりの部分を、近い書名に当てはめる
        var r = prefix.length
        while (r > 0 && isNameChar(prefix[r - 1]) && prefix.length - r < 14) r--
        val run = toKatakana(prefix.substring(r))
        var best: Triple<Int, Int, Int>? = null // (長さ, 書, 距離)
        for (len in minOf(run.length, 14) downTo 3) {
            val s = run.takeLast(len)
            for ((n, b) in sortedKana) {
                if (n.length < 3) continue
                val allow = if (n.length >= 5) 2 else 1
                if (kotlin.math.abs(n.length - s.length) > allow) continue
                val d = distance(s, n)
                if (d <= allow && (best == null || d < best.third || (d == best.third && len > best.first))) best = Triple(len, b, d)
            }
        }
        return best?.let { it.first to it.second }
    }

    /** 実在するものも、しないものも含めて全部 */
    fun findAll(text: String): List<Ref> {
        if (text.isEmpty()) return emptyList()
        val n = normalize(text)
        val t = n.text
        fun orig(a: Int, b: Int) = n.start[a] to n.end[b - 1]
        val out = ArrayList<Ref>()
        var lastBook: Int? = null
        var lastBookPos = -1
        var lastEnd = -1 // 前の参照の終わり（正規化した文の位置）
        for (m in cv.findAll(t)) {
            val ch = m.groupValues[1].toInt()
            val viaChapter = m.groupValues[2].isNotEmpty()
            val v = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: continue
            val hasSetsu = m.groupValues[3].isNotEmpty()
            val ev = (m.groupValues[5].toIntOrNull() ?: m.groupValues[6].toIntOrNull())?.takeIf { it > v }
            val list = m.groupValues[6].isNotEmpty() && ev != null
            // 直前の書名（間の空白・「の」は飛ばす）
            val prefixRaw = t.substring(maxOf(0, m.range.first - 30), m.range.first)
            var prefix = prefixRaw.trimEnd(' ', '　', 'の', 'ノ')
            var found = bookBefore(prefix)
            if (found == null) {
                // 整形で書名と章の間に句読点が入ったもの（「コリント第一、9:10」「ヨハネ。3章16節」）。
                // 聞き間違いの当てはめはせず、登録してある書名にぴったり合うときだけ
                val p2 = prefixRaw.trimEnd(*SEP)
                if (p2.length < prefix.length) {
                    exactBookBefore(p2)?.let { found = it; prefix = p2 }
                }
            }
            val gap = prefixRaw.length - prefix.length
            val fb = found
            // 詩編は「23編1節」と言う
            val viaHen = HEN.containsMatchIn(m.value)
            val (start, book, bookless) = when {
                fb != null -> Triple(m.range.first - gap - fb.first, fb.second, false)
                // 書名のない「◯編◯節」は詩編
                viaHen && hasSetsu -> Triple(m.range.first, 19, false)
                // 書名がない「◯章◯節」は、前に出てきた書名を使う（時刻と区別するため「章」「節」が必要）
                viaChapter && hasSetsu -> {
                    val b = mentionedBefore(t, m.range.first, lastBook, lastBookPos) ?: continue
                    Triple(m.range.first, b, true)
                }
                else -> continue
            }
            if (start < lastEnd) continue
            val (a, bEnd) = orig(start.coerceAtLeast(0), m.range.last + 1)
            out.add(Ref(a, bEnd, book, ch, v, ev, exists(book, ch, v) && (ev == null || exists(book, ch, ev)), list, bookless))
            lastBook = book
            lastBookPos = m.range.last
            lastEnd = m.range.last + 1
        }
        return out
    }

    /** pos より前に出てきた書名（参照の書名か、文の中の書名） */
    private fun mentionedBefore(t: String, pos: Int, lastBook: Int?, lastBookPos: Int): Int? {
        val before = toKatakana(t.substring(0, pos))
        var best: Pair<Int, Int>? = null // (位置, 書)
        for ((name, b) in sortedKana) {
            val k = before.lastIndexOf(name)
            if (k >= 0 && (best == null || k > best.first)) best = k to b
        }
        return when {
            best != null && best.first > lastBookPos -> best.second
            lastBook != null -> lastBook
            else -> best?.second
        }
    }

    /** リンクにする参照（実在するものだけ） */
    fun find(text: String): List<Ref> = findAll(text).filter { it.valid }

    /** 実在しない参照（「10:90」など。整形の誤りを見つけるのに使う） */
    fun findInvalid(text: String): List<Ref> = findAll(text).filter { !it.valid }

    /** 「コリント第一9:10」の形（書名がなかったものは「9:10」） */
    fun canonical(r: Ref): String {
        val tail = when {
            r.endVerse == null -> ""
            r.list -> ",${r.endVerse}"
            else -> "-${r.endVerse}"
        }
        val head = if (r.bookless) "" else books[r.book - 1]
        return "$head${r.chapter}:${r.verse}$tail"
    }

    /** 文の中の聖句を「コリント第一9:10」の形に書き直す（実在するものだけ） */
    fun normalizeRefs(text: String): String {
        val refs = find(text)
        if (refs.isEmpty()) return text
        val sb = StringBuilder(text)
        for (r in refs.sortedByDescending { it.start }) sb.replace(r.start, r.end, canonical(r))
        return sb.toString()
    }

    /** JW Library の聖句の番号（書・章・節を 2・3・3 桁で。例 ヨハネ 3:16 → 43003016）。範囲は「-」でつなぐ */
    private fun bibleParam(r: Ref): String {
        fun code(v: Int) = "%02d%03d%03d".format(r.book, r.chapter, v)
        return if (r.endVerse != null) "${code(r.verse)}-${code(r.endVerse)}" else code(r.verse)
    }

    /** JW Library アプリで開く（日本語・新世界訳） */
    fun jwLibraryUri(r: Ref) = "jwlibrary:///finder?wtlocale=J&pub=nwtsty&bible=${bibleParam(r)}"

    /** JW Library がないとき・書き出しの文章用（jw.org の「探す」リンク。JW Library があればそちらで開く端末もある） */
    fun webUrl(r: Ref) = "https://www.jw.org/finder?wtlocale=J&prefer=lang&pub=nwtsty&bible=${bibleParam(r)}"

    /** 章のページ（前からの形。残しておく） */
    fun url(book: Int, chapter: Int) = "https://wol.jw.org/ja/wol/b/r7/lp-j/nwtsty/$book/$chapter"

    /** 聖句を開く：JW Library → だめなら jw.org */
    fun open(context: android.content.Context, r: Ref) {
        val app = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(jwLibraryUri(r)))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(app)
        } catch (e: android.content.ActivityNotFoundException) {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(webUrl(r)))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun annotate(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
        var pos = 0
        val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        for (r in find(text)) {
            if (r.start < pos) continue
            append(text.substring(pos, r.start))
            pushLink(LinkAnnotation.Url(webUrl(r), style))
            append(text.substring(r.start, r.end))
            pop()
            pos = r.end
        }
        append(text.substring(pos))
    }
}

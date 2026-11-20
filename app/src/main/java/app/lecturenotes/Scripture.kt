package app.lecturenotes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/**
 * 聖句の参照（例「ヨハネ 3:16」「マタイ24章14節」）を見つけて、wol.jw.org の該当の章へのリンクにする。
 * 本文は取りに行かない（jw.org の利用規約で、サイトから自動でデータを取り出すことは禁じられているため）。
 */
object Scripture {
    /** 新世界訳の書名（並び＝書の番号） */
    val books = listOf(
        "創世記", "出エジプト記", "レビ記", "民数記", "申命記", "ヨシュア記", "裁き人", "ルツ記",
        "サムエル記第一", "サムエル記第二", "列王記第一", "列王記第二", "歴代誌第一", "歴代誌第二",
        "エズラ記", "ネヘミヤ記", "エステル記", "ヨブ記", "詩編", "格言", "伝道の書", "ソロモンの歌",
        "イザヤ書", "エレミヤ書", "哀歌", "エゼキエル書", "ダニエル書", "ホセア書", "ヨエル書", "アモス書",
        "オバデヤ書", "ヨナ書", "ミカ書", "ナホム書", "ハバクク書", "ゼパニヤ書", "ハガイ書", "ゼカリヤ書", "マラキ書",
        "マタイ", "マルコ", "ルカ", "ヨハネ", "使徒", "ローマ", "コリント第一", "コリント第二", "ガラテア",
        "エフェソス", "フィリピ", "コロサイ", "テサロニケ第一", "テサロニケ第二", "テモテ第一", "テモテ第二",
        "テトス", "フィレモン", "ヘブライ", "ヤコブ", "ペテロ第一", "ペテロ第二", "ヨハネ第一", "ヨハネ第二",
        "ヨハネ第三", "ユダ", "啓示",
    )

    /** 話し言葉・ほかの訳の書名 → 書の番号 */
    private val aliases: Map<String, Int> = buildMap {
        books.forEachIndexed { i, b -> put(b, i + 1) }
        // 「記」「書」を付けない言い方
        books.forEachIndexed { i, b ->
            if (b.endsWith("記") || b.endsWith("書")) put(b.dropLast(1), i + 1)
        }
        put("裁き人の書", 7); put("士師記", 7)
        put("詩篇", 19); put("格言の書", 20); put("箴言", 20); put("伝道者の書", 21); put("雅歌", 22)
        put("マタイによる福音書", 40); put("マルコによる福音書", 41); put("ルカによる福音書", 42); put("ヨハネによる福音書", 43)
        put("使徒行伝", 44); put("使徒の活動", 44)
        put("エペソ", 49); put("ピリピ", 50); put("ヘブル", 58)
        put("黙示録", 66); put("ヨハネの黙示録", 66)
        // 「第一コリント」のような言い方
        listOf("サムエル記" to 9, "列王記" to 11, "歴代誌" to 13, "コリント" to 46, "テサロニケ" to 52, "テモテ" to 54, "ペテロ" to 60)
            .forEach { (n, first) ->
                put("第一$n", first); put("第二$n", first + 1)
                put("${n}一", first); put("${n}二", first + 1)
            }
        put("第一ヨハネ", 62); put("第二ヨハネ", 63); put("第三ヨハネ", 64)
    }

    private const val SEP = "(?:章\\s*(?:の)?\\s*|[:：]\\s*)"
    private const val END = "(?:\\s*(?:[-‐－–〜~～]|から)\\s*(\\d{1,3})(?:\\s*節)?|\\s*[,，、]\\s*(\\d{1,3})(?:\\s*節)?)?"

    private val namesAlt: String by lazy { aliases.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } }

    /** 「ヨハネ 3:16」「マタイ24章14節」「テモテ第二 3:1-5」「ヨハネ3章16節から18節」「ヨハネ10章の9節」「10:9,10」 */
    private val pattern: Regex by lazy {
        Regex("($namesAlt)\\s*(?:の)?\\s*(\\d{1,3})\\s*$SEP(\\d{1,3})(?:\\s*節)?$END")
    }

    /** 書名のない「10章の5節」（同じ文の中で前に出てきた書名を使う）。時刻と区別するため「章」「節」が必要 */
    private val bookless: Regex by lazy { Regex("(\\d{1,3})\\s*章\\s*(?:の)?\\s*(\\d{1,3})\\s*節$END") }
    private val bookName: Regex by lazy { Regex(namesAlt) }

    /** endVerse は「1-5」「9,10」の後ろの数（なければ null）。valid = 実在する章・節か */
    data class Ref(val start: Int, val end: Int, val book: Int, val chapter: Int, val verse: Int, val endVerse: Int? = null, val valid: Boolean = true)

    // ---- 章ごとの節の数（assets/verse_counts.txt。実在しない「10:90」などを見分ける）
    @Volatile private var counts: List<IntArray>? = null

    fun init(context: android.content.Context) {
        counts = runCatching {
            context.assets.open("verse_counts.txt").bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line -> line.split(',').map { it.trim().toInt() }.toIntArray() }
        }.getOrNull()
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
                val beforeUnit = k2 < s.length && (s[k2] == '章' || s[k2] == '節')
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

    /** 実在するものも、しないものも含めて全部 */
    fun findAll(text: String): List<Ref> {
        if (text.isEmpty()) return emptyList()
        val n = normalize(text)
        val t = n.text
        fun orig(a: Int, b: Int) = n.start[a] to n.end[b - 1]
        val out = ArrayList<Ref>()
        for (m in pattern.findAll(t)) {
            val book = aliases[m.groupValues[1]] ?: continue
            val ch = m.groupValues[2].toInt()
            val v = m.groupValues[3].toInt()
            val ev = (m.groupValues[4].toIntOrNull() ?: m.groupValues[5].toIntOrNull())?.takeIf { it > v }
            val (a, b) = orig(m.range.first, m.range.last + 1)
            out.add(Ref(a, b, book, ch, v, ev, exists(book, ch, v) && (ev == null || exists(book, ch, ev))))
        }
        // 書名のない「10章の5節」：前に出てきた書名を使う
        val books = bookName.findAll(t).map { it.range.last + 1 to aliases[it.value] }.toList()
        for (m in bookless.findAll(t)) {
            val (a, b) = orig(m.range.first, m.range.last + 1)
            if (out.any { a < it.end && b > it.start }) continue
            val book = books.lastOrNull { it.first <= m.range.first }?.second ?: continue
            val ch = m.groupValues[1].toInt()
            val v = m.groupValues[2].toInt()
            val ev = (m.groupValues[3].toIntOrNull() ?: m.groupValues[4].toIntOrNull())?.takeIf { it > v }
            out.add(Ref(a, b, book, ch, v, ev, exists(book, ch, v) && (ev == null || exists(book, ch, ev))))
        }
        return out.sortedBy { it.start }
    }

    /** リンクにする参照（実在するものだけ） */
    fun find(text: String): List<Ref> = findAll(text).filter { it.valid }

    /** 実在しない参照（「10:90」など。整形の誤りを見つけるのに使う） */
    fun findInvalid(text: String): List<Ref> = findAll(text).filter { !it.valid }

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

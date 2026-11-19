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

    /** 「ヨハネ 3:16」「マタイ24章14節」「テモテ第二 3:1-5」「ヨハネ3章16節から18節」 */
    private val pattern: Regex by lazy {
        val names = aliases.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        Regex(
            "($names)\\s*(?:の)?\\s*(\\d{1,3})\\s*(?:章\\s*|[:：]\\s*)(\\d{1,3})(?:\\s*節)?" +
                "(?:\\s*(?:[-‐－–〜~～]|から)\\s*(\\d{1,3})(?:\\s*節)?)?",
        )
    }

    /** endVerse は「1-5」の 5（なければ null） */
    data class Ref(val start: Int, val end: Int, val book: Int, val chapter: Int, val verse: Int, val endVerse: Int? = null)

    /** 全角数字を半角に（長さは変えない） */
    private fun normalizeDigits(s: String) = buildString(s.length) {
        s.forEach { c -> append(if (c in '０'..'９') ('0' + (c - '０')) else c) }
    }

    fun find(text: String): List<Ref> {
        val t = normalizeDigits(text)
        return pattern.findAll(t).mapNotNull { m ->
            val book = aliases[m.groupValues[1]] ?: return@mapNotNull null
            val v = m.groupValues[3].toInt()
            val ev = m.groupValues[4].toIntOrNull()?.takeIf { it > v }
            Ref(m.range.first, m.range.last + 1, book, m.groupValues[2].toInt(), v, ev)
        }.toList()
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

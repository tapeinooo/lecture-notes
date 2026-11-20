package app.lecturenotes

/** 話の要約（完了した話を、仕上げが済んだあとに頭の良い AI でまとめる） */
object Summary {
    enum class Style(val key: String, val label: String, val help: String, val format: String) {
        POINTS(
            "points", "A 要点", "主題と、要点の箇条書き",
            """
【主題】話の主題を1行で
【要点】
・要点を3〜7個。1つ1文で、話の順に
""".trim(),
        ),
        TALK(
            "talk", "B 講演ノート", "主題・主な聖句・要点・実践できること",
            """
【主題】話の主題を1行で
【主な聖句】
・書名章:節 ― その聖句から話されたことを1文で（話に出た聖句だけ。なければ「なし」）
【要点】
・要点を3〜6個。1つ1文で
【実践できること】
・話の中で勧められたことを1〜3個
""".trim(),
        ),
        FLOW(
            "flow", "C 話の流れ", "時刻付きの見出しで、話の流れをたどる",
            """
【主題】話の主題を1行で
【流れ】
・◯分　見出し ― 内容を1〜2文で（入力の［◯分］を使い、話の区切りごとに4〜8個）
""".trim(),
        ),
        SHORT(
            "short", "D ひと言で", "ひと言・覚えておきたいこと・自分への当てはめ",
            """
【ひと言で】話全体を1文で
【覚えておきたいこと】
・3個まで
【自分に当てはめる】1〜2文で
""".trim(),
        ),
        QA(
            "qa", "E 問いと答え", "話の中の問いかけごとに、答えの要点",
            """
【主題】話の主題を1行で
話の中で問いかけられたことごとに：
【問い】問いかけ
・答えの要点を1〜3個
（問いかけが多いときは主なもの6個まで）
""".trim(),
        );

        companion object {
            fun of(key: String) = entries.firstOrNull { it.key == key } ?: POINTS
        }
    }

    fun system(style: Style) = """
あなたは日本語の講演の要約を作る編集者です。講演はエホバの証人の集会・大会のものです。
規則：
1. 話された内容だけをもとに要約してください。話されていないことを足さないでください。
2. 用語・書名・言い回しは wol.jw.org（新世界訳聖書 2019年改訂版、ものみの塔出版物）の表記に合わせてください。
3. 聖句は「コリント第一9:10」の形で書いてください。
4. 次の形で、余計な前置きや説明を付けずに出力してください：
${style.format}
""".trim()

    /** 要約に渡す本文（段落ごとに［◯分］を付ける） */
    fun input(s: Session): String = buildString {
        if (s.title.isNotBlank()) append("題：${s.title}\n")
        if (s.speaker.isNotBlank()) append("話し手：${s.speaker}\n")
        append("\n本文：\n")
        for (p in Transcript.paragraphs(s)) {
            val t = p.text.trim()
            if (t.isEmpty()) continue
            append("［${p.startMs / 60000}分］").append(t).append("\n")
        }
    }.take(60_000)
}

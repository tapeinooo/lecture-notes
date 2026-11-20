package app.lecturenotes

/** 話の要約（完了した話を、仕上げが済んだあとに頭の良い AI でまとめる） */
object Summary {
    enum class Style(val key: String, val label: String, val help: String, val format: String) {
        POINTS(
            "points", "A 要点", "主題と、要点の箇条書き",
            """
【主題】1行
【要点】
・3〜5個、話の順に
""".trim(),
        ),
        TALK(
            "talk", "B 講演ノート", "主題・要点・実践できること（聖句は括弧で添える）",
            """
【主題】1行
【要点】
・3〜5個
【実践できること】
・1〜3個
""".trim(),
        ),
        FLOW(
            "flow", "C 話の流れ", "時刻付きの見出しで、話の流れをたどる",
            """
【主題】1行
【流れ】
・◯分　見出し：内容（入力の［◯分］を使い、話の区切りごとに3〜5個）
""".trim(),
        ),
        SHORT(
            "short", "D ひと言で", "ひと言・覚えておきたいこと・自分への当てはめ",
            """
【ひと言で】1文
【覚えておきたいこと】
・3個まで
【自分に当てはめる】1文
""".trim(),
        ),
        QA(
            "qa", "E 問いと答え", "話の中の問いかけごとに、答えの要点",
            """
【主題】1行
話の中の主な問いかけごとに（5個まで）：
【問い】問いかけ
・答えの要点を1〜2個
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
2. できるだけ短く。1項目は30字くらいまで、体言止めでかまいません。前置き・まとめ・説明の文は書かないでください。
3. 1つの見出しの下の項目は、多くても5個までにしてください。
4. 聖句は、関係する項目の終わりに（コリント第一9:10）のように括弧で添えるだけにしてください。「〜にあるように」「〜で述べられているとおり」などの言い回しで聖句を説明しないでください。
5. 用語・書名・言い回しは wol.jw.org（新世界訳聖書 2019年改訂版、ものみの塔出版物）の表記に合わせてください。
6. 次の形で出力してください（項目の行は「・」で始める）：
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

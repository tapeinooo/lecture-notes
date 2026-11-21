package app.lecturenotes

/**
 * 会話モード（ラベル「会話」の記録）。
 *  - 発言ごとに1行、行の頭に話し手「Ａ：」（名前を付けたら「田中：」）
 *  - 話し言葉のまま残し、相づちも残す
 */
object Talk {
    const val LABEL = "会話"

    /** 行の頭の話し手（「Ａ：」「田中：」）。12 文字まで */
    val SPEAKER = Regex("(?m)^[ 　]*([^\\s：:、。「」]{1,12})[：:]")

    /** 話し手の色（名前ごとに決まる） */
    fun colorIndex(name: String): Int {
        val letters = "ＡＢＣＤＥＦＧＨ"
        val k = letters.indexOf(name)
        return if (k >= 0) k % 4 else (name.hashCode() and 0x7fffffff) % 4
    }

    /** 文の中の話し手の名前を、付けた名前に置き換える（「Ａ：」→「田中：」） */
    fun applyNames(text: String, names: Map<String, String>): String {
        if (names.isEmpty() || text.isEmpty()) return text
        return SPEAKER.replace(text) { m ->
            val n = names[m.groupValues[1]] ?: return@replace m.value
            m.value.replaceRange(m.groups[1]!!.range.first - m.range.first, m.groups[1]!!.range.last + 1 - m.range.first, n)
        }
    }

    /** 話し手の名前を変える（その記録のすべての文で）。これから出てくる「Ａ」も、この名前にする */
    fun rename(id: String, from: String, to: String) {
        val name = to.trim()
        if (name.isEmpty() || name == from) return
        val one = mapOf(from to name)
        Repo.update(id) { s ->
            // もとの印（Ａ など）→ 名前。名前をもう一度変えたときは、もとの印の行き先を変える
            val map = s.speakerMap.toMutableMap()
            var hit = false
            for ((k, v) in s.speakerMap) if (v == from) { map[k] = name; hit = true }
            if (!hit) map[from] = name
            s.copy(
                speakerMap = map,
                segments = s.segments.map { g ->
                    g.copy(
                        raw = g.raw?.let { applyNames(it, one) },
                        formatted = g.formatted?.let { applyNames(it, one) },
                        edited = g.edited?.let { applyNames(it, one) },
                    )
                },
            )
        }
        Meta.addSpeaker(name)
    }

    /** 直前の内容（話し手と改行を残す） */
    fun previous(s: Session, before: Int, max: Int): String {
        val i = s.segments.indexOfFirst { it.index == before }.let { if (it < 0) s.segments.size else it }
        val sb = StringBuilder()
        for (k in i - 1 downTo 0) {
            val g = s.segments[k]
            if (g.silent || g.rawEngine == "local") continue
            val t = (displayText(g) ?: "").trim()
            if (t.isEmpty()) continue
            sb.insert(0, t + "\n")
            if (sb.length >= max) break
        }
        return sb.toString().takeLast(max).trim()
    }
}

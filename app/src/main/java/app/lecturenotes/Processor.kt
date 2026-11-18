package app.lecturenotes

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 裏で動き続け、文字起こし待ちの区間をクラウドに送り、まとまったら AI で整形する。
 * 制限にかかったサービスはしばらく休ませ、次の候補に切り替える。
 */
object Processor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeCh = Channel<Unit>(Channel.CONFLATED)
    private lateinit var app: Context
    private val settings: Settings get() = Settings(app)

    /** 画面に出す、いまの状態 */
    val status = MutableStateFlow("")

    /** 失敗した回数（区間ごと） */
    private val failures = HashMap<String, Int>()

    fun start(context: Context) {
        app = context.applicationContext
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = wake()
            },
        )
        scope.launch { loop() }
    }

    fun wake() {
        wakeCh.trySend(Unit)
    }

    private suspend fun loop() {
        while (true) {
            val worked = try {
                step()
            } catch (e: Exception) {
                status.value = "エラー: ${e.message?.take(120)}"
                false
            }
            if (!worked) withTimeoutOrNull(20_000) { wakeCh.receive() }
        }
    }

    // ---------- どの方法が使えるか ----------

    private fun groqUsable() = settings.groqKey.isNotBlank() && Cooldown.ok(Groq.sttKey(settings.groqSttModel))
    private fun geminiUsableModels() =
        if (settings.geminiKey.isBlank()) emptyList() else settings.geminiModelList().filter { Cooldown.ok(Gemini.cooldownKey(it)) }

    /** いまクラウドで文字起こしできるか（録音側が端末内の文字起こしを使うかの判断にも使う） */
    fun cloudSttAvailable(): Boolean {
        if (!Net.online(app)) return false
        return when (settings.mode) {
            Mode.AUTO -> groqUsable() || geminiUsableModels().isNotEmpty()
            Mode.GROQ -> groqUsable()
            Mode.GEMINI -> geminiUsableModels().isNotEmpty()
            Mode.LOCAL -> false
        }
    }

    /** 端末内の文字起こしを使うべきか */
    fun shouldUseLocal(): Boolean = when (settings.mode) {
        Mode.LOCAL -> true
        Mode.AUTO -> !cloudSttAvailable()
        else -> false
    }

    private fun needsStt(seg: Segment): Boolean {
        if (seg.silent || seg.rawEngine == "error") return false
        if (seg.raw == null) return settings.mode != Mode.LOCAL
        // 自動のときは、端末内で起こした分をクラウドで起こし直す
        return seg.rawEngine == "local" && settings.mode == Mode.AUTO
    }

    fun pendingCounts(s: Session): Pair<Int, Int> {
        val stt = s.segments.count { needsStt(it) }
        val fmt = if (!settings.formatEnabled) 0 else s.segments.count { !it.silent && it.formatted == null && !it.raw.isNullOrBlank() }
        return stt to fmt
    }

    // ---------- 1回分の仕事 ----------

    private fun step(): Boolean {
        val sessions = Repo.sessions.value.sortedByDescending { it.recording }
        val online = Net.online(app)

        if (online) {
            for (s in sessions) {
                val seg = s.segments.firstOrNull { needsStt(it) } ?: continue
                when (transcribe(s, seg)) {
                    Outcome.DONE -> return true
                    Outcome.UNAVAILABLE -> break
                }
            }
        }

        if (online && settings.formatEnabled) {
            for (s in sessions) {
                val batch = pickBatch(s) ?: continue
                when (format(s, batch)) {
                    Outcome.DONE -> return true
                    Outcome.UNAVAILABLE -> break
                }
            }
        }

        status.value = idleStatus(online)
        return false
    }

    private fun idleStatus(online: Boolean): String {
        if (!online) return "オフライン（ネットが戻ったら送ります）"
        val waits = Cooldown.reasons.keys.filter { !Cooldown.ok(it) }
            .map { "$it は${(Cooldown.remainingMs(it) / 60000) + 1}分休み" }
        return if (waits.isEmpty()) "待機中" else waits.joinToString("・")
    }

    private enum class Outcome { DONE, UNAVAILABLE }

    private fun failKey(s: Session, seg: Segment) = "${s.id}/${seg.index}"

    private fun onSegmentError(s: Session, seg: Segment, e: Exception, provider: String, cooldownKey: String) {
        val msg = e.message?.take(200) ?: e.javaClass.simpleName
        if (e is HttpError && e.code in listOf(400, 401, 403, 404)) {
            // 鍵やモデル名の誤り。しばらくそのサービスを使わない
            Cooldown.set(cooldownKey, 10 * 60_000L, "$provider: $msg")
        }
        val k = failKey(s, seg)
        val n = (failures[k] ?: 0) + 1
        failures[k] = n
        if (n >= 3 && seg.raw == null) {
            Repo.updateSegment(s.id, seg.index) { it.copy(raw = "", rawEngine = "error", error = msg) }
        }
        status.value = "$provider: $msg"
    }

    private fun transcribe(s: Session, seg: Segment): Outcome {
        val file = Repo.audioFile(s.id, seg)
        if (!file.exists()) {
            Repo.updateSegment(s.id, seg.index) { it.copy(raw = "", rawEngine = "error", error = "音声ファイルがありません") }
            return Outcome.DONE
        }
        val glossary = Glossary.load(app)
        val useGroq = settings.mode == Mode.AUTO || settings.mode == Mode.GROQ
        val useGemini = settings.mode == Mode.AUTO || settings.mode == Mode.GEMINI

        if (useGroq && groqUsable()) {
            val model = settings.groqSttModel
            status.value = "Groq で文字起こし中（${fmtClock(seg.startMs)}）"
            try {
                val text = cleanStt(Groq.transcribe(settings.groqKey, model, file, Glossary.whisperPrompt(glossary)))
                save(s, seg, text, "groq")
                return Outcome.DONE
            } catch (e: RateLimited) {
                Cooldown.set(Groq.sttKey(model), e.waitMs, "Groq: ${e.message}")
            } catch (e: Exception) {
                onSegmentError(s, seg, e, "Groq", Groq.sttKey(model))
                return Outcome.DONE
            }
        }
        if (useGemini) {
            for (model in geminiUsableModels()) {
                status.value = "Gemini（$model）で文字起こし中（${fmtClock(seg.startMs)}）"
                try {
                    val text = cleanStt(Gemini.transcribe(settings.geminiKey, model, file, Prompts.stt(glossary)))
                    save(s, seg, text, "gemini")
                    return Outcome.DONE
                } catch (e: RateLimited) {
                    Cooldown.set(Gemini.cooldownKey(model), e.waitMs, "Gemini $model: ${e.message}")
                } catch (e: Exception) {
                    onSegmentError(s, seg, e, "Gemini", Gemini.cooldownKey(model))
                    return Outcome.DONE
                }
            }
        }
        return Outcome.UNAVAILABLE
    }

    private fun save(s: Session, seg: Segment, text: String, engine: String) {
        Repo.updateSegment(s.id, seg.index) {
            // 起こし直したら整形もやり直す
            it.copy(raw = text, rawEngine = engine, formatted = null, error = null)
        }
    }

    /** Whisper が無音などで出しがちな決まり文句を消す */
    private val hallucinations = listOf(
        "ご視聴ありがとうございました", "ご清聴ありがとうございました", "チャンネル登録", "お疲れ様でした",
        "字幕", "最後までご視聴", "おやすみなさい",
    )

    private fun cleanStt(text: String): String {
        val t = text.trim()
        val stripped = t.trim('。', '、', ' ', '　', '!', '！')
        if (stripped.length <= 25 && hallucinations.any { stripped.contains(it) }) return ""
        return t
    }

    // ---------- 整形 ----------

    /** 整形する区間のまとまり。まだそろっていなければ null */
    private fun pickBatch(s: Session): List<Segment>? {
        val segs = s.segments
        val first = segs.indexOfFirst { !it.silent && it.formatted == null && !it.raw.isNullOrBlank() && !needsStt(it) }
        if (first < 0) return null
        val batch = ArrayList<Segment>()
        for (i in first until segs.size) {
            val g = segs[i]
            if (g.silent || g.rawEngine == "error") continue
            if (needsStt(g) || g.raw == null) break
            if (g.formatted != null) break
            if (g.raw.isBlank()) continue
            batch.add(g)
            if (batch.size >= settings.formatBatch.coerceIn(1, 20)) return batch
        }
        if (batch.isEmpty()) return null
        // 録音中は、決まった数たまるまで待つ。録音が終わっていれば残りも整形する
        // （文字起こし待ちの区間で止まったときも、録音が終わっていれば先にできた分を整形する）
        if (s.recording) return null
        return batch
    }

    private fun format(s: Session, batch: List<Segment>): Outcome {
        val glossary = Glossary.load(app)
        val firstIdx = s.segments.indexOfFirst { it.index == batch.first().index }
        val context = s.segments.take(firstIdx.coerceAtLeast(0))
            .mapNotNull { it.formatted ?: it.raw }.joinToString("").takeLast(400)
        val system = Prompts.formatSystem(glossary)
        val user = Prompts.formatUser(context, batch.map { it.raw ?: "" })

        val tryGemini = settings.geminiKey.isNotBlank()
        if (tryGemini) {
            for (model in geminiUsableModels()) {
                status.value = "Gemini（$model）で整形中（${fmtClock(batch.first().startMs)}〜）"
                try {
                    val out = Gemini.text(settings.geminiKey, model, system, user)
                    applyFormatted(s, batch, out)
                    return Outcome.DONE
                } catch (e: RateLimited) {
                    Cooldown.set(Gemini.cooldownKey(model), e.waitMs, "Gemini $model: ${e.message}")
                } catch (e: Exception) {
                    if (handleFormatError(s, batch, e, "Gemini", Gemini.cooldownKey(model))) return Outcome.DONE
                }
            }
        }
        if (settings.groqKey.isNotBlank()) {
            val model = settings.groqChatModel
            val key = Groq.chatKey(model)
            if (Cooldown.ok(key)) {
                status.value = "Groq（$model）で整形中（${fmtClock(batch.first().startMs)}〜）"
                try {
                    val out = Groq.chat(settings.groqKey, model, system, user)
                    applyFormatted(s, batch, out)
                    return Outcome.DONE
                } catch (e: RateLimited) {
                    Cooldown.set(key, e.waitMs, "Groq $model: ${e.message}")
                } catch (e: Exception) {
                    if (handleFormatError(s, batch, e, "Groq", key)) return Outcome.DONE
                }
            }
        }
        return Outcome.UNAVAILABLE
    }

    /** true = この区間はあきらめた（次へ進む） */
    private fun handleFormatError(s: Session, batch: List<Segment>, e: Exception, provider: String, key: String): Boolean {
        val msg = e.message?.take(200) ?: e.javaClass.simpleName
        status.value = "$provider 整形: $msg"
        if (e is HttpError && e.code in listOf(400, 401, 403, 404)) {
            Cooldown.set(key, 10 * 60_000L, "$provider: $msg")
            return false
        }
        val k = "fmt:${s.id}/${batch.first().index}"
        val n = (failures[k] ?: 0) + 1
        failures[k] = n
        if (n >= 3) {
            // 整形できなかったら、文字起こしのまま残す
            batch.forEach { g -> Repo.updateSegment(s.id, g.index) { it.copy(formatted = it.raw) } }
            return true
        }
        return false
    }

    private fun applyFormatted(s: Session, batch: List<Segment>, output: String) {
        val list = parseSegments(output) ?: throw IllegalStateException("整形の結果を読めません: ${output.take(120)}")
        val texts = if (list.size == batch.size) list else {
            // 数が合わないときは、まとめて最初の区間に入れる
            listOf(list.joinToString("")) + List(batch.size - 1) { "" }
        }
        batch.forEachIndexed { i, g ->
            Repo.updateSegment(s.id, g.index) { it.copy(formatted = texts[i].trim()) }
        }
    }

    private fun parseSegments(text: String): List<String>? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val arr: JSONArray = JSONObject(text.substring(start, end + 1)).getJSONArray("segments")
            (0 until arr.length()).map { arr.optString(it) }
        }.getOrNull()
    }
}

object Prompts {
    fun stt(glossary: String) = """
これは日本語の講演（エホバの証人の集会・大会の講演）の録音の一部です。
話された内容を、そのまま正確に書き起こしてください。
・句読点を付けてください。
・話していない内容を足さないでください。要約しないでください。
・聞き取れない部分は「…」としてください。
・無音や音楽だけのときは、何も出力しないでください。
・用語・書名・聖句の言い回しは、新世界訳聖書（2019年改訂版）と jw.org の出版物の表記に合わせてください。
・次の用語集の表記に合わせてください（「誤→正」は直し方の例）：
${Glossary.forPrompt(glossary)}

出力は書き起こした本文だけにしてください。
""".trim()

    fun formatSystem(glossary: String) = """
あなたは日本語の講演の書き起こしを整える編集者です。講演はエホバの証人の集会・大会のものです。

規則：
1. 入力は音声認識の結果を区間ごとに分けた配列です。必ず同じ数・同じ順の配列で返してください。
2. 話した内容は変えないでください。要約・言い換え・付け足しはしないでください。
3. 消してよいのは「えー」「あのー」などのつなぎ言葉と、言い直しだけです。
4. 句読点を整え、読みやすくしてください。
5. 音声認識の誤りと思われる語は、文脈と用語集から正しい語に直してください。
6. 用語・人名・書名・言い回しは、wol.jw.org（新世界訳聖書 2019年改訂版、ものみの塔出版物）の表記に合わせてください。
7. 聖句の参照は「ヨハネ 3:16」「テモテ第二 3:1-5」の形にそろえてください（書名は新世界訳の書名）。
8. 聖句を朗読している部分は、新世界訳の本文の言い回しに合わせて直してかまいません。ただし確信がなければ、聞こえたとおりにしてください。
9. 区間の境目で文が切れていても、そのままにしてください（前後の区間に文を動かさない）。
10. 出力は JSON だけ：{"segments":["…","…"]}

用語集（「誤→正」は直し方の例）：
${Glossary.forPrompt(glossary)}
""".trim()

    fun formatUser(context: String, raws: List<String>): String {
        val input = JSONObject().put("segments", JSONArray(raws)).toString()
        return buildString {
            if (context.isNotBlank()) {
                append("直前の内容（参考。出力には含めない）：\n")
                append(context)
                append("\n\n")
            }
            append("入力：\n")
            append(input)
        }
    }
}

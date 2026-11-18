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
    private val polishWakeCh = Channel<Unit>(Channel.CONFLATED)
    private lateinit var app: Context
    private val settings: Settings get() = Settings(app)

    /** 画面に出す、いまの状態 */
    val status = MutableStateFlow("")

    /** 失敗した回数（区間ごと） */
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 仕上げの状態（画面に出す） */
    val polishStatus = MutableStateFlow("")

    fun start(context: Context) {
        app = context.applicationContext
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = wake()
            },
        )
        scope.launch { loop() }
        scope.launch { polishLoop() }
    }

    fun wake() {
        wakeCh.trySend(Unit)
        polishWakeCh.trySend(Unit)
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

    private fun usable(role: Role): List<Cand> =
        Catalog.candidates(app, settings, role).filter { Cooldown.ok(it.key) }

    /** いまクラウドで文字起こしできるか（録音側が端末内の文字起こしを使うかの判断にも使う） */
    fun cloudSttAvailable(): Boolean = Net.online(app) && usable(Role.STT).isNotEmpty()

    /** 端末内の文字起こしを使うべきか */
    fun shouldUseLocal(): Boolean = when (settings.mode) {
        Mode.LOCAL -> true
        Mode.AUTO -> !cloudSttAvailable()
        else -> false
    }

    private fun needsStt(seg: Segment): Boolean {
        if (seg.silent || seg.rawEngine == "error") return false
        if (seg.raw == null) return settings.mode != Mode.LOCAL
        // 端末内で起こした分（その場の表示用）は、クラウドで起こし直す
        return seg.rawEngine == "local" && settings.mode != Mode.LOCAL
    }

    data class Pending(val stt: Int, val fast: Int, val polish: Int)

    fun pendingCounts(s: Session): Pending {
        val st = settings
        val stt = s.segments.count { needsStt(it) }
        val fmt = if (!st.formatEnabled) 0 else s.segments.count { !it.silent && it.formatted == null && !it.raw.isNullOrBlank() }
        val pol = if (!st.formatEnabled || !st.polishEnabled) 0 else s.segments.count { !it.silent && !it.polished && !it.raw.isNullOrBlank() }
        return Pending(stt, fmt, pol)
    }

    // ---------- 1回分の仕事 ----------

    private fun step(): Boolean {
        val sessions = Repo.sessions.value.sortedByDescending { it.recording }
        val online = Net.online(app)

        if (online && Catalog.needsRefresh(app, settings)) {
            status.value = "使えるモデルを確かめています…"
            Catalog.refresh(app, settings)
        }

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

        if (compressOne(sessions)) return true

        status.value = idleStatus(online)
        return false
    }

    /** 古い WAV の区間を m4a に変えて小さくする（文字起こしが済んだもの） */
    private fun compressOne(sessions: List<Session>): Boolean {
        for (s in sessions) {
            if (s.recording) continue
            val seg = s.segments.firstOrNull { it.audio.endsWith(".wav") && !needsStt(it) } ?: continue
            val wav = Repo.audioFile(s.id, seg)
            val m4a = java.io.File(wav.parentFile, wav.name.removeSuffix(".wav") + ".m4a")
            status.value = "音声を小さくしています（${s.title}）"
            try {
                if (wav.exists()) Audio.wavToM4a(wav, m4a)
                if (m4a.exists() && m4a.length() > 0) {
                    Repo.updateSegment(s.id, seg.index) { it.copy(audio = m4a.name) }
                    wav.delete()
                } else {
                    return false
                }
            } catch (e: Exception) {
                m4a.delete()
                status.value = "音声を小さくできません: ${e.message?.take(80)}"
                return false
            }
            return true
        }
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

    private fun onSegmentError(s: Session, seg: Segment, e: Exception, c: Cand) {
        val msg = e.message?.take(200) ?: e.javaClass.simpleName
        val k = failKey(s, seg)
        val n = (failures[k] ?: 0) + 1
        failures[k] = n
        if (n >= 3 && seg.raw == null) {
            Repo.updateSegment(s.id, seg.index) { it.copy(raw = "", rawEngine = "error", error = msg) }
        }
        status.value = "${c.model}: $msg"
    }

    private fun transcribe(s: Session, seg: Segment): Outcome {
        val file = Repo.audioFile(s.id, seg)
        if (!file.exists()) {
            Repo.updateSegment(s.id, seg.index) { it.copy(raw = "", rawEngine = "error", error = "音声ファイルがありません") }
            return Outcome.DONE
        }
        val glossary = Glossary.load(app)
        for (c in usable(Role.STT)) {
            status.value = "${c.model} で文字起こし中（${fmtClock(seg.startMs)}）"
            try {
                val text = when (c.provider) {
                    "groq" -> Groq.transcribe(settings.groqKey, c.model, file, Glossary.whisperPrompt(glossary))
                    else -> Gemini.transcribe(settings.geminiKey, c.model, file, Prompts.stt(glossary))
                }
                Usage.add(c.key)
                save(s, seg, cleanStt(text), c)
                return Outcome.DONE
            } catch (e: RateLimited) {
                Cooldown.set(c.key, e.waitMs, "${c.model}: ${e.message?.take(120)}")
            } catch (e: HttpError) {
                if (e.code in listOf(400, 401, 403, 404)) {
                    // 鍵の誤り・使えないモデル。そのモデルはしばらく使わず次へ
                    Cooldown.set(c.key, 30 * 60_000L, "${c.model}: ${e.message?.take(120)}")
                    continue
                }
                onSegmentError(s, seg, e, c)
                return Outcome.DONE
            } catch (e: Exception) {
                onSegmentError(s, seg, e, c)
                return Outcome.DONE
            }
        }
        return Outcome.UNAVAILABLE
    }

    private fun save(s: Session, seg: Segment, text: String, c: Cand) {
        Repo.updateSegment(s.id, seg.index) {
            // 起こし直したら整形もやり直す
            it.copy(raw = text, rawEngine = c.provider, rawModel = c.model, formatted = null, formatModel = null, error = null)
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
            // 録音中はすぐ出すため少しずつ、録音が終わっていればまとめて
            val size = if (s.recording) settings.formatBatch.coerceIn(1, 20) else maxOf(settings.formatBatch, 6)
            if (batch.size >= size) return batch
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

        for (c in usable(Role.FAST)) {
            status.value = "${c.model} で整形中（${fmtClock(batch.first().startMs)}〜）"
            try {
                val out = when (c.provider) {
                    "groq" -> Groq.chat(settings.groqKey, c.model, system, user, fast = true)
                    else -> Gemini.text(settings.geminiKey, c.model, system, user, fast = true)
                }
                Usage.add(c.key)
                applyFormatted(s, batch, out, c, polished = false)
                return Outcome.DONE
            } catch (e: RateLimited) {
                Cooldown.set(c.key, e.waitMs, "${c.model}: ${e.message?.take(120)}")
            } catch (e: Exception) {
                if (handleFormatError(s, batch, e, c)) return Outcome.DONE
            }
        }
        return Outcome.UNAVAILABLE
    }

    /** true = この区間はあきらめた（次へ進む）。false = 次のモデルを試す */
    private fun handleFormatError(s: Session, batch: List<Segment>, e: Exception, c: Cand): Boolean {
        val msg = e.message?.take(200) ?: e.javaClass.simpleName
        status.value = "${c.model} 整形: $msg"
        if (e is HttpError && e.code in listOf(400, 401, 403, 404)) {
            Cooldown.set(c.key, 30 * 60_000L, "${c.model}: $msg")
            return false
        }
        val k = "fmt:${s.id}/${batch.first().index}"
        val n = (failures[k] ?: 0) + 1
        failures[k] = n
        if (n >= 4) {
            // 整形できなかったら、文字起こしのまま残す
            batch.forEach { g -> Repo.updateSegment(s.id, g.index) { it.copy(formatted = it.raw, formatModel = "（整形できず）", polished = true) } }
            return true
        }
        // 結果の形が崩れていた（頭の弱いモデルで起こりやすい）ときは、次のモデルで試す
        return false
    }

    private fun applyFormatted(s: Session, batch: List<Segment>, output: String, c: Cand, polished: Boolean) {
        val list = parseSegments(output) ?: throw IllegalStateException("整形の結果を読めません: ${output.take(120)}")
        val texts = if (list.size == batch.size) list else {
            // 数が合わないときは、まとめて最初の区間に入れる
            listOf(list.joinToString("")) + List(batch.size - 1) { "" }
        }
        batch.forEachIndexed { i, g ->
            Repo.updateSegment(s.id, g.index) {
                // 送っている間に文字起こしがやり直されていたら使わない
                if (it.raw != g.raw) it else it.copy(formatted = texts[i].trim(), formatModel = c.model, polished = polished)
            }
        }
    }

    // ---------- 仕上げ（頭の良いモデルで、裏でゆっくり直し直す） ----------

    private suspend fun polishLoop() {
        while (true) {
            val worked = try {
                polishStep()
            } catch (e: Exception) {
                polishStatus.value = "仕上げ: ${e.message?.take(120)}"
                false
            }
            if (!worked) withTimeoutOrNull(30_000) { polishWakeCh.receive() }
        }
    }

    private fun polishBatchOf(s: Session): List<Segment>? {
        val size = settings.polishBatch.coerceIn(2, 30)
        val segs = s.segments
        val first = segs.indexOfFirst { !it.silent && !it.polished && it.formatted != null && !it.raw.isNullOrBlank() && !needsStt(it) }
        if (first < 0) return null
        val batch = ArrayList<Segment>()
        for (i in first until segs.size) {
            val g = segs[i]
            if (g.silent || g.rawEngine == "error" || g.raw.isNullOrBlank()) continue
            if (needsStt(g) || g.formatted == null || g.polished) break
            batch.add(g)
            if (batch.size >= size) return batch
        }
        if (batch.isEmpty() || s.recording) return null
        return batch
    }

    private fun polishStep(): Boolean {
        val st = settings
        if (!st.formatEnabled || !st.polishEnabled || !Net.online(app)) {
            polishStatus.value = ""
            return false
        }
        for (s in Repo.sessions.value.sortedByDescending { it.recording }) {
            val batch = polishBatchOf(s) ?: continue
            val glossary = Glossary.load(app)
            val firstIdx = s.segments.indexOfFirst { it.index == batch.first().index }
            val context = s.segments.take(firstIdx.coerceAtLeast(0))
                .mapNotNull { it.formatted ?: it.raw }.joinToString("").takeLast(800)
            val system = Prompts.formatSystem(glossary)
            val user = Prompts.formatUser(context, batch.map { it.raw ?: "" })
            val cands = Catalog.candidates(app, st, Role.POLISH).filter { Cooldown.ok(it.key) }
            if (cands.isEmpty()) {
                polishStatus.value = "仕上げ：使えるモデルが休み中"
                return false
            }
            for (c in cands) {
                polishStatus.value = "${c.model} で仕上げ中（${fmtClock(batch.first().startMs)}〜）"
                try {
                    val out = Gemini.text(st.geminiKey, c.model, system, user, fast = false)
                    Usage.add(c.key)
                    applyFormatted(s, batch, out, c, polished = true)
                    polishStatus.value = ""
                    return true
                } catch (e: RateLimited) {
                    Cooldown.set(c.key, e.waitMs, "${c.model}: ${e.message?.take(120)}")
                } catch (e: Exception) {
                    if (e is HttpError && e.code in listOf(400, 401, 403, 404)) {
                        Cooldown.set(c.key, 30 * 60_000L, "${c.model}: ${e.message?.take(120)}")
                        continue
                    }
                    val k = "pol:${s.id}/${batch.first().index}"
                    val n = (failures[k] ?: 0) + 1
                    failures[k] = n
                    if (n >= 3) {
                        // 仕上げをあきらめて、すぐの整形のまま残す
                        batch.forEach { g -> Repo.updateSegment(s.id, g.index) { it.copy(polished = true) } }
                        return true
                    }
                    polishStatus.value = "${c.model} 仕上げ: ${e.message?.take(100)}"
                }
            }
            return false
        }
        polishStatus.value = ""
        return false
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

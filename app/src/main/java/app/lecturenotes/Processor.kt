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

    /** 役割ごとに、最後に使ったモデル（画面に小さく出す） */
    val lastUsed = MutableStateFlow<Map<Role, String>>(emptyMap())

    private fun used(role: Role, c: Cand) {
        lastUsed.value = lastUsed.value + (role to c.model)
        Http.lastRemaining.get()?.let { Usage.remainingRequests[c.key] = it }
    }

    /** いまの作業を短く（画面の下の動くアイコンの横に出す）。何もしていなければ null */
    val activity = MutableStateFlow<String?>(null)
    val polishActivity = MutableStateFlow<String?>(null)

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
            if (!worked) {
                activity.value = null
                withTimeoutOrNull(20_000) { wakeCh.receive() }
            }
        }
    }

    // ---------- どの方法が使えるか ----------

    private fun usable(role: Role): List<Cand> =
        Catalog.candidates(app, settings, role).filter { Cooldown.ok(it.key) && Cooldown.ok(roleKey(it, role)) }

    /** 遅すぎて打ち切ったモデルは、その役割でだけしばらく使わない */
    private fun roleKey(c: Cand, role: Role) = "${c.key}#${role.name}"

    /** 時間を測りながら呼ぶ。打ち切りになったら、その役割で15分休ませる */
    private fun <T> timed(c: Cand, role: Role, block: (Int) -> T): T {
        val t0 = System.currentTimeMillis()
        try {
            val r = block(Latency.timeoutMs(role))
            Latency.record(app, role, c, System.currentTimeMillis() - t0)
            return r
        } catch (e: java.net.SocketTimeoutException) {
            Latency.record(app, role, c, Latency.timeoutMs(role).toLong())
            Cooldown.set(roleKey(c, role), 15 * 60_000L, "${c.model}: 遅すぎるので${role.label}では15分休み")
            throw RateLimited(0, "${c.model} が遅すぎます（${Latency.timeoutMs(role) / 1000}秒で打ち切り）")
        } catch (e: RateLimited) {
            // 混雑（503 など）も遅さとして数える
            Latency.record(app, role, c, System.currentTimeMillis() - t0 + 5_000)
            throw e
        }
    }

    /** いまクラウドで文字起こしできるか（録音側が端末内の文字起こしを使うかの判断にも使う） */
    fun cloudSttAvailable(): Boolean = Net.online(app) && usable(Role.STT).isNotEmpty()

    /** 端末内の文字起こしを使うべきか */
    fun shouldUseLocal(): Boolean = when (settings.mode) {
        Mode.LOCAL -> true
        Mode.AUTO -> !cloudSttAvailable()
        else -> false
    }

    private fun needsStt(seg: Segment): Boolean {
        if (seg.silent || seg.rawEngine == "error" || seg.edited != null) return false
        if (seg.raw == null) return settings.mode != Mode.LOCAL
        // 端末内で起こした分（その場の表示用）は、クラウドで起こし直す
        return seg.rawEngine == "local" && settings.mode != Mode.LOCAL
    }

    data class Pending(val stt: Int, val fast: Int, val polish: Int)

    fun pendingCounts(s: Session): Pending {
        val st = settings
        val stt = s.segments.count { needsStt(it) }
        val fmt = if (!st.formatEnabled) 0 else s.segments.count { !it.silent && it.edited == null && it.formatted == null && !it.raw.isNullOrBlank() }
        val pol = if (!st.formatEnabled || !st.polishEnabled) 0 else s.segments.count { !it.silent && it.edited == null && !it.polished && !it.raw.isNullOrBlank() }
        return Pending(stt, fmt, pol)
    }

    // ---------- 1回分の仕事 ----------

    /** タイトルを自動で付ける記録は、文字起こしの出だしに合わせる */
    private fun updateAutoTitles() {
        for (s in Repo.sessions.value) {
            if (!s.titleAuto) continue
            val t = autoTitle(s) ?: continue
            if (t != s.title) Repo.update(s.id) { if (it.titleAuto) it.copy(title = t) else it }
        }
    }

    private fun step(): Boolean {
        updateAutoTitles()
        val sessions = Repo.sessions.value.sortedByDescending { it.recording }
        val online = Net.online(app)

        if (online && Catalog.needsRefresh(app, settings)) {
            status.value = "使えるモデルを確かめています…"
            activity.value = "準備中"
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
        if (Backup.step(app)) return true

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
            activity.value = "音声を圧縮中"
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
        // 会話は、話し手を聞き分けられる Gemini を先に
        val sttCands = usable(Role.STT).let { l -> if (s.isTalk) l.sortedBy { if (it.provider == "gemini") 0 else 1 } else l }
        for (c in sttCands) {
            status.value = "${c.model} で文字起こし中（${fmtClock(seg.startMs)}）"
            activity.value = "清書中（${c.model}）"
            try {
                val text = timed(c, Role.STT) { to ->
                    when (c.provider) {
                        "groq" -> Groq.transcribe(settings.groqKey, c.model, file, Prompts.whisper(glossary, s, seg), to)
                        "cloudflare" -> Cloudflare.transcribe(settings.cfAccountId, settings.cfToken, file, Prompts.whisper(glossary, s, seg), to)
                        else -> Gemini.transcribe(
                            settings.geminiKey, c.model, file,
                            if (s.isTalk) Prompts.sttTalk(glossary, s, seg) else Prompts.stt(glossary, s, seg), to,
                        )
                    }
                }
                Usage.add(c.key, (seg.endMs - seg.startMs) / 1000.0)
                used(Role.STT, c)
                // 聖句は「コリント第一9:10」の形にそろえる
                // 会話：名前を付けた話し手は、その名前に
                save(s, seg, Talk.applyNames(Scripture.normalizeRefs(cleanStt(text)), s.speakerMap), c)
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
        checkNoText(s.id)
    }

    /**
     * 録音中、文章にならない音（無音・音楽・ざわめき）が決まった分数続いたら、知らせて録音を止める。
     * 清書の結果で判断する（清書待ちの区間があれば、そこで数えるのをやめる＝止めない）。
     */
    private fun checkNoText(id: String) {
        val limitMin = settings.noTextStopMinutes
        if (limitMin <= 0) return
        val s = Repo.get(id) ?: return
        if (!s.recording) return
        var ms = 0L
        for (g in s.segments.asReversed()) {
            val noText = g.silent || (g.raw != null && g.rawEngine != "local" && g.raw.isBlank())
            if (!noText) break
            ms += g.endMs - g.startMs
        }
        if (ms >= limitMin * 60_000L) {
            RecorderService.stopWithNotice(app, "文章にならない音が${limitMin}分以上続いたので、録音を止めました（「続きを録音」で再開できます）")
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

    /** 声が変わったらしい位置を、整形の AI に渡す形にする（区間の番号は 1 から） */
    private fun voiceHints(s: Session, batch: List<Segment>): List<String> {
        if (!settings.voiceChangeHints) return emptyList()
        return batch.mapIndexedNotNull { i, g ->
            val t = s.voiceChanges.firstOrNull { it >= g.startMs - 1500 && it < g.endMs } ?: return@mapIndexedNotNull null
            val off = t - g.startMs
            if (off < 3000) "区間${i + 1}の先頭付近" else "区間${i + 1}の途中（約${off / 1000}秒目）"
        }
    }

    /** 話に長めの間があるか（録音中） */
    private fun inLongPause(): Boolean {
        val t = Live.lastVoiceAt
        return t > 0 && System.currentTimeMillis() - t >= 1_800
    }

    /**
     * すぐの整形をするまとまり。まだ待つなら null。
     *  - 録音中：長めの間があって、たまった音声が fastMinSec 以上 → 整形。間がなくても fastMaxSec 以上 → 整形。
     *    （1回あたりの量をある程度まとめて、呼び出しの回数とトークンを抑える）
     *  - 録音していない：残りをまとめて（最大 120 秒分）。
     */
    private fun pickBatch(s: Session): List<Segment>? {
        val st = settings
        val segs = s.segments
        val first = segs.indexOfFirst { !it.silent && it.edited == null && it.formatted == null && !it.raw.isNullOrBlank() && !needsStt(it) }
        if (first < 0) return null
        val batch = ArrayList<Segment>()
        val maxMs = (if (s.recording) st.fastMaxSec.coerceIn(15, 180) else 120) * 1000L
        var blocked = false
        for (i in first until segs.size) {
            val g = segs[i]
            if (g.silent || g.rawEngine == "error") continue
            if (needsStt(g) || g.raw == null) { blocked = true; break }
            if (g.formatted != null || g.edited != null) break
            if (g.raw.isBlank()) continue
            batch.add(g)
            if (batch.last().endMs - batch.first().startMs >= maxMs) return batch
        }
        if (batch.isEmpty()) return null
        if (!s.recording) return batch
        val durMs = batch.last().endMs - batch.first().startMs
        val minMs = st.fastMinSec.coerceIn(5, 120) * 1000L
        // 長めの間：清書がそろっている（次が清書待ちでない）ときだけ
        if (!blocked && inLongPause() && durMs >= minMs) return batch
        return null
    }

    private fun format(s: Session, batch: List<Segment>): Outcome {
        val glossary = Glossary.load(app)
        val firstIdx = s.segments.indexOfFirst { it.index == batch.first().index }
        val context = if (s.isTalk) Talk.previous(s, batch.first().index, 300) else s.segments.take(firstIdx.coerceAtLeast(0))
            .mapNotNull { displayText(it) }.joinToString("").takeLast(300)
        val system = if (s.isTalk) Prompts.formatSystemTalk(glossary, compact = true) else Prompts.formatSystem(glossary, compact = true)
        val user = Prompts.formatUser(context, batch.map { it.raw ?: "" }, Marker.keep(batch), s, voiceHints(s, batch))

        for (c in usable(Role.FAST)) {
            status.value = "${c.model} で整形中（${fmtClock(batch.first().startMs)}〜）"
            activity.value = "整形中（${c.model}）"
            try {
                val out = timed(c, Role.FAST) { to ->
                    when (c.provider) {
                        "groq" -> Groq.chat(settings.groqKey, c.model, system, user, fast = true, timeoutMs = to)
                        else -> Gemini.text(settings.geminiKey, c.model, system, user, fast = true, timeoutMs = to)
                    }
                }
                Usage.add(c.key)
                used(Role.FAST, c)
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
        // 区間の数が合わない結果は、文がずれたり消えたりしているおそれがあるので使わない（次のモデルで試す）
        if (list.size != batch.size) throw IllegalStateException("整形の結果の区間の数が合いません（${list.size}/${batch.size}）")
        var rejected = 0
        batch.forEachIndexed { i, g ->
            // 「\n」という文字で返ってきた改行を、本当の改行に直す
            // 聖句は「コリント第一9:10」の形にそろえる
            val out = Talk.applyNames(Scripture.normalizeRefs(fixNewlines(list[i])).trimEnd().trimStart(' ', '\u3000'), s.speakerMap)
            val ok = Guard.keepsContent(g.raw ?: "", out)
            if (!ok) rejected++
            Repo.updateSegment(s.id, g.index) {
                // 送っている間に文字起こしがやり直されていたら使わない
                if (it.raw != g.raw || it.edited != null) it
                else if (ok) it.copy(formatted = out, formatModel = c.model, polished = polished)
                else if (polished) it.copy(polished = true) // 仕上げで文が減った：前の文のまま（仕上げ済みとして、繰り返さない）
                else {
                    // すぐの整形で文が減った：2回目までは次の機会にやり直し、それでもだめなら文字起こしのまま
                    val k = "guard:${s.id}/${g.index}"
                    val n = (failures[k] ?: 0) + 1
                    failures[k] = n
                    if (n >= 2) it.copy(formatted = it.raw, formatModel = "（整形を見送り）") else it
                }
            }
        }
        if (rejected > 0) {
            val msg = "${c.model} の${if (polished) "仕上げ" else "整形"}で文が減っていた ${rejected} か所は、元の文を残しました"
            if (polished) polishStatus.value = msg else status.value = msg
        }
    }

    // ---------- 仕上げ（頭の良いモデルで、裏でゆっくり直し直す） ----------

    private suspend fun polishLoop() {
        while (true) {
            val worked = try {
                polishStep() || summaryStep()
            } catch (e: Exception) {
                polishStatus.value = "仕上げ: ${e.message?.take(120)}"
                false
            }
            if (!worked) {
                polishActivity.value = null
                withTimeoutOrNull(30_000) { polishWakeCh.receive() }
            }
        }
    }

    /**
     * 完了した話で、清書・整形・仕上げがすべて済んだら、頭の良い AI に要約を作らせる（仕上げと同じ順番のモデル）。
     */
    private fun summaryStep(): Boolean {
        val st = settings
        if (!Net.online(app)) return false
        val s = Repo.sessions.value.firstOrNull { s ->
            s.completed && s.summary == null && !s.recording && s.segments.isNotEmpty() &&
                pendingCounts(s).let { it.stt == 0 && it.fast == 0 && it.polish == 0 } &&
                (failures["sum:${s.id}"] ?: 0) < 3
        } ?: return false
        val style = Summary.Style.of(st.summaryStyle)
        val system = if (s.isTalk) Summary.systemTalk() else Summary.system(style)
        val user = Summary.input(s)
        if (user.length < 30) {
            Repo.update(s.id) { it.copy(summary = "（要約するほどの文がありません）", summaryModel = "") }
            return true
        }
        // Groq は1分あたりの量が少ないので、短い話だけ
        val cands = usable(Role.POLISH).filter { it.provider != "groq" || user.length < 6000 }
        if (cands.isEmpty()) {
            polishStatus.value = "要約：使えるモデルが休み中"
            return false
        }
        for (c in cands) {
            polishStatus.value = "${c.model} で要約中（${s.shownTitle}）"
            polishActivity.value = "要約中（${c.model}）"
            try {
                val out = timed(c, Role.POLISH) { to ->
                    when (c.provider) {
                        "groq" -> Groq.chat(st.groqKey, c.model, system, user, fast = false, timeoutMs = to, json = false)
                        "openrouter" -> OpenRouter.chat(st.openRouterKey, c.model, system, user, to)
                        else -> Gemini.generate(
                            st.geminiKey, c.model, system,
                            org.json.JSONArray().put(org.json.JSONObject().put("text", user)), json = false, timeoutMs = to,
                        )
                    }
                }
                Usage.add(c.key)
                used(Role.POLISH, c)
                val text = Scripture.normalizeRefs(fixNewlines(out).replace(Regex("^```[a-z]*\\s*|```\\s*$"), "").trim())
                if (text.length < 10) throw IllegalStateException("要約が空でした")
                Repo.update(s.id) { if (it.summary == null) it.copy(summary = text, summaryModel = c.model, summaryDurationMs = it.durationMs) else it }
                polishStatus.value = ""
                return true
            } catch (e: RateLimited) {
                Cooldown.set(c.key, e.waitMs, "${c.model}: ${e.message?.take(120)}")
            } catch (e: Exception) {
                if (e is HttpError && e.code in listOf(400, 401, 403, 404, 413)) {
                    Cooldown.set(c.key, 30 * 60_000L, "${c.model}: ${e.message?.take(120)}")
                    continue
                }
                failures["sum:${s.id}"] = (failures["sum:${s.id}"] ?: 0) + 1
                polishStatus.value = "${c.model} 要約: ${e.message?.take(100)}"
                return false
            }
        }
        return false
    }

    private fun polishBatchOf(s: Session): List<Segment>? {
        val size = settings.polishBatch.coerceIn(2, 30)
        val segs = s.segments
        val first = segs.indexOfFirst { !it.silent && it.edited == null && !it.polished && it.formatted != null && !it.raw.isNullOrBlank() && !needsStt(it) }
        if (first < 0) return null
        val batch = ArrayList<Segment>()
        for (i in first until segs.size) {
            val g = segs[i]
            if (g.silent || g.rawEngine == "error" || g.raw.isNullOrBlank()) continue
            if (needsStt(g) || g.formatted == null || g.polished || g.edited != null) break
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
            val full = polishBatchOf(s) ?: continue
            val cands = usable(Role.POLISH)
            if (cands.isEmpty()) {
                // 止まっている理由と、再開の目安
                val all = Catalog.candidates(app, st, Role.POLISH)
                val wait = all.minOfOrNull { maxOf(Cooldown.remainingMs(it.key), Cooldown.remainingMs("${it.key}#POLISH")) } ?: 0L
                val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.JAPAN).format(java.util.Date(System.currentTimeMillis() + wait))
                polishStatus.value = if (all.isEmpty()) "仕上げ：使えるモデルがありません（キーを確かめてください）"
                else "仕上げ：無料枠の上限などで休み中（$at ごろ再開）"
                return false
            }
            val glossary = Glossary.load(app)
            for (c in cands) {
                // Groq は1分あたりの量が少ないので、小さく分けて（8区間・用語集は短く）送る
                val batch = if (c.provider == "groq") full.take(8) else full
                val firstIdx = s.segments.indexOfFirst { it.index == batch.first().index }
                val ctxLen = if (c.provider == "groq") 300 else 800
                val context = if (s.isTalk) Talk.previous(s, batch.first().index, ctxLen) else s.segments.take(firstIdx.coerceAtLeast(0))
                    .mapNotNull { displayText(it) }.joinToString("").takeLast(ctxLen)
                val system = if (s.isTalk) Prompts.formatSystemTalk(glossary, compact = c.provider == "groq")
                else Prompts.formatSystem(glossary, compact = c.provider == "groq")
                val user = Prompts.formatUser(context, batch.map { it.raw ?: "" }, Marker.keep(batch), s, voiceHints(s, batch))
                polishStatus.value = "${c.model} で仕上げ中（${fmtClock(batch.first().startMs)}〜、${batch.size}区間）"
                polishActivity.value = "仕上げ中（${c.model}）"
                try {
                    val out = timed(c, Role.POLISH) { to ->
                        when (c.provider) {
                            "groq" -> Groq.chat(st.groqKey, c.model, system, user, fast = false, timeoutMs = to)
                            "openrouter" -> OpenRouter.chat(st.openRouterKey, c.model, system, user, to)
                            else -> Gemini.text(st.geminiKey, c.model, system, user, fast = false, timeoutMs = to)
                        }
                    }
                    Usage.add(c.key)
                    used(Role.POLISH, c)
                    applyFormatted(s, batch, out, c, polished = true)
                    polishStatus.value = ""
                    return true
                } catch (e: RateLimited) {
                    Cooldown.set(c.key, e.waitMs, "${c.model}: ${e.message?.take(120)}")
                } catch (e: Exception) {
                    if (e is HttpError && e.code in listOf(400, 401, 403, 404, 413)) {
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
    /** この記録で利用者が直した「誤→正」 */
    private fun corrections(s: Session): String =
        if (s.corrections.isEmpty()) "" else "\n利用者がこの講演で直した語（必ずこのとおりにする）：\n" + s.corrections.takeLast(60).joinToString("\n") + "\n"

    /** 利用者が直した区間の例（書き方の参考） */
    private fun examples(s: Session): String {
        val ex = s.segments.filter { it.edited != null && !it.raw.isNullOrBlank() }.takeLast(3)
        if (ex.isEmpty()) return ""
        return "\n利用者が直した例（直し方・書き方・段落の付け方の参考）：\n" + ex.joinToString("\n") {
            "・直す前：${(it.formatted ?: it.raw ?: "").take(250)}\n  直した後：${it.edited!!.take(250)}"
        } + "\n"
    }

    /** この区間の直前に話された文（区切りの境目で言葉が切れても、つながりが分かるように） */
    fun previousText(s: Session, seg: Segment, max: Int): String {
        val i = s.segments.indexOfFirst { it.index == seg.index }
        if (i <= 0) return ""
        val sb = StringBuilder()
        for (k in i - 1 downTo 0) {
            val g = s.segments[k]
            if (g.silent) continue
            val t = (displayText(g) ?: "").replace(Regex("\\s+"), "")
            if (t.isEmpty()) continue
            sb.insert(0, t)
            if (sb.length >= max) break
        }
        return sb.toString().takeLast(max)
    }

    /**
     * Whisper の prompt。Whisper は prompt を「直前に話された文」として読むので、
     * 用語（語彙のヒント）のあとに、直前の文を最後に置く（区切りの境目の聞き違いが減る）。
     */
    fun whisper(glossary: String, s: Session, seg: Segment): String {
        val fixed = s.corrections.takeLast(20).map { it.substringAfter('→') }
        val head = if (fixed.isEmpty()) "" else fixed.joinToString("、") + "、"
        val prev = previousText(s, seg, 90)
        val terms = (head + Glossary.whisperPrompt(glossary)).take(if (prev.isEmpty()) 220 else 130)
        return if (prev.isEmpty()) terms else "$terms\n$prev"
    }

    /** 聖句の書き方（講演・会話で共通） */
    private const val SCRIPTURE_RULE = "聖句の参照は「ヨハネ3:16」「コリント第一9:10」「テモテ第二3:1-5」の形にそろえてください（書名は新世界訳の書名）。書名と章・節の間には、読点・句点・空白・改行を入れず、続けて書いてください（「コリント第一、9:10」ではなく「コリント第一9:10」）。「十章の五節」のような漢数字も「10:5」の形にします。詩編は「◯編◯節」と言うので、「詩編二十三編一節」は「詩編23:1」にします。章や節だけのときも漢数字は使わず「1章」「2節」と書いてください。節が複数のときは「10:9, 10」のように区切りを残し、数字をつなげないでください（9,10 を 90 にしない）。"

    /** 会話の書き起こし（Gemini は話し手を聞き分けられる） */
    fun sttTalk(glossary: String, s: Session, seg: Segment) = """
これは日本語の会話（複数の人の話し合い。エホバの証人の仲間どうしなど）の録音の一部です。
話し手ごとに分けて、話されたとおりに書き起こしてください。
・発言ごとに1行にし、行の頭に話し手を「Ａ：」「Ｂ：」のように付けてください。
・直前の内容に出てくる人と同じ人なら、同じ書き方（名前が付いていればその名前）を使ってください。新しい人には、まだ使っていない文字（Ｃ、Ｄ…）を使ってください。
・この録音の最初が、直前の内容の最後の発言の続きなら、その行は話し手を付けずに書き始めてください。
・話し言葉のまま書いてください（書き言葉や、です・ます調に直さない）。「はい」「うん」「そうですね」などの相づちも1行として残してください。「えー」「あのー」は消してかまいません。
・句読点を付けてください。話していない内容を足さないでください。要約しないでください。
・聞き取れない部分は「…」としてください。無音や音楽だけのときは、何も出力しないでください。
・用語・書名は、新世界訳聖書（2019年改訂版）と jw.org の出版物の表記に合わせてください。$SCRIPTURE_RULE
・次の用語集の表記に合わせてください（「誤→正」は直し方の例）：
${Glossary.forPrompt(glossary)}
${corrections(s)}
${Talk.previous(s, seg.index, 300).let { if (it.isEmpty()) "" else "直前の内容（参考。出力には含めない）：\n$it\n" }}
出力は書き起こした本文だけにしてください。
""".trim()

    /** 会話の整形の決まった部分 */
    fun formatSystemTalk(glossary: String, compact: Boolean) = """
あなたは日本語の会話の書き起こしを整える編集者です。会話はエホバの証人の仲間どうしの話し合いなどです。

規則：
1. 入力は音声認識の結果を区間ごとに分けた配列です。必ず同じ数・同じ順の配列で返してください。
2. 話した内容は変えないでください。要約・言い換え・付け足しはしないでください。話し言葉のまま残してください（書き言葉や、です・ます調に直さない）。
3. 消してよいのは「えー」「あのー」などのつなぎ言葉と、言い直しだけです。「はい」「うん」「そうですね」などの相づちは消さずに残してください。文を丸ごと消すことは絶対にしないでください。
4. 発言ごとに改行し、行の頭に話し手を「Ａ：」「Ｂ：」のように付けてください。
   ・入力にすでに話し手が付いていれば、それを変えないでください（名前が付いていれば名前のまま）。
   ・付いていなければ、話の流れ（問いと答え・相づち・呼びかけ）と「声が変わった可能性のある位置」から判断して付けてください。
   ・直前の内容に出てくる人と同じ人には、同じ書き方を使ってください。新しい人には、まだ使っていない文字（Ｃ、Ｄ…）を使ってください。
   ・区間の始めが、前の区間の最後の発言の続きなら、その行は話し手を付けずに続けてください。
5. 句読点を整えてください。音声認識の誤りと思われる語は、文脈と用語集から正しい語に直してください。
6. 用語・人名・書名は、wol.jw.org（新世界訳聖書 2019年改訂版、ものみの塔出版物）の表記に合わせてください。
7. $SCRIPTURE_RULE
8. 区間の境目で文が切れていても、そのままにしてください（前後の区間に文を動かさない）。
9. 話題が大きく変わるところだけ、空行（改行を2つ）を入れてください。「\n」という文字を書くのではなく、本当の改行にしてください。
10. 利用者が直した語・例があれば、それに従ってください。
11. 出力は JSON だけ：{"segments":["…","…"]}

用語集（「誤→正」は直し方の例）：
${if (compact) Glossary.forPromptCompact(glossary) else Glossary.forPrompt(glossary)}
""".trim()

    fun stt(glossary: String, s: Session, seg: Segment) = """
これは日本語の講演（エホバの証人の集会・大会の講演）の録音の一部です。
話された内容を、そのまま正確に書き起こしてください。
・句読点を付けてください。
・話していない内容を足さないでください。要約しないでください。
・聞き取れない部分は「…」としてください。
・無音や音楽だけのときは、何も出力しないでください。
・用語・書名・聖句の言い回しは、新世界訳聖書（2019年改訂版）と jw.org の出版物の表記に合わせてください。
・次の用語集の表記に合わせてください（「誤→正」は直し方の例）：
${Glossary.forPrompt(glossary)}
${corrections(s)}
${previousText(s, seg, 200).let { if (it.isEmpty()) "" else "直前に話された内容（参考。出力には含めない。言葉が区切りの境目で切れていたら、つながりを考えて書き起こす）：\n$it\n" }}
出力は書き起こした本文だけにしてください。
""".trim()

    /**
     * 整形の決まった部分。記録ごとに変わるもの（直した語・例・マーカー）は入れない
     * （毎回同じ先頭部分にすると、Gemini が自動でキャッシュして、トークンの消費と待ち時間が減る）。
     * compact = true（すぐの整形）は用語集を短くする。
     */
    fun formatSystem(glossary: String, compact: Boolean) = """
あなたは日本語の講演の書き起こしを整える編集者です。講演はエホバの証人の集会・大会のものです。

規則：
1. 入力は音声認識の結果を区間ごとに分けた配列です。必ず同じ数・同じ順の配列で返してください。
2. 話した内容は変えないでください。要約・言い換え・付け足しはしないでください。
3. 消してよいのは「えー」「あのー」などのつなぎ言葉と、言い直しだけです。文を丸ごと消すことは絶対にしないでください（同じことの繰り返しに見えても残す）。
3-2. 聖句の朗読・引用・参照を含む文は、必ず残してください。
4. 句読点を整え、読みやすくしてください。
5. 音声認識の誤りと思われる語は、文脈と用語集から正しい語に直してください。
6. 用語・人名・書名・言い回しは、wol.jw.org（新世界訳聖書 2019年改訂版、ものみの塔出版物）の表記に合わせてください。
7. $SCRIPTURE_RULE
8. 聖句を朗読している部分は、聞こえた言葉をそのまま残してください。本文を自分で補ったり、言い換えたり、省いたりしないでください（明らかな聞き間違いの語だけ直す）。
9. 区間の境目で文が切れていても、そのままにしてください（前後の区間に文を動かさない）。
10. 段落：話題が変わるところ、聖句の朗読の前後、問いかけの前などで新しい段落にします。新しい段落を始める位置に空行（改行を2つ）を入れてください（区間の先頭でも途中でもよい）。「\n」という文字を書くのではなく、本当の改行にしてください。段落は長くしすぎず、目安は200〜400字です。直前の内容の最後の段落がすでに長ければ、早めに区切ってください。
11. 利用者が直した語・例があれば、それに従い、同じ書き方にそろえてください。
11-2. 入力に「声が変わった可能性のある位置」があれば、そこを段落の区切りの有力な候補にしてください（話し手が替わったか、話が切り替わった可能性があります）。話の流れから見て不自然なら、区切らなくてかまいません。
12. 出力は JSON だけ：{"segments":["…","…"]}

用語集（「誤→正」は直し方の例）：
${if (compact) Glossary.forPromptCompact(glossary) else Glossary.forPrompt(glossary)}
""".trim()

    fun formatUser(context: String, raws: List<String>, keep: List<String>, s: Session, voiceHints: List<String> = emptyList()): String {
        val input = JSONObject().put("segments", JSONArray(raws)).toString()
        return buildString {
            append(corrections(s))
            append(examples(s))
            if (voiceHints.isNotEmpty()) {
                append("声が変わった可能性のある位置（段落の区切りの参考。区間の番号は 1 から）：\n")
                append(voiceHints.joinToString("\n") { "・$it" })
                append("\n\n")
            }
            if (keep.isNotEmpty()) {
                append("利用者がマーカーを引いた語句（この言い回しのまま残す）：\n")
                append(keep.joinToString("\n") { "・$it" })
                append("\n\n")
            }
            if (context.isNotBlank()) {
                append("直前の内容（参考。出力には含めない。空行は段落の区切り）：\n")
                append(context)
                append("\n\n")
            }
            append("入力：\n")
            append(input)
        }
    }
}

/**
 * 整形・仕上げの結果が、元の文字起こしの中身を残しているかを確かめる。
 *  - つなぎ言葉・句読点・空白を除いて比べ、元の文字の 6 割以上が順番どおりに残っていること
 *  - 元に聖句の参照（「ヨハネ 3:16」や「3章16節」）があれば、結果にも参照が残っていること
 */
object Guard {
    private val FILLERS = Regex("えーと|えーっと|えー|ええと|あのー|あの|そのー|まあ|うーん|んー")
    private val NOISE = Regex("[、。，．,.!！?？「」『』（）()・…ー〜~\\s　\"'“”:：;；]")
    private val VERSE = Regex("\\d+\\s*[:：章]\\s*\\d+|[０-９]+\\s*[:：章]\\s*[０-９]+")

    private fun norm(t: String) = t.replace(FILLERS, "").replace(NOISE, "")

    fun keepsContent(raw: String, out: String): Boolean {
        val a = norm(raw)
        if (a.length < 6) return true // 短すぎるものは比べない
        val b = norm(out)
        if (b.isEmpty()) return false
        if (lcs(a.take(1200), b.take(1500)).toDouble() / a.take(1200).length < 0.6) return false
        // 聖句の参照が消えていないか（数字の書き方が変わっても「数字:数字」か「章」が残っていればよい）
        val rawHasVerse = VERSE.containsMatchIn(raw) || Scripture.find(raw).isNotEmpty()
        if (rawHasVerse && !(VERSE.containsMatchIn(out) || Scripture.find(out).isNotEmpty())) return false
        // 実在しない聖句（「10:90」など）を作っていたら使わない（元からあったものは除く）
        val badOut = Scripture.findInvalid(out).map { Triple(it.book, it.chapter, it.verse) }.toSet()
        val badRaw = Scripture.findInvalid(raw).map { Triple(it.book, it.chapter, it.verse) }.toSet()
        if ((badOut - badRaw).isNotEmpty()) return false
        return true
    }

    /** 最長共通部分列の長さ */
    private fun lcs(a: String, b: String): Int {
        var prev = IntArray(b.length + 1)
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            val t = prev; prev = cur; cur = t
            cur.fill(0)
        }
        return prev[b.length]
    }
}

/** 「\n」という2文字で返ってきた改行を、本当の改行に直す */
fun fixNewlines(t: String): String = t.replace("\\r\\n", "\n").replace("\\n", "\n")

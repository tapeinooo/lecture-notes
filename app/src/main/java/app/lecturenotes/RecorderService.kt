package app.lecturenotes

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.update
import kotlin.math.sqrt

/**
 * 録音（画面を消しても続ける）。決まった長さごとに WAV を書き出し、文字起こしの順番待ちに入れる。
 */
class RecorderService : Service() {
    companion object {
        /** いま動いている録音のサービス（ほかから止めるため） */
        @Volatile var current: RecorderService? = null

        /** 理由を知らせて録音を止める（画面の帯と通知） */
        fun stopWithNotice(context: Context, message: String) {
            Notice.show(context, message)
            val svc = current
            if (svc != null) android.os.Handler(context.mainLooper).post { svc.finishFromOutside() }
            else stop(context)
        }

        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_ID = "id"
        private const val CHANNEL = "recording"

        fun start(context: Context, sessionId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecorderService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, sessionId),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var local: LocalRecognizer? = null
    private val localText = StringBuilder()
    private lateinit var settings: Settings

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
    }

    fun finishFromOutside() {
        // 録音の流れがまだ残っていれば止める（止めかけで running が false になっていても）
        if (running || thread != null) finish()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // startForegroundService のあとは必ず前面に出す（すでに録音中でも。出さないと Android がアプリを止める）
                startInForeground()
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                val current = Live.state.value.sessionId
                if (running && current == id) return START_NOT_STICKY
                // ほかの記録を録音中なら、止めてから新しく始める（止め忘れ対策）
                if (running) {
                    stopRecording()
                    Processor.status.value = "前の録音を止めて、新しい録音を始めました"
                }
                begin(id)
            }
            ACTION_STOP -> finish()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "録音", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("録音中")
            .setContentText("たっぴぃず録音が録音しています")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
    }

    private var lastPartialAt = 0L

    /** 最近確定した端末内の文（重複を見分けるため。区切りをまたいで残す） */
    private var recentFinals = ""

    /** 画面がついているか */
    @Volatile private var screenOn = true

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn = intent?.action != Intent.ACTION_SCREEN_OFF
            if (running) updateLocal()
        }
    }
    private var screenRegistered = false

    private fun begin(id: String) {
        settings = Settings(this)
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        if (!screenRegistered) {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                android.content.IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenRegistered = true
        }
        val session = Repo.update(id) { it.copy(recording = true) } ?: run { finish(); return }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lecturenotes:rec").apply { acquire(14 * 60 * 60 * 1000L) }
        Live.state.value = Live.State(sessionId = id, startedAt = System.currentTimeMillis(), baseMs = session.durationMs)
        running = true
        thread = Thread({ recordLoop(id) }, "recorder").apply { start() }
        Processor.wake()
    }

    /** 録音を止める（サービスは残す。続けて別の記録を録音できる） */
    private fun stopRecording() {
        running = false
        thread?.join(5000)
        thread = null
        stopLocal()
        // 録音が途中で終わったとき（マイクが使えないなど）も、録音中のままにしない
        Live.state.value.sessionId?.let { sid -> Repo.update(sid) { it.copy(recording = false) } }
        Live.state.value = Live.State()
        Live.lastVoiceAt = 0L
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Processor.wake()
    }

    private fun finish() {
        stopRecording()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (screenRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenRegistered = false
        }
        if (running) finish()
        if (current === this) current = null
        super.onDestroy()
    }

    // ---------- 端末内の文字起こしの出し入れ ----------

    private fun updateLocal() {
        // その場の表示のため、画面がついている間は端末内の音声認識を動かす（クラウドの結果が来たら置き換える）。
        // 画面を消している間は誰も見ていないので止める（電池のため）。ただし端末内しか使えないときは動かし続ける
        val want = ((settings.livePreview && screenOn) || Processor.shouldUseLocal()) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (want && local == null) startLocal()
        if (!want && local != null) stopLocal()
        Live.state.update { st -> st.copy(
            engine = listOfNotNull(
                if (local?.active == true) "その場：端末内" else null,
                when {
                    settings.mode == Mode.LOCAL -> if (local?.active == true) null else "端末内（使えません）"
                    Processor.cloudSttAvailable() -> "清書：クラウド"
                    else -> "清書：あとで"
                },
            ).joinToString("・"),
        ) }
    }

    private fun startLocal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val r = LocalRecognizer(
            this,
            onFinal = { t ->
                // 重複を取り除く（最近確定した文と比べて、まだない部分だけ足す。区切りをまたいでも比べる）
                val add = synchronized(localText) {
                    val part = Dedupe.newPart(recentFinals, t)
                    if (part.isNotEmpty()) {
                        localText.append(part).append(' ')
                        recentFinals = (recentFinals + " " + part).takeLast(400)
                    }
                    part
                }
                Live.state.update { if (add.isEmpty()) it.copy(partial = "") else it.copy(liveText = (it.liveText + " " + add).trim(), partial = "") }
            },
            onPartial = { t ->
                // 省電力モードでは、途中の文字の更新を 0.6 秒に 1 回までにする（画面の描き直しを減らす）
                val now = System.currentTimeMillis()
                if (!Power.save.value || t.isEmpty() || now - lastPartialAt >= 600) {
                    lastPartialAt = now
                    Live.state.update { it.copy(partial = t) }
                }
            },
            onError = { msg -> Live.state.update { it.copy(error = msg) } },
        )
        local = r
        r.start()
    }

    private fun stopLocal() {
        local?.stop()
        local = null
        // 止めたときは、残っている仮の文字も消す（古い文字が下に残らないように）
        Live.state.update { it.copy(partial = "", liveText = "") }
    }

    private var lastWarning: String? = null

    /** 通話中・ほかのアプリがマイクを使っているときは、録音が無音になるので知らせる */
    private fun checkMicTaken(rec: AudioRecord, am: android.media.AudioManager?) {
        val inCall = am?.mode == android.media.AudioManager.MODE_IN_CALL ||
            am?.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
        val silenced = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (rec.activeRecordingConfiguration?.isClientSilenced == true)
        val w = when {
            inCall -> "📞 通話中は録音できません（この間は無音になります）"
            silenced -> "🎤 ほかのアプリがマイクを使っています（この間は無音になります）"
            else -> null
        }
        if (w != lastWarning) {
            lastWarning = w
            Live.state.update { it.copy(warning = w) }
            updateNotification(w)
        }
    }

    private fun updateNotification(warning: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (warning != null) "録音が止まっています" else "録音中")
            .setContentText(warning ?: "たっぴぃず録音が録音しています")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        nm.notify(1, n)
    }

    /** マイクが使えなかったとき：録音中の表示のまま止まらないよう、録音を終える */
    private fun failMic(msg: String) {
        running = false
        Processor.status.value = msg
        android.os.Handler(mainLooper).post { finish() }
    }

    // ---------- 録音 ----------

    @SuppressLint("MissingPermission")
    private fun recordLoop(id: String) {
        // 設定のマイクの取り方で開く（だめなら標準）
        val opened = AudioInput.open(MicSource.of(settings.micSource))
        if (opened == null) {
            failMic("マイクを使えません（マイクの権限を確認してください）")
            return
        }
        val rec = opened.first
        val gainer = AutoGain()
        var rawAvg = 0.0
        rec.startRecording()
        val buf = ShortArray(SAMPLE_RATE / 10) // 0.1秒
        val audioManager = getSystemService(android.media.AudioManager::class.java)
        var tick = 0
        android.os.Handler(mainLooper).post { updateLocal() }

        val session = Repo.get(id) ?: return
        var startMs = session.durationMs
        // 声の変わり目（段落を区切る参考にする）
        val voice = VoiceChangeDetector { at ->
            Repo.update(id) { it.copy(voiceChanges = (it.voiceChanges + at).sorted()) }
        }
        var index = (session.segments.maxOfOrNull { it.index } ?: 0) + 1

        while (running) {
            // 区切り方（清書に送る単位）
            //  ・10秒を過ぎたら、0.6秒以上の間（文の切れ目）で区切る（Groq は1回10秒分として数えるので、それより短くしない）
            //  ・目安の長さを過ぎたら、少しでも静かなところで区切る
            //  ・切れ目がなければ目安の1.5倍で区切る
            val targetSamples = settings.chunkSeconds.coerceIn(10, 120) * SAMPLE_RATE
            val minSamples = minOf(10 * SAMPLE_RATE, targetSamples)
            val maxSamples = targetSamples * 3 / 2
            val writer = Audio.newChunk(Repo.audioDir(id), index)
            val name = writer.file.name
            var samples = 0
            var loudFrames = 0
            var frames = 0
            var rmsSum = 0.0
            var quietRun = 0
            synchronized(localText) { localText.setLength(0) }
            Live.state.update { it.copy(liveText = "", chunkStartedAt = System.currentTimeMillis()) }

            while (running && samples < maxSamples) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                // 大きさは書き換える前の音で見る（無音の判断などがぶれないように）
                val rms = AudioInput.rms(buf, n)
                rawAvg = if (rawAvg == 0.0) rms else rawAvg * 0.995 + rms * 0.005
                // 小さい音を、割れない範囲で大きくしてから保存・送信する
                if (settings.autoGain) gainer.process(buf, n, rms, isVoice = rms > maxOf(150.0, rawAvg * 0.8))
                writer.write(buf, n)
                local?.feed(buf, n)
                samples += n
                frames++
                rmsSum += rms
                if (settings.voiceChangeHints) voice.feed(buf, n, rms, startMs + (samples - n) * 1000L / SAMPLE_RATE)
                if (rms > 120) loudFrames++
                // 音の大きさを dB で（-50dB〜0dB を 0〜1 に）。耳の感じ方に近い
                val outRms = (rms * (if (settings.autoGain) gainer.gain else 1.0)).coerceAtLeast(1.0)
                Live.level.value = ((20 * kotlin.math.log10(outRms / 32768.0) + 50) / 50).coerceIn(0.0, 1.0).toFloat()
                // 1秒ごとに、通話などでマイクが取られていないか・無音が続いていないかを見る
                if (++tick % 10 == 0) {
                    checkMicTaken(rec, audioManager)
                    val limitMin = settings.autoStopMinutes
                    if (limitMin > 0) {
                        val since = maxOf(Live.lastVoiceAt, Live.state.value.startedAt)
                        if (since > 0 && System.currentTimeMillis() - since > limitMin * 60_000L) {
                            running = false
                            stopWithNotice(this, "無音が${limitMin}分続いたので、録音を止めました")
                        }
                    }
                }
                val quiet = rms < maxOf(150.0, rmsSum / frames * 0.35)
                if (quiet) quietRun++ else {
                    quietRun = 0
                    Live.lastVoiceAt = System.currentTimeMillis()
                }
                // 長めの間（2秒）に入ったら、整形を急がせる
                if (quietRun == 20) Processor.wake()
                if (samples >= minSamples && quietRun >= 6) break
                if (samples >= targetSamples && quiet) break
            }
            runCatching { writer.close() }

            val durMs = samples * 1000L / SAMPLE_RATE
            if (durMs < 1000) {
                writer.file.delete()
                break
            }
            if (!writer.file.exists()) {
                Live.state.update { it.copy(error = "音声を保存できなかった区間があります") }
                startMs += durMs
                index++
                continue
            }
            // ほとんど音がない区間は文字起こしに送らない
            val silent = frames > 0 && loudFrames * 100 / frames < 2
            // 端末内の仮の文字。直前の区間（清書済み）に含まれる文は除く（遅れて返ってきた文の二重表示を防ぐ）
            val localResult = if (local?.active == true) synchronized(localText) { localText.toString().trim() }.let { t ->
                val ref = Repo.get(id)?.segments?.takeLast(3)?.filter { it.rawEngine != "local" }?.mapNotNull { displayText(it) }?.joinToString("") ?: ""
                Dedupe.dropCovered(t, ref)
            } else null
            val seg = Segment(
                index = index,
                startMs = startMs,
                endMs = startMs + durMs,
                audio = name,
                raw = localResult,
                rawEngine = if (localResult != null) "local" else null,
                silent = silent,
            )
            Repo.update(id) { it.copy(segments = it.segments + seg) }
            Processor.wake()
            startMs += durMs
            index++
            android.os.Handler(mainLooper).post { if (running) updateLocal() }
        }

        rec.stop()
        rec.release()
        Repo.update(id) { it.copy(recording = false) }
    }
}

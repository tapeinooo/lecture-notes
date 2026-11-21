package app.lecturenotes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 録音の再生。区間（約15秒ごとの音声ファイル）を順に鳴らす。
 * 一覧から始めたときは、話が終わると次の話へ続ける（queue）。
 * 録音を始めるときは止める（スピーカーの音を録音しないように）。
 */
object Player {
    data class State(
        val sessionId: String? = null,
        val title: String = "",
        /** 鳴らしている区間の番号 */
        val segIndex: Int = -1,
        /** その区間の中の位置 */
        val segPosMs: Long = 0,
        /** 話の頭からの位置 */
        val posMs: Long = 0,
        val totalMs: Long = 0,
        val playing: Boolean = false,
        /** このあと続けて鳴らす話 */
        val queue: List<String> = emptyList(),
    ) {
        val active get() = sessionId != null
    }

    val state = MutableStateFlow(State())

    private val main = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var segs: List<Segment> = emptyList()
    private var cur = 0
    private var focus: AudioFocusRequest? = null
    private lateinit var app: Context

    /** 再生を始める。録音中なら始めない（false を返す） */
    fun play(context: Context, sessionId: String, fromMs: Long = 0, queue: List<String> = emptyList()): Boolean {
        app = context.applicationContext
        if (Live.state.value.sessionId != null) {
            Processor.status.value = "録音中は再生できません"
            return false
        }
        main.post { start(sessionId, fromMs, queue) }
        return true
    }

    private fun start(sessionId: String, fromMs: Long, queue: List<String>) {
        release()
        val s = Repo.sessions.value.firstOrNull { it.id == sessionId }
        val list = s?.segments?.sortedBy { it.index }?.filter { Repo.audioFile(s.id, it).exists() }.orEmpty()
        if (s == null || list.isEmpty()) {
            // 音声のない話は飛ばす
            if (queue.isNotEmpty()) start(queue.first(), 0, queue.drop(1)) else stop()
            return
        }
        segs = list
        cur = list.indexOfLast { it.startMs <= fromMs }.coerceAtLeast(0)
        state.value = State(s.id, s.shownTitle, list[cur].index, 0, list[cur].startMs, s.durationMs, true, queue)
        requestFocus()
        PlayerService.show(app)
        openSeg((fromMs - list[cur].startMs).coerceAtLeast(0))
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun openSeg(seekMs: Long) {
        mp?.release()
        mp = null
        val s = state.value.sessionId ?: return
        if (cur >= segs.size) { finishedSession(); return }
        val g = segs[cur]
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            p.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(Repo.audioFile(s, g).absolutePath)
            p.prepare()
            if (seekMs > 0 && seekMs < p.duration) p.seekTo(seekMs.toInt())
            p.setOnCompletionListener { cur++; openSeg(0) }
            p.setOnErrorListener { _, _, _ -> main.post { cur++; openSeg(0) }; true }
            mp = p
            state.value = state.value.copy(segIndex = g.index, segPosMs = seekMs, posMs = g.startMs + seekMs)
            if (state.value.playing) p.start()
        } catch (e: Exception) {
            // 読めない区間は飛ばす
            cur++
            main.post { openSeg(0) }
        }
    }

    private fun finishedSession() {
        val q = state.value.queue
        if (q.isNotEmpty()) start(q.first(), 0, q.drop(1)) else stop()
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = mp
            val st = state.value
            if (!st.active) return
            if (p != null && cur < segs.size) {
                val pos = runCatching { p.currentPosition.toLong() }.getOrDefault(st.segPosMs)
                state.value = st.copy(segPosMs = pos, posMs = segs[cur].startMs + pos)
            }
            main.postDelayed(this, if (Power.save.value) 1000 else 400)
        }
    }

    fun pause() = main.post {
        runCatching { mp?.pause() }
        state.value = state.value.copy(playing = false)
        if (state.value.active) PlayerService.show(app)
    }

    fun resume() = main.post {
        if (!state.value.active) return@post
        if (Live.state.value.sessionId != null) { Processor.status.value = "録音中は再生できません"; return@post }
        requestFocus()
        state.value = state.value.copy(playing = true)
        if (mp == null) openSeg(0) else runCatching { mp?.start() }
        PlayerService.show(app)
    }

    fun toggle() { if (state.value.playing) pause() else resume() }

    /** 話の中の位置へ動く（秒送り・戻し） */
    fun seekTo(ms: Long) = main.post {
        val st = state.value
        if (!st.active || segs.isEmpty()) return@post
        val t = ms.coerceIn(0, st.totalMs)
        cur = segs.indexOfLast { it.startMs <= t }.coerceAtLeast(0)
        openSeg((t - segs[cur].startMs).coerceAtLeast(0))
    }

    fun seekBy(deltaMs: Long) = seekTo(state.value.posMs + deltaMs)

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { stop() }; return }
        val was = state.value.active
        release()
        state.value = State()
        if (was && ::app.isInitialized) PlayerService.end(app)
    }

    private fun release() {
        main.removeCallbacks(tick)
        runCatching { mp?.release() }
        mp = null
        segs = emptyList()
        cur = 0
        if (::app.isInitialized) {
            focus?.let { app.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        }
        focus = null
    }

    private fun requestFocus() {
        if (focus != null) return
        val am = app.getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            // 電話などでほかのアプリが音を使うときは一時停止
            .setOnAudioFocusChangeListener { f ->
                if (f == AudioManager.AUDIOFOCUS_LOSS || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            }
            .build()
        am.requestAudioFocus(req)
        focus = req
    }
}

/** 再生中に画面を消しても止まらないよう、通知を出して前面で動かす */
class PlayerService : Service() {
    companion object {
        private const val CHANNEL = "playback"
        private const val ACTION_SHOW = "show"
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_STOP = "stop"
        private const val ID = 3
        @Volatile private var running = false

        fun show(context: Context) {
            if (running) {
                // すでに前面で動いていれば、通知だけ書き直す
                context.getSystemService(NotificationManager::class.java).notify(ID, build(context))
                return
            }
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, PlayerService::class.java).setAction(ACTION_SHOW))
            }
        }

        fun end(context: Context) {
            if (!running) return
            context.startService(Intent(context, PlayerService::class.java).setAction(ACTION_STOP))
        }

        private fun build(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "再生", NotificationManager.IMPORTANCE_LOW))
            val st = Player.state.value
            fun pi(action: String, code: Int) = PendingIntent.getService(
                context, code, Intent(context, PlayerService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(if (st.playing) "再生中" else "一時停止中")
                .setContentText(st.title + if (st.queue.isNotEmpty()) "（このあと ${st.queue.size} 件）" else "")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(0, if (st.playing) "一時停止" else "再生", pi(ACTION_TOGGLE, 1))
                .addAction(0, "止める", pi(ACTION_STOP, 2))
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                ServiceCompat.startForeground(
                    this, ID, build(this),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
                )
                running = true
                // 開くまでに止められていたら、すぐ片付ける
                if (!Player.state.value.active) finish()
            }
            ACTION_TOGGLE -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                Player.toggle()
            }
            ACTION_STOP -> {
                running = false
                if (Player.state.value.active) Player.stop()
                finish()
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_ID)
                if (id == null || running) return START_NOT_STICKY
                startInForeground()
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
            .setContentText("講演メモが録音しています")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
    }

    private fun begin(id: String) {
        settings = Settings(this)
        val session = Repo.update(id) { it.copy(recording = true) } ?: run { finish(); return }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lecturenotes:rec").apply { acquire(14 * 60 * 60 * 1000L) }
        Live.state.value = Live.State(sessionId = id, startedAt = System.currentTimeMillis(), baseMs = session.durationMs)
        running = true
        thread = Thread({ recordLoop(id) }, "recorder").apply { start() }
        Processor.wake()
    }

    private fun finish() {
        running = false
        thread?.join(5000)
        thread = null
        stopLocal()
        Live.state.value = Live.State()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Processor.wake()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) finish()
        super.onDestroy()
    }

    // ---------- 端末内の文字起こしの出し入れ ----------

    private fun updateLocal() {
        val want = Processor.shouldUseLocal() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (want && local == null) startLocal()
        if (!want && local != null) stopLocal()
        Live.state.update { st -> st.copy(
            engine = when {
                local?.active == true -> "端末内"
                settings.mode == Mode.LOCAL -> "端末内（使えません）"
                Processor.cloudSttAvailable() -> "クラウド（${settings.mode.label}）"
                else -> "録音のみ（あとで文字起こし）"
            },
        ) }
    }

    private fun startLocal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val r = LocalRecognizer(
            this,
            onFinal = { t -> synchronized(localText) { localText.append(t).append(' ') } },
            onPartial = { t -> Live.state.update { it.copy(partial = t) } },
            onError = { msg -> Live.state.update { it.copy(error = msg) } },
        )
        local = r
        r.start()
    }

    private fun stopLocal() {
        local?.stop()
        local = null
        Live.state.update { it.copy(partial = "") }
    }

    // ---------- 録音 ----------

    @SuppressLint("MissingPermission")
    private fun recordLoop(id: String) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE * 2),
            )
        } catch (e: Exception) {
            Live.state.update { it.copy(error = "マイクを使えません: ${e.message}") }
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Live.state.update { it.copy(error = "マイクを使えません（権限を確認してください）") }
            rec.release()
            return
        }
        rec.startRecording()
        val buf = ShortArray(SAMPLE_RATE / 10) // 0.1秒
        android.os.Handler(mainLooper).post { updateLocal() }

        val session = Repo.get(id) ?: return
        var startMs = session.durationMs
        var index = (session.segments.maxOfOrNull { it.index } ?: 0) + 1

        while (running) {
            val chunkSamples = settings.chunkSeconds.coerceIn(10, 120) * SAMPLE_RATE
            val writer = Audio.newChunk(Repo.audioDir(id), index)
            val name = writer.file.name
            var samples = 0
            var loudFrames = 0
            var frames = 0
            synchronized(localText) { localText.setLength(0) }

            while (running && samples < chunkSamples) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                writer.write(buf, n)
                local?.feed(buf, n)
                samples += n
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / n)
                frames++
                if (rms > 250) loudFrames++
                Live.state.update { it.copy(level = (rms / 4000.0).coerceIn(0.0, 1.0).toFloat()) }
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
            val localResult = if (local?.active == true) synchronized(localText) { localText.toString().trim() } else null
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

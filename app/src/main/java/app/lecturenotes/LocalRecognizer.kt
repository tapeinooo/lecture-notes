package app.lecturenotes

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 端末内の文字起こし（Android 13 以上）。
 * マイクは録音側が持っているので、録音した音をパイプで音声認識に渡す（EXTRA_AUDIO_SOURCE）。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class LocalRecognizer(
    private val context: Context,
    /** 確定した文字 */
    private val onFinal: (String) -> Unit,
    /** 途中の文字 */
    private val onPartial: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var out: OutputStream? = null
    @Volatile var active = false
        private set

    private val queue = LinkedBlockingQueue<ByteArray>(300)
    private var writer: Thread? = null
    private val recentErrors = ArrayDeque<Long>()

    fun start() {
        if (active) return
        active = true
        writer = Thread({ writeLoop() }, "local-stt-writer").apply { start() }
        main.post { begin() }
    }

    fun stop() {
        active = false
        writer?.interrupt()
        writer = null
        main.post { end() }
    }

    /** 録音側から呼ぶ。詰まっていたら捨てる（録音を止めないため） */
    fun feed(pcm: ShortArray, n: Int) {
        if (!active) return
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = pcm[i].toInt()
            bytes[i * 2] = (v and 0xff).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        queue.offer(bytes)
    }

    private fun writeLoop() {
        try {
            while (active) {
                val b = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val o = out ?: continue
                try {
                    o.write(b)
                } catch (e: IOException) {
                    // 認識が終わってパイプが閉じた。次のパイプを待つ
                    out = null
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun begin() {
        if (!active) return
        end()
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])

        val r = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            // 音が続く限り区切りながら認識し続ける
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        recognizer = r
        r.startListening(intent)
    }

    private fun end() {
        runCatching { out?.close() }
        out = null
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }

    private fun restartSoon(delayMs: Long = 300) {
        if (!active) return
        main.postDelayed({ begin() }, delayMs)
    }

    private fun texts(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            onPartial(texts(partialResults))
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val t = texts(segmentResults)
            if (t.isNotEmpty()) onFinal(t)
            onPartial("")
        }

        override fun onEndOfSegmentedSession() {
            restartSoon()
        }

        override fun onResults(results: Bundle?) {
            val t = texts(results)
            if (t.isNotEmpty()) onFinal(t)
            onPartial("")
            restartSoon()
        }

        override fun onError(error: Int) {
            val now = System.currentTimeMillis()
            recentErrors.addLast(now)
            while (recentErrors.isNotEmpty() && now - recentErrors.first() > 60_000) recentErrors.removeFirst()
            val fatal = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            if (fatal || recentErrors.size >= 8) {
                onError("端末内の文字起こしが使えません（コード $error）。設定の「端末内の日本語モデルを準備」を試してください。録音は続いています。")
                active = false
                end()
                return
            }
            // 無音で終わった（NO_MATCH・SPEECH_TIMEOUT）などは、すぐやり直す
            restartSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 300)
        }
    }

    companion object {
        /** 端末内の日本語モデルのダウンロードを促す */
        fun prepareModel(context: Context): String {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "Android 13 以上が必要です"
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return "この端末では端末内の音声認識が使えません"
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            r.triggerModelDownload(intent)
            Handler(Looper.getMainLooper()).postDelayed({ r.destroy() }, 5000)
            return "日本語モデルの準備を頼みました（通知や画面が出たら従ってください）"
        }
    }
}

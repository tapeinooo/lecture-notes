package app.lecturenotes

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/** マイクの取り方（機種によって、遠くの声に強いものが違う） */
enum class MicSource(val key: String, val label: String, val help: String, val android: Int) {
    MIC("mic", "標準", "ふつうの録音", MediaRecorder.AudioSource.MIC),
    CAMCORDER("camcorder", "遠くの音向け", "ビデオ撮影用。離れた音を拾いやすい機種が多い", MediaRecorder.AudioSource.CAMCORDER),
    VOICE("voice", "声向け", "音声認識用。雑音を抑えることが多い", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    UNPROCESSED("unprocessed", "加工なし", "機種の加工をせずそのまま（使えない機種もある）", MediaRecorder.AudioSource.UNPROCESSED);

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: MIC

        fun available(context: Context): List<MicSource> {
            val am = context.getSystemService(AudioManager::class.java)
            val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            return entries.filter { it != UNPROCESSED || unprocessed }
        }
    }
}

object AudioInput {
    /** 指定の取り方でマイクを開く。開けなければ標準で開き直す。だめなら null */
    @SuppressLint("MissingPermission")
    fun open(source: MicSource): Pair<AudioRecord, MicSource>? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        for (s in listOf(source, MicSource.MIC).distinct()) {
            val rec = try {
                AudioRecord(s.android, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE * 2))
            } catch (e: Exception) {
                null
            }
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) return rec to s
            rec?.release()
        }
        return null
    }

    fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        return if (n > 0) sqrt(sum / n) else 0.0
    }
}

/**
 * 小さい音を、割れない範囲で自動で大きくする（遠い席向け）。
 *  - 声のあるところの大きさ（ゆっくり追う）を目標の大きさに近づける倍率をかける（1〜6倍）
 *  - 声のないところでは倍率を変えない（雑音を持ち上げすぎないため）
 *  - 大きくなりすぎるところは、やわらかく頭を抑える（割れを防ぐ）
 */
class AutoGain {
    private companion object {
        const val TARGET = 3500.0
        const val MAX_GAIN = 6.0
    }

    var gain = 1.0
        private set
    private var voiced = 0.0

    /** buf を書き換える。rawRms は書き換える前の大きさ */
    fun process(buf: ShortArray, n: Int, rawRms: Double, isVoice: Boolean) {
        if (isVoice && rawRms > 50) {
            voiced = if (voiced == 0.0) rawRms else voiced * 0.97 + rawRms * 0.03
            val want = (TARGET / voiced).coerceIn(1.0, MAX_GAIN)
            // 上げるのはゆっくり、下げるのは速く
            gain = if (want > gain) gain + (want - gain) * 0.02 else gain + (want - gain) * 0.3
        }
        if (gain <= 1.01) return
        for (i in 0 until n) {
            val x = buf[i] * gain
            // 24000 を超えるところは、やわらかく抑える（最大でも 32767 に収まる）
            val y = if (abs(x) <= 24000) x else {
                val sign = if (x < 0) -1.0 else 1.0
                sign * (24000 + 8767 * tanh((abs(x) - 24000) / 8767))
            }
            buf[i] = y.toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}

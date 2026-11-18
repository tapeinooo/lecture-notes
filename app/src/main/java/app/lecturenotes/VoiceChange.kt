package app.lecturenotes

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 声の変わり目（話し手が替わったらしいところ）を、端末の中だけで見つける。
 * 誰かを見分けるのではなく「変わった」ことが分かればよい（段落を区切る参考）。
 *
 * しくみ：
 *  - 0.1 秒ごとに、声があるところだけ、声の高さ（基本周波数）と響き方（周波数ごとの強さの形）を測る。
 *  - 声のある 2 秒ぶんを 1 つのまとまりにして、いまの話し手の「声の特徴」（直近のまとまりの平均）と比べる。
 *  - 違いの大きさは会場や人で変わるので、決まった値ではなく、この録音の中のふだんの揺れと比べる。
 *    ふだんよりはっきり大きく、しかも直前に間（0.4 秒以上）があれば「変わった」とする。
 */
class VoiceChangeDetector(private val onChange: (atMs: Long) -> Unit) {
    private companion object {
        const val FFT_N = 512
        const val BANDS = 16
        const val WINDOW_FRAMES = 20 // 声のある 0.1 秒 × 20 = 2 秒
        const val MIN_HISTORY = 8
        const val Z_THRESHOLD = 5.5
        const val MIN_PAUSE_MS = 400L
    }

    private val win = DoubleArray(FFT_N) { 0.5 - 0.5 * cos(2 * Math.PI * it / (FFT_N - 1)) }
    private val re = DoubleArray(FFT_N)
    private val im = DoubleArray(FFT_N)

    // いまのまとまり
    private val pitches = ArrayList<Double>()
    private val bandSum = DoubleArray(BANDS)
    private var frames = 0
    private var windowStartMs = -1L
    private var pauseBeforeWindowMs = 0L

    // いまの話し手の特徴（直近のまとまり）
    private val profile = ArrayDeque<Pair<Double, DoubleArray>>()

    // これまでの違いの大きさ（ふだんの揺れ）
    private val scores = ArrayDeque<Double>()

    /** 最近 10 秒の音の大きさ（声があるかの判断に使う） */
    private val recentRms = ArrayDeque<Double>()
    private var lastVoicedMs = -1L
    private var pending: Long? = null

    /** 録音側から 0.1 秒ごとに呼ぶ。atMs は記録の中での時刻 */
    fun feed(pcm: ShortArray, n: Int, rms: Double, atMs: Long) {
        // 声があるか：最近の大きさの真ん中の半分より大きいか（会場の雑音の大きさに左右されにくい）
        recentRms.addLast(rms)
        if (recentRms.size > 100) recentRms.removeFirst()
        val median = recentRms.sorted()[recentRms.size / 2]
        if (rms <= maxOf(250.0, median * 0.5)) return
        val gap = if (lastVoicedMs < 0) 0L else atMs - lastVoicedMs
        lastVoicedMs = atMs + 100
        // 間があったら、そこでまとまりを切る（変わり目は間のあとに来ることが多い）
        if (gap >= MIN_PAUSE_MS && frames > 0) {
            if (frames >= 8) closeWindow() else resetWindow()
        }
        if (frames == 0) {
            windowStartMs = atMs
            pauseBeforeWindowMs = gap
        }
        pitch(pcm, n)?.let { pitches.add(it) }
        bands(pcm, n)?.let { b -> for (i in 0 until BANDS) bandSum[i] += b[i] }
        frames++
        if (frames >= WINDOW_FRAMES) closeWindow()
    }

    private fun closeWindow() {
        val p = if (pitches.size >= 5) pitches.sorted()[pitches.size / 2] else Double.NaN
        val b = DoubleArray(BANDS) { bandSum[it] / frames }
        // 大きさの違いを消す（形だけを比べる）
        val mean = b.average()
        for (i in 0 until BANDS) b[i] -= mean
        val start = windowStartMs
        val pause = pauseBeforeWindowMs
        resetWindow()

        if (profile.isNotEmpty()) {
            val pp = profile.map { it.first }.filter { !it.isNaN() }
            val profPitch = if (pp.isEmpty()) Double.NaN else pp.average()
            val profBands = DoubleArray(BANDS) { i -> profile.sumOf { it.second[i] } / profile.size }
            val dPitch = if (p.isNaN() || profPitch.isNaN()) 0.0 else abs(p - profPitch) / 3.0 // 半音 3 つで 1
            var dSpec = 0.0
            for (i in 0 until BANDS) dSpec += (b[i] - profBands[i]).let { it * it }
            val score = dPitch + sqrt(dSpec / BANDS)

            val ready = scores.size >= MIN_HISTORY
            val m = if (ready) scores.average() else 0.0
            val sd = if (ready) sqrt(scores.sumOf { (it - m) * (it - m) } / scores.size).coerceAtLeast(1e-6) else 1.0
            val z = (score - m) / sd
            val changed = ready && z > Z_THRESHOLD

            if (changed && pause >= MIN_PAUSE_MS && pending == null) {
                // 1 回だけ大きく外れたのでなく、次のまとまりも新しい声に近ければ「変わった」
                pending = start
                profile.clear()
            } else {
                pending?.let { t ->
                    if (z < 1.0) onChange(t)
                    pending = null
                }
                scores.addLast(score)
                if (scores.size > 60) scores.removeFirst()
            }
        }
        profile.addLast(p to b)
        if (profile.size > 5) profile.removeFirst()
    }

    private fun resetWindow() {
        pitches.clear()
        bandSum.fill(0.0)
        frames = 0
    }

    /** 声の高さ（半音。A0 = 0）。自己相関で 80〜400Hz を探す */
    private fun pitch(pcm: ShortArray, n: Int): Double? {
        val len = minOf(n, 1024)
        if (len < 512) return null
        var best = 0.0
        var bestLag = -1
        var energy = 0.0
        for (i in 0 until len) energy += pcm[i].toDouble() * pcm[i]
        if (energy <= 0) return null
        for (lag in SAMPLE_RATE / 400..SAMPLE_RATE / 80) {
            var sum = 0.0
            var i = 0
            while (i + lag < len) {
                sum += pcm[i].toDouble() * pcm[i + lag]
                i += 2 // 半分だけ見る（軽くするため）
            }
            if (sum > best) {
                best = sum
                bestLag = lag
            }
        }
        if (bestLag < 0 || best * 2 / energy < 0.3) return null // 声らしい周期がない
        val f0 = SAMPLE_RATE.toDouble() / bestLag
        return 12 * ln(f0 / 27.5) / ln(2.0)
    }

    /** 周波数ごとの強さ（対数）を 16 本にまとめる（〜4kHz） */
    private fun bands(pcm: ShortArray, n: Int): DoubleArray? {
        if (n < FFT_N) return null
        for (i in 0 until FFT_N) {
            re[i] = pcm[i] * win[i]
            im[i] = 0.0
        }
        fft(re, im)
        val out = DoubleArray(BANDS)
        val maxBin = FFT_N / 4 // 4kHz
        for (bnd in 0 until BANDS) {
            // 低い方を細かく（だいたい対数の間隔）
            val lo = (2 + (maxBin - 2) * Math.pow(bnd.toDouble() / BANDS, 1.6)).toInt()
            val hi = (2 + (maxBin - 2) * Math.pow((bnd + 1.0) / BANDS, 1.6)).toInt().coerceAtLeast(lo + 1)
            var e = 0.0
            for (k in lo until hi) e += re[k] * re[k] + im[k] * im[k]
            out[bnd] = ln(e / (hi - lo) + 1.0)
        }
        return out
    }

    private fun fft(x: DoubleArray, y: DoubleArray) {
        val n = x.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = x[i]; x[i] = x[j]; x[j] = t
                t = y[i]; y[i] = y[j]; y[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = x[i + k]
                    val ui = y[i + k]
                    val vr = x[i + k + len / 2] * cr - y[i + k + len / 2] * ci
                    val vi = x[i + k + len / 2] * ci + y[i + k + len / 2] * cr
                    x[i + k] = ur + vr; y[i + k] = ui + vi
                    x[i + k + len / 2] = ur - vr; y[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

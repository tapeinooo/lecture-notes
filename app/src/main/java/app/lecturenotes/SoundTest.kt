package app.lecturenotes

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * 音のテスト。会場で席に着いたら、短く録って
 *  - 音の大きさ（良好・小さい・割れている）
 *  - 端末内・Groq・Gemini の文字起こしの結果
 * を見比べる。マイクの取り方を4種類試して、いちばん文字になったものを選ぶこともできる。
 */
object SoundTest {
    data class Rec(
        val file: File,
        val voicedAvg: Double,
        val peak: Int,
        val clipPct: Double,
        val voicedPct: Double,
        val localText: String,
        val source: MicSource,
    )

    fun verdict(r: Rec): String = when {
        r.voicedPct < 15 -> "🤫 声がほとんど入っていません（話している間に試してください）"
        r.clipPct > 0.5 -> "📢 大きすぎて割れています（スピーカーから少し離れてください）"
        r.voicedAvg < 700 -> "🔈 小さめです（スピーカーに近づくか、本体の下の端をスピーカーへ向けてください）"
        else -> "✅ 良好です"
    }

    /** seconds 秒録る（設定の「自動で大きく」も同じようにかける） */
    suspend fun record(
        context: Context, source: MicSource, seconds: Int, withLocal: Boolean,
        onProgress: (Float) -> Unit,
    ): Rec = withContext(Dispatchers.IO) {
        val st = Settings(context)
        val (rec, used) = AudioInput.open(source) ?: throw IllegalStateException("マイクを使えません（マイクの権限を確認してください）")
        val file = File(context.cacheDir, "soundtest_${used.key}.m4a").apply { delete() }
        val writer = AacWriter(file)
        val gainer = AutoGain()
        val local = if (withLocal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sb = StringBuilder()
            var partial = ""
            val lr = LocalRecognizer(context, onFinal = { t -> synchronized(sb) { sb.append(t).append(' ') } }, onPartial = { partial = it }, onError = {})
            lr.start()
            Triple(lr, sb) { partial }
        } else null
        val buf = ShortArray(SAMPLE_RATE / 10)
        var frames = 0
        var voicedFrames = 0
        var voicedSum = 0.0
        var peak = 0
        var clip = 0L
        var total = 0L
        val recent = ArrayDeque<Double>()
        var rawAvg = 0.0
        try {
            rec.startRecording()
            val need = seconds * 10
            while (frames < need) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val raw = AudioInput.rms(buf, n)
                rawAvg = if (rawAvg == 0.0) raw else rawAvg * 0.95 + raw * 0.05
                if (st.autoGain) gainer.process(buf, n, raw, isVoice = raw > maxOf(150.0, rawAvg * 0.8))
                writer.write(buf, n)
                local?.first?.feed(buf, n)
                val r = AudioInput.rms(buf, n)
                recent.addLast(r)
                if (recent.size > 50) recent.removeFirst()
                val median = recent.sorted()[recent.size / 2]
                if (r > maxOf(300.0, median * 0.5)) {
                    voicedFrames++
                    voicedSum += r
                }
                for (i in 0 until n) {
                    val a = abs(buf[i].toInt())
                    if (a > peak) peak = a
                    if (a >= 32000) clip++
                }
                total += n
                frames++
                onProgress(frames.toFloat() / need)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            runCatching { writer.close() }
        }
        var localText = ""
        if (local != null) {
            delay(1500) // 最後の文字が確定するのを少し待つ
            local.first.stop()
            localText = (synchronized(local.second) { local.second.toString() } + " " + local.third()).trim()
        }
        Rec(
            file = file,
            voicedAvg = if (voicedFrames > 0) voicedSum / voicedFrames else 0.0,
            peak = peak,
            clipPct = if (total > 0) clip * 100.0 / total else 0.0,
            voicedPct = voicedFrames * 100.0 / frames.coerceAtLeast(1),
            localText = localText,
            source = used,
        )
    }

    private const val GEMINI_PROMPT =
        "これは日本語の講演の録音の一部です。話された内容を、そのまま正確に書き起こしてください。句読点を付け、出力は本文だけにしてください。"

    /** provider（groq / gemini）の、いま使える最初のモデルで文字にする */
    suspend fun transcribe(context: Context, provider: String, file: File): String = withContext(Dispatchers.IO) {
        val st = Settings(context)
        val c = Catalog.candidates(context, st, Role.STT).firstOrNull { it.provider == provider && Cooldown.ok(it.key) }
            ?: return@withContext "（使えるモデルがありません）"
        try {
            val text = when (provider) {
                "groq" -> Groq.transcribe(st.groqKey, c.model, file, Glossary.whisperPrompt(Glossary.load(context)), 40_000)
                "cloudflare" -> Cloudflare.transcribe(st.cfAccountId, st.cfToken, file, Glossary.whisperPrompt(Glossary.load(context)), 60_000)
                else -> Gemini.transcribe(st.geminiKey, c.model, file, GEMINI_PROMPT, 60_000)
            }
            Usage.add(c.key, 10.0)
            text.trim().ifEmpty { "（文字になりませんでした）" }
        } catch (e: Exception) {
            "（失敗：${e.message?.take(80)}）"
        }
    }

    /** 文字の多さ（比べる目安） */
    fun score(text: String) = if (text.startsWith("（")) 0 else text.count { !it.isWhitespace() }
}

@Composable
fun SoundTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by Live.state.collectAsState()
    val st = remember { Settings(context) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<SoundTest.Rec?>(null) }
    var texts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var compare by remember { mutableStateOf<List<Triple<MicSource, SoundTest.Rec, String>>>(emptyList()) }
    var chosen by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val recording = live.sessionId != null

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text("音のテスト", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(
                "話が始まって声が聞こえているときに試してください。いまのマイクの取り方：${MicSource.of(st.micSource).label}" +
                    (if (st.autoGain) "・自動で大きく：オン" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (recording) {
                Text("録音中はテストできません（マイクを使っているため）", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            busy?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)

            // ---- 15秒テスト
            Spacer(Modifier.height(12.dp))
            Text("① 15秒テスト", style = MaterialTheme.typography.titleMedium)
            Text("音の大きさを確かめ、端末内・Groq・Gemini の文字起こしを並べます。", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    scope.launch {
                        error = ""; result = null; texts = emptyList()
                        try {
                            busy = "録っています（15秒）…"
                            val r = SoundTest.record(context, MicSource.of(st.micSource), 15, withLocal = true) { progress = it }
                            result = r
                            busy = "文字にしています…"; progress = 1f
                            val g = SoundTest.transcribe(context, "groq", r.file)
                            val m = SoundTest.transcribe(context, "gemini", r.file)
                            val cf = if (st.cfToken.isNotBlank() && st.cfAccountId.isNotBlank()) SoundTest.transcribe(context, "cloudflare", r.file) else null
                            texts = listOfNotNull(
                                "端末内" to r.localText.ifEmpty { "（文字になりませんでした）" }, "Groq" to g, "Gemini" to m,
                                cf?.let { "Cloudflare" to it },
                            )
                        } catch (e: Exception) {
                            error = e.message ?: "失敗しました"
                        }
                        busy = null
                    }
                },
                enabled = busy == null && !recording,
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("🎤 15秒テストを始める") }
            result?.let { r ->
                ResultBox {
                    Text(SoundTest.verdict(r), fontWeight = FontWeight.Bold)
                    Text(
                        "声の大きさ %.0f・いちばん大きい音 %d・割れ %.2f%%・声のある時間 %.0f%%".format(r.voicedAvg, r.peak, r.clipPct, r.voicedPct),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            texts.forEach { (name, t) ->
                ResultBox {
                    Text("$name（${SoundTest.score(t)}字）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(t, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // ---- マイクの取り方を比べる
            Spacer(Modifier.height(16.dp))
            Text("② マイクの取り方を比べる", style = MaterialTheme.typography.titleMedium)
            val sources = remember { MicSource.available(context) }
            Text(
                "${sources.joinToString("・") { it.label }} の ${sources.size} 種類で 10 秒ずつ録り（約${sources.size * 10 + 10}秒）、いちばん文字になったものを自動で選びます。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        error = ""; compare = emptyList(); chosen = ""
                        try {
                            val provider = if (st.groqKey.isNotBlank()) "groq" else "gemini"
                            val out = ArrayList<Triple<MicSource, SoundTest.Rec, String>>()
                            sources.forEachIndexed { i, src ->
                                busy = "「${src.label}」で録っています（${i + 1}/${sources.size}）…"
                                val r = SoundTest.record(context, src, 10, withLocal = false) { progress = (i + it) / sources.size }
                                val t = SoundTest.transcribe(context, provider, r.file)
                                out.add(Triple(src, r, t))
                                compare = out.toList()
                            }
                            val best = out.filter { it.second.clipPct <= 0.5 }.maxByOrNull { SoundTest.score(it.third) }
                                ?: out.maxByOrNull { SoundTest.score(it.third) }
                            if (best != null && SoundTest.score(best.third) > 0) {
                                st.micSource = best.first.key
                                chosen = "「${best.first.label}」にしました（設定の「録音」で変えられます）"
                            } else {
                                chosen = "どれも文字になりませんでした。話している間に、もう一度試してください"
                            }
                        } catch (e: Exception) {
                            error = e.message ?: "失敗しました"
                        }
                        busy = null
                    }
                },
                enabled = busy == null && !recording,
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("🔍 比べて自動で選ぶ") }
            compare.forEach { (src, r, t) ->
                ResultBox {
                    Text(
                        "${src.label}（${SoundTest.score(t)}字）" + if (src.key == st.micSource && chosen.isNotEmpty()) "　← これにしました" else "",
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    )
                    Text(SoundTest.verdict(r), style = MaterialTheme.typography.labelSmall)
                    Text(t, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (chosen.isNotEmpty()) Text(chosen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ResultBox(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) { content() }
}

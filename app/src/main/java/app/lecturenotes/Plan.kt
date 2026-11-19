package app.lecturenotes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar

/**
 * 大会などのプログラムを貼り付けて、タイトルだけの空の記録（枠）をまとめて作る。
 * 「9:40 司会者の話：…」のような時刻付きの行を読み取り、曜日の見出し（金曜日 など）で日を進める。
 */
object Plan {
    /** sub = シンポジウムの小見出し（話）。parent = その親の位置 */
    data class Item(
        val day: Int, val minutes: Int?, val title: String, val skipByDefault: Boolean,
        val sub: Boolean = false, val parent: Int = -1,
    )

    private val TIME_ONLY = Regex("^\\s*(?:(午前|午後)\\s*)?(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*$")
    private val TIME_LINE = Regex("^\\s*(?:(午前|午後)\\s*)?(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*[　 ・\\-–—]*\\s*(.+)$")
    private val DAY = Regex("([月火水木金土日])曜日|第?\\s*[1-3１-３一二三]\\s*日目")

    /** 歌・祈り・音楽・休憩などは、最初は作らない */
    private fun looksLikeSkip(t: String): Boolean {
        val x = t.replace(Regex("\\s"), "")
        return x.startsWith("歌") || x.startsWith("音楽") || x.contains("休憩") || x.contains("昼休み") ||
            x == "祈り" || x.startsWith("祈り") || x.contains("発表") || x.contains("お知らせ") ||
            Regex("^\\d+番の歌").containsMatchIn(x)
    }

    fun parse(text: String): List<Item> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Item>()
        var day = -1
        var lastMin = -1
        var pendingTime: Int? = null
        var lastTimed: Item? = null
        fun toMin(ampm: String?, h: Int, m: Int): Int {
            var hh = h
            if (ampm == "午後" && hh < 12) hh += 12
            var min = hh * 60 + m
            // 同じ日の中で時刻が戻ったら午後とみなす（「1:35」→ 13:35）
            if (ampm == null && lastMin >= 0 && min < lastMin && hh < 12) min += 12 * 60
            return min
        }
        for (line in lines) {
            if (line.length <= 15 && DAY.containsMatchIn(line) && TIME_LINE.find(line) == null) {
                day++
                lastMin = -1
                continue
            }
            val only = TIME_ONLY.find(line)
            if (only != null) {
                val t = toMin(only.groupValues[1].ifEmpty { null }, only.groupValues[2].toInt(), only.groupValues[3].toInt())
                pendingTime = t
                lastMin = t
                continue
            }
            val m = TIME_LINE.find(line)
            if (m != null) {
                val min = toMin(m.groupValues[1].ifEmpty { null }, m.groupValues[2].toInt(), m.groupValues[3].toInt())
                lastMin = min
                val t = m.groupValues[4].trim()
                val item = Item(day.coerceAtLeast(0), min, t, looksLikeSkip(t))
                out.add(item)
                lastTimed = item
                pendingTime = null
            } else if (pendingTime != null) {
                // 時刻だけの行の次の行がタイトル
                val item = Item(day.coerceAtLeast(0), pendingTime, line, looksLikeSkip(line))
                out.add(item)
                lastTimed = item
                pendingTime = null
            } else if (out.isNotEmpty() && line.first() in "•・●◦‐-–—" && line.length in 3..60) {
                // シンポジウムの各部分（「• 祈りによって」）も、それぞれ枠にする（親と同じ時刻の少しあと）
                // 親（シンポジウム）は、最後の時刻付きの項目
                val parent = lastTimed ?: out.last()
                val theme = parent.title.substringAfter(':').substringAfter('：').trim()
                val t = line.trimStart('•', '・', '●', '◦', '‐', '-', '–', '—', ' ', '　')
                out.add(Item(parent.day, parent.minutes, "$t（$theme）", false, sub = true, parent = out.indexOf(parent)))
            }
        }
        // シンポジウム：題だけの枠は作らず、小見出しの話に、次の項目までの時間を等分して割り当てる
        if (out.any { it.sub }) {
            val result = ArrayList<Item>()
            out.forEachIndexed { i, it ->
                if (it.sub) return@forEachIndexed
                val subs = out.filter { s -> s.sub && s.parent == i }
                if (subs.isEmpty()) {
                    result.add(it)
                    return@forEachIndexed
                }
                val start = it.minutes
                // 次の時刻付きの項目（同じ日）までの長さ。なければ 1 つ 15 分
                val next = out.drop(i + 1).firstOrNull { n -> !n.sub && n.minutes != null && n.day == it.day }?.minutes
                val total = if (start != null && next != null && next > start) next - start else 15 * subs.size
                subs.forEachIndexed { k, sb ->
                    result.add(sb.copy(minutes = start?.plus(total * k / subs.size)))
                }
            }
            out.clear()
            out.addAll(result)
        }
        // 時刻が1つもなければ、1行1つの枠にする
        if (out.isEmpty()) {
            lines.filter { !(it.length <= 15 && DAY.containsMatchIn(it)) }.forEach { out.add(Item(0, null, it, looksLikeSkip(it))) }
        }
        return out
    }

    /** 枠を作る。firstDay は最初の日の 0 時 */
    fun create(items: List<Item>, firstDay: Long, labels: List<String>): Int {
        var n = 0
        items.forEachIndexed { i, it ->
            val cal = Calendar.getInstance().apply {
                timeInMillis = firstDay
                add(Calendar.DAY_OF_MONTH, it.day)
                add(Calendar.MINUTE, it.minutes ?: (9 * 60 + i))
            }
            // タイトルは「10:00 タイトル」
            val title = it.minutes?.let { m -> "%d:%02d %s".format(m / 60, m % 60, it.title) } ?: it.title
            Repo.createPlanned(title.take(90), cal.timeInMillis, labels)
            n++
        }
        return n
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanScreen(onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    val allLabels by Meta.labels.collectAsState()
    var text by remember { mutableStateOf("") }
    // 最初の日（初めは明後日）
    var firstDay by remember {
        mutableStateOf(Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, 2)
        }.timeInMillis)
    }
    var labels by remember { mutableStateOf(listOf("大会")) }
    val items = remember(text) { Plan.parse(text) }
    var checked by remember(items) { mutableStateOf(items.map { !it.skipByDefault }) }
    var done by remember { mutableStateOf("") }

    fun dayLabel(d: Int) = fmtDay(firstDay + d * 86_400_000L)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text("予定から枠を作る", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                Text("最初の日", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { firstDay -= 86_400_000L }) { Text("◀") }
                    Text(fmtDay(firstDay), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { firstDay += 86_400_000L }) { Text("▶") }
                }
                Text("ラベル", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    allLabels.forEach { l ->
                        FilterChip(
                            selected = l in labels,
                            onClick = { labels = if (l in labels) labels - l else labels + l },
                            label = { Text(l) },
                        )
                    }
                }
                Text(
                    "プログラムのページを開き、本文をコピーして下に貼り付けてください。「9:40 …」のような時刻付きの行を読み取り、" +
                        "「金曜日」などの見出しで次の日に進めます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = { uri.openUri("https://wol.jw.org/ja/wol/h/r7/lp-j") }) { Text("wol.jw.org を開く ↗") }
                OutlinedTextField(
                    value = text, onValueChange = { text = it; done = "" },
                    placeholder = { Text("ここに貼り付け") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp),
                )
                if (items.isNotEmpty()) {
                    Text(
                        "${items.size}件 読み取りました（✓ の付いたものを作ります。歌・祈り・休憩は最初は外しています）",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            itemsIndexed(items) { i, it ->
                val prevDay = items.getOrNull(i - 1)?.day
                if (prevDay != it.day) {
                    Text(dayLabel(it.day), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    HorizontalDivider()
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        checked = checked.toMutableList().also { l -> l[i] = !l[i] }
                    },
                ) {
                    Checkbox(checked = checked.getOrElse(i) { false }, onCheckedChange = null)
                    Text(
                        it.minutes?.let { m -> "%d:%02d".format(m / 60, m % 60) } ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(48.dp),
                    )
                    Text(it.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(done, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            val n = checked.count { it }
            Button(
                onClick = {
                    val pick = items.filterIndexed { i, _ -> checked.getOrElse(i) { false } }
                    val made = Plan.create(pick, firstDay, labels)
                    done = "${made}件の枠を作りました"
                    onBack()
                },
                enabled = n > 0,
            ) { Text("✓ ${n}件の枠を作る") }
        }
    }
}

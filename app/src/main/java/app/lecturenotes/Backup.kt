package app.lecturenotes

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Google ドライブへの自動の控え。
 * 受け口は自分の Google Apps Script（assets/drive_backup.gs）。そこへ文章・情報・音声を送る。
 *  - 文章（Markdown）と情報（session.json）：変わったら送る（録音中は10分ごと）
 *  - 音声：録音が終わったら、まだ送っていない分を約20MBずつ「音声_01.m4a」…として送る（設定で Wi-Fi のときだけ）
 */
object Backup {
    private const val PREF = "backup"
    private const val PART_MAX_BYTES = 20L * 1024 * 1024
    private const val TEXT_INTERVAL_MS = 10 * 60_000L

    /** 画面に出す、いまの状態 */
    val status = MutableStateFlow("")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 合言葉（なければ作る） */
    fun token(ctx: Context): String {
        val st = Settings(ctx)
        if (st.backupToken.isNotBlank()) return st.backupToken
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        val t = (1..16).map { chars[r.nextInt(chars.length)] }.joinToString("")
        st.backupToken = t
        return t
    }

    /** 合言葉を入れた受け口のスクリプト */
    fun script(ctx: Context): String =
        ctx.assets.open("drive_backup.gs").bufferedReader().use { it.readText() }.replace("@@TOKEN@@", token(ctx))

    private fun post(ctx: Context, body: JSONObject, timeoutMs: Int = 180_000): JSONObject {
        val url = Settings(ctx).backupUrl.trim()
        require(url.startsWith("https://")) { "受け口の URL が入っていません" }
        body.put("token", token(ctx))
        val resp = Http.post(url, emptyMap(), body.toString().toByteArray(), "application/json", timeoutMs)
        val j = runCatching { JSONObject(resp) }.getOrElse {
            throw IllegalStateException("受け口から思わぬ返事が来ました（デプロイの「アクセスできるユーザー」が「全員」か確かめてください）")
        }
        if (!j.optBoolean("ok")) throw IllegalStateException(j.optString("error", "失敗しました"))
        return j
    }

    /** つながるか試す */
    fun ping(ctx: Context): String = try {
        post(ctx, JSONObject().put("action", "ping"), 30_000)
        "つながりました。ドライブの「たっぴぃず録音」フォルダに保存します"
    } catch (e: Exception) {
        "つながりません：${e.message?.take(150)}"
    }

    private fun upload(ctx: Context, folder: String, name: String, mime: String, bytes: ByteArray) {
        post(
            ctx,
            JSONObject()
                .put("action", "save")
                .put("folder", folder)
                .put("name", name)
                .put("mime", mime)
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
        )
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60)

    /** ドライブでの記録のフォルダ名（最初に送ったときの名前を使い続ける） */
    private fun folderName(ctx: Context, s: Session): String {
        val p = prefs(ctx)
        p.getString("${s.id}.folder", null)?.let { return it }
        val name = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.JAPAN).format(Date(s.startedAt)) + " " + safe(s.title)
        p.edit().putString("${s.id}.folder", name).apply()
        return name
    }

    private fun textHash(s: Session) = (Export.markdown(s).hashCode() * 31 + s.toString().hashCode())

    private fun wifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** 文章も音声も送り終わっているか（一覧に ☁ を出す） */
    fun upToDate(ctx: Context, s: Session): Boolean {
        val st = Settings(ctx)
        if (!st.backupEnabled || st.backupUrl.isBlank()) return false
        val p = prefs(ctx)
        if (p.getInt("${s.id}.hash", 0) != textHash(s)) return false
        if (st.backupAudio && !s.recording) {
            val last = s.segments.lastOrNull()?.index ?: return true
            if (p.getInt("${s.id}.audioUpTo", 0) < last) return false
        }
        return true
    }

    /**
     * 1回分の仕事（Processor が暇なときに呼ぶ）。送ったら true。
     * 失敗したら10分休む。
     */
    fun step(ctx: Context): Boolean {
        val st = Settings(ctx)
        if (!st.backupEnabled || st.backupUrl.isBlank() || !Net.online(ctx)) return false
        if (!Cooldown.ok("backup")) return false
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        try {
            // 文章と情報
            for (s in Repo.sessions.value) {
                val h = textHash(s)
                if (p.getInt("${s.id}.hash", 0) == h) continue
                if (s.recording && now - p.getLong("${s.id}.textAt", 0) < TEXT_INTERVAL_MS) continue
                val folder = folderName(ctx, s)
                Processor.activity.value = "ドライブに控え中"
                status.value = "「${s.title}」の文章を送っています"
                upload(ctx, folder, "文章.md", "text/markdown", Export.markdown(s).toByteArray())
                val json = File(Repo.dir(s.id), "session.json")
                if (json.exists()) upload(ctx, folder, "session.json", "application/json", json.readBytes())
                p.edit().putInt("${s.id}.hash", h).putLong("${s.id}.textAt", now).apply()
                status.value = "「${s.title}」の文章を送りました（${fmtDate(now)}）"
                return true
            }
            // 音声（録音が終わったものだけ）
            if (st.backupAudio && (!st.backupAudioWifiOnly || wifi(ctx))) {
                for (s in Repo.sessions.value) {
                    if (s.recording) continue
                    val upTo = p.getInt("${s.id}.audioUpTo", 0)
                    val todo = s.segments.filter { it.index > upTo }
                    if (todo.isEmpty()) continue
                    // まだ WAV のものがあれば、先に小さくするのを待つ
                    if (todo.any { it.audio.endsWith(".wav") && Repo.audioFile(s.id, it).exists() }) continue
                    val part = ArrayList<Segment>()
                    var bytes = 0L
                    for (g in todo) {
                        val f = Repo.audioFile(s.id, g)
                        val len = if (f.exists()) f.length() else 0
                        if (part.isNotEmpty() && bytes + len > PART_MAX_BYTES) break
                        part.add(g)
                        bytes += len
                    }
                    val files = part.map { Repo.audioFile(s.id, it) }.filter { it.exists() }
                    val partNo = p.getInt("${s.id}.parts", 0) + 1
                    val folder = folderName(ctx, s)
                    Processor.activity.value = "ドライブに控え中"
                    status.value = "「${s.title}」の音声（${partNo}）を送っています"
                    if (files.isNotEmpty()) {
                        val tmp = File(ctx.cacheDir, "backup_part.m4a")
                        Audio.concatM4a(files, tmp)
                        upload(ctx, folder, "音声_%02d.m4a".format(partNo), "audio/mp4", tmp.readBytes())
                        tmp.delete()
                    }
                    p.edit().putInt("${s.id}.audioUpTo", part.last().index).putInt("${s.id}.parts", partNo).apply()
                    status.value = "「${s.title}」の音声（${partNo}）を送りました"
                    return true
                }
            }
        } catch (e: Exception) {
            Cooldown.set("backup", 10 * 60_000L, "ドライブ: ${e.message}")
            status.value = "ドライブに送れません（10分後にもう一度）：${e.message?.take(120)}"
        }
        return false
    }

    /** 「今すぐ控える」：休みを解いて、全部送り直しの印を付ける */
    fun forceAll(ctx: Context) {
        Cooldown.set("backup", 0, "")
        val e = prefs(ctx).edit()
        Repo.sessions.value.forEach { e.remove("${it.id}.hash").remove("${it.id}.textAt") }
        e.apply()
        Processor.wake()
    }
}

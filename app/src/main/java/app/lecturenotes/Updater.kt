package app.lecturenotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub の Releases から新しい版を取ってきてインストールする */
object Updater {
    const val REPO = "tapeinooo/lecture-notes"

    data class Release(val code: Int, val tag: String, val apkUrl: String, val notes: String)

    /** 新しい版があれば入る */
    val available = MutableStateFlow<Release?>(null)

    fun latest(): Release? {
        val j = JSONObject(Http.get("https://api.github.com/repos/$REPO/releases/latest", mapOf("Accept" to "application/vnd.github+json")))
        val tag = j.getString("tag_name")
        val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
        val assets = j.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
        return Release(code, tag, apk.getString("browser_download_url"), j.optString("body"))
    }

    /** 確かめて、新しければ available に入れる。戻り値は画面に出す文 */
    fun check(): String = try {
        val r = latest()
        if (r != null && r.code > BuildConfig.VERSION_CODE) {
            available.value = r
            "新しい版 ${r.tag} があります"
        } else {
            available.value = null
            "最新です（${BuildConfig.VERSION_NAME}）"
        }
    } catch (e: Exception) {
        "確認できませんでした: ${e.message?.take(100)}"
    }

    fun download(context: Context, r: Release, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "lecture-notes-${r.tag}.apk")
        var url = URL(r.apkUrl)
        var c: HttpURLConnection
        // github.com → objects.githubusercontent.com への転送をたどる
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "lecture-notes-app")
            val code = c.responseCode
            if (code in 300..399 && hops < 5) {
                url = URL(url, c.getHeaderField("Location"))
                c.disconnect()
                hops++
                continue
            }
            if (code !in 200..299) throw HttpError(code, "ダウンロードできません")
            break
        }
        val total = c.contentLengthLong
        c.inputStream.use { i ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n <= 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        c.disconnect()
        return out
    }

    /** false = 「不明なアプリのインストール」の許可画面を開いた */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}

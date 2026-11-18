package app.lecturenotes

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** 制限にかかった（しばらく待てば使える） */
class RateLimited(val waitMs: Long, message: String) : Exception(message)

class HttpError(val code: Int, message: String) : Exception("HTTP $code: $message")

object Http {
    private const val UA = "lecture-notes-app"

    fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String, timeoutMs: Int = 120_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20_000
            c.readTimeout = timeoutMs
            c.setRequestProperty("Content-Type", contentType)
            c.setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body) }
            return read(c)
        } finally {
            c.disconnect()
        }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            return read(c)
        } finally {
            c.disconnect()
        }
    }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code == 429 || code == 503 || code == 502 || code == 500) {
            throw RateLimited(waitFrom(c, text, code), "HTTP $code ${text.take(200)}")
        }
        if (code !in 200..299) throw HttpError(code, text.take(300))
        return text
    }

    private fun waitFrom(c: HttpURLConnection, body: String, code: Int): Long {
        if (code != 429) return 30_000
        c.getHeaderField("retry-after")?.trim()?.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(5_000) }
        // 無料枠では使えないモデル
        if (Regex("limit:\\s*0\\b").containsMatchIn(body)) return 24 * 3600_000L
        // 1日の上限：Gemini は太平洋時間の0時に戻る
        if (body.contains("PerDay", ignoreCase = true) || body.contains("per day", ignoreCase = true)) return msUntilPacificMidnight()
        // Gemini: "retryDelay": "36s"
        Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)").find(body)?.let { return it.groupValues[1].toLong() * 1000 + 1000 }
        return 60_000
    }

    private fun msUntilPacificMidnight(): Long {
        val zone = java.time.ZoneId.of("America/Los_Angeles")
        val now = java.time.ZonedDateTime.now(zone)
        val next = now.toLocalDate().plusDays(1).atStartOfDay(zone)
        return java.time.Duration.between(now, next).toMillis() + 60_000
    }
}

/** 制限にかかったサービス・モデルを、しばらく使わないようにする */
object Cooldown {
    private val until = ConcurrentHashMap<String, Long>()
    val reasons = ConcurrentHashMap<String, String>()

    fun ok(key: String) = (until[key] ?: 0L) <= System.currentTimeMillis()
    fun set(key: String, ms: Long, reason: String) {
        until[key] = System.currentTimeMillis() + ms
        reasons[key] = reason
    }

    fun clear() {
        until.clear()
        reasons.clear()
    }

    fun remainingMs(key: String) = ((until[key] ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0)
}

object Net {
    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

object Groq {
    private const val BASE = "https://api.groq.com/openai/v1"

    fun sttKey(model: String) = "groq-stt:$model"
    fun chatKey(model: String) = "groq-chat:$model"

    fun transcribe(key: String, model: String, wav: File, prompt: String, timeoutMs: Int): String {
        val boundary = "----lecturenotes${System.nanoTime()}"
        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", model)
        field("language", "ja")
        field("response_format", "json")
        field("temperature", "0")
        if (prompt.isNotBlank()) field("prompt", prompt)
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${wav.name}\"\r\nContent-Type: ${Audio.mime(wav)}\r\n\r\n".toByteArray())
        out.write(wav.readBytes())
        out.write("\r\n--$boundary--\r\n".toByteArray())
        val resp = Http.post(
            "$BASE/audio/transcriptions",
            mapOf("Authorization" to "Bearer $key"),
            out.toByteArray(),
            "multipart/form-data; boundary=$boundary",
            timeoutMs,
        )
        return JSONObject(resp).optString("text").trim()
    }

    fun chat(key: String, model: String, system: String, user: String, fast: Boolean, timeoutMs: Int): String {
        val body = JSONObject().apply {
            put("model", model)
            if (fast && model.contains("gpt-oss")) put("reasoning_effort", "low")
            put("temperature", 0.2)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }
        val resp = Http.post(
            "$BASE/chat/completions",
            mapOf("Authorization" to "Bearer $key"),
            body.toString().toByteArray(),
            "application/json",
            timeoutMs,
        )
        return JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }

    fun listModels(key: String): List<String> {
        val resp = Http.get("$BASE/models", mapOf("Authorization" to "Bearer $key"))
        val arr = JSONObject(resp).optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }.sorted()
    }
}

object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    fun cooldownKey(model: String) = "gemini:$model"

    /** 考える時間の指定を受け付けなかったモデル・minimal を受け付けなかったモデル */
    private val noThinkingCfg = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val noMinimal = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * 速さ優先のとき、考える時間をできるだけ短くする。
     *  - 2.5 系：thinkingBudget 0（考えない）
     *  - 3 以降：thinkingLevel "minimal"（受け付けないモデル、例 3.8 Flash は "low"）
     */
    private fun thinkingConfig(model: String, fast: Boolean): JSONObject? {
        if (!fast || model in noThinkingCfg) return null
        val m = model.lowercase()
        return when {
            m.startsWith("gemma") -> null
            m.startsWith("gemini-2.5") -> if (m.contains("pro")) null else JSONObject().put("thinkingBudget", 0)
            Regex("^gemini-([3-9]|\\d{2})").containsMatchIn(m) ->
                JSONObject().put("thinkingLevel", if (model in noMinimal || m.contains("pro")) "low" else "minimal")
            else -> null
        }
    }

    fun generate(
        key: String, model: String, system: String?, parts: JSONArray, json: Boolean,
        fast: Boolean = false, timeoutMs: Int = 180_000,
    ): String {
        var tries = 0
        while (true) {
            val cfg = thinkingConfig(model, fast)
            try {
                return generateOnce(key, model, system, parts, json, cfg, timeoutMs)
            } catch (e: HttpError) {
                val msg = e.message ?: ""
                val aboutThinking = msg.contains("thinking", ignoreCase = true) || msg.contains("minimal", ignoreCase = true)
                if (e.code != 400 || cfg == null || !aboutThinking || ++tries > 2) throw e
                if (cfg.optString("thinkingLevel") == "minimal") noMinimal.add(model) else noThinkingCfg.add(model)
            }
        }
    }

    private fun generateOnce(key: String, model: String, system: String?, parts: JSONArray, json: Boolean, thinking: JSONObject?, timeoutMs: Int): String {
        // Gemma は systemInstruction と JSON 指定を受け付けないので、本文の先頭に入れる
        val gemma = model.startsWith("gemma")
        val body = JSONObject().apply {
            if (system != null && !gemma) put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            val ps = if (system != null && gemma) {
                JSONArray().put(JSONObject().put("text", system)).also { a -> for (i in 0 until parts.length()) a.put(parts.get(i)) }
            } else parts
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", ps)))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                if (json && !gemma) put("responseMimeType", "application/json")
                if (thinking != null) put("thinkingConfig", thinking)
            })
        }
        val resp = Http.post(
            "$BASE/models/${URLEncoder.encode(model, "UTF-8")}:generateContent",
            mapOf("x-goog-api-key" to key),
            body.toString().toByteArray(),
            "application/json",
            timeoutMs = timeoutMs,
        )
        val j = JSONObject(resp)
        val cands = j.optJSONArray("candidates") ?: throw HttpError(200, "応答が空です: ${resp.take(200)}")
        if (cands.length() == 0) return ""
        val ps = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return ""
        return (0 until ps.length()).joinToString("") {
            val p = ps.getJSONObject(it)
            if (p.optBoolean("thought")) "" else p.optString("text")
        }
    }

    fun transcribe(key: String, model: String, audio: File, prompt: String, timeoutMs: Int): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "audio/wav")
                .put("data", Base64.encodeToString(Audio.toWavBytes(audio), Base64.NO_WRAP))))
        return generate(key, model, null, parts, json = false, fast = true, timeoutMs = timeoutMs).trim()
    }

    fun text(key: String, model: String, system: String, user: String, fast: Boolean, timeoutMs: Int): String =
        generate(key, model, system, JSONArray().put(JSONObject().put("text", user)), json = true, fast = fast, timeoutMs = timeoutMs)

    fun listModels(key: String): List<String> {
        val resp = Http.get("$BASE/models?pageSize=200", mapOf("x-goog-api-key" to key))
        val arr = JSONObject(resp).optJSONArray("models") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { m ->
                val methods = m.optJSONArray("supportedGenerationMethods")
                methods != null && (0 until methods.length()).any { methods.getString(it) == "generateContent" }
            }
            .map { it.optString("name").removePrefix("models/") }
            .filter { it.startsWith("gemini") || it.startsWith("gemma") }
    }
}

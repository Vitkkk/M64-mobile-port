package io.github.zenith.util

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** HTTP mínimo sobre HttpURLConnection (nada de bibliotecas além do SDK). */
object Http {
    private const val USER_AGENT = "ZenithLauncher/0.1 (Android; Horizon)"

    class HttpError(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(300)}")

    fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        request("GET", url, headers, null, null)

    fun getJson(url: String, headers: Map<String, String> = emptyMap()) = JSONObject(get(url, headers))

    fun postJson(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject =
        JSONObject(request("POST", url, headers + ("Accept" to "application/json"), "application/json", body.toString().toByteArray()))

    fun postForm(url: String, form: Map<String, String>): JSONObject {
        val body = form.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        return JSONObject(request("POST", url, mapOf("Accept" to "application/json"), "application/x-www-form-urlencoded", body.toByteArray()))
    }

    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        contentType: String?,
        body: ByteArray?,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Multipart com um arquivo (envio de skin para a conta). */
    fun postMultipart(url: String, headers: Map<String, String>, fields: Map<String, String>, fileField: String, file: File, fileType: String): String {
        val boundary = "----zenith" + System.nanoTime()
        val out = java.io.ByteArrayOutputStream()
        fun line(s: String) = out.write((s + "\r\n").toByteArray())
        fields.forEach { (k, v) ->
            line("--$boundary"); line("Content-Disposition: form-data; name=\"$k\""); line(""); line(v)
        }
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"")
        line("Content-Type: $fileType"); line("")
        out.write(file.readBytes()); line("")
        line("--$boundary--")
        return request("POST", url, headers, "multipart/form-data; boundary=$boundary", out.toByteArray())
    }

    /**
     * Baixa para [dest] conferindo tamanho e SHA-256 quando dados. [progress]
     * recebe a fração baixada (0..1) ou -1 quando o tamanho é desconhecido.
     */
    fun download(url: String, dest: File, expectedSize: Long? = null, sha256: String? = null, progress: (Float) -> Unit = {}) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode !in 200..299) throw HttpError(conn.responseCode, "")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize ?: -1
            if (expectedSize != null && expectedSize > 64L * 1024 * 1024) throw IOException("arquivo grande demais")
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        progress(if (total > 0) read.toFloat() / total else -1f)
                    }
                }
            }
            if (expectedSize != null && read != expectedSize) {
                tmp.delete(); throw IOException("tamanho diferente do índice ($read ≠ $expectedSize)")
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha256 != null && !hex.equals(sha256, ignoreCase = true)) {
                tmp.delete(); throw IOException("SHA-256 não confere com o índice")
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true); tmp.delete()
            }
        } finally {
            conn.disconnect()
        }
    }
}

package io.github.zenith.install

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Baixa uma lista de arquivos em paralelo, cada um conferido pelo hash que o manifesto dá
 * (SHA-1 nos arquivos da Mojang, SHA-256 no runtime Java). Um arquivo que já está no lugar
 * com o tamanho e o hash certos não é baixado de novo, então uma instalação interrompida
 * continua de onde parou. Cada arquivo tem algumas tentativas antes de desistir.
 */
class Downloader(
    private val threads: Int = 6,
    private val attempts: Int = 4,
    private val userAgent: String = "ZenithLauncher/0.2 (Android; Horizon)",
) {
    data class Item(
        val url: String,
        val file: File,
        val hash: String?,
        val size: Long?,
        val algorithm: String = "SHA-1",
    )

    data class Progress(
        val files: Int,
        val filesTotal: Int,
        val bytes: Long,
        val bytesTotal: Long,
        /** bytes por segundo, média dos últimos instantes */
        val speed: Long,
        val current: String,
    )

    class Cancelled : IOException("Cancelado")

    /** Baixa [items]; [progress] é chamado de várias threads, no máximo a cada ~200 ms. */
    fun run(items: List<Item>, cancelled: () -> Boolean = { false }, progress: (Progress) -> Unit = {}) {
        val unique = items.distinctBy { it.file.canonicalPath }
        val total = unique.sumOf { it.size ?: 0L }
        val bytes = AtomicLong(0)
        val files = AtomicInteger(0)
        val speed = SpeedMeter()
        var current = ""
        var lastReport = 0L

        fun report(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastReport < 200) return
            lastReport = now
            progress(Progress(files.get(), unique.size, bytes.get(), total, speed.rate(bytes.get()), current))
        }

        // Os que já estão certos no disco contam como baixados.
        val todo = unique.filter { item ->
            if (isValid(item)) {
                bytes.addAndGet(item.size ?: item.file.length()); files.incrementAndGet(); false
            } else true
        }
        report(force = true)

        val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
        try {
            val futures: List<Future<*>> = todo.map { item ->
                pool.submit {
                    if (cancelled()) throw Cancelled()
                    current = item.file.name
                    fetchWithRetry(item, cancelled) { n -> bytes.addAndGet(n); report() }
                    files.incrementAndGet()
                    report()
                }
            }
            var failure: Throwable? = null
            for (f in futures) {
                try {
                    f.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    if (failure == null) failure = e.cause
                    if (e.cause is Cancelled) break
                }
            }
            failure?.let { throw it }
        } finally {
            pool.shutdownNow()
        }
        report(force = true)
    }

    fun isValid(item: Item): Boolean {
        val f = item.file
        if (!f.isFile) return false
        if (item.size != null && f.length() != item.size) return false
        return item.hash == null || hashOf(f, item.algorithm).equals(item.hash, ignoreCase = true)
    }

    private fun fetchWithRetry(item: Item, cancelled: () -> Boolean, counted: (Long) -> Unit) {
        var last: IOException? = null
        repeat(attempts) { attempt ->
            if (cancelled()) throw Cancelled()
            var got = 0L
            try {
                fetch(item, cancelled) { n -> got += n; counted(n) }
                return
            } catch (e: Cancelled) {
                throw e
            } catch (e: IOException) {
                last = e
                counted(-got) // o que veio desta tentativa não conta
                if (e is HashMismatch) return@repeat
                Thread.sleep(1000L shl attempt)
            }
        }
        throw IOException("${item.file.name}: ${last?.message ?: "falhou"}", last)
    }

    class HashMismatch(message: String) : IOException(message)

    private fun fetch(item: Item, cancelled: () -> Boolean, counted: (Long) -> Unit) {
        val conn = URL(item.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", userAgent)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code em ${item.url}")
            item.file.parentFile?.mkdirs()
            val part = File(item.file.parentFile, item.file.name + ".part")
            val digest = MessageDigest.getInstance(item.algorithm)
            var read = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = try { input.read(buf) } catch (e: InterruptedIOException) { throw IOException("tempo esgotado", e) }
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        counted(n.toLong())
                        if (item.size != null && read > item.size) throw IOException("maior que o esperado")
                    }
                }
            }
            if (item.size != null && read != item.size) {
                part.delete(); throw IOException("tamanho errado ($read de ${item.size} bytes)")
            }
            val hex = digest.digest().toHex()
            if (item.hash != null && !hex.equals(item.hash, ignoreCase = true)) {
                part.delete(); throw HashMismatch("${item.algorithm} não confere (esperado ${item.hash}, veio $hex)")
            }
            if (!part.renameTo(item.file)) {
                item.file.delete()
                if (!part.renameTo(item.file)) throw IOException("não deu para gravar ${item.file}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Velocidade pela janela dos últimos ~3 s. */
    private class SpeedMeter {
        private val samples = ArrayDeque<Pair<Long, Long>>()
        @Synchronized fun rate(bytes: Long): Long {
            val now = System.currentTimeMillis()
            samples.addLast(now to bytes)
            while (samples.size > 2 && now - samples.first().first > 3000) samples.removeFirst()
            val (t0, b0) = samples.first()
            val dt = now - t0
            return if (dt <= 0) 0 else (bytes - b0) * 1000 / dt
        }
    }

    companion object {
        fun hashOf(file: File, algorithm: String = "SHA-1"): String {
            val digest = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        /** Baixa um arquivo pequeno (um JSON) para a memória, conferindo o SHA-1 se dado. */
        fun fetchText(url: String, sha1: String? = null, userAgent: String = "ZenithLauncher/0.2 (Android; Horizon)"): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", userAgent)
            try {
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} em $url")
                val bytes = conn.inputStream.use { it.readBytes() }
                if (sha1 != null) {
                    val hex = MessageDigest.getInstance("SHA-1").digest(bytes).toHex()
                    if (!hex.equals(sha1, ignoreCase = true)) throw HashMismatch("SHA-1 de $url não confere")
                }
                return bytes.toString(Charsets.UTF_8)
            } finally {
                conn.disconnect()
            }
        }
    }
}

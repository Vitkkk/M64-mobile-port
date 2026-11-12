package io.github.zenith.install

import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * O OpenJDK 25 para Android que roda os exportadores do Horizon (código da Mojang, por isso
 * Java) durante a preparação. Só fica no celular enquanto prepara: depois é apagado.
 *
 * Builds públicos do projeto AngelAuraMC (GPLv2 + Classpath Exception), os mesmos que launchers
 * Java para Android usam; fixados pelo SHA-256, como o Starling faz. Este de arm64 traz o
 * jdk.compiler e o ct.sym, então os exportadores são compilados no celular (`javac --release 25`)
 * e o APK não precisa levar classes compiladas contra os jars da Mojang.
 */
object JavaRuntime {
    data class Runtime(val url: String, val sha256: String, val size: Long)

    private val ARM64 = Runtime(
        "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre25/jre25-android-arm64.tar.xz",
        "d3eb7afe2240c26728a1bb440502c5f18ac3883e932d202dd7f0c9bcbbce4c37",
        38_031_580,
    )
    private val X86_64 = Runtime(
        "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre25/jre25-android-x86_64.tar.xz",
        "7fca862ee1b2d5fe23cd9c9c3d9b7ad3c241947ad1a6cc9464ef2e674867105d",
        39_061_384,
    )

    fun forAbi(abi: String): Runtime? = when (abi) {
        "arm64-v8a" -> ARM64
        "x86_64" -> X86_64
        else -> null
    }

    private const val MARKER = ".zenith-runtime"

    fun installed(runtime: Runtime, dir: File): Boolean =
        File(dir, MARKER).takeIf { it.isFile }?.readText()?.trim() == runtime.sha256 &&
            File(dir, "lib/libjli.so").isFile

    /** Baixa (com [downloader], conferindo o SHA-256) e descompacta em [dir]. */
    fun install(runtime: Runtime, dir: File, downloader: Downloader, cancelled: () -> Boolean, progress: (Downloader.Progress) -> Unit) {
        if (installed(runtime, dir)) return
        val archive = File(dir.parentFile, dir.name + ".tar.xz")
        downloader.run(
            listOf(Downloader.Item(runtime.url, archive, runtime.sha256, runtime.size, "SHA-256")),
            cancelled, progress,
        )
        dir.deleteRecursively()
        dir.mkdirs()
        archive.inputStream().buffered(1 shl 16).use { raw ->
            XZInputStream(raw).use { xz -> Tar.extract(xz, dir) }
        }
        archive.delete()
        if (!File(dir, "lib/libjli.so").isFile) throw IOException("O runtime Java veio sem lib/libjli.so")
        File(dir, MARKER).writeText(runtime.sha256)
    }
}

/**
 * Leitor de tar (ustar, com o prefixo de nome), só o necessário para o runtime: arquivos e
 * pastas. Links simbólicos são ignorados (no runtime só existem em legal/). Nada sai de [dir].
 */
object Tar {
    fun extract(input: InputStream, dir: File) {
        val root = dir.canonicalFile
        val stream = BufferedInputStream(input, 1 shl 16)
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (!readFully(stream, header)) break
            if (header.all { it == 0.toByte() }) break
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val name = longName ?: run {
                val prefix = text(header, 345, 155)
                val base = text(header, 0, 100)
                if (prefix.isNotEmpty()) "$prefix/$base" else base
            }
            longName = null
            when (type) {
                'L' -> { longName = String(readBytes(stream, size), Charsets.UTF_8).trimEnd('\u0000') ; skipPadding(stream, size); continue }
                'x', 'g' -> { skip(stream, size); skipPadding(stream, size); continue }
            }
            val target = File(root, name.removePrefix("./")).canonicalFile
            if (!target.path.startsWith(root.path)) throw IOException("Entrada fora da pasta no tar: $name")
            when (type) {
                '5' -> target.mkdirs()
                '0', '\u0000', '7' -> {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        var left = size
                        val buf = ByteArray(1 shl 16)
                        while (left > 0) {
                            val n = stream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw EOFException("tar truncado em $name")
                            out.write(buf, 0, n)
                            left -= n
                        }
                    }
                    val mode = octal(header, 100, 8)
                    if ((mode and 0b001_001_001L) != 0L) target.setExecutable(true, false)
                    skipPadding(stream, size)
                }
                else -> { skip(stream, size); skipPadding(stream, size) } // links e outros: ignorados
            }
        }
    }

    private fun readFully(s: InputStream, b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) {
            val n = s.read(b, off, b.size - off)
            if (n < 0) { if (off == 0) return false; throw EOFException("cabeçalho tar truncado") }
            off += n
        }
        return true
    }

    private fun readBytes(s: InputStream, size: Long): ByteArray {
        val b = ByteArray(size.toInt())
        if (!readFully(s, b)) throw EOFException()
        return b
    }

    private fun skip(s: InputStream, size: Long) {
        var left = size
        while (left > 0) {
            val n = s.skip(left)
            if (n <= 0) { if (s.read() < 0) throw EOFException(); left-- } else left -= n
        }
    }

    private fun skipPadding(s: InputStream, size: Long) {
        val pad = (512 - size % 512) % 512
        skip(s, pad)
    }

    private fun text(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        // Tamanhos grandes podem vir em base-256 (bit alto ligado).
        if (b[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }
        val s = text(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }
}

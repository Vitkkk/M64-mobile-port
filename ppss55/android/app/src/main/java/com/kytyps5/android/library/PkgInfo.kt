package com.kytyps5.android.library

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PPSS55: reads the *plaintext* metadata of a PlayStation package (.pkg) so the library can show
 * what the file is. It never decrypts anything.
 *
 * Why .pkg cannot be played: the game body (eboot, modules, data in the PFS image) is encrypted with
 * keys bound to the purchase licence. The emulator core only loads decrypted dumps (folder, .exfat,
 * .zar), and PPSS55 does not ship or derive Sony keys, so a .pkg is listed with an explanation.
 *
 * PS4 layout ("\u007FCNT", big-endian; documented by the community, e.g. psdevwiki "PKG files"):
 * 0x40 content id (36 chars), 0x70 DRM type, 0x74 content type, 0x18 entry table offset,
 * 0x10 entry count; 32-byte entries {id, nameOff, flags1, flags2, offset, size, pad}, where
 * flags1 bit 31 marks an encrypted entry. param.sfo is entry 0x1000 and is usually plaintext.
 * PS5 packages use a different, undocumented container; for those (and anything unrecognised) the
 * content id is found by scanning the first bytes for its fixed pattern.
 */
data class PkgInfo(
    val platform: Platform,
    val contentId: String?,
    val titleId: String?,
    val title: String?,
    val drm: String?,
    val kind: String?,
) {
    enum class Platform(val label: String) { PS4("PS4"), PS5("PS5"), UNKNOWN("desconhecida") }

    /** Text for the "why not" dialog. */
    fun explanation(): String = buildString {
        append("Pacote ${platform.label}")
        kind?.let { append(" · $it") }
        contentId?.let { append("\nContent ID: $it") }
        drm?.let { append("\nDRM: $it") }
        append("\n\n")
        if (platform == Platform.PS4) {
            append("Este é um jogo de PS4. O núcleo do PPSS55 (KytyPS5) só executa jogos de PS5.\n\n")
        }
        append(
            "Arquivos .pkg guardam o jogo criptografado com chaves da Sony ligadas à sua licença. " +
                "O PPSS55 lê só os dados públicos do pacote (nome, ID) e não descriptografa o conteúdo.\n\n" +
                "Para jogar: faça o dump do jogo instalado no seu próprio console (as ferramentas de dump " +
                "geram a pasta já descriptografada, com eboot.bin e sce_sys/param.json) e copie a pasta, " +
                "ou uma imagem .exfat/.zar dela, para a pasta de jogos.",
        )
    }

    companion object {
        const val EXTENSION = "pkg"
        private const val PS4_MAGIC = 0x7F434E54 // "\x7FCNT"
        private const val HEAD_BYTES = 64 * 1024
        private const val ENTRY_PARAM_SFO = 0x1000
        private const val MAX_SFO_BYTES = 64 * 1024
        private const val MAX_ENTRIES = 4096

        /** Content id: XX0000-AAAA00000_00-16 alphanumerics (title id at chars 7..15). */
        private val CONTENT_ID = Regex("[A-Z]{2}[0-9]{4}-([A-Z]{4}[0-9]{5})_[0-9]{2}-[A-Z0-9]{16}")

        fun read(file: File): PkgInfo? = try {
            RandomAccessFile(file, "r").use { raf -> parse(RafAccess(raf), raf.length()) }
        } catch (e: IOException) {
            null
        }

        fun parse(bytes: ByteArray): PkgInfo? = parse(ByteArrayAccess(bytes), bytes.size.toLong())

        private fun parse(src: Access, length: Long): PkgInfo? {
            val head = ByteArray(minOf(length, HEAD_BYTES.toLong()).toInt())
            src.readAt(0, head)
            if (head.size < 4) return null
            val be = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
            if (be.getInt(0) == PS4_MAGIC && head.size >= 0x80) return parsePs4(src, length, be, head)

            // Not a PS4 package: identify by the content id inside the header.
            val text = String(head, Charsets.ISO_8859_1)
            val match = CONTENT_ID.find(text) ?: return if (head[0] == 0x7F.toByte()) {
                PkgInfo(Platform.UNKNOWN, null, null, null, null, null)
            } else {
                null
            }
            val titleId = match.groupValues[1]
            return PkgInfo(platformOf(titleId), match.value, titleId, null, null, null)
        }

        private fun parsePs4(src: Access, length: Long, be: ByteBuffer, head: ByteArray): PkgInfo {
            val contentId = String(head, 0x40, 36, Charsets.US_ASCII).trimEnd('\u0000').takeIf { CONTENT_ID.matches(it) }
            val titleId = contentId?.let { CONTENT_ID.matchEntire(it)?.groupValues?.get(1) }
            val drm = when (be.getInt(0x70)) {
                0 -> "nenhum"
                0xF -> "PS4 (licença)"
                0xD -> "PS Vita"
                else -> "0x%X".format(be.getInt(0x70))
            }
            val kind = when (be.getInt(0x74)) {
                0x1A -> "jogo/app"
                0x1B -> "conteúdo adicional"
                0x1C -> "DLC sem dados"
                0x1E -> "atualização (patch)"
                else -> null
            }
            val title = readPlainSfoTitle(src, length, be.getInt(0x18).toLong() and 0xFFFFFFFFL, be.getInt(0x10))
            // The title id says which console the content targets (PPSA = PS5 inside a CNT container).
            val platform = titleId?.let(::platformOf)?.takeIf { it != Platform.UNKNOWN } ?: Platform.PS4
            return PkgInfo(platform, contentId, titleId, title, drm, kind)
        }

        /** TITLE from param.sfo when that entry is stored unencrypted; null otherwise. */
        private fun readPlainSfoTitle(src: Access, length: Long, tableOffset: Long, entryCount: Int): String? {
            if (entryCount <= 0 || entryCount > MAX_ENTRIES || tableOffset <= 0 || tableOffset + entryCount * 32L > length) return null
            val table = ByteArray(entryCount * 32)
            src.readAt(tableOffset, table)
            val t = ByteBuffer.wrap(table).order(ByteOrder.BIG_ENDIAN)
            for (i in 0 until entryCount) {
                val base = i * 32
                if (t.getInt(base) != ENTRY_PARAM_SFO) continue
                val encrypted = t.getInt(base + 8) and Int.MIN_VALUE != 0
                val offset = t.getInt(base + 16).toLong() and 0xFFFFFFFFL
                val size = t.getInt(base + 20).toLong() and 0xFFFFFFFFL
                if (encrypted || size <= 0 || size > MAX_SFO_BYTES || offset + size > length) return null
                val sfo = ByteArray(size.toInt())
                src.readAt(offset, sfo)
                return runCatching { (ParamSfo.parse(sfo)?.get("TITLE") as? String)?.trim()?.ifEmpty { null } }.getOrNull()
            }
            return null
        }

        private fun platformOf(titleId: String): Platform = when {
            titleId.startsWith("PPSA") || titleId.startsWith("ECAS") -> Platform.PS5
            titleId.startsWith("CUSA") || titleId.startsWith("PLAS") -> Platform.PS4
            else -> Platform.UNKNOWN
        }
    }

    private interface Access {
        fun readAt(offset: Long, into: ByteArray)
    }

    private class ByteArrayAccess(private val bytes: ByteArray) : Access {
        override fun readAt(offset: Long, into: ByteArray) {
            System.arraycopy(bytes, offset.toInt(), into, 0, minOf(into.size, bytes.size - offset.toInt()))
        }
    }

    private class RafAccess(private val raf: RandomAccessFile) : Access {
        override fun readAt(offset: Long, into: ByteArray) {
            raf.seek(offset)
            raf.readFully(into, 0, minOf(into.size.toLong(), raf.length() - offset).toInt())
        }
    }
}

package io.github.m64port

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Guarda a ROM do usuário e a tabela de símbolos no armazenamento privado
 * do app (inacessível a outros apps, apagado ao desinstalar).
 */
class RomStore(private val context: Context) {
    private val romDir = File(context.filesDir, "rom")
    private val symsDir = File(context.filesDir, "syms")

    fun romFile(): File? = romDir.listFiles { f -> f.name.endsWith(".z64") }?.firstOrNull()

    fun versionId(): String? = romFile()?.name?.removePrefix("sm64.")?.removeSuffix(".z64")

    fun importRom(uri: Uri): NativeResult {
        // ROMs de N64 têm no máximo 64 MiB; evita carregar um arquivo enorme por engano.
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
                if (buffer.size() > MAX_ROM_SIZE) {
                    return NativeResult(false, "Arquivo grande demais para ser uma ROM de N64.", "")
                }
            }
            buffer.toByteArray()
        } ?: return NativeResult(false, "Não foi possível ler o arquivo.", "")
        val result = NativeBridge.validateRom(bytes)
        if (result.ok) {
            romDir.deleteRecursively()
            romDir.mkdirs()
            File(romDir, "sm64.${result.versionId}.z64").writeBytes(bytes)
        }
        return result
    }

    fun deleteRom() {
        romDir.deleteRecursively()
    }

    /** Tabela importada pelo usuário ou, se existir, a que veio no APK. */
    fun symsFile(versionId: String): File? {
        val name = "sm64.$versionId.syms.toml"
        val imported = File(symsDir, name)
        if (imported.exists()) return imported
        return try {
            context.assets.open("syms/$name").use { input ->
                symsDir.mkdirs()
                imported.outputStream().use { input.copyTo(it) }
            }
            imported
        } catch (e: java.io.IOException) {
            null
        }
    }

    fun importSyms(uri: Uri, versionId: String): Boolean {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        if (!String(bytes, Charsets.UTF_8).contains("[[section]]")) return false
        symsDir.mkdirs()
        File(symsDir, "sm64.$versionId.syms.toml").writeBytes(bytes)
        return true
    }

    companion object {
        private const val MAX_ROM_SIZE = 64 * 1024 * 1024
    }
}

package io.github.zenith.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.zenith.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Os recursos preparados: o que o `horizon-prepare` gera a partir da **sua** cópia
 * do Minecraft: Java Edition (texturas, modelos, dados, sons, tabelas do gerador
 * de mundo). O app não traz nem baixa nada disso.
 *
 * No PC: `horizon-prepare --prepare --output preparado --complete` (ou o script
 * tools/prepare-resources.sh deste repositório), depois zipar a pasta e importar
 * aqui. Uma preparação no próprio celular precisa de um Java 25 para Android
 * (roadmap).
 */
object GameResources {
    data class Status(
        val ready: Boolean,
        val version: String?,
        val outputVersion: Int?,
        val sizeBytes: Long,
        val message: String,
    )

    fun dir(): File = Paths.resources()

    fun status(): Status {
        val dir = dir()
        val manifest = File(dir, "horizon-resources.json")
        if (!manifest.isFile) {
            return Status(false, null, null, 0, "Nenhum recurso importado")
        }
        val stamp = File(dir, "horizon-prepare.json")
        val stampJson = runCatching { JSONObject(stamp.readText()) }.getOrNull()
        val minecraft = stampJson?.optString("minecraft")?.takeIf { it.isNotBlank() }
            ?: runCatching { JSONObject(manifest.readText()).optString("minecraft") }.getOrNull()
        val outputVersion = stampJson?.optInt("output_version")
        val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val ok = stamp.isFile && (minecraft == null || minecraft.isBlank() || minecraft == BuildConfig.GAME_VERSION)
        val message = when {
            !stamp.isFile -> "Preparação incompleta (falta horizon-prepare.json): prepare de novo no PC com --complete"
            !ok -> "Recursos de outra versão ($minecraft); este Horizon é para ${BuildConfig.GAME_VERSION}"
            !File(dir, "sounds").exists() && !File(dir, "assets/minecraft/sounds").exists() ->
                "Pronto (sem sons: use --complete no PC para incluir)"
            else -> "Pronto"
        }
        return Status(ok, minecraft, outputVersion, size, message)
    }

    /**
     * Importa um .zip com a pasta preparada. Aceita o zip da pasta em si ou de uma
     * pasta acima dela: o prefixo até `horizon-resources.json` é removido.
     */
    fun importZip(context: Context, uri: Uri, progress: (String, Float) -> Unit) {
        val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 } ?: -1L
        // 1ª passada: achar o prefixo.
        var prefix: String? = null
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name.endsWith("horizon-resources.json")) {
                        prefix = e.name.removeSuffix("horizon-resources.json"); break
                    }
                }
            }
        } ?: throw IOException("não deu para abrir o arquivo")
        val root = prefix ?: throw IOException("o zip não tem horizon-resources.json: não é uma pasta preparada pelo horizon-prepare")

        val staging = File(Paths.resourcesRoot, ".importing")
        staging.deleteRecursively()
        staging.mkdirs()
        var done = 0L
        val counting = context.contentResolver.openInputStream(uri) ?: throw IOException("não deu para abrir o arquivo")
        val counter = object : java.io.FilterInputStream(counting.buffered(1 shl 16)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) done += it }
        }
        ZipInputStream(counter).use { zip ->
            val buf = ByteArray(1 shl 16)
            var lastReport = 0L
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.name.startsWith(root)) continue
                val rel = e.name.removePrefix(root)
                if (rel.isEmpty() || rel.contains("..")) continue
                val out = File(staging, rel)
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                    }
                }
                if (done - lastReport > 1_000_000) {
                    lastReport = done
                    progress(rel, if (total > 0) done.toFloat() / total else -1f)
                }
            }
        }
        publish(staging)
    }

    /** Importa uma pasta escolhida pelo seletor do Android (mais lento que o zip). */
    fun importFolder(context: Context, tree: Uri, progress: (String, Float) -> Unit) {
        val doc = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("pasta inválida")
        if (doc.findFile("horizon-resources.json") == null) {
            throw IOException("a pasta não tem horizon-resources.json: escolha a pasta de saída do horizon-prepare")
        }
        val staging = File(Paths.resourcesRoot, ".importing")
        staging.deleteRecursively()
        staging.mkdirs()
        var count = 0
        fun copy(d: DocumentFile, into: File) {
            for (child in d.listFiles()) {
                val name = child.name ?: continue
                if (name.contains("/") || name == "..") continue
                if (child.isDirectory) {
                    copy(child, File(into, name).apply { mkdirs() })
                } else {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        File(into, name).outputStream().use { input.copyTo(it, 1 shl 16) }
                    }
                    if (++count % 50 == 0) progress(name, -1f)
                }
            }
        }
        copy(doc, staging)
        publish(staging)
    }

    /** Troca os recursos atuais por [staging] (que precisa ter horizon-resources.json). */
    fun publish(staging: File) {
        if (!File(staging, "horizon-resources.json").isFile) {
            staging.deleteRecursively()
            throw IOException("importação incompleta")
        }
        val target = dir()
        val old = File(Paths.resourcesRoot, ".old")
        old.deleteRecursively()
        if (target.exists() && !target.renameTo(old)) target.deleteRecursively()
        if (!staging.renameTo(target)) throw IOException("não deu para mover os recursos para ${target.path}")
        old.deleteRecursively()
    }

    fun delete() {
        dir().deleteRecursively()
    }
}

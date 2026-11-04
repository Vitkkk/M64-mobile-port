package io.github.zenith.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.zenith.BuildConfig
import io.github.zenith.util.Http
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Mods, resource packs e shader packs de uma instância, nas pastas que o Horizon
 * lê (docs/mods.md, docs/resource-packs.md, docs/shaders.md):
 *
 * | Tipo | Arquivo | Pasta | Ligado em |
 * |---|---|---|---|
 * | Mod de conteúdo | `.hmod` | `horizon-mods/` | `horizon-mods.txt` (`mod.<id>:true`) |
 * | Mod nativo | `.hnmod` | `horizon-native-mods/` | precisa recompilar o jogo (não dá no celular) |
 * | Resource pack | `.zip` ou pasta | `resourcepacks/` | `options.txt` (`resourcePacks`) |
 * | Shader pack do Horizon | `.hshader` ou pasta | `shaderpacks/` | `horizon-graphics.txt` (`pickedShaderPack`) |
 *
 * Jars do Fabric/Forge/NeoForge não rodam no Horizon.
 */
object Mods {
    enum class Kind(val label: String, val registryKind: String) {
        MOD("Mods", "mod"),
        RESOURCE_PACK("Resource packs", "resourcepack"),
        SHADER("Shaders", "shader"),
        NATIVE_MOD("Mods nativos", "native-mod"),
    }

    data class Installed(
        val file: File,
        val kind: Kind,
        val id: String,
        val name: String,
        val version: String,
        val enabled: Boolean,
        val supported: Boolean = true,
    )

    fun folder(instance: Instance, kind: Kind): File = when (kind) {
        Kind.MOD -> instance.modsDir
        Kind.RESOURCE_PACK -> instance.resourcePacksDir
        Kind.SHADER -> instance.shaderPacksDir
        Kind.NATIVE_MOD -> instance.nativeModsDir
    }

    fun kindOfFile(name: String): Kind? = when {
        name.endsWith(".hmod") -> Kind.MOD
        name.endsWith(".hnmod") -> Kind.NATIVE_MOD
        name.endsWith(".hshader") -> Kind.SHADER
        name.endsWith(".zip") -> Kind.RESOURCE_PACK
        else -> null
    }

    fun installed(instance: Instance): List<Installed> {
        val state = KeyValueFile(instance.modsStateFile)
        val options = KeyValueFile(instance.optionsFile)
        val packs = KeyValueFile.parseStringList(options["resourcePacks"])
        val shader = KeyValueFile(instance.graphicsFile)["pickedShaderPack"].orEmpty()
        val out = mutableListOf<Installed>()
        instance.modsDir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
            val manifest = readModManifest(f) ?: return@forEach
            val id = manifest["id"] ?: f.nameWithoutExtension
            out += Installed(f, Kind.MOD, id, manifest["name"] ?: id, manifest["version"].orEmpty(), state.getBool("mod.$id", false))
        }
        instance.resourcePacksDir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
            if (!(f.isDirectory || f.name.endsWith(".zip"))) return@forEach
            out += Installed(f, Kind.RESOURCE_PACK, f.name, f.name.removeSuffix(".zip"), "", "file/${f.name}" in packs)
        }
        instance.shaderPacksDir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
            if (!(f.isDirectory || f.name.endsWith(".hshader"))) return@forEach
            out += Installed(f, Kind.SHADER, f.name, f.name.removeSuffix(".hshader"), "", shader == f.name)
        }
        instance.nativeModsDir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
            out += Installed(f, Kind.NATIVE_MOD, f.nameWithoutExtension, f.nameWithoutExtension, "", false, supported = false)
        }
        return out
    }

    /** Liga ou desliga, do jeito que o Horizon guarda cada tipo. */
    fun setEnabled(instance: Instance, item: Installed, enabled: Boolean) {
        when (item.kind) {
            Kind.MOD -> KeyValueFile(instance.modsStateFile).apply { set("mod.${item.id}", enabled); save() }
            Kind.RESOURCE_PACK -> {
                val options = KeyValueFile(instance.optionsFile)
                val packs = KeyValueFile.parseStringList(options["resourcePacks"])
                if (packs.isEmpty()) packs += "vanilla"
                val entry = "file/${item.file.name}"
                packs.remove(entry)
                if (enabled) packs += entry
                options["resourcePacks"] = KeyValueFile.formatStringList(packs)
                options.save()
            }
            Kind.SHADER -> KeyValueFile(instance.graphicsFile).apply {
                set("pickedShaderPack", if (enabled) item.file.name else ""); save()
            }
            Kind.NATIVE_MOD -> {}
        }
    }

    fun delete(instance: Instance, item: Installed) {
        if (item.enabled) setEnabled(instance, item, false)
        item.file.deleteRecursively()
    }

    /** Copia um arquivo escolhido pelo jogador para a pasta certa. */
    fun importFile(context: Context, instance: Instance, uri: Uri): Installed? {
        val name = displayName(context, uri) ?: throw IOException("arquivo sem nome")
        val kind = kindOfFile(name.lowercase())
            ?: throw IOException("$name não é .hmod, .hnmod, .hshader nem .zip (jars do Fabric/Forge não rodam no Horizon)")
        val dest = File(folder(instance, kind), File(name).name)
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("não deu para ler $name")
        if (kind == Kind.MOD && readModManifest(dest) == null) {
            dest.delete(); throw IOException("$name não tem horizon-mod.toml")
        }
        return installed(instance).firstOrNull { it.file == dest }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment

    /** `horizon-mod.toml` de um .hmod (zip) ou de uma pasta: id, name, version. */
    fun readModManifest(file: File): Map<String, String>? {
        val text = runCatching {
            if (file.isDirectory) File(file, "horizon-mod.toml").takeIf { it.isFile }?.readText()
            else if (file.name.endsWith(".hmod")) ZipFile(file).use { zip ->
                zip.getEntry("horizon-mod.toml")?.let { e -> zip.getInputStream(e).bufferedReader().readText() }
            } else null
        }.getOrNull() ?: return null
        val out = mutableMapOf<String, String>()
        var section = ""
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.startsWith("[")) { section = line; continue }
            if (section.isNotEmpty() && section != "[mod]") continue
            val m = Regex("^([A-Za-z_]+)\\s*=\\s*\"(.*)\"$").find(line) ?: continue
            out[m.groupValues[1]] = m.groupValues[2]
        }
        return out
    }

    // --- O índice de mods do Horizon (horizon-rust/horizon-mods), docs/api/mod-index.md ---

    data class Project(
        val id: String,
        val kind: String,
        val name: String,
        val summary: String,
        val description: String,
        val authors: List<String>,
        val license: String,
        val source: String,
        val icon: String,
        val downloads: Long,
        val signed: Boolean,
        val version: String,
        val game: String,
        val fileName: String,
        val fileUrl: String,
        val fileSize: Long,
        val sha256: String,
    ) {
        val installable get() = kind in setOf("mod", "resourcepack", "shader")
        val kindLabel get() = when (kind) {
            "mod" -> "Mod de conteúdo"
            "native-mod" -> "Mod nativo"
            "resourcepack" -> "Resource pack"
            "shader" -> "Shader pack"
            "modpack" -> "Modpack"
            else -> kind
        }
    }

    data class Index(val projects: List<Project>, val revokedHashes: Set<String>, val revokedIds: Set<String>)

    const val INDEX_URL = "https://horizon-rust.github.io/horizon-mods/index.json"

    fun fetchIndex(): Index {
        val o = Http.getJson(INDEX_URL)
        if (o.optInt("schema", 1) > 1) throw IOException("O índice de mods é de um formato mais novo: atualize o launcher")
        val projects = mutableListOf<Project>()
        val arr = o.optJSONArray("projects")
        for (i in 0 until (arr?.length() ?: 0)) {
            val p = arr!!.getJSONObject(i)
            val v = p.optJSONArray("versions")?.optJSONObject(0) ?: continue
            val f = v.optJSONObject("file") ?: continue
            projects += Project(
                id = p.getString("id"),
                kind = p.optString("kind"),
                name = p.optString("name", p.getString("id")),
                summary = p.optString("summary"),
                description = p.optString("description"),
                authors = p.optJSONArray("authors")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                license = p.optString("license"),
                source = p.optString("source"),
                icon = p.optString("icon"),
                downloads = p.optLong("downloads", 0),
                signed = p.optString("key").isNotBlank(),
                version = v.optString("version"),
                game = v.optString("game"),
                fileName = f.optString("name"),
                fileUrl = f.optString("url"),
                fileSize = f.optLong("size"),
                sha256 = f.optString("sha256"),
            )
        }
        val revokedHashes = mutableSetOf<String>()
        val revokedIds = mutableSetOf<String>()
        o.optJSONArray("revoked")?.let { r ->
            for (i in 0 until r.length()) {
                val e: JSONObject = r.getJSONObject(i)
                val sha = e.optString("sha256")
                if (sha.isNotBlank()) revokedHashes += sha.lowercase() else revokedIds += e.optString("id")
            }
        }
        return Index(projects.sortedByDescending { it.downloads }, revokedHashes, revokedIds)
    }

    private val ALLOWED_URL = Regex("^https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[^/]+/[^/]+$")

    /**
     * Baixa e instala um projeto do índice: confere tamanho, SHA-256, a lista de
     * revogados, o formato do endereço e o nome do arquivo antes de pôr na pasta.
     * (A assinatura minisign não é conferida no celular.)
     */
    fun install(instance: Instance, index: Index, p: Project, progress: (Float) -> Unit): File {
        if (!p.installable) throw IOException("${p.kindLabel} não pode ser instalado no celular")
        if (p.id in index.revokedIds || p.sha256.lowercase() in index.revokedHashes) throw IOException("Este arquivo foi revogado pelo índice")
        if (p.game.isNotBlank() && p.game != BuildConfig.GAME_VERSION) throw IOException("Feito para ${p.game}, este Horizon é ${BuildConfig.GAME_VERSION}")
        if (!ALLOWED_URL.matches(p.fileUrl)) throw IOException("Endereço de download fora do formato aceito pelo índice")
        val name = File(p.fileName).name
        val kind = kindOfFile(name) ?: throw IOException("Arquivo de tipo desconhecido: $name")
        val dest = File(folder(instance, kind), name)
        val tmp = File(Paths.cache, "mod-downloads/$name")
        Http.download(p.fileUrl, tmp, p.fileSize, p.sha256, progress)
        dest.parentFile?.mkdirs()
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
        if (kind == Kind.MOD) {
            val id = readModManifest(dest)?.get("id")
            if (id != null) KeyValueFile(instance.modsStateFile).apply { set("mod.$id", true); save() }
        }
        return dest
    }
}

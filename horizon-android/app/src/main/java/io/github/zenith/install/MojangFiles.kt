package io.github.zenith.install

import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Os arquivos oficiais do jogo, dos servidores da Mojang, no layout do launcher oficial
 * (`versions/<v>/<v>.json|.jar`, `libraries/…`, `assets/indexes|objects`), que é o que o
 * `horizon-prepare --minecraft-dir` lê. A cadeia de hashes começa no `research-lock.json` do
 * Horizon: o JSON da versão é buscado pelo sha1 fixado, e ele dá o sha1 de cada arquivo.
 *
 * O que é baixado é o que o horizon-prepare usa (crates/horizon-prepare do Horizon):
 * - client e server jar (conferidos contra os hashes fixados);
 * - as bibliotecas do client (classpath dos exportadores);
 * - o índice de assets e os objetos de sons, panorama, fontes, idiomas e packs embutidos
 *   (`sounds.rs`: is_sound, is_panorama, is_font, is_language, is_built_in_pack).
 */
class MojangFiles(
    val minecraftDir: File,
    val pinned: PinnedVersion,
    private val metaBase: String = "https://piston-meta.mojang.com",
    private val resourcesBase: String = "https://resources.download.minecraft.net",
    private val fetchText: (String, String?) -> String = { url, sha1 -> Downloader.fetchText(url, sha1) },
) {
    val version get() = pinned.version
    val versionDir get() = File(minecraftDir, "versions/$version")
    val manifestFile get() = File(versionDir, "$version.json")
    val clientJar get() = File(versionDir, "$version.jar")
    val serverJar get() = File(minecraftDir, "server/$version-server.jar")

    /**
     * O JSON da versão: o que já está no disco se o hash for um dos aceitos, senão baixado pelo
     * hash (o fixado primeiro, depois as republicações que o Horizon aceita).
     */
    fun manifest(): JSONObject {
        if (manifestFile.isFile) {
            val sha = Downloader.hashOf(manifestFile)
            if (pinned.manifests.any { it.equals(sha, true) }) return JSONObject(manifestFile.readText())
        }
        var last: Exception? = null
        for (sha1 in pinned.manifests) {
            try {
                val text = fetchText(metaBase.trimEnd('/') + "/v1/packages/$sha1/$version.json", sha1)
                versionDir.mkdirs()
                manifestFile.writeText(text)
                return JSONObject(text)
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Não deu para baixar o JSON da versão $version: ${last?.message}", last)
    }

    /** O índice de assets que o JSON da versão nomeia, conferido pelo sha1 dele. */
    fun assetIndex(manifest: JSONObject): JSONObject {
        val ref = manifest.getJSONObject("assetIndex")
        val id = ref.getString("id")
        val sha1 = ref.getString("sha1")
        val file = File(minecraftDir, "assets/indexes/$id.json")
        if (file.isFile && Downloader.hashOf(file).equals(sha1, true)) return JSONObject(file.readText())
        val text = fetchText(ref.getString("url"), sha1)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return JSONObject(text)
    }

    /** Tudo o que falta baixar depois do JSON da versão e do índice. */
    fun plan(manifest: JSONObject, index: JSONObject): List<Downloader.Item> {
        val items = mutableListOf<Downloader.Item>()
        val downloads = manifest.getJSONObject("downloads")
        val client = downloads.getJSONObject("client")
        val server = downloads.getJSONObject("server")
        if (!client.getString("sha1").equals(pinned.clientSha1, true)) throw IOException("O client do JSON da versão não é o fixado pelo Horizon")
        if (!server.getString("sha1").equals(pinned.serverSha1, true)) throw IOException("O server do JSON da versão não é o fixado pelo Horizon")
        items += Downloader.Item(client.getString("url"), clientJar, pinned.clientSha1, client.optLong("size").takeIf { it > 0 })
        items += Downloader.Item(server.getString("url"), serverJar, pinned.serverSha1, server.optLong("size").takeIf { it > 0 })
        items += libraries(manifest)
        items += assets(index)
        return items
    }

    /**
     * As bibliotecas com artefato. Todas, de qualquer sistema: o horizon-prepare escolhe as do
     * Android ("linux" ARM64) pelas regras do launcher; as outras são poucos MB e evitam que uma
     * diferença de regra deixe faltar alguma.
     */
    fun libraries(manifest: JSONObject): List<Downloader.Item> {
        val out = mutableListOf<Downloader.Item>()
        val libs = manifest.optJSONArray("libraries") ?: return out
        for (i in 0 until libs.length()) {
            val artifact = libs.getJSONObject(i).optJSONObject("downloads")?.optJSONObject("artifact") ?: continue
            val path = artifact.optString("path")
            if (!safeRelative(path)) throw IOException("Caminho de biblioteca inseguro no JSON da versão: $path")
            out += Downloader.Item(
                artifact.getString("url"), File(minecraftDir, "libraries/$path"),
                artifact.optString("sha1").ifBlank { null }, artifact.optLong("size").takeIf { it > 0 },
            )
        }
        return out
    }

    fun assets(index: JSONObject): List<Downloader.Item> {
        val objects = index.getJSONObject("objects")
        val out = mutableListOf<Downloader.Item>()
        for (name in objects.keys()) {
            if (!wanted(name)) continue
            val o = objects.getJSONObject(name)
            val hash = o.getString("hash").lowercase()
            if (!Regex("^[0-9a-f]{40}$").matches(hash)) throw IOException("Hash inválido no índice de assets: $name")
            out += Downloader.Item(
                "${resourcesBase.trimEnd('/')}/${hash.substring(0, 2)}/$hash",
                File(minecraftDir, "assets/objects/${hash.substring(0, 2)}/$hash"),
                hash, o.optLong("size").takeIf { it > 0 },
            )
        }
        return out
    }

    companion object {
        private val PANORAMA = (0..5).map { "minecraft/textures/gui/title/background/panorama_$it.png" } +
            "minecraft/textures/gui/title/background/panorama_overlay.png"

        /** Os assets que o horizon-prepare copia (sounds.rs do Horizon). */
        fun wanted(name: String): Boolean =
            name == "minecraft/sounds.json" || name.startsWith("minecraft/sounds/") ||
                name in PANORAMA ||
                name.startsWith("minecraft/font/") ||
                name == "pack.mcmeta" || name.startsWith("minecraft/lang/") ||
                name.startsWith("minecraft/resourcepacks/")

        fun safeRelative(path: String): Boolean =
            path.isNotBlank() && !path.startsWith("/") && !path.contains('\\') &&
                path.split('/').none { it == ".." }
    }
}

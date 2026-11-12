package io.github.zenith.install

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Importação de arquivos do jogo que já estão no celular: um JAR escolhido no seletor do Android
 * ou uma instalação inteira (`.minecraft` de outro launcher, pasta de versão). Tudo é copiado para
 * a mesma pasta que o instalador usa (`Installer.minecraftDir`, layout do launcher oficial) e
 * conferido pelos mesmos hashes; o preparo depois é o mesmo do BAIXAR.
 *
 * O que o horizon-prepare exige (artifacts.rs `import`): o JSON da versão, o client jar, o server
 * jar e as bibliotecas do client. Os assets (sons, idiomas, panorama, fontes, packs) são extras:
 * sem eles o jogo roda, mudo e só em inglês (check.rs `EXTRAS`).
 *
 * Ter um JAR não prova que a pessoa tem o jogo: abrir a partida continua pedindo a conta dona do
 * Java Edition, como antes.
 */
object LocalImport {
    enum class Kind(val label: String) { CLIENT("client"), SERVER("servidor"), OTHER("desconhecido") }

    data class JarInfo(
        val fileName: String,
        val kind: Kind,
        /** O id de `version.json` dentro do JAR (ex.: "26.3"), ou null. */
        val versionId: String?,
        val versionName: String?,
        val sha1: String,
        val size: Long,
        val compatible: Boolean,
        val message: String,
    )

    /**
     * Lê um JAR já copiado para [file]: `version.json` (id e nome da versão), se é client
     * (`net/minecraft/client/main/Main.class`) ou server (bundler da Mojang), e se é exatamente o
     * que o Horizon deste APK fixa (SHA-1).
     */
    fun inspect(file: File, fileName: String, pinned: PinnedVersion): JarInfo {
        val sha1 = Downloader.hashOf(file)
        val (id, name, kind) = try {
            ZipFile(file).use { zip ->
                val version = zip.getEntry("version.json")?.let { e ->
                    runCatching { JSONObject(zip.getInputStream(e).bufferedReader().readText()) }.getOrNull()
                }
                val kind = when {
                    zip.getEntry("net/minecraft/client/main/Main.class") != null -> Kind.CLIENT
                    zip.getEntry("net/minecraft/bundler/Main.class") != null ||
                        zip.getEntry("META-INF/versions.list") != null -> Kind.SERVER
                    else -> Kind.OTHER
                }
                Triple(version?.optString("id")?.ifBlank { null }, version?.optString("name")?.ifBlank { null }, kind)
            }
        } catch (e: java.util.zip.ZipException) {
            return JarInfo(fileName, Kind.OTHER, null, null, sha1, file.length(), false, "Não é um JAR (zip) válido: ${e.message}")
        }
        val pinnedSha = when (kind) {
            Kind.CLIENT -> pinned.clientSha1
            Kind.SERVER -> pinned.serverSha1
            Kind.OTHER -> null
        }
        val compatible = pinnedSha != null && pinnedSha.equals(sha1, ignoreCase = true)
        val message = when {
            compatible -> "Minecraft ${pinned.version} (${kind.label}) original: compatível com o Horizon deste app"
            kind == Kind.OTHER -> "Não parece um JAR do Minecraft (sem net/minecraft/client/main/Main.class nem o bundler do servidor)"
            id != null && id != pinned.version ->
                "É o Minecraft $id (${kind.label}). O motor Rust deste app só roda o ${pinned.version}"
            else ->
                "É o ${kind.label} ${id ?: pinned.version}, mas o arquivo não é o original da Mojang " +
                    "(SHA-1 $sha1). JARs modificados (Fabric, Forge, OptiFine, patches do launcher) não servem"
        }
        return JarInfo(fileName, kind, id, name, sha1, file.length(), compatible, message)
    }

    /** Põe um JAR conferido no lugar que o horizon-prepare lê. */
    fun place(file: File, info: JarInfo, files: MojangFiles) {
        if (!info.compatible) throw IOException(info.message)
        val target = when (info.kind) {
            Kind.CLIENT -> files.clientJar
            Kind.SERVER -> files.serverJar
            Kind.OTHER -> throw IOException(info.message)
        }
        target.parentFile?.mkdirs()
        if (!file.renameTo(target)) {
            file.copyTo(target, overwrite = true); file.delete()
        }
    }

    // --- Instalação inteira ---

    /** Uma pasta navegável: a árvore do seletor do Android (SAF) no app, uma pasta comum nos testes. */
    interface Tree {
        val name: String
        fun child(name: String): Tree?
        fun children(): List<Tree>
        val isDirectory: Boolean
        val length: Long
        fun open(): InputStream
    }

    class FileTree(private val file: File) : Tree {
        override val name: String get() = file.name
        override fun child(name: String): Tree? = File(file, name).takeIf { it.exists() }?.let(::FileTree)
        override fun children(): List<Tree> = file.listFiles()?.map(::FileTree) ?: emptyList()
        override val isDirectory get() = file.isDirectory
        override val length get() = file.length()
        override fun open(): InputStream = file.inputStream()
    }

    private fun Tree.path(vararg parts: String): Tree? {
        var t: Tree? = this
        for (p in parts) t = t?.child(p) ?: return null
        return t
    }

    /** O que foi achado na pasta escolhida. */
    data class Found(val root: Tree?, val versionDir: Tree?, val description: String)

    /**
     * Acha a instalação dentro da pasta escolhida: a própria `.minecraft`, uma pasta acima dela
     * (ex.: a pasta do launcher, com `.minecraft` ou `minecraft` dentro) ou a pasta da versão.
     */
    fun locate(picked: Tree, version: String): Found {
        fun isRoot(t: Tree) = t.child("versions")?.isDirectory == true
        if (isRoot(picked)) return Found(picked, picked.path("versions", version), "instalação em ${picked.name}")
        for (name in listOf(".minecraft", "minecraft", "game")) {
            picked.child(name)?.takeIf(::isRoot)?.let { return Found(it, it.path("versions", version), "instalação em ${picked.name}/$name") }
        }
        if (picked.child("$version.jar") != null || picked.child("$version.json") != null) {
            return Found(null, picked, "pasta da versão ${picked.name} (sem bibliotecas nem assets)")
        }
        // Um nível abaixo (ex.: a pasta de instâncias de um launcher)
        for (c in picked.children().filter { it.isDirectory }.take(50)) {
            if (isRoot(c)) return Found(c, c.path("versions", version), "instalação em ${picked.name}/${c.name}")
            for (name in listOf(".minecraft", "minecraft")) {
                c.child(name)?.takeIf(::isRoot)?.let { return Found(it, it.path("versions", version), "instalação em ${picked.name}/${c.name}/$name") }
            }
        }
        return Found(null, null, "nada do Minecraft encontrado em ${picked.name}")
    }

    data class Report(val copied: Int, val reused: Int, val skipped: List<String>, val description: String)

    /**
     * Copia da instalação [picked] para [files] o que o horizon-prepare usa, conferindo cada
     * arquivo: o JSON da versão (hash aceito pelo Horizon), o client (hash fixado), as bibliotecas
     * e os assets (hash do JSON/índice). O que não confere é deixado de fora e listado.
     * [progress] recebe (arquivos feitos, total, nome).
     */
    fun importInstallation(
        picked: Tree,
        files: MojangFiles,
        abi: String,
        cancelled: () -> Boolean,
        progress: (Int, Int, String) -> Unit,
    ): Report {
        val pinned = files.pinned
        val found = locate(picked, pinned.version)
        if (found.versionDir == null && found.root == null) throw IOException("Não achei uma instalação do Minecraft: ${found.description}")
        val skipped = mutableListOf<String>()
        var copied = 0
        var reused = 0

        fun take(source: Tree?, target: File, sha1: String?, label: String): Boolean {
            if (source == null || source.isDirectory) return false
            if (target.isFile && sha1 != null && Downloader.hashOf(target).equals(sha1, true)) { reused++; return true }
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            val digest = MessageDigest.getInstance("SHA-1")
            source.open().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (cancelled()) throw Downloader.Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n)
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha1 != null && !hex.equals(sha1, true)) {
                part.delete(); skipped += "$label (diferente do original)"; return false
            }
            if (!part.renameTo(target)) { target.delete(); part.renameTo(target) }
            copied++
            return true
        }

        // 1. JSON da versão
        val v = pinned.version
        val jsonSource = found.versionDir?.child("$v.json")
        val manifestText = jsonSource?.takeIf { !it.isDirectory }?.open()?.use { it.readBytes() }
        if (manifestText != null) {
            val sha = MessageDigest.getInstance("SHA-1").digest(manifestText).joinToString("") { "%02x".format(it) }
            if (pinned.manifests.any { it.equals(sha, true) }) {
                files.versionDir.mkdirs(); files.manifestFile.writeBytes(manifestText); copied++
            } else skipped += "$v.json (alterado pelo launcher; o original vem da Mojang pelo hash)"
        }
        // Sem um JSON aceito, só os metadados (alguns KB) vêm da Mojang, pelo hash fixado.
        val manifest = runCatching { files.manifest() }.getOrElse { throw IOException("Sem o JSON da versão $v: ${it.message}") }

        // 2. Client
        take(found.versionDir?.child("$v.jar"), files.clientJar, pinned.clientSha1, "$v.jar")

        // 3. Bibliotecas e assets (só numa instalação inteira)
        val libs = ClientLibraries.required(manifest, abi)
        var index: JSONObject? = null
        val root = found.root
        if (root != null) {
            val ref = manifest.getJSONObject("assetIndex")
            val indexSource = root.path("assets", "indexes", "${ref.getString("id")}.json")
            val indexFile = File(files.minecraftDir, "assets/indexes/${ref.getString("id")}.json")
            if (take(indexSource, indexFile, ref.getString("sha1"), "índice de assets")) index = JSONObject(indexFile.readText())
        }
        val assets = index?.let { files.assets(it) } ?: emptyList()
        val total = 2 + libs.size + assets.size
        var done = 2
        progress(done, total, "$v.jar")
        if (root != null) {
            for (lib in libs) {
                if (cancelled()) throw Downloader.Cancelled()
                val src = root.path("libraries", *lib.path.split('/').toTypedArray())
                take(src, File(files.minecraftDir, "libraries/${lib.path}"), lib.sha1, lib.path.substringAfterLast('/'))
                progress(++done, total, lib.path.substringAfterLast('/'))
            }
            val objects = root.path("assets", "objects")
            val buckets = HashMap<String, Tree?>()
            for (item in assets) {
                if (cancelled()) throw Downloader.Cancelled()
                val hash = item.hash!!
                val bucket = buckets.getOrPut(hash.substring(0, 2)) { objects?.child(hash.substring(0, 2)) }
                take(bucket?.child(hash), item.file, hash, "asset $hash")
                if (++done % 25 == 0) progress(done, total, "assets")
            }
        }
        progress(total, total, "")
        return Report(copied, reused, skipped, found.description)
    }

    // --- O que falta ---

    data class Requirement(val label: String, val ok: Boolean, val detail: String, val required: Boolean)

    /** O que está na pasta de trabalho e o que falta para o preparo, em ordem. */
    fun requirements(files: MojangFiles, abi: String, javaReady: Boolean): List<Requirement> {
        val pinned = files.pinned
        val v = pinned.version
        val manifestOk = files.manifestFile.isFile && pinned.manifests.any { it.equals(Downloader.hashOf(files.manifestFile), true) }
        val client = files.clientJar.isFile && Downloader.hashOf(files.clientJar).equals(pinned.clientSha1, true)
        val server = files.serverJar.isFile && Downloader.hashOf(files.serverJar).equals(pinned.serverSha1, true)
        val out = mutableListOf(
            Requirement("JSON da versão $v", manifestOk, if (manifestOk) "conferido" else "vem da Mojang pelo hash (alguns KB)", true),
            Requirement("Client $v ($v.jar)", client, if (client) "original, conferido" else "importe o JAR ou a instalação", true),
            Requirement("Servidor $v (server.jar)", server, if (server) "original, conferido" else "o horizon-prepare roda o gerador de dados dele; launchers de celular não o guardam", true),
        )
        if (manifestOk) {
            val manifest = JSONObject(files.manifestFile.readText())
            val libs = ClientLibraries.required(manifest, abi)
            val present = libs.count { l -> File(files.minecraftDir, "libraries/${l.path}").let { it.isFile && it.length() == l.size } }
            val needed = libs.count { !it.optional }
            val presentNeeded = libs.count { l -> !l.optional && File(files.minecraftDir, "libraries/${l.path}").let { it.isFile && it.length() == l.size } }
            out += Requirement("Bibliotecas do client", presentNeeded == needed, "$present de ${libs.size}", true)
            val ref = manifest.getJSONObject("assetIndex")
            val indexFile = File(files.minecraftDir, "assets/indexes/${ref.getString("id")}.json")
            val assets = runCatching { files.assets(JSONObject(indexFile.readText())) }.getOrNull()
            val have = assets?.count { a -> a.file.isFile && a.file.length() == a.size } ?: 0
            out += Requirement(
                "Assets (sons, idiomas, panorama, fontes)",
                assets != null && have == assets.size,
                if (assets == null) "sem o índice: o jogo fica mudo e em inglês" else "$have de ${assets.size}",
                false,
            )
        }
        out += Requirement("Java 25 para preparar", javaReady, if (javaReady) "pronto" else "baixado do GitHub (~38 MB) ou importado (.tar.xz)", true)
        return out
    }

    /** Os assets estão todos lá (o preparo usa --complete e inclui os sons). */
    fun assetsComplete(files: MojangFiles): Boolean = runCatching {
        val manifest = JSONObject(files.manifestFile.readText())
        val ref = manifest.getJSONObject("assetIndex")
        val index = JSONObject(File(files.minecraftDir, "assets/indexes/${ref.getString("id")}.json").readText())
        files.assets(index).all { a -> a.file.isFile && a.file.length() == a.size }
    }.getOrDefault(false)
}

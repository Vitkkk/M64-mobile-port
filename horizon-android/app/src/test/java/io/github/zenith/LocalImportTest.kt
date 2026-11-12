package io.github.zenith

import io.github.zenith.install.ClientLibraries
import io.github.zenith.install.LocalImport
import io.github.zenith.install.MojangFiles
import io.github.zenith.install.PinnedVersion
import io.github.zenith.install.Preparer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalImportTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("zenith-local").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private fun jar(entries: Map<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in entries) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private val client = jar(mapOf(
        "version.json" to """{"id":"9.9","name":"9.9","world_version":4000}""",
        "net/minecraft/client/main/Main.class" to "classe",
        "assets/minecraft/lang/en_us.json" to "{}",
    ))
    private val server = jar(mapOf("net/minecraft/bundler/Main.class" to "x", "META-INF/versions.list" to "x"))

    private fun file(name: String, bytes: ByteArray) = File(dir, name).also { it.parentFile?.mkdirs(); it.writeBytes(bytes) }

    @Test
    fun recognisesTheOriginalClientJar() {
        val pinned = PinnedVersion("9.9", listOf("m"), sha1(client), sha1(server), 25)
        val info = LocalImport.inspect(file("26.jar", client), "9.9.jar", pinned)
        assertEquals(LocalImport.Kind.CLIENT, info.kind)
        assertEquals("9.9", info.versionId)
        assertTrue(info.message, info.compatible)
    }

    @Test
    fun refusesModifiedJarsAndOtherVersions() {
        val pinned = PinnedVersion("9.9", listOf("m"), sha1(client), sha1(server), 25)
        val patched = jar(mapOf("version.json" to """{"id":"9.9"}""", "net/minecraft/client/main/Main.class" to "OUTRA"))
        val modified = LocalImport.inspect(file("p.jar", patched), "p.jar", pinned)
        assertFalse(modified.compatible)
        assertTrue(modified.message, modified.message.contains("não é o original"))

        val other = jar(mapOf("version.json" to """{"id":"1.21.4"}""", "net/minecraft/client/main/Main.class" to "x"))
        val old = LocalImport.inspect(file("o.jar", other), "1.21.4.jar", pinned)
        assertFalse(old.compatible)
        assertEquals("1.21.4", old.versionId)
        assertTrue(old.message, old.message.contains("só roda o 9.9"))

        val notMinecraft = LocalImport.inspect(file("n.jar", jar(mapOf("a.txt" to "b"))), "n.jar", pinned)
        assertEquals(LocalImport.Kind.OTHER, notMinecraft.kind)
        assertFalse(notMinecraft.compatible)

        val notZip = LocalImport.inspect(file("z.jar", "texto".toByteArray()), "z.jar", pinned)
        assertFalse(notZip.compatible)
        assertTrue(notZip.message, notZip.message.contains("zip"))
    }

    @Test
    fun recognisesTheServerJarAndPlacesBoth() {
        val pinned = PinnedVersion("9.9", listOf("m"), sha1(client), sha1(server), 25)
        val files = MojangFiles(File(dir, "mc"), pinned) { _, _ -> error("sem rede") }
        val s = LocalImport.inspect(file("server.jar", server), "server.jar", pinned)
        assertEquals(LocalImport.Kind.SERVER, s.kind)
        assertTrue(s.compatible)
        LocalImport.place(File(dir, "server.jar"), s, files)
        assertTrue(files.serverJar.readBytes().contentEquals(server))
        val c = LocalImport.inspect(file("c.jar", client), "9.9.jar", pinned)
        LocalImport.place(File(dir, "c.jar"), c, files)
        assertTrue(files.clientJar.readBytes().contentEquals(client))
    }

    @Test
    fun importsALauncherInstallationAndListsWhatIsMissing() {
        val lib = "biblioteca".toByteArray()
        val badLib = "x".toByteArray()
        val sound = "ogg".toByteArray()
        val lang = "{}".toByteArray()
        val index = """{"objects":{
            "minecraft/sounds/a.ogg":{"hash":"${sha1(sound)}","size":${sound.size}},
            "minecraft/lang/pt_br.json":{"hash":"${sha1(lang)}","size":${lang.size}},
            "icons/icon.png":{"hash":"${"f".repeat(40)}","size":9}}}""".toByteArray()
        val manifest = """{"id":"9.9","assetIndex":{"id":"34","sha1":"${sha1(index)}","url":"u"},
            "downloads":{"client":{"sha1":"${sha1(client)}","url":"u"},"server":{"sha1":"${sha1(server)}","url":"u"}},
            "libraries":[
              {"name":"a:b:1","downloads":{"artifact":{"path":"a/b/1/b-1.jar","sha1":"${sha1(lib)}","size":${lib.size},"url":"u"}}},
              {"name":"c:d:2","downloads":{"artifact":{"path":"c/d/2/d-2.jar","sha1":"${sha1("original".toByteArray())}","size":8,"url":"u"}}},
              {"name":"mac:only:1","rules":[{"action":"allow","os":{"name":"osx"}}],"downloads":{"artifact":{"path":"mac/only.jar","sha1":"00","size":1,"url":"u"}}}
            ]}""".toByteArray()
        val pinned = PinnedVersion("9.9", listOf(sha1(manifest)), sha1(client), sha1(server), 25)

        // Uma pasta de launcher com a .minecraft dentro, como as dos launchers Java do celular.
        val launcher = File(dir, "MJLauncher")
        val mc = File(launcher, ".minecraft")
        fun put(path: String, b: ByteArray) = File(mc, path).also { it.parentFile?.mkdirs(); it.writeBytes(b) }
        put("versions/9.9/9.9.json", manifest)
        put("versions/9.9/9.9.jar", client)
        put("libraries/a/b/1/b-1.jar", lib)
        put("libraries/c/d/2/d-2.jar", badLib) // alterada: não confere
        put("assets/indexes/34.json", index)
        put("assets/objects/${sha1(sound).take(2)}/${sha1(sound)}", sound)
        // pt_br.json ausente

        val work = File(dir, "work")
        val files = MojangFiles(work, pinned) { _, _ -> error("não devia usar a rede: o JSON local confere") }
        val progress = mutableListOf<Int>()
        val report = LocalImport.importInstallation(LocalImport.FileTree(launcher), files, "arm64-v8a", { false }) { done, _, _ -> progress += done }
        assertTrue(report.description, report.description.contains(".minecraft"))
        assertTrue(files.manifestFile.isFile)
        assertTrue(files.clientJar.readBytes().contentEquals(client))
        assertTrue(File(work, "libraries/a/b/1/b-1.jar").isFile)
        assertFalse("a biblioteca alterada fica de fora", File(work, "libraries/c/d/2/d-2.jar").exists())
        assertTrue(report.skipped.any { it.contains("d-2.jar") })
        assertTrue(File(work, "assets/objects/${sha1(sound).take(2)}/${sha1(sound)}").isFile)
        assertTrue(progress.isNotEmpty())

        val reqs = LocalImport.requirements(files, "arm64-v8a", javaReady = false).associateBy { it.label.substringBefore(" (").substringBefore(" 9.9") }
        assertTrue(reqs.getValue("JSON da versão").ok)
        assertTrue(reqs.getValue("Client").ok)
        assertFalse(reqs.getValue("Servidor").ok)
        assertFalse(reqs.getValue("Bibliotecas do client").ok)
        assertEquals("1 de 2", reqs.getValue("Bibliotecas do client").detail)
        assertFalse(reqs.getValue("Assets").ok)
        assertFalse(reqs.getValue("Assets").required)
        assertFalse(LocalImport.assetsComplete(files))

        // Reimportar não copia de novo o que já confere.
        val again = LocalImport.importInstallation(LocalImport.FileTree(mc), files, "arm64-v8a", { false }) { _, _, _ -> }
        assertTrue(again.reused >= 3)
    }

    @Test
    fun aVersionFolderAloneIsFoundButHasNoLibraries() {
        val v = File(dir, "versions/9.9").apply { mkdirs() }
        File(v, "9.9.jar").writeBytes(client)
        val found = LocalImport.locate(LocalImport.FileTree(v), "9.9")
        assertEquals(null, found.root)
        assertTrue(found.versionDir != null)
        val nothing = LocalImport.locate(LocalImport.FileTree(File(dir, "versions").parentFile!!.resolve("vazio").apply { mkdirs() }), "9.9")
        assertEquals(null, nothing.versionDir)
        try {
            LocalImport.importInstallation(LocalImport.FileTree(File(dir, "vazio")), MojangFiles(File(dir, "w"), PinnedVersion("9.9", listOf("m"), "c", "s", 25)) { _, _ -> "" }, "arm64-v8a", { false }) { _, _, _ -> }
            fail("devia recusar")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Não achei"))
        }
    }

    @Test
    fun libraryRulesFollowHorizonPrepare() {
        val manifest = JSONObject("""{"libraries":[
            {"name":"plain:lib:1","downloads":{"artifact":{"path":"p.jar","sha1":"1","size":1,"url":"u"}}},
            {"name":"org.lwjgl:lwjgl:3:natives-linux","rules":[{"action":"allow","os":{"name":"linux"}}],"downloads":{"artifact":{"path":"x64.jar","sha1":"2","size":1,"url":"u"}}},
            {"name":"org.lwjgl:lwjgl:3:natives-linux-arm64","rules":[{"action":"allow","os":{"name":"linux"}}],"downloads":{"artifact":{"path":"arm.jar","sha1":"3","size":1,"url":"u"}}},
            {"name":"org.lwjgl:lwjgl:3:natives-windows","rules":[{"action":"allow","os":{"name":"windows"}}],"downloads":{"artifact":{"path":"win.jar","sha1":"4","size":1,"url":"u"}}},
            {"name":"io.netty:netty-transport-native-epoll:4:linux-aarch_64","downloads":{"artifact":{"path":"epoll.jar","sha1":"5","size":1,"url":"u"}}},
            {"name":"feat:lib:1","rules":[{"action":"allow","features":{"is_demo_user":true}}],"downloads":{"artifact":{"path":"demo.jar","sha1":"6","size":1,"url":"u"}}}
        ]}""")
        val arm = ClientLibraries.required(manifest, "arm64-v8a").map { it.path }
        assertEquals(listOf("p.jar", "arm.jar", "epoll.jar"), arm)
        assertTrue(ClientLibraries.required(manifest, "arm64-v8a").first { it.path == "epoll.jar" }.optional)
        val x64 = ClientLibraries.required(manifest, "x86_64").map { it.path }
        assertEquals(listOf("p.jar", "x64.jar"), x64)
    }

    @Test
    fun checkLinesForExtrasAreTold() {
        assertTrue(Preparer.isExtra("the sounds missing: horizon-prepare sounds"))
        assertTrue(Preparer.isExtra("the languages missing: horizon-prepare languages"))
        assertFalse(Preparer.isExtra("/x is not prepared: horizon-prepare --prepare"))
    }
}

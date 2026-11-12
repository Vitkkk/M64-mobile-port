package io.github.zenith

import io.github.zenith.install.Downloader
import io.github.zenith.install.MojangFiles
import io.github.zenith.install.PinnedVersion
import io.github.zenith.install.Preparer
import io.github.zenith.install.Tar
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class InstallerTest {
    private lateinit var server: ServerSocket
    private lateinit var dir: File
    private val hits = HashMap<String, AtomicInteger>()
    private val files = HashMap<String, ByteArray>()
    /** Caminhos que falham na primeira vez (HTTP 500). */
    private val flaky = HashSet<String>()

    private val base get() = "http://127.0.0.1:${server.localPort}"

    /** Um servidor HTTP/1.0 mínimo: GET, um pedido por conexão. */
    @Before
    fun start() {
        dir = Files.createTempDirectory("zenith-install").toFile()
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) {
                    socket.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return@use
                        while (reader.readLine()?.isNotEmpty() == true) {}
                        val n = synchronized(hits) { hits.getOrPut(path) { AtomicInteger() } }.incrementAndGet()
                        val body = files[path]
                        val out = s.getOutputStream()
                        fun head(code: Int, len: Int) =
                            out.write("HTTP/1.0 $code X\r\nContent-Length: $len\r\nConnection: close\r\n\r\n".toByteArray())
                        when {
                            body == null -> head(404, 0)
                            path in flaky && n == 1 -> head(500, 0)
                            else -> { head(200, body.size); out.write(body) }
                        }
                        out.flush()
                    }
                }
            }
        }
    }

    @After
    fun stop() {
        server.close()
        dir.deleteRecursively()
    }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun downloadsVerifiesAndReusesFiles() {
        val a = "conteúdo A".repeat(1000).toByteArray()
        val b = ByteArray(300_000) { (it % 251).toByte() }
        files["/a"] = a; files["/b"] = b
        val items = listOf(
            Downloader.Item("$base/a", File(dir, "x/a.bin"), sha1(a), a.size.toLong()),
            Downloader.Item("$base/b", File(dir, "y/b.bin"), sha1(b), b.size.toLong()),
        )
        var last: Downloader.Progress? = null
        Downloader(threads = 2, attempts = 2).run(items) { last = it }
        assertTrue(File(dir, "x/a.bin").readBytes().contentEquals(a))
        assertTrue(File(dir, "y/b.bin").readBytes().contentEquals(b))
        assertEquals(2, last!!.files)
        assertEquals((a.size + b.size).toLong(), last!!.bytes)
        // De novo: nada é baixado, os arquivos certos são reaproveitados.
        Downloader(threads = 2).run(items)
        assertEquals(1, hits["/a"]!!.get())
        assertEquals(1, hits["/b"]!!.get())
    }

    @Test
    fun wrongHashFailsAndLeavesNoFile() {
        files["/c"] = "verdadeiro".toByteArray()
        val target = File(dir, "c.bin")
        try {
            Downloader(threads = 1, attempts = 2).run(listOf(Downloader.Item("$base/c", target, sha1("falso".toByteArray()), null)))
            fail("devia falhar")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("não confere"))
        }
        assertFalse(target.exists())
        assertFalse(File(dir, "c.bin.part").exists())
    }

    @Test
    fun retriesAfterServerError() {
        val d = "depois do erro".toByteArray()
        files["/d"] = d
        flaky += "/d"
        Downloader(threads = 1, attempts = 3).run(listOf(Downloader.Item("$base/d", File(dir, "d"), sha1(d), d.size.toLong())))
        assertEquals(2, hits["/d"]!!.get())
    }

    @Test
    fun cancelStopsTheDownload() {
        files["/e"] = ByteArray(10)
        try {
            Downloader(threads = 1).run(listOf(Downloader.Item("$base/e", File(dir, "e"), null, null)), cancelled = { true })
            fail("devia cancelar")
        } catch (e: Downloader.Cancelled) {
        }
    }

    private val lock = JSONObject(
        """{"minecraft":{"version":"9.9","manifest_sha1":"MANIFEST","accepted_manifest_sha1":["OTHER"],
           "client_sha1":"CLIENT","server_sha1":"SERVER","java_major":25}}"""
    )

    @Test
    fun pinnedVersionReadsTheLock() {
        val p = PinnedVersion.fromLock(lock)
        assertEquals("9.9", p.version)
        assertEquals(listOf("MANIFEST", "OTHER"), p.manifests)
        assertEquals(25, p.javaMajor)
        assertEquals("https://piston-meta.mojang.com/v1/packages/MANIFEST/9.9.json", p.manifestUrl("MANIFEST"))
    }

    @Test
    fun plansTheOfficialLayout() {
        val manifestText = """
            {"id":"9.9","assetIndex":{"id":"34","sha1":"IDX","url":"$base/idx.json"},
             "downloads":{"client":{"sha1":"cafe","size":10,"url":"$base/client.jar"},
                          "server":{"sha1":"beef","size":20,"url":"$base/server.jar"}},
             "libraries":[
               {"name":"a:b:1","downloads":{"artifact":{"path":"a/b/1/b-1.jar","sha1":"11","size":5,"url":"$base/lib"}}},
               {"name":"natives","downloads":{"classifiers":{}}}
             ]}""".trimIndent()
        val manifestSha = sha1(manifestText.toByteArray())
        val pinned = PinnedVersion("9.9", listOf(manifestSha), "cafe", "beef", 25)
        val fetched = mutableListOf<String>()
        val mc = MojangFiles(File(dir, "mc"), pinned, metaBase = base, resourcesBase = "$base/res") { url, sha ->
            fetched += url
            when {
                url.endsWith("/v1/packages/$manifestSha/9.9.json") -> manifestText
                url.endsWith("idx.json") -> """{"objects":{
                    "minecraft/sounds.json":{"hash":"${"a".repeat(40)}","size":1},
                    "minecraft/sounds/step/grass1.ogg":{"hash":"${"b".repeat(40)}","size":2},
                    "minecraft/lang/pt_br.json":{"hash":"${"c".repeat(40)}","size":3},
                    "minecraft/textures/gui/title/background/panorama_0.png":{"hash":"${"d".repeat(40)}","size":4},
                    "icons/icon_16x16.png":{"hash":"${"e".repeat(40)}","size":5}}}"""
                else -> error("url inesperada $url ($sha)")
            }
        }
        val manifest = mc.manifest()
        assertEquals(manifestText, File(dir, "mc/versions/9.9/9.9.json").readText())
        val plan = mc.plan(manifest, mc.assetIndex(manifest))
        val paths = plan.map { it.file.relativeTo(File(dir, "mc")).path.replace('\\', '/') }.toSet()
        assertTrue("versions/9.9/9.9.jar" in paths)
        assertTrue("server/9.9-server.jar" in paths)
        assertTrue("libraries/a/b/1/b-1.jar" in paths)
        assertTrue("assets/objects/bb/${"b".repeat(40)}" in paths)
        assertFalse("ícone não é usado pelo horizon-prepare", "assets/objects/ee/${"e".repeat(40)}" in paths)
        assertEquals(4, plan.count { it.file.path.contains("assets/objects") })
        assertTrue(plan.first { it.file.name == "${"b".repeat(40)}" }.url == "$base/res/bb/${"b".repeat(40)}")
        // O manifesto já no disco e conferido não é baixado de novo.
        mc.manifest()
        assertEquals(1, fetched.count { it.contains("/v1/packages/") })
    }

    @Test
    fun refusesAClientThatIsNotPinned() {
        val pinned = PinnedVersion("9.9", listOf("x"), "PINNED", "beef", 25)
        val mc = MojangFiles(File(dir, "mc"), pinned) { _, _ -> error("sem rede") }
        val manifest = JSONObject("""{"downloads":{"client":{"sha1":"other","url":"u"},"server":{"sha1":"beef","url":"u"}},"libraries":[]}""")
        try {
            mc.plan(manifest, JSONObject("""{"objects":{}}"""))
            fail("devia recusar")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("fixado"))
        }
    }

    @Test
    fun assetFilterMatchesWhatHorizonPrepareCopies() {
        for (yes in listOf("minecraft/sounds.json", "minecraft/sounds/a.ogg", "minecraft/font/unifont.zip", "pack.mcmeta",
            "minecraft/lang/en_us.json", "minecraft/resourcepacks/high_contrast.zip",
            "minecraft/textures/gui/title/background/panorama_overlay.png")) assertTrue(yes, MojangFiles.wanted(yes))
        for (no in listOf("icons/icon_16x16.png", "minecraft/textures/block/stone.png", "realms/x"))
            assertFalse(no, MojangFiles.wanted(no))
        assertFalse(MojangFiles.safeRelative("../x.jar"))
        assertFalse(MojangFiles.safeRelative("/etc/x"))
        assertTrue(MojangFiles.safeRelative("org/lwjgl/lwjgl/3/lwjgl-3.jar"))
    }

    /** Um tar ustar mínimo, como o do runtime: pastas, arquivos e um link (ignorado). */
    private fun tar(entries: List<Triple<String, Char, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, type, data) in entries) {
            val h = ByteArray(512)
            fun put(s: String, off: Int) = s.toByteArray().copyInto(h, off)
            put(name, 0)
            put("%07o".format(if (type == '5') 0b111_101_101 else 0b111_101_101), 100)
            put("%011o".format(data.size), 124)
            put("%011o".format(0), 136)
            h[156] = type.code.toByte()
            if (type == '2') put("../outro", 157)
            put("ustar", 257); put("00", 263)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            put("%06o".format(h.sumOf { it.toInt() and 0xff }) + "\u0000 ", 148)
            out.write(h)
            out.write(data)
            out.write(ByteArray(((512 - data.size % 512) % 512)))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    @Test
    fun extractsTheRuntimeTarXz() {
        val tarBytes = tar(listOf(
            Triple("./", '5', ByteArray(0)),
            Triple("./lib/", '5', ByteArray(0)),
            Triple("./lib/libjli.so", '0', "ELF".toByteArray()),
            Triple("./release", '0', "JAVA_VERSION=\"25\"".toByteArray()),
            Triple("./legal/x/LICENSE", '2', ByteArray(0)),
        ))
        val xz = ByteArrayOutputStream().also { o -> XZOutputStream(o, LZMA2Options()).use { it.write(tarBytes) } }.toByteArray()
        val target = File(dir, "jre")
        Tar.extract(XZInputStream(xz.inputStream()), target)
        assertEquals("ELF", File(target, "lib/libjli.so").readText())
        assertTrue(File(target, "release").readText().contains("25"))
        assertFalse(File(target, "legal/x/LICENSE").exists())
    }

    @Test
    fun tarRefusesPathsOutsideTheFolder() {
        val bad = tar(listOf(Triple("../fora.txt", '0', "x".toByteArray())))
        try {
            Tar.extract(bad.inputStream(), File(dir, "jre"))
            fail("devia recusar")
        } catch (e: IOException) {
        }
        assertFalse(File(dir, "fora.txt").exists())
    }

    @Test
    fun readsPrepareProgressLines() {
        assertEquals("message" to "Importing", Preparer.parseProgress("""{"message":"Importing"}"""))
        assertEquals("fraction", Preparer.parseProgress("""{"fraction":0.5}""")!!.first)
        assertEquals("error" to "falhou", Preparer.parseProgress("""{"error":"falhou"}"""))
        assertEquals(null, Preparer.parseProgress("texto solto"))
    }
}

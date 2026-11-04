package io.github.zenith

import io.github.zenith.controls.ControlAction
import io.github.zenith.controls.ControlLayout
import io.github.zenith.controls.DefaultLayouts
import io.github.zenith.data.Account
import io.github.zenith.data.KeyValueFile
import io.github.zenith.data.Mods
import io.github.zenith.game.GameLauncher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LauncherLogicTest {

    @Test
    fun keyValueFileKeepsUnknownKeysAndOrder() {
        val dir = Files.createTempDirectory("kv").toFile()
        val f = File(dir, "options.txt")
        f.writeText("version:5023\nrenderDistance:12\nsomeModKey:abc\nlang:en_us\n")
        val kv = KeyValueFile(f)
        assertEquals(12, kv.getInt("renderDistance", 0))
        kv["renderDistance"] = "8"
        kv["lang"] = "pt_br"
        kv["guiScale"] = "2"
        kv.save()
        assertEquals("version:5023\nrenderDistance:8\nsomeModKey:abc\nlang:pt_br\nguiScale:2\n", f.readText())
        dir.deleteRecursively()
    }

    @Test
    fun resourcePackListRoundTrips() {
        val list = KeyValueFile.parseStringList("[\"vanilla\",\"file/My \\\"Pack\\\".zip\"]")
        assertEquals(listOf("vanilla", "file/My \"Pack\".zip"), list)
        assertEquals("[\"vanilla\",\"file/My \\\"Pack\\\".zip\"]", KeyValueFile.formatStringList(list))
        assertTrue(KeyValueFile.parseStringList("[]").isEmpty())
    }

    @Test
    fun offlineUuidMatchesJava() {
        // UUID.nameUUIDFromBytes("OfflinePlayer:Notch") no Java.
        assertEquals("b50ad385829d3141a2167e7d7539ba7f", Account.offlineUuid("Notch"))
        assertTrue(Account.validName("Alex_99"))
        assertFalse(Account.validName("ab"))
        assertFalse(Account.validName("nome com espaço"))
    }

    @Test
    fun splitArgsHonoursQuotes() {
        assertEquals(listOf("--render-distance", "6", "--world", "Meu Mundo"), GameLauncher.splitArgs("--render-distance 6  --world \"Meu Mundo\""))
        assertTrue(GameLauncher.splitArgs("   ").isEmpty())
        assertEquals(listOf(""), GameLauncher.splitArgs("\"\""))
    }

    @Test
    fun layoutJsonRoundTrips() {
        val layout = DefaultLayouts.classic()
        val back = ControlLayout.fromJson(JSONObject(layout.toJson().toString()))
        assertEquals(layout, back)
        val jump = back.buttons.first { it.id == "jump" }
        assertEquals("key space down", jump.action.pressCommand())
        assertEquals("key space up", jump.action.releaseCommand())
        assertNull(ControlAction.special(ControlAction.KEYBOARD).pressCommand())
        assertEquals("chat /time set day", ControlAction.command("chat /time set day").pressCommand())
        assertNull(ControlAction.command("chat /time set day").releaseCommand())
    }

    @Test
    fun readsContentModManifest() {
        val dir = Files.createTempDirectory("mod").toFile()
        val hmod = File(dir, "coords.hmod")
        ZipOutputStream(hmod.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("horizon-mod.toml"))
            zip.write("# manifest\nid = \"compass_bar\"\nname = \"Compass Bar\"\nversion = \"0.1.0\"\n\n[capabilities]\nid = \"nope\"\n".toByteArray())
            zip.closeEntry()
        }
        val m = Mods.readModManifest(hmod)!!
        assertEquals("compass_bar", m["id"])
        assertEquals("Compass Bar", m["name"])
        assertEquals("0.1.0", m["version"])
        assertEquals(Mods.Kind.MOD, Mods.kindOfFile("x.hmod"))
        assertEquals(Mods.Kind.SHADER, Mods.kindOfFile("x.hshader"))
        assertNull(Mods.kindOfFile("fabric-mod.jar"))
        dir.deleteRecursively()
    }
}

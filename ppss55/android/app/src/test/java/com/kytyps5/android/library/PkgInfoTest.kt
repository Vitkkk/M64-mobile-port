package com.kytyps5.android.library

import com.kytyps5.android.GameLibrary
import com.kytyps5.android.ScanResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkgInfoTest {

    /** Minimal PS4-style package: header, one entry (param.sfo) and its data. Synthetic, no real game data. */
    private fun ps4Pkg(contentId: String, sfo: ByteArray, encryptedSfo: Boolean = false, contentType: Int = 0x1A): ByteArray {
        val tableOffset = 0x100
        val dataOffset = 0x200
        val buf = ByteBuffer.allocate(dataOffset + sfo.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0, 0x7F434E54)
        buf.putInt(0x10, 1) // entry count
        buf.putInt(0x18, tableOffset)
        contentId.toByteArray().forEachIndexed { i, b -> buf.put(0x40 + i, b) }
        buf.putInt(0x70, 0xF)
        buf.putInt(0x74, contentType)
        buf.putInt(tableOffset, 0x1000)
        buf.putInt(tableOffset + 8, if (encryptedSfo) Int.MIN_VALUE else 0)
        buf.putInt(tableOffset + 16, dataOffset)
        buf.putInt(tableOffset + 20, sfo.size)
        sfo.forEachIndexed { i, b -> buf.put(dataOffset + i, b) }
        return buf.array()
    }

    @Test
    fun readsPs4HeaderAndPlaintextParamSfo() {
        val info = PkgInfo.parse(ps4Pkg("UP0000-CUSA12345_00-ABCDEFGHIJKLMNOP", SfoFixture.build("TITLE" to "Test Game")))!!
        assertEquals(PkgInfo.Platform.PS4, info.platform)
        assertEquals("UP0000-CUSA12345_00-ABCDEFGHIJKLMNOP", info.contentId)
        assertEquals("CUSA12345", info.titleId)
        assertEquals("Test Game", info.title)
        assertEquals("jogo/app", info.kind)
        assertTrue(info.explanation().contains("PS4"))
    }

    @Test
    fun encryptedParamSfoGivesNoTitleButStillIdentifies() {
        val info = PkgInfo.parse(ps4Pkg("EP0000-CUSA54321_00-0000000000000000", SfoFixture.build("TITLE" to "X"), encryptedSfo = true))!!
        assertNull(info.title)
        assertEquals("CUSA54321", info.titleId)
    }

    @Test
    fun ps5TitleIdInCntContainerIsPs5() {
        val info = PkgInfo.parse(ps4Pkg("UP0000-PPSA01234_00-ABCDEFGHIJKLMNOP", SfoFixture.build("TITLE" to "Five"), contentType = 0x1E))!!
        assertEquals(PkgInfo.Platform.PS5, info.platform)
        assertEquals("atualização (patch)", info.kind)
    }

    @Test
    fun unknownContainerIsIdentifiedByContentIdScan() {
        val bytes = ByteArray(4096).also { it[0] = 0x7F; it[1] = 'F'.code.toByte() }
        "IP9100-PPSA99999_00-ZZZZZZZZZZZZZZZZ".toByteArray().copyInto(bytes, 0x400)
        val info = PkgInfo.parse(bytes)!!
        assertEquals(PkgInfo.Platform.PS5, info.platform)
        assertEquals("PPSA99999", info.titleId)
    }

    @Test
    fun nonPackageIsRejectedAndTruncatedPs4DoesNotCrash() {
        assertNull(PkgInfo.parse("hello world".toByteArray()))
        val truncated = ps4Pkg("UP0000-CUSA12345_00-ABCDEFGHIJKLMNOP", SfoFixture.build("TITLE" to "T")).copyOf(0x90)
        assertNotNull(PkgInfo.parse(truncated))
    }

    @Test
    fun libraryListsPkgAsNotPlayable() {
        val dir = Files.createTempDirectory("pkgscan").toFile()
        try {
            dir.resolve("game.pkg").writeBytes(ps4Pkg("UP0000-CUSA12345_00-ABCDEFGHIJKLMNOP", SfoFixture.build("TITLE" to "Listed")))
            dir.resolve("notes.pkg").writeText("not a package")
            val games = (GameLibrary.scan(dir) as ScanResult.Found).games
            assertEquals(1, games.size)
            assertEquals("Listed", games[0].title)
            assertFalse(games[0].playable)
        } finally {
            dir.deleteRecursively()
        }
    }
}

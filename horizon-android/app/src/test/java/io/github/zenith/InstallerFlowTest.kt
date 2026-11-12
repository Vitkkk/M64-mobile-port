package io.github.zenith

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.zenith.data.Paths
import io.github.zenith.install.GameVersions
import io.github.zenith.install.Installer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** O fluxo da importação dentro do app (estado, mensagens), com o kit real do APK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallerFlowTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun fakeClient(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("version.json")); z.write("""{"id":"26.3","name":"26.3"}""".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("net/minecraft/client/main/Main.class")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun aModifiedJarIsRefusedWithAClearMessage() {
        assumeTrue("o kit do preparador não está em assets (gerado por build-native.sh)", GameVersions.pinned(context) != null)
        Paths.init(context)
        Installer.reset()
        val bytes = fakeClient()
        Installer.importJar(context, "26.3.jar", bytes.size.toLong()) { bytes.inputStream() }
        val s = Installer.state.value
        assertEquals(Installer.Step.FAILED, s.step)
        assertEquals(false, s.compatible)
        assertTrue(s.detected, s.detected.contains("Minecraft 26.3"))
        assertTrue(s.error!!, s.error!!.contains("não é o original"))
    }

    @Test
    fun prepareRefusesWhileRequiredFilesAreMissing() {
        assumeTrue(GameVersions.pinned(context) != null)
        Paths.init(context)
        Installer.clearCache()
        Installer.reset()
        Installer.prepareLocal(context, keepFiles = true)
        val s = Installer.state.value
        assertEquals(Installer.Step.FAILED, s.step)
        assertTrue(s.error!!, s.error!!.startsWith("Ainda falta"))
        assertTrue(s.error!!, s.error!!.contains("Client 26.3"))
        assertTrue(s.error!!, s.error!!.contains("Servidor 26.3"))
    }
}

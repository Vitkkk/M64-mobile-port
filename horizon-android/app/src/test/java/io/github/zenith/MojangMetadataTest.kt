package io.github.zenith

import io.github.zenith.install.MojangFiles
import io.github.zenith.install.PinnedVersion
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Opcional (-Pzenith.network=…/research-lock.json): baixa só os metadados reais (JSON da versão
 * pelo hash fixado e o índice de assets) e confere o plano. Nenhum arquivo do jogo é baixado.
 */
class MojangMetadataTest {
    @Test
    fun planFromTheRealManifests() {
        val lockPath = System.getProperty("zenith.network")
        assumeTrue(lockPath != null)
        val pinned = PinnedVersion.fromLock(JSONObject(File(lockPath!!).readText()))
        val dir = Files.createTempDirectory("zenith-meta").toFile()
        try {
            val mc = MojangFiles(dir, pinned)
            val manifest = mc.manifest()
            val plan = mc.plan(manifest, mc.assetIndex(manifest))
            val total = plan.sumOf { it.size ?: 0 }
            println("Plano real: ${plan.size} arquivos, ${total shr 20} MB")
            println("  client+server: ${plan.take(2).sumOf { it.size ?: 0 } shr 20} MB")
            println("  bibliotecas: ${plan.count { it.file.path.contains("/libraries/") }}")
            println("  assets: ${plan.count { it.file.path.contains("/assets/objects/") }}, ${plan.filter { it.file.path.contains("/assets/objects/") }.sumOf { it.size ?: 0 } shr 20} MB")
            assertTrue(plan.all { it.hash != null && it.url.startsWith("https://") })
            assertTrue(plan.any { it.url.startsWith("https://piston-data.mojang.com/") })
            assertTrue(plan.any { it.url.startsWith("https://resources.download.minecraft.net/") })
            assertTrue(total in (200L shl 20)..(2000L shl 20))
        } finally {
            dir.deleteRecursively()
        }
    }
}

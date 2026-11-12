package io.github.zenith.install

import android.content.Context
import org.json.JSONObject
import java.util.zip.ZipInputStream

/**
 * As versões que o motor deste APK consegue rodar: exatamente a que o Horizon fixado
 * reimplementa, com os hashes que ele exige (`research-lock.json`, dentro do kit do
 * preparador em assets/prepare-kit.zip). Nenhuma outra versão é oferecida.
 */
data class PinnedVersion(
    val version: String,
    /** sha1 do JSON da versão; o primeiro é o fixado, os outros são republicações aceitas. */
    val manifests: List<String>,
    val clientSha1: String,
    val serverSha1: String,
    val javaMajor: Int,
) {
    /** O JSON da versão pelo hash: imutável no piston-meta, ao contrário do índice "latest". */
    fun manifestUrl(sha1: String) = "https://piston-meta.mojang.com/v1/packages/$sha1/$version.json"

    companion object {
        fun fromLock(lock: JSONObject): PinnedVersion {
            val game = lock.getJSONObject("minecraft")
            val manifests = mutableListOf(game.getString("manifest_sha1"))
            game.optJSONArray("accepted_manifest_sha1")?.let { a ->
                for (i in 0 until a.length()) manifests += a.getString(i)
            }
            return PinnedVersion(
                version = game.getString("version"),
                manifests = manifests,
                clientSha1 = game.getString("client_sha1"),
                serverSha1 = game.getString("server_sha1"),
                javaMajor = game.optInt("java_major", lock.optInt("java_major", 25)),
            )
        }
    }
}

object GameVersions {
    const val KIT_ASSET = "prepare-kit.zip"

    @Volatile private var cached: PinnedVersion? = null

    /** A versão fixada no kit deste APK, ou null se o APK foi compilado sem o preparador. */
    fun pinned(context: Context): PinnedVersion? {
        cached?.let { return it }
        val lock = runCatching {
            context.assets.open(KIT_ASSET).use { raw ->
                ZipInputStream(raw).use { zip ->
                    generateSequence { zip.nextEntry }.firstOrNull { it.name == "research-lock.json" }
                        ?.let { zip.readBytes().toString(Charsets.UTF_8) }
                }
            }
        }.getOrNull() ?: return null
        return runCatching { PinnedVersion.fromLock(JSONObject(lock)) }.getOrNull()?.also { cached = it }
    }

    /** Versões que dá para escolher ao criar uma instância. */
    fun available(context: Context): List<PinnedVersion> = listOfNotNull(pinned(context))
}

package io.github.zenith.install

import org.json.JSONArray
import org.json.JSONObject

/**
 * As bibliotecas do client que o `horizon-prepare` exige, com as mesmas regras que ele aplica
 * (crates/horizon-prepare/src/artifacts.rs: library_allowed, native_library_matches, runs_without),
 * para o Zenith saber, antes de preparar, o que falta numa instalação importada.
 *
 * No Android o preparador se vê como "linux" (rule_system) na arquitetura do processo.
 */
object ClientLibraries {
    data class Library(val name: String, val path: String, val sha1: String, val size: Long, val url: String, val optional: Boolean)

    /** `rule_architectures` do Horizon para a ABI do Android. */
    fun architectures(abi: String): List<String> = when (abi) {
        "arm64-v8a" -> listOf("arm64", "aarch64")
        "x86_64" -> listOf("x86_64", "amd64", "x64")
        "armeabi-v7a" -> listOf("arm")
        "x86" -> listOf("i686")
        else -> listOf(abi)
    }

    fun required(manifest: JSONObject, abi: String, system: String = "linux"): List<Library> {
        val libraries = manifest.optJSONArray("libraries") ?: JSONArray()
        val archs = architectures(abi)
        val names = (0 until libraries.length()).mapNotNull { libraries.getJSONObject(it).optString("name").takeIf { n -> n.isNotBlank() } }
        val out = mutableListOf<Library>()
        for (i in 0 until libraries.length()) {
            val lib = libraries.getJSONObject(i)
            val artifact = lib.optJSONObject("downloads")?.optJSONObject("artifact") ?: continue
            if (!allowed(lib, system, archs) || !nativeMatches(lib, names, archs)) continue
            val name = lib.optString("name")
            out += Library(
                name, artifact.optString("path"), artifact.optString("sha1"),
                artifact.optLong("size"), artifact.optString("url"), runsWithout(name),
            )
        }
        return out
    }

    /** As regras do launcher oficial: a última que casa decide; sem regras, permitido. */
    fun allowed(library: JSONObject, system: String, archs: List<String>): Boolean {
        val rules = library.optJSONArray("rules") ?: return true
        if (rules.length() == 0) return true
        var allowed = false
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            val os = rule.optJSONObject("os") ?: JSONObject()
            if (os.optString("name", system) != system) continue
            if (os.has("arch")) {
                val pattern = Regex("^(?:${os.optString("arch")})$")
                if (archs.none { pattern.matches(it) }) continue
            }
            // Só JSONs antigos pedem a versão do kernel; aqui ninguém casa com ela.
            if (os.has("version")) continue
            val features = rule.opt("features")
            val hasFeatures = when (features) {
                null, JSONObject.NULL, false -> false
                is JSONObject -> features.length() > 0
                is JSONArray -> features.length() > 0
                is String -> features.isNotEmpty()
                else -> true
            }
            if (hasFeatures) continue
            allowed = rule.optString("action") == "allow"
        }
        return allowed
    }

    private fun classifierEndsWith(classifier: String, suffixes: List<String>) =
        suffixes.any { classifier == it || classifier.removeSuffix(it).let { rest -> rest != classifier && rest.endsWith("-") } }

    /** Nativos x64 ou ARM: fica o desta máquina (native_library_matches). */
    fun nativeMatches(library: JSONObject, names: List<String>, archs: List<String>): Boolean {
        val arm = "arm64" in archs
        val name = library.optString("name")
        val classifier = name.split(':').getOrNull(3) ?: ""
        if (classifierEndsWith(classifier, listOf("arm64", "aarch64", "aarch_64"))) return arm
        if (classifierEndsWith(classifier, listOf("x86_64", "x64"))) return !arm
        if (arm && classifier.startsWith("natives-")) {
            return listOf("arm64", "aarch64").none { "$name-$it" in names }
        }
        return true
    }

    /** Os transportes nativos do Netty: o jogo roda sem eles (runs_without). */
    fun runsWithout(name: String): Boolean {
        val parts = name.split(':')
        return parts.getOrNull(0) == "io.netty" &&
            parts.getOrNull(1)?.startsWith("netty-transport-native-") == true && parts.size > 3
    }
}

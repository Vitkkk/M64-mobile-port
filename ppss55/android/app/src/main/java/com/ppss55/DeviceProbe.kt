package com.ppss55

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Device facts for the launcher, all read from the device (JNI probe in src/main/cpp/probe.cpp and
 * Android APIs). Nothing here is hard-coded per device.
 */
object DeviceProbe {
    private val loaded: Throwable? = runCatching { System.loadLibrary("ppss55_probe") }.exceptionOrNull()

    @JvmStatic private external fun nativeVulkanInfo(): String
    @JvmStatic private external fun nativeCpuInfo(): String
    @JvmStatic private external fun nativeTryLoadCore(dir: String): String

    /** "key=value" lines into a map (order kept). */
    fun parse(text: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) {
                val key = line.substring(0, i)
                // Repeated keys (e.g. several CPU parts) are joined.
                map[key] = map[key]?.let { "$it, ${line.substring(i + 1)}" } ?: line.substring(i + 1)
            }
        }
        return map
    }

    fun vulkan(): Map<String, String> =
        loaded?.let { mapOf("error" to "sonda nativa indisponível: ${it.message}") } ?: parse(nativeVulkanInfo())

    fun cpu(): Map<String, String> =
        loaded?.let { mapOf("error" to "sonda nativa indisponível: ${it.message}") } ?: parse(nativeCpuInfo())

    /** dlopen() of libSDL3.so + libmain.so from the APK's native library dir; the result or dlerror(). */
    fun tryLoadCore(context: Context): Map<String, String> =
        loaded?.let { mapOf("error" to "sonda nativa indisponível: ${it.message}") }
            ?: parse(nativeTryLoadCore(context.applicationInfo.nativeLibraryDir))

    /** Highest Vulkan version the system advertises (PackageManager feature), e.g. "1.3". */
    fun advertisedVulkan(context: Context): String? {
        val pm = context.packageManager
        val feature = pm.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            ?: return null
        val v = feature.version
        return "${v ushr 22}.${(v ushr 12) and 0x3FF}"
    }

    /** Short human summary for the launcher's device card. */
    fun summary(context: Context): List<Pair<String, String>> {
        val cpu = cpu()
        val vk = vulkan()
        val soc = listOfNotNull(Build.SOC_MANUFACTURER, Build.SOC_MODEL).joinToString(" ").ifBlank { cpu["hardware"] ?: "?" }
        val maxMhz = cpu.filterKeys { it.endsWith(".max_mhz") }.values.mapNotNull { it.toIntOrNull() }.maxOrNull()
        val rows = mutableListOf<Pair<String, String>>()
        rows += "Aparelho" to "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        rows += "CPU" to buildString {
            append(soc)
            append(" · ${cpu["cores"] ?: "?"} núcleos")
            maxMhz?.let { append(" · até $it MHz") }
            append(" · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
        }
        rows += "Recursos ARM (FEX)" to listOf("atomics_lse" to "LSE", "lrcpc" to "RCpc", "lrcpc2" to "RCpc2", "flagm" to "FlagM", "afp" to "AFP", "sve2" to "SVE2")
            .joinToString(" ") { (k, label) -> if (cpu[k] == "1") "$label✓" else "$label✗" }
        rows += "RAM" to "${cpu["ram_mib"]?.toLongOrNull()?.let { it / 1024.0 }?.let { "%.1f GB".format(it) } ?: "?"} · página ${cpu["page_size"] ?: "?"} B"
        if (vk["error"] != null) {
            rows += "Vulkan" to "ERRO: ${vk["error"]}"
        } else {
            rows += "GPU" to (vk["device0.name"] ?: "nenhuma GPU Vulkan")
            rows += "Vulkan" to "dispositivo ${vk["device0.api"] ?: "?"} · loader ${vk["instance_version"] ?: "?"} · sistema anuncia ${advertisedVulkan(context) ?: "?"}"
            vk["device0.driver"]?.let { rows += "Driver do sistema" to it.trim() }
            vk["device0.device_local_mib"]?.let { rows += "Memória GPU (heap local)" to "$it MiB" }
        }
        return rows
    }
}

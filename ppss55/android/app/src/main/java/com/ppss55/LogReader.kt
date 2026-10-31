package com.ppss55

import java.util.concurrent.TimeUnit

/**
 * Reads the emulator's own log lines back from logcat. The core routes stdout/stderr to logcat
 * (tag "Kyty", src/main.cpp RedirectStdioToLogcat); an app may read log records of its own UID, which
 * covers the separate ":game" process. Nothing is invented: if logcat returns nothing, that is shown.
 */
object LogReader {
    private val TAGS = arrayOf("Kyty:V", "SDL:V", "KytyPS5:V", "libc:F", "DEBUG:F", "AndroidRuntime:E", "*:S")

    /** Lines logged since [sinceEpochMs] (0 = whatever logcat still holds, last [maxLines]). */
    fun read(sinceEpochMs: Long = 0, maxLines: Int = 4000): List<String> {
        val args = mutableListOf("logcat", "-d", "-v", "time")
        if (sinceEpochMs > 0) {
            args += listOf("-T", "%d.%03d".format(sinceEpochMs / 1000, sinceEpochMs % 1000))
        } else {
            args += listOf("-t", maxLines.toString())
        }
        args += TAGS
        return try {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor(5, TimeUnit.SECONDS)
            lines.takeLast(maxLines)
        } catch (e: Exception) {
            listOf("Não foi possível ler o logcat: ${e.message}")
        }
    }

    /** FEX self-test verdict from the lines printed by Cpu::GuestCpu::RunGuestCpuSelfTest. */
    fun selfTestSummary(lines: List<String>): String {
        val relevant = lines.filter { "FEX-SELFTEST" in it }
        if (relevant.isEmpty()) return "Nenhuma linha FEX-SELFTEST encontrada no log (o teste não rodou ou o processo foi encerrado antes)."
        val done = relevant.lastOrNull { "DONE" in it }
        val verdict = when {
            done == null -> "INCOMPLETO"
            Regex("failed=0\\b").containsMatchIn(done) -> "PASSOU"
            else -> "FALHOU"
        }
        return "Resultado: $verdict\n\n" + relevant.joinToString("\n") { it.substringAfter("FEX-SELFTEST").let { s -> "FEX-SELFTEST$s" } }
    }
}

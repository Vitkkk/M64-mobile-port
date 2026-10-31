package com.ppss55

import android.content.Context
import android.os.Environment
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-session log file in Download/PPSS55/logs. The game process creates it and hands its path to the
 * core (KYTY_STDOUT_FILE); the core writes every console line into it, flushed per line, so the file
 * keeps everything up to the moment the game dies. The launcher then checks it for crash markers.
 */
object SessionLog {
    private const val PREFIX = "ppss55-"
    const val ENV_STDOUT_FILE = "KYTY_STDOUT_FILE"
    private val CRASH_MARKERS = listOf(
        // Kyty's EXIT_IF / EXIT_NOT_IMPLEMENTED report (common/assert.cpp), then Android's own crash lines.
        "--- Fatal Error ---", "--- Error ---", "Fatal signal", "Abort message", "backtrace:", "FATAL EXCEPTION",
    )

    fun directory(context: Context): File {
        val shared = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PPSS55/logs")
        if ((shared.isDirectory || shared.mkdirs()) && shared.canWrite()) return shared
        return File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
    }

    /** Called in the game process before the emulator loads. Returns the file being written. */
    fun start(context: Context, label: String): File? = runCatching {
        val dir = directory(context)
        dir.listFiles { f -> f.name.startsWith(PREFIX) }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val file = File(dir, "$PREFIX$stamp-$safe.txt")
        // No Vulkan query here: the emulator may load a custom Turnip driver through libadrenotools
        // and must be the first to touch Vulkan in this process.
        val header = buildString {
            append("PPSS55 sessão: $label\n")
            append("Aparelho: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n")
            append("SoC: ${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}\n")
            append("App: ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()}\n\n")
        }
        file.writeText(header)
        // The core appends every console line here itself (patch 0003, KYTY_STDOUT_FILE), which
        // works even where an app gets nothing back from logcat.
        android.system.Os.setenv(ENV_STDOUT_FILE, file.absolutePath, true)
        file
    }.getOrNull()

    /** Launcher side: stop logcat processes a crashed game left running (same app UID only). */
    fun stopOrphans() {
        runCatching { ProcessBuilder("pkill", "-f", "logcat -v threadtime --pid=").start().waitFor() }
    }

    fun latest(context: Context, since: Long): File? =
        directory(context).listFiles { f -> f.name.startsWith(PREFIX) && f.lastModified() >= since - 5_000 }
            ?.maxByOrNull { it.lastModified() }

    /** Lines that look like a crash, with a little context; empty when none. */
    fun crashExcerpt(file: File, context: Int = 30): List<String> {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val first = lines.indexOfFirst { line -> CRASH_MARKERS.any { line.contains(it) } }
        if (first < 0) return emptyList()
        return lines.subList(maxOf(0, first - context), minOf(lines.size, first + context * 3))
    }

    /**
     * Compact report for sharing: header, every distinct stub the game actually *called* (missing
     * HLE functions), error blocks, then the last [tailLines] lines - small enough to paste in a chat.
     */
    fun report(file: File, tailLines: Int = 250): String {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val header = lines.takeWhile { it.isNotBlank() }.take(6)
        val stubs = lines.filter { "Unresolved import stub called" in it }
            .map { it.substringAfter("symbol=", it.substringAfter("called: ")).substringBefore(" type=") }
            .distinct()
        val errors = lines.withIndex().filter { (_, l) -> CRASH_MARKERS.any { l.contains(it) } }
            .take(5).flatMap { (i, _) -> lines.subList(i, minOf(lines.size, i + 12)) }
        return buildString {
            header.forEach { appendLine(it) }
            appendLine("Linhas no log: ${lines.size}")
            appendLine()
            appendLine("=== Funções ausentes chamadas pelo jogo (${stubs.size}) ===")
            stubs.take(60).forEach { appendLine(it) }
            appendLine()
            appendLine("=== Erros ===")
            if (errors.isEmpty()) appendLine("(nenhum relatório de erro do núcleo)") else errors.forEach { appendLine(it) }
            appendLine()
            appendLine("=== Últimas $tailLines linhas ===")
            lines.takeLast(tailLines).forEach { appendLine(it.take(300)) }
        }
    }

    fun tail(file: File, n: Int): String = runCatching { file.readLines().takeLast(n).joinToString("\n") }.getOrDefault("")

    private const val KEEP = 20
}

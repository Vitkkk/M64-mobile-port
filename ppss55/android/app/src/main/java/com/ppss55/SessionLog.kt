package com.ppss55

import android.content.Context
import android.os.Environment
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-session log file. The game process starts `logcat --pid=<self>` writing straight into a file in
 * Download/PPSS55/logs, so the file keeps everything up to the moment the game dies, even on a
 * native crash (logcat is a separate process). The launcher then finds that file, stops a logcat
 * left behind by a crash, and checks it for crash markers.
 */
object SessionLog {
    private const val PREFIX = "ppss55-"
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
        ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(file))
            .start()
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

    fun tail(file: File, n: Int): String = runCatching { file.readLines().takeLast(n).joinToString("\n") }.getOrDefault("")

    private const val KEEP = 20
}

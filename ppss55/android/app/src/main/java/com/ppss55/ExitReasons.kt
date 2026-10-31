package com.ppss55

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File

/**
 * Why the game process (":game") ended, as recorded by Android itself
 * (ActivityManager.getHistoricalProcessExitReasons, API 30+). This catches what the core cannot log:
 * the low-memory killer, signals, and native crashes (with the tombstone trace on API 31+).
 */
object ExitReasons {

    data class Exit(val reason: String, val text: String, val tombstone: String?)

    fun lastGameExit(context: Context, since: Long): Exit? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 10) }.getOrNull()
            ?.firstOrNull { it.processName.endsWith(":game") && it.timestamp >= since - 5_000 } ?: return null
        val reason = reasonName(info.reason)
        val text = buildString {
            appendLine("Motivo: $reason (código ${info.reason}), status ${info.status}")
            info.description?.let { appendLine("Descrição: $it") }
            appendLine("Importância: ${info.importance} · PSS: ${info.pss / 1024} MB · RSS: ${info.rss / 1024} MB")
            appendLine("Explicação: ${explain(info.reason, info.status)}")
        }
        val tombstone = if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            runCatching { info.traceInputStream?.use { String(it.readBytes()) } }.getOrNull()
        } else {
            null
        }
        return Exit(reason, text, tombstone)
    }

    /** Recent ":game" exits, newest first, for the diagnostics screen. */
    fun recent(context: Context, max: Int = 5): String {
        val am = context.getSystemService(ActivityManager::class.java) ?: return "ActivityManager indisponível"
        val list = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 20) }.getOrNull()
            ?.filter { it.processName.endsWith(":game") }?.take(max).orEmpty()
        if (list.isEmpty()) return "O Android não tem registro de saídas do processo do jogo."
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.US)
        return list.joinToString("\n") { i ->
            "${fmt.format(java.util.Date(i.timestamp))} · ${reasonName(i.reason)} · ${i.description ?: ""} · PSS ${i.pss / 1024} MB\n  → ${explain(i.reason, i.status)}"
        }
    }

    /** Appends the exit record to the session log so "Compartilhar log" carries it. */
    fun appendTo(file: File, exit: Exit) {
        runCatching {
            file.appendText("\n=== Saída do processo do jogo (Android) ===\n${exit.text}")
            exit.tombstone?.let { file.appendText("\n=== Tombstone (crash nativo) ===\n${it.take(60_000)}\n") }
        }
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN"
    }

    private fun signalName(sig: Int) = when (sig) {
        4 -> "SIGILL"; 6 -> "SIGABRT"; 7 -> "SIGBUS"; 9 -> "SIGKILL"; 11 -> "SIGSEGV"; 15 -> "SIGTERM"
        else -> "sinal $sig"
    }

    private fun explain(r: Int, status: Int) = when (r) {
        ApplicationExitInfo.REASON_SIGNALED -> when (status) {
            9 -> "SIGKILL: o sistema matou o processo (normalmente falta de memória ou o gerenciador de RAM do fabricante)."
            11, 7, 4, 6 -> "${signalName(status)}: crash nativo (acesso inválido à memória ou instrução inválida) no núcleo, FEX ou driver de GPU."
            else -> "${signalName(status)}: o processo recebeu esse sinal."
        }
        ApplicationExitInfo.REASON_LOW_MEMORY -> "o Android matou o jogo por falta de memória RAM."
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash no código nativo (núcleo, FEX ou driver de GPU); veja o tombstone."
        ApplicationExitInfo.REASON_EXIT_SELF -> "o próprio jogo/núcleo encerrou o processo."
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "o sistema encerrou por uso excessivo de recursos (CPU/temperatura)."
        ApplicationExitInfo.REASON_ANR -> "o app parou de responder."
        else -> "ver código e descrição."
    }
}

package io.github.zenith.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zenith.install.InstallService
import io.github.zenith.install.Installer
import io.github.zenith.ui.LauncherViewModel
import io.github.zenith.ui.theme.Mono

/**
 * O instalador na tela: BAIXAR, o andamento (etapa, barra, MB, velocidade, tempo restante),
 * Cancelar, Tentar de novo e o log. O trabalho roda no InstallService; isto só mostra.
 */
@Composable
fun InstallPanel(
    vm: LauncherViewModel,
    title: String = "Instalar o jogo",
    onGoToAccount: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val state by Installer.state.collectAsState()
    val version = remember { io.github.zenith.install.GameVersions.pinned(context)?.version ?: "?" }
    var keep by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val blocker = remember(vm.accounts, state.step) { Installer.blocker(context) }
    val start = rememberInstallStarter { keep }
    LaunchedEffect(state.step) {
        if (state.step == Installer.Step.DONE) vm.refresh()
    }

    Section(title, subtitle = "Minecraft: Java Edition $version · Horizon (Rust)") {
        when {
            state.running -> {
                SourceLine(state)
                StepList(state)
                Spacer(Modifier.height(6.dp))
                Text(state.step.label, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { state.overall }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                if (state.fraction in 0f..1f && state.step != Installer.Step.DOWNLOAD) {
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                } else if (state.fraction < 0f) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
                if (state.bytesTotal > 0) {
                    val eta = if (state.speed > 0) (state.bytesTotal - state.bytes) / state.speed else -1
                    Text(
                        "${formatBytes(state.bytes)} de ${formatBytes(state.bytesTotal)}" +
                            (if (state.speed > 0) " · ${formatBytes(state.speed)}/s" else "") +
                            (if (eta in 1..86_400) " · faltam ${formatSeconds(eta)}" else "") +
                            (if (state.filesTotal > 1) " · ${state.files}/${state.filesTotal} arquivos" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { InstallService.cancel(context) }) { Text("Cancelar") }
                    TextButton(onClick = { showLog = true }) { Text("Ver log") }
                }
                Text(
                    "Pode sair do app: a instalação continua e aparece na notificação.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            vm.resources.ready && state.step != Installer.Step.FAILED -> {
                if (state.step == Installer.Step.DONE) { SourceLine(state); StepList(state) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.step == Installer.Step.DONE) "Instalação concluída!" else "Instalado", fontWeight = FontWeight.Bold)
                }
                Text(vm.resources.message, style = MaterialTheme.typography.bodySmall)
                state.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                if (onPlay != null) {
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = onPlay, shape = RoundedCornerShape(12.dp)) { Text("JOGAR", fontWeight = FontWeight.Black) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { start() }) { Text("Instalar de novo") }
                    if (state.step == Installer.Step.DONE) TextButton(onClick = { showLog = true }) { Text("Ver log") }
                }
            }
            else -> {
                if (state.step == Installer.Step.FAILED || state.step == Installer.Step.DONE) {
                    SourceLine(state)
                    StepList(state)
                }
                if (state.step == Installer.Step.FAILED) {
                    Text(state.error ?: "A instalação parou", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                } else if (state.step == Installer.Step.DONE) {
                    Text("✓ ${state.message}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    state.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "Baixa os arquivos oficiais da Mojang (~700 MB), confere cada um, baixa um Java temporário " +
                        "e prepara os recursos do Horizon aqui no celular. Leva alguns minutos; precisa de ~2,2 GB livres enquanto roda.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                if (blocker != null) {
                    Text(blocker, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (onGoToAccount != null && blocker.contains("Microsoft")) {
                        TextButton(onClick = onGoToAccount) { Text("Ir para Conta") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keep, { keep = it })
                    Text("Guardar os arquivos baixados (reinstalar sem baixar de novo)", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { start() }, enabled = blocker == null, shape = RoundedCornerShape(12.dp)) {
                        Icon(if (state.step == Installer.Step.FAILED) Icons.Filled.Refresh else Icons.Filled.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.step == Installer.Step.FAILED) "TENTAR DE NOVO" else "BAIXAR", fontWeight = FontWeight.Black)
                    }
                    if (state.step == Installer.Step.FAILED) TextButton(onClick = { showLog = true }) { Text("Ver log") }
                }
            }
        }
    }

    if (showLog) {
        val text = remember(state) { runCatching { Installer.logFile.readText().takeLast(40_000) }.getOrDefault("(sem log)") }
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("Log da instalação") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(text, style = Mono, modifier = Modifier.horizontalScroll(rememberScrollState())) }
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("Fechar") } },
        )
    }
}

/** O arquivo ou pasta da importação, o que foi detectado nele e se é compatível. */
@Composable
private fun SourceLine(state: Installer.State) {
    if (state.source.isBlank()) return
    Text("Importando: ${state.source}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    if (state.detected.isNotBlank()) {
        val ok = state.compatible
        Text(
            "Detectado: ${state.detected}" + when (ok) { true -> " · compatível ✓"; false -> " · incompatível ✗"; null -> "" },
            style = MaterialTheme.typography.bodySmall,
            color = when (ok) { false -> MaterialTheme.colorScheme.error; true -> MaterialTheme.colorScheme.primary; null -> MaterialTheme.colorScheme.onSurfaceVariant },
        )
    }
    Spacer(Modifier.height(4.dp))
}

/** "Verificando o JAR... ✓" para cada etapa já feita, e a atual com "...". */
@Composable
private fun StepList(state: Installer.State) {
    if (state.done.isEmpty() && !state.running) return
    state.done.forEach { Text("${it.label}... ✓", style = MaterialTheme.typography.bodySmall) }
    if (state.running) Text("${state.step.label}...", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    if (state.step == Installer.Step.FAILED) Text("✗ parou aqui", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** Começa a instalação, pedindo antes a permissão de notificação (Android 13+). */
@Composable
fun rememberInstallStarter(keepDownloads: () -> Boolean = { false }): () -> Unit {
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        InstallService.start(context, keepDownloads())
    }
    return {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else InstallService.start(context, keepDownloads())
    }
}

fun formatSeconds(s: Long): String = when {
    s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
    s >= 60 -> "${s / 60} min ${s % 60} s"
    else -> "$s s"
}

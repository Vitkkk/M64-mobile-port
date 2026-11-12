package io.github.zenith.ui.components

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import io.github.zenith.install.GameVersions
import io.github.zenith.install.InstallService
import io.github.zenith.install.Installer
import io.github.zenith.install.LocalImport
import io.github.zenith.install.MojangFiles
import io.github.zenith.ui.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Importar do celular": um JAR local ou uma instalação inteira (de outro launcher), pelo
 * seletor do Android, sem permissão de armazenamento. Mostra o que já está pronto e o que falta
 * para o preparo, e só prepara quando o obrigatório está lá. O andamento aparece no InstallPanel.
 */
@Composable
fun LocalImportPanel(vm: LauncherViewModel) {
    val context = LocalContext.current
    val state by Installer.state.collectAsState()
    val pinned = remember { GameVersions.pinned(context) }
    var requirements by remember { mutableStateOf<List<LocalImport.Requirement>?>(null) }
    LaunchedEffect(state.step, vm.resources) {
        if (pinned == null || state.running) return@LaunchedEffect
        requirements = withContext(Dispatchers.IO) {
            val files = MojangFiles(Installer.minecraftDir, pinned)
            if (!files.minecraftDir.exists()) null
            else LocalImport.requirements(files, Build.SUPPORTED_ABIS.first(), Installer.javaReady())
        }
    }
    val jarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) InstallService.launch(context, InstallService.ACTION_IMPORT_JAR, uri)
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) InstallService.launch(context, InstallService.ACTION_IMPORT_TREE, uri)
    }
    val javaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) InstallService.launch(context, InstallService.ACTION_IMPORT_JAVA, uri)
    }
    // Os tipos que o seletor mostra para um .jar variam entre gerenciadores de arquivos.
    val jarTypes = arrayOf("application/java-archive", "application/x-java-archive", "application/zip", "application/octet-stream", "*/*")

    if (pinned == null || state.running) return
    Section("Importar do celular", subtitle = "Usa arquivos do Minecraft ${pinned.version} que já estão no aparelho (ex.: de outro launcher)") {
        Text(
            "Escolha o ${pinned.version}.jar (ex.: .minecraft/versions/${pinned.version}/${pinned.version}.jar) ou a pasta .minecraft " +
                "inteira. Pastas dentro de Android/data de outros apps o Android não deixa abrir.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { jarPicker.launch(jarTypes) }) {
                Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Importar JAR local")
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = { treePicker.launch(null) }) {
            Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Importar instalação existente")
        }

        val reqs = requirements
        if (reqs != null) {
            Spacer(Modifier.height(12.dp))
            Text("O que o Horizon precisa", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            reqs.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when { r.ok -> Icons.Filled.CheckCircle; r.required -> Icons.Filled.ErrorOutline; else -> Icons.Filled.RadioButtonUnchecked },
                        null, Modifier.size(18.dp),
                        tint = when { r.ok -> MaterialTheme.colorScheme.primary; r.required -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.outline },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.label + if (!r.required) " (opcional)" else "", style = MaterialTheme.typography.bodySmall)
                        Text(r.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val missingRequired = reqs.filter { it.required && !it.ok && !it.label.startsWith("Java") && !it.label.startsWith("JSON") }
            val serverMissing = reqs.any { it.label.startsWith("Servidor") && !it.ok }
            val javaMissing = reqs.any { it.label.startsWith("Java") && !it.ok }
            Spacer(Modifier.height(8.dp))
            if (serverMissing) {
                TextButton(onClick = { jarPicker.launch(jarTypes) }) { Text("Selecionar o server.jar ${pinned.version}") }
            }
            if (javaMissing) {
                TextButton(onClick = { javaPicker.launch(arrayOf("application/x-xz", "application/octet-stream", "*/*")) }) {
                    Text("Importar o Java 25 (.tar.xz), em vez de baixar")
                }
            }
            if (missingRequired.isNotEmpty()) {
                Text(
                    "Falta: " + missingRequired.joinToString { it.label } + ". Importe esses arquivos, ou use o BAIXAR " +
                        "(conta Microsoft dona do jogo): ele reaproveita tudo o que já foi importado e baixa só o resto.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { InstallService.launch(context, InstallService.ACTION_PREPARE_LOCAL, null) },
                enabled = missingRequired.isEmpty(),
                shape = RoundedCornerShape(12.dp),
            ) { Text("PREPARAR E INSTALAR", fontWeight = FontWeight.Black) }
            TextButton(onClick = { vm.work("Limpando…") { Installer.clearCache() }; requirements = null }) {
                Text("Descartar os arquivos importados")
            }
        }
    }
}

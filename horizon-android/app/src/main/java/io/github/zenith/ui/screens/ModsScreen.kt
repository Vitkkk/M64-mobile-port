package io.github.zenith.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zenith.data.Mods
import io.github.zenith.ui.LauncherViewModel
import io.github.zenith.ui.components.Chip
import io.github.zenith.ui.components.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ModsScreen(vm: LauncherViewModel) {
    val context = LocalContext.current
    val instance = vm.instance ?: return
    var tab by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    val installed = remember(instance, version, vm.busy) { Mods.installed(instance) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.work("Importando…") {
            val item = Mods.importFile(context, instance, uri)
            vm.toast(if (item != null) "${item.name} instalado em ${item.kind.label}" else "Importado")
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text("Mods e packs", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Instância: ${instance.name}. O Horizon roda mods de conteúdo (.hmod), resource packs e shaders " +
                    "próprios. Jars do Fabric/Forge não rodam.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Instalados (${installed.size})") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Explorar") })
        }
        when (tab) {
            0 -> InstalledList(vm, installed, onImport = { picker.launch(arrayOf("*/*")) }) { version++ }
            else -> BrowseList(vm)
        }
    }
}

@Composable
private fun InstalledList(vm: LauncherViewModel, installed: List<Mods.Installed>, onImport: () -> Unit, changed: () -> Unit) {
    val instance = vm.instance ?: return
    var deleting by remember { mutableStateOf<Mods.Installed?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Importar arquivo (.hmod, .zip, .hshader)")
            }
            Spacer(Modifier.height(8.dp))
            if (installed.isEmpty()) Text("Nada instalado nesta instância ainda. Veja a aba Explorar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Mods.Kind.entries.forEach { kind ->
            val list = installed.filter { it.kind == kind }
            if (list.isEmpty()) return@forEach
            item { Text(kind.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            items(list, key = { it.file.path }) { item ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.Bold)
                            Text(
                                listOfNotNull(item.version.takeIf { it.isNotBlank() }, item.file.name).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (!item.supported) Text("Mod nativo: precisa recompilar o motor (veja a documentação)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            if (item.kind == Mods.Kind.MOD && item.enabled) Text("Reinicie o jogo depois de ligar ou desligar", style = MaterialTheme.typography.bodySmall)
                        }
                        if (item.supported) Switch(checked = item.enabled, onCheckedChange = {
                            Mods.setEnabled(instance, item, it); changed()
                        })
                        IconButton(onClick = { deleting = item }) { Icon(Icons.Filled.Delete, "Apagar") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Apagar ${item.name}?") },
            confirmButton = { TextButton(onClick = { Mods.delete(instance, item); deleting = null; changed() }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun BrowseList(vm: LauncherViewModel) {
    val instance = vm.instance ?: return
    var index by remember { mutableStateOf<Mods.Index?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("all") }
    var details by remember { mutableStateOf<Mods.Project?>(null) }
    LaunchedEffect(Unit) {
        try {
            index = withContext(Dispatchers.IO) { Mods.fetchIndex() }
        } catch (e: Exception) {
            error = "Não deu para baixar o índice de mods: ${e.message}"
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Buscar no índice do Horizon") },
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            val kinds = listOf("all" to "Todos", "mod" to "Mods", "resourcepack" to "Resource packs", "shader" to "Shaders", "native-mod" to "Nativos")
            items(kinds) { (k, label) -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(label) }) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val list = index?.projects.orEmpty().filter {
            (kind == "all" || it.kind == kind) &&
                (query.isBlank() || it.name.contains(query, true) || it.summary.contains(query, true) || it.id.contains(query, true))
        }
        if (index == null && error == null) Text("Carregando o índice…")
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { p ->
                Card(
                    onClick = { details = p },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Chip(p.kindLabel, if (p.installable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            }
                            Text(p.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "v${p.version} · ${p.authors.joinToString()} · ${p.downloads} downloads",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (p.installable) IconButton(onClick = { install(vm, instance, index!!, p) }, enabled = vm.busy == null) {
                            Icon(Icons.Filled.Download, "Instalar")
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    details?.let { p ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(p.name) },
            text = {
                // Column com rolagem: LazyColumn dentro do AlertDialog não fica estável.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    run {
                        Text("${p.kindLabel} · v${p.version} · ${p.license}", style = MaterialTheme.typography.labelLarge)
                        Text("Autores: ${p.authors.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        Text("Código: ${p.source}", style = MaterialTheme.typography.bodySmall)
                        Text("Arquivo: ${p.fileName} (${formatBytes(p.fileSize)})" + if (p.signed) " · assinado" else " · sem assinatura", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text(p.description.ifBlank { p.summary })
                        if (!p.installable) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Mods nativos mudam o código do jogo e precisam de um motor compilado com eles " +
                                    "(no PC ou no CI). Não dá para instalar direto no celular.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (p.installable) TextButton(onClick = { details = null; install(vm, instance, index!!, p) }) { Text("Instalar") }
            },
            dismissButton = { TextButton(onClick = { details = null }) { Text("Fechar") } },
        )
    }
}

private fun install(vm: LauncherViewModel, instance: io.github.zenith.data.Instance, index: Mods.Index, p: Mods.Project) {
    vm.work("Baixando ${p.name}…") {
        Mods.install(instance, index, p) { f -> vm.progress = f }
        vm.toast("${p.name} instalado e ligado")
    }
}

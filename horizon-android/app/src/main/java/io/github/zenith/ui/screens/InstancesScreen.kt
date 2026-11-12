package io.github.zenith.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import io.github.zenith.install.GameVersions
import io.github.zenith.install.Installer
import io.github.zenith.ui.components.rememberInstallStarter
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zenith.controls.LayoutStore
import io.github.zenith.data.Instance
import io.github.zenith.data.InstanceStore
import io.github.zenith.ui.LauncherViewModel
import io.github.zenith.ui.components.BlockIcon
import io.github.zenith.ui.components.Chip
import io.github.zenith.ui.components.ChoiceRow
import java.text.DateFormat
import java.util.Date

@Composable
fun InstancesScreen(vm: LauncherViewModel) {
    var editing by remember { mutableStateOf<Instance?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Instance?>(null) }
    var installHint by remember { mutableStateOf(false) }
    val install by Installer.state.collectAsState()
    val startInstall = rememberInstallStarter()
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)) {
            item {
                Text("Instâncias", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Cada instância tem sua pasta: mundos, opções, resource packs, shaders, mods e prints.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }
            items(vm.instances, key = { it.id }) { inst ->
                val selected = inst.id == vm.instance?.id
                var menu by remember { mutableStateOf(false) }
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .let { if (selected) it.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else it }
                        .clickable { vm.selectInstance(inst) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BlockIcon(inst.icon, Color(inst.color), 52.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(inst.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (selected) { Spacer(Modifier.width(8.dp)); Chip("Selecionada") }
                            }
                            val worlds = remember(inst) { inst.worlds().size }
                            Text(
                                "$worlds mundos · " + if (inst.lastPlayed > 0) "jogada em " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(inst.lastPlayed)) else "nunca jogada",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (inst.quickServer.isNotBlank()) Text("Entra em ${inst.quickServer}", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Chip("Minecraft ${inst.version}")
                                Spacer(Modifier.width(6.dp))
                                when {
                                    vm.resources.ready && vm.resources.version.let { it == null || it == inst.version } -> Chip("Instalada")
                                    install.running -> Chip("Baixando ${(install.overall * 100).toInt()}%", MaterialTheme.colorScheme.tertiary)
                                    else -> TextButton(onClick = {
                                        vm.selectInstance(inst)
                                        if (Installer.blocker(context) == null) startInstall() else installHint = true
                                    }) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(4.dp)); Text("BAIXAR", fontWeight = FontWeight.Black) }
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Opções") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Editar") }, onClick = { menu = false; editing = inst })
                                DropdownMenuItem(text = { Text("Duplicar") }, onClick = {
                                    menu = false
                                    vm.work("Duplicando ${inst.name}…") { InstanceStore.duplicate(inst) }
                                })
                                DropdownMenuItem(text = { Text("Apagar") }, enabled = vm.instances.size > 1, onClick = { menu = false; deleting = inst })
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Nova instância") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (creating) InstanceDialog(null, onDismiss = { creating = false }, onSaveAndInstall = { name, color, icon, extra ->
        creating = false
        vm.work("Criando $name…") {
            val created = InstanceStore.create(name, color, icon, copyOptionsFrom = vm.instance)
            InstanceStore.save(extra(created))
            vm.settings.selectedInstance = created.id
        }
        if (!vm.resources.ready && !Installer.state.value.running) {
            if (Installer.blocker(context) == null) startInstall() else installHint = true
        }
    }) { name, color, icon, extra ->
        creating = false
        vm.work("Criando $name…") {
            val created = InstanceStore.create(name, color, icon, copyOptionsFrom = vm.instance)
            InstanceStore.save(extra(created))
            vm.settings.selectedInstance = created.id
        }
    }
    if (installHint) AlertDialog(
        onDismissRequest = { installHint = false },
        title = { Text("Não deu para baixar ainda") },
        text = { Text(Installer.blocker(context) ?: "") },
        confirmButton = { TextButton(onClick = { installHint = false }) { Text("OK") } },
    )
    editing?.let { inst ->
        InstanceDialog(inst, onDismiss = { editing = null }) { name, color, icon, extra ->
            editing = null
            vm.work("Salvando…") { InstanceStore.save(extra(inst.copy(name = name, color = color, icon = icon))) }
        }
    }
    deleting?.let { inst ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Apagar ${inst.name}?") },
            text = { Text("Os mundos, packs, mods e prints desta instância serão apagados de vez.") },
            confirmButton = { TextButton(onClick = { deleting = null; vm.work("Apagando…") { InstanceStore.delete(inst) } }) { Text("Apagar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun InstanceDialog(
    initial: Instance?,
    onDismiss: () -> Unit,
    onSaveAndInstall: ((String, Long, String, (Instance) -> Instance) -> Unit)? = null,
    onSave: (String, Long, String, (Instance) -> Instance) -> Unit,
) {
    val context = LocalContext.current
    // Só as versões que o motor deste APK roda (o Horizon fixado reimplementa uma).
    val versions = remember { GameVersions.available(context).map { it.version }.ifEmpty { listOf(io.github.zenith.BuildConfig.GAME_VERSION) } }
    var version by remember { mutableStateOf(initial?.version ?: versions.first()) }
    var name by remember { mutableStateOf(initial?.name ?: "Nova instância") }
    var color by remember { mutableStateOf(initial?.color ?: Instance.COLORS.first()) }
    var icon by remember { mutableStateOf(initial?.icon ?: "grass") }
    var server by remember { mutableStateOf(initial?.quickServer ?: "") }
    var world by remember { mutableStateOf(initial?.quickWorld ?: "") }
    var args by remember { mutableStateOf(initial?.extraArgs ?: "") }
    var layout by remember { mutableStateOf(initial?.layout ?: "") }
    @Composable fun item(content: @Composable () -> Unit) = content()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nova instância" else "Editar instância") },
        text = {
            // Column com rolagem, não LazyColumn: a lista é curta e o AlertDialog mede o conteúdo.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(name, { name = it.take(32) }, label = { Text("Nome") }, singleLine = true) }
                item {
                    ChoiceRow(
                        "Versão do Minecraft",
                        versions.map { it to "$it (Horizon)" },
                        version,
                        subtitle = "Só aparecem as versões que o motor Rust deste app roda",
                    ) { version = it }
                }
                item {
                    Text("Ícone", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Instance.ICONS) { k ->
                            Box(Modifier.clip(RoundedCornerShape(10.dp)).border(if (k == icon) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)).clickable { icon = k }) {
                                BlockIcon(k, Color(color), 44.dp)
                            }
                        }
                    }
                }
                item {
                    Text("Cor", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Instance.COLORS) { c ->
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                                    .border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    .clickable { color = c }
                            )
                        }
                    }
                }
                if (initial != null) {
                    item { OutlinedTextField(world, { world = it.trim() }, label = { Text("Abrir direto o mundo (pasta)") }, singleLine = true, supportingText = { Text("Mundos: " + initial.worlds().joinToString().ifBlank { "nenhum ainda" }) }) }
                    item { OutlinedTextField(server, { server = it.trim() }, label = { Text("Entrar direto no servidor (host:porta)") }, singleLine = true) }
                    item {
                        ChoiceRow(
                            "Layout de controles",
                            listOf("" to "O ativo") + LayoutStore.all().map { it.id to it.name },
                            layout,
                        ) { layout = it }
                    }
                    item { OutlinedTextField(args, { args = it }, label = { Text("Argumentos extras do jogo") }, supportingText = { Text("Ex.: --render-distance 6 --pregen") }) }
                }
            }
        },
        confirmButton = {
            val extra: (Instance) -> Instance = { it.copy(quickServer = server, quickWorld = world, extraArgs = args, layout = layout, version = version) }
            Row {
                TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), color, icon, extra) }) {
                    Text(if (initial == null) "Criar" else "Salvar")
                }
                if (onSaveAndInstall != null) {
                    Button(enabled = name.isNotBlank(), onClick = { onSaveAndInstall(name.trim(), color, icon, extra) }) {
                        Text("Criar e BAIXAR", fontWeight = FontWeight.Black)
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

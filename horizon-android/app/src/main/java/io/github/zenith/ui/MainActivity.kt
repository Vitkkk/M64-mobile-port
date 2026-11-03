package io.github.zenith.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.zenith.data.InstanceStore
import io.github.zenith.data.AccountStore
import io.github.zenith.game.GameLauncher
import io.github.zenith.ui.screens.AccountScreen
import io.github.zenith.ui.screens.ControlsScreen
import io.github.zenith.ui.screens.HomeScreen
import io.github.zenith.ui.screens.InstancesScreen
import io.github.zenith.ui.screens.ModsScreen
import io.github.zenith.ui.screens.SettingsScreen
import io.github.zenith.ui.theme.ZenithTheme

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Início", Icons.Filled.Home),
    INSTANCES("Instâncias", Icons.Filled.Layers),
    MODS("Mods", Icons.Filled.Extension),
    ACCOUNT("Conta", Icons.Filled.AccountCircle),
    CONTROLS("Controles", Icons.Filled.Gamepad),
    SETTINGS("Ajustes", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()
    private var pendingLink by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingLink = intent?.data
        setContent {
            ZenithTheme(vm.theme, vm.accent) {
                Surface(Modifier.fillMaxSize()) { Root() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingLink = intent.data
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    @Composable
    private fun Root() {
        var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
        var subPage by rememberSaveable { mutableStateOf<String?>(null) }
        val snackbar = remember { SnackbarHostState() }
        val wide = LocalConfiguration.current.screenWidthDp >= 600

        LaunchedEffect(vm.message) {
            vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
        }
        BackHandler(enabled = subPage != null || tab != Tab.HOME) {
            if (subPage != null) subPage = null else tab = Tab.HOME
        }

        if (!vm.settings.acceptedNotice) FirstRunNotice()
        pendingLink?.let { DeepLinkDialog(it) }
        vm.lastExit?.let { exit ->
            AlertDialog(
                onDismissRequest = { GameLauncher.clearLastExit(); vm.lastExit = null },
                title = { Text("O jogo fechou com erro") },
                text = { Text("Código ${exit.code}${exit.error?.let { ": $it" } ?: ""}. O log e o relatório de crash estão em Ajustes → Logs.") },
                confirmButton = { TextButton(onClick = { GameLauncher.clearLastExit(); vm.lastExit = null; tab = Tab.SETTINGS; subPage = "logs" }) { Text("Ver logs") } },
                dismissButton = { TextButton(onClick = { GameLauncher.clearLastExit(); vm.lastExit = null }) { Text("Fechar") } },
            )
        }

        val content: @Composable (Modifier) -> Unit = { mod ->
            Box(mod) {
                when (tab) {
                    Tab.HOME -> HomeScreen(vm, onNavigate = { t, p -> tab = t; subPage = p })
                    Tab.INSTANCES -> InstancesScreen(vm)
                    Tab.MODS -> ModsScreen(vm)
                    Tab.ACCOUNT -> AccountScreen(vm)
                    Tab.CONTROLS -> ControlsScreen(vm)
                    Tab.SETTINGS -> SettingsScreen(vm, subPage) { subPage = it }
                }
                vm.busy?.let { label ->
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                        Surface(tonalElevation = 6.dp, shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(8.dp))
                                if (vm.progress in 0f..1f) LinearProgressIndicator(progress = { vm.progress }, Modifier.fillMaxWidth())
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }

        if (wide) {
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
                Row(Modifier.padding(pad).fillMaxSize()) {
                    NavigationRail {
                        Tab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = tab == t,
                                onClick = { tab = t; subPage = null },
                                icon = { Icon(t.icon, t.label) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                    content(Modifier.weight(1f).fillMaxSize())
                }
            }
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t; subPage = null },
                                icon = { Icon(t.icon, t.label) },
                                label = { Text(t.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                },
            ) { pad -> content(Modifier.padding(pad).fillMaxSize()) }
        }
    }

    @Composable
    private fun FirstRunNotice() {
        var agreed by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Bem-vindo ao Zenith") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "O Zenith é um launcher para Android do Horizon, uma reescrita open source em Rust do " +
                            "Minecraft: Java Edition 26.3.\n\n" +
                            "• Nem o app nem o Horizon trazem código, texturas, sons ou dados do Minecraft.\n" +
                            "• Você precisa da sua própria cópia do Java Edition: os recursos do jogo são preparados " +
                            "a partir dela (no PC, com o horizon-prepare) e importados aqui.\n" +
                            "• Para jogar, entre com uma conta Microsoft que tenha o jogo, como nos outros launchers.\n\n" +
                            "Não é um produto oficial do Minecraft, nem aprovado ou associado à Mojang ou à Microsoft."
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                        Text("Entendi e tenho minha cópia do jogo")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = agreed, onClick = { vm.settings.acceptedNotice = true; vm.refresh() }) { Text("Continuar") }
            },
        )
    }

    /** zenith://play?instance=ID&world=PASTA&server=HOST:PORTA */
    @Composable
    private fun DeepLinkDialog(uri: Uri) {
        val instance = InstanceStore.get(uri.getQueryParameter("instance")) ?: vm.instance
        val world = uri.getQueryParameter("world")?.takeIf { it.isNotBlank() && !it.contains('/') }
        val server = uri.getQueryParameter("server")?.takeIf { it.isNotBlank() }
        fun go() {
            pendingLink = null
            val account = vm.account ?: AccountStore.all().firstOrNull()
            if (instance == null || account == null) { vm.toast("Escolha uma instância e uma conta primeiro"); return }
            vm.work("Abrindo o jogo…") { GameLauncher.launch(this, instance, account, GameLauncher.Options(world, server)) }
        }
        if (vm.settings.trustDeepLinks) {
            LaunchedEffect(uri) { go() }
            return
        }
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text("Abrir o jogo?") },
            text = {
                Text(
                    "Um atalho pediu para abrir \"${instance?.name ?: "?"}\"" +
                        (world?.let { " no mundo $it" } ?: "") + (server?.let { " no servidor $it" } ?: "") + "."
                )
            },
            confirmButton = { TextButton(onClick = { go() }) { Text("Jogar") } },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text("Cancelar") } },
        )
    }
}

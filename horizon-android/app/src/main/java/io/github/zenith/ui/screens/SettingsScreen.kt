package io.github.zenith.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.zenith.BuildConfig
import io.github.zenith.data.GameOptions
import io.github.zenith.data.GameResources
import io.github.zenith.data.Paths
import io.github.zenith.game.GameLauncher
import io.github.zenith.install.Installer
import io.github.zenith.ui.LauncherViewModel
import io.github.zenith.ui.components.InstallPanel
import io.github.zenith.ui.components.LocalImportPanel
import io.github.zenith.ui.components.ChoiceRow
import io.github.zenith.ui.components.NavRow
import io.github.zenith.ui.components.Section
import io.github.zenith.ui.components.SliderRow
import io.github.zenith.ui.components.SwitchRow
import io.github.zenith.ui.components.formatBytes
import io.github.zenith.ui.theme.ACCENTS
import io.github.zenith.ui.theme.Mono
import java.io.File

@Composable
fun SettingsScreen(vm: LauncherViewModel, page: String?, open: (String?) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (page != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { open(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                Text(PAGES.firstOrNull { it.first == page }?.second ?: "Ajustes", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(8.dp))
        } else {
            Text("Ajustes", style = MaterialTheme.typography.headlineMedium)
            Text("Instância: ${vm.instance?.name ?: "—"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        when (page) {
            null -> Root(open)
            "graphics" -> GraphicsPage(vm)
            "game" -> GamePage(vm)
            "sound" -> SoundPage(vm)
            "resources" -> ResourcesPage(vm)
            "microsoft" -> MicrosoftPage(vm)
            "launcher" -> LauncherPage(vm)
            "api" -> ApiPage(vm)
            "logs" -> LogsPage(vm)
            "about" -> AboutPage(vm)
        }
        Spacer(Modifier.height(80.dp))
    }
}

private val PAGES = listOf(
    "graphics" to "Gráficos e desempenho",
    "game" to "Jogo",
    "sound" to "Som",
    "resources" to "Instalar o jogo",
    "microsoft" to "Conta Microsoft",
    "launcher" to "Aparência do launcher",
    "api" to "Avançado e API",
    "logs" to "Logs e crashes",
    "about" to "Sobre",
)

@Composable
private fun Root(open: (String) -> Unit) {
    Section("Jogo") {
        NavRow("Gráficos e desempenho", "API gráfica, resolução, FPS, distância, presets", Icons.Filled.Speed) { open("graphics") }
        NavRow("Jogo", "Idioma, pulo automático, agachar/correr", Icons.Filled.SportsEsports) { open("game") }
        NavRow("Som", "Volume por categoria", Icons.Filled.VolumeUp) { open("sound") }
        NavRow("Instalar o jogo", "Baixar e preparar no celular, ou importar um ZIP", Icons.Filled.Folder) { open("resources") }
    }
    Section("Launcher") {
        NavRow("Conta Microsoft", "Client ID do Azure, verificação de posse", Icons.Filled.Key) { open("microsoft") }
        NavRow("Aparência", "Tema e cor", Icons.Filled.Palette) { open("launcher") }
        NavRow("Avançado e API", "Console, atalhos zenith://, variáveis de ambiente", Icons.Filled.Api) { open("api") }
        NavRow("Logs e crashes", "latest.log e relatórios de crash", Icons.Filled.Article) { open("logs") }
        NavRow("Sobre", "Versões, licenças, como compilar", Icons.Filled.Info) { open("about") }
    }
}

/** Abre as opções da instância, deixa [content] mexer e salva a cada mudança. */
@Composable
private fun WithOptions(vm: LauncherViewModel, content: @Composable (GameOptions, () -> Unit) -> Unit) {
    val instance = vm.instance ?: return
    var version by remember { mutableIntStateOf(0) }
    val options = remember(instance, version) { GameOptions(instance) }
    content(options) { options.save(); version++ }
}

@Composable
private fun GraphicsPage(vm: LauncherViewModel) {
    val context = LocalContext.current
    val vulkan = remember {
        val pm = context.packageManager
        when {
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x403000) -> "Vulkan 1.3"
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x402000) -> "Vulkan 1.2"
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000) -> "Vulkan 1.1"
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x400000) -> "Vulkan 1.0"
            else -> "sem Vulkan"
        }
    }
    WithOptions(vm) { o, save ->
        Section("Presets para celular", subtitle = "Ajustam várias opções de uma vez; dá para mudar cada uma depois.") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameOptions.MobilePreset.entries.forEach { p ->
                    OutlinedButton(onClick = { o.applyMobilePreset(p); save(); vm.toast("Preset ${p.label}: ${p.description}") }) { Text(p.label) }
                }
            }
        }
        Section("API gráfica", subtitle = "Seu aparelho: $vulkan · ${Build.MANUFACTURER} ${Build.MODEL} (${socName()})") {
            ChoiceRow(
                "Backend do wgpu",
                listOf("auto" to "Automático (Vulkan, senão OpenGL ES)", "vulkan" to "Vulkan", "opengl" to "OpenGL ES 3"),
                o.graphicsApi,
                subtitle = "Vale na próxima vez que o jogo abrir. Use OpenGL ES se o Vulkan do aparelho der problema.",
            ) { o.graphicsApi = it; save() }
            SliderRow("Escala de resolução", o.resolutionScale.toFloat(), 50f..100f, steps = 9, format = { "${it.toInt()}%" },
                subtitle = "Renderiza menor e amplia: o maior ganho de FPS no celular") { o.resolutionScale = it.toInt(); save() }
            ChoiceRow("Preset do Horizon", listOf("performance" to "Desempenho", "balanced" to "Equilibrado", "quality" to "Qualidade", "ultra" to "Ultra", "custom" to "Personalizado"), o.horizonPreset) { o.horizonPreset = it; save() }
            SwitchRow("Modo bateria", o.batteryMode, subtitle = "Máx. 60 FPS no mundo, 30 nos menus, metade das threads") { o.batteryMode = it; save() }
            ChoiceRow("Medidor de desempenho", listOf("off" to "Desligado", "fps" to "FPS", "graph" to "FPS + gráfico"), o.overlay) { o.overlay = it; save() }
            ChoiceRow("Ritmo dos quadros", listOf("efficient" to "Eficiente", "precise" to "Preciso"), o.framePacing) { o.framePacing = it; save() }
            SliderRow("Threads de trabalho", o.workers.toFloat(), 0f..8f, steps = 7, format = { if (it < 1) "Auto" else it.toInt().toString() }) { o.workers = it.toInt(); save() }
        }
        Section("Distância e FPS") {
            SliderRow("Distância de renderização", o.renderDistance.toFloat(), 2f..32f, steps = 29, format = { "${it.toInt()} chunks" }) { o.renderDistance = it.toInt(); save() }
            SliderRow("Distância de simulação", o.simulationDistance.toFloat(), 5f..16f, steps = 10, format = { "${it.toInt()} chunks" }) { o.simulationDistance = it.toInt(); save() }
            SliderRow("Terreno distante (LOD)", o.lodDistance.toFloat(), 0f..512f, steps = 7, format = { if (it < 64) "Desligado" else "${it.toInt()} chunks" },
                subtitle = "Mostra montanhas ao longe com pouco custo") { o.lodDistance = if (it < 64) 0 else it.toInt(); save() }
            SliderRow("FPS máximo", o.maxFps.toFloat(), 30f..260f, steps = 22, format = { if (it >= 260) "Sem limite" else "${it.toInt()}" }) { o.maxFps = it.toInt(); save() }
            SwitchRow("VSync", o.vsync) { o.vsync = it; save() }
        }
        Section("Qualidade") {
            ChoiceRow("Gráficos (vanilla)", listOf("fast" to "Rápidos", "fancy" to "Bonitos", "fabulous" to "Fabulosos"), o.graphicsPreset) { o.graphicsPreset = it; save() }
            ChoiceRow("Nuvens", listOf("false" to "Desligadas", "fast" to "Rápidas", "true" to "Bonitas"), o.clouds) { o.clouds = it; save() }
            ChoiceRow("Partículas", listOf("0" to "Todas", "1" to "Reduzidas", "2" to "Mínimas"), o.particles.toString()) { o.particles = it.toInt(); save() }
            SwitchRow("Iluminação suave", o.smoothLighting) { o.smoothLighting = it; save() }
            SwitchRow("Sombras de entidades", o.entityShadows) { o.entityShadows = it; save() }
            SwitchRow("Texturas animadas", o.animatedTextures) { o.animatedTextures = it; save() }
            SwitchRow("Cave culling", o.caveCulling, subtitle = "Não desenha cavernas escondidas") { o.caveCulling = it; save() }
            SliderRow("Mistura de biomas", o.biomeBlend.toFloat(), 0f..7f, steps = 6) { o.biomeBlend = it.toInt(); save() }
            SliderRow("Mipmaps", o.mipmaps.toFloat(), 0f..4f, steps = 3) { o.mipmaps = it.toInt(); save() }
            SwitchRow("FXAA", o.fxaa, subtitle = "Suaviza bordas, custo baixo") { o.fxaa = it; save() }
            SwitchRow("TAA", o.taa, subtitle = "Suaviza bordas, custo médio") { o.taa = it; save() }
        }
        Section("Tela") {
            SliderRow("Escala da interface", o.guiScale.toFloat(), 0f..6f, steps = 5, format = { if (it < 1) "Auto" else it.toInt().toString() }) { o.guiScale = it.toInt(); save() }
            SliderRow("Campo de visão", o.fov.toFloat(), 30f..110f, format = { "${it.toInt()}°" }) { o.fov = it.toInt(); save() }
            SliderRow("Brilho", o.gamma, 0f..1f, format = { "${(it * 100).toInt()}%" }) { o.gamma = it; save() }
            SwitchRow("Balanço da visão", o.viewBobbing) { o.viewBobbing = it; save() }
        }
    }
}

private fun socName(): String =
    if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE

@Composable
private fun GamePage(vm: LauncherViewModel) = WithOptions(vm) { o, save ->
    Section("Jogo") {
        ChoiceRow("Idioma", GameOptions.LANGUAGES, o.lang) { o.lang = it; save() }
        SwitchRow("Pulo automático", o.autoJump, subtitle = "Sobe um bloco andando: recomendado no toque") { o.autoJump = it; save() }
        SwitchRow("Agachar alterna", o.toggleCrouch) { o.toggleCrouch = it; save() }
        SwitchRow("Correr alterna", o.toggleSprint) { o.toggleSprint = it; save() }
        SliderRow("Sensibilidade do mouse (jogo)", o.mouseSensitivity, 0f..1f, format = { "${(it * 200).toInt()}%" },
            subtitle = "Multiplica com a sensibilidade do toque (aba Controles)") { o.mouseSensitivity = it; save() }
        SwitchRow("Legendas", o.showSubtitles) { o.showSubtitles = it; save() }
        SwitchRow("Pausar ao sair do app", o.pauseOnLostFocus) { o.pauseOnLostFocus = it; save() }
    }
}

@Composable
private fun SoundPage(vm: LauncherViewModel) = WithOptions(vm) { o, save ->
    Section("Volume") {
        GameOptions.SOUND_CATEGORIES.forEach { (key, label) ->
            SliderRow(label, o.volume(key), 0f..1f, format = { "${(it * 100).toInt()}%" }) { o.setVolume(key, it); save() }
        }
    }
}

@Composable
private fun ResourcesPage(vm: LauncherViewModel) {
    val context = LocalContext.current
    val status = vm.resources
    var confirmDelete by remember { mutableStateOf(false) }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.work("Importando recursos…") {
            GameResources.importZip(context, uri) { _, f -> vm.progress = f }
            vm.toast("Recursos importados")
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.work("Copiando a pasta (pode demorar)…") {
            GameResources.importFolder(context, uri) { _, _ -> }
            vm.toast("Recursos importados")
        }
    }
    InstallPanel(vm, title = "Baixar e instalar no celular")
    LocalImportPanel(vm)
    var cache by remember(vm.busy) { mutableStateOf(-1L) }
    androidx.compose.runtime.LaunchedEffect(vm.busy) {
        cache = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Installer.cacheSize() }
    }
    if (cache > 0) Section("Arquivos da instalação", subtitle = "Downloads guardados, o Java temporário e o log") {
        Text("Ocupam ${formatBytes(cache)}. Os recursos instalados não são apagados.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { vm.work("Limpando…") { Installer.clearCache() } }) { Text("Limpar") }
    }
    Section("Avançado: importar recursos preparados no PC", subtitle = "Minecraft ${BuildConfig.GAME_VERSION} · ${GameResources.dir().path}") {
        Text(status.message, color = if (status.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        if (status.sizeBytes > 0) Text("Tamanho: ${formatBytes(status.sizeBytes)}" + (status.outputVersion?.let { " · formato $it" } ?: ""))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }) { Text("Importar .zip") }
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Importar pasta") }
        }
        if (status.sizeBytes > 0) TextButton(onClick = { confirmDelete = true }) { Text("Apagar recursos", color = MaterialTheme.colorScheme.error) }
    }
    Section("Como preparar no PC (alternativa)") {
        Text(
            "Os recursos saem da SUA cópia do Minecraft: Java Edition ${BuildConfig.GAME_VERSION}, pelo horizon-prepare " +
                "(precisa de Java 25 e do jogo instalado no launcher oficial, Prism ou MultiMC):",
        )
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(
                "horizon-prepare --prepare --output preparado \\\n  --server-jar server.jar --complete\n" +
                    "zip -r horizon-resources.zip preparado\n\n" +
                    "# ou, neste repositório:\nhorizon-android/tools/prepare-resources.sh",
                style = Mono,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Passe o .zip para o celular e toque em Importar .zip. Nada disso é baixado pelo app.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Apagar os recursos?") },
        text = { Text("Os mundos ficam. Para jogar de novo será preciso importar outra vez.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.work("Apagando…") { GameResources.delete() } }) { Text("Apagar") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun MicrosoftPage(vm: LauncherViewModel) {
    val s = vm.settings
    var clientId by remember { mutableStateOf(s.msaClientIdOverride) }
    var bypass by remember { mutableStateOf(s.ownerGateBypass) }
    var confirmBypass by remember { mutableStateOf(false) }
    Section("Client ID do Azure", subtitle = if (BuildConfig.MSA_CLIENT_ID.isNotBlank()) "Este build já tem um Client ID" else "Este build não tem Client ID") {
        Text(
            "O login Microsoft precisa de um app registrado no Azure (contas pessoais, fluxos públicos ligados) e " +
                "aprovado pela Mojang para a API do Minecraft (aka.ms/mce-reviewappid). Nunca use o Client ID de outro aplicativo.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(clientId, { clientId = it }, Modifier.fillMaxWidth(), label = { Text("Client ID (GUID)") }, singleLine = true)
        TextButton(onClick = { s.msaClientIdOverride = clientId; vm.toast("Client ID salvo") }) { Text("Salvar") }
    }
    Section("Posse do jogo", subtitle = "Como no launcher do Horizon: só abre o jogo com uma conta que tem o Java Edition.") {
        SwitchRow(
            "Desligar a verificação (desenvolvimento)", bypass,
            subtitle = "Para testar o port sem login. Só use com recursos preparados da sua própria cópia.",
        ) { if (it) confirmBypass = true else { bypass = false; s.ownerGateBypass = false } }
    }
    if (confirmBypass) AlertDialog(
        onDismissRequest = { confirmBypass = false },
        title = { Text("Desligar a verificação de posse?") },
        text = { Text("O Horizon pede que launchers só abram o jogo para quem tem o Minecraft: Java Edition. Desligue apenas para desenvolver e testar, com os recursos da sua cópia.") },
        confirmButton = { TextButton(onClick = { confirmBypass = false; bypass = true; s.ownerGateBypass = true }) { Text("Desligar") } },
        dismissButton = { TextButton(onClick = { confirmBypass = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun LauncherPage(vm: LauncherViewModel) {
    Section("Tema") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "Sistema", "dark" to "Escuro", "light" to "Claro").forEach { (k, l) ->
                FilterChip(vm.theme == k, { vm.setThemeMode(k) }, label = { Text(l) })
            }
        }
    }
    Section("Cor de destaque") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val names = mapOf("grass" to "Grama", "diamond" to "Diamante", "gold" to "Ouro", "redstone" to "Redstone", "amethyst" to "Ametista", "sky" to "Céu")
            ACCENTS.keys.forEach { k -> FilterChip(vm.accent == k, { vm.setAccentColor(k) }, label = { Text(names[k] ?: k) }) }
        }
    }
    Section("No jogo") {
        var keep by remember { mutableStateOf(vm.settings.keepScreenOn) }
        SwitchRow("Manter a tela ligada", keep) { keep = it; vm.settings.keepScreenOn = it }
    }
}

@Composable
private fun ApiPage(vm: LauncherViewModel) {
    val context = LocalContext.current
    val s = vm.settings
    var console by remember { mutableStateOf(s.showConsole) }
    var trust by remember { mutableStateOf(s.trustDeepLinks) }
    var env by remember { mutableStateOf(s.extraEnv) }
    Section("API de controle do Horizon", subtitle = "A mesma do horizon-ctl: uma linha de texto por comando, uma linha JSON de resposta.") {
        SwitchRow("Console de comandos no menu do jogo", console, subtitle = "state, give, tp, time, options-set, screenshot, bench…") { console = it; s.showConsole = it }
        Text("Socket (Unix, só deste app):", style = MaterialTheme.typography.labelLarge)
        SelectionContainer { Text(Paths.controlSocket.path, style = Mono) }
        Text(
            "Botões dos controles podem rodar qualquer comando (tipo \"Comando da API\"), ex.: chat /time set day, give torch 64, key f2 tap.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Section("Atalhos (deep links)", subtitle = "Outros apps e atalhos da tela inicial podem abrir o jogo.") {
        SelectionContainer { Text("zenith://play?instance=ID&world=PASTA\nzenith://play?server=host:25565", style = Mono) }
        SwitchRow("Abrir sem perguntar", trust) { trust = it; s.trustDeepLinks = it }
        vm.instance?.let { inst ->
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("zenith", "zenith://play?instance=${inst.id}"))
                vm.toast("Copiado")
            }) { Text("Copiar o link da instância ${inst.name}") }
        }
    }
    Section("Variáveis de ambiente do jogo", subtitle = "Uma por linha: CHAVE=valor. Ex.: HORIZON_HDR_OUTPUT=pq, WGPU_BACKEND=gl") {
        OutlinedTextField(env, { env = it }, Modifier.fillMaxWidth().height(140.dp), textStyle = Mono)
        TextButton(onClick = { s.extraEnv = env; vm.toast("Salvo") }) { Text("Salvar") }
    }
    Section("Arquivo de lançamento") {
        Text(
            "Antes de abrir o jogo o launcher escreve files/launch/launch.json (argumentos da Launch API do Horizon e ambiente). " +
                "O jogo deixa files/launch/exit.json ao terminar.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (Paths.launchFile.isFile) SelectionContainer { Text(Paths.launchFile.readText().take(3000), style = Mono) }
    }
}

@Composable
private fun LogsPage(vm: LauncherViewModel) {
    val context = LocalContext.current
    val instance = vm.instance ?: return
    val files = remember(instance, vm.busy) {
        listOfNotNull(File(instance.logsDir, "latest.log").takeIf { it.isFile }) +
            (instance.crashDir.listFiles()?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()) +
            (instance.logsDir.listFiles { f -> f.name != "latest.log" }?.sortedByDescending { it.lastModified() }?.take(5) ?: emptyList())
    }
    var shown by remember(files) { mutableStateOf(files.firstOrNull()) }
    GameLauncher.lastExit()?.let { Text("Última saída do jogo: código ${it.code}" + (it.error?.let { e -> " ($e)" } ?: ""), color = MaterialTheme.colorScheme.error) }
    if (files.isEmpty()) {
        Text("Nenhum log ainda: jogue uma vez.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { f -> FilterChip(shown == f, { shown = f }, label = { Text(f.name) }) }
    }
    shown?.let { f ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartilhar ${f.name}"))
            }) { Text("Compartilhar") }
            OutlinedButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(f.name, f.readText().takeLast(100_000)))
                vm.toast("Copiado")
            }) { Text("Copiar") }
        }
        Spacer(Modifier.height(8.dp))
        val text = remember(f) { runCatching { f.readText().takeLast(60_000) }.getOrDefault("") }
        SelectionContainer {
            Text(text, style = Mono, modifier = Modifier.horizontalScroll(rememberScrollState()))
        }
    }
}

@Composable
private fun AboutPage(vm: LauncherViewModel) {
    Section("Zenith Launcher ${BuildConfig.VERSION_NAME}") {
        Text("Launcher Android do Horizon, a reescrita open source em Rust do Minecraft: Java Edition.")
        Spacer(Modifier.height(6.dp))
        Text("Minecraft: ${BuildConfig.GAME_VERSION}", style = Mono)
        Text("Horizon: ${BuildConfig.HORIZON_COMMIT.take(12)}", style = Mono)
        Text("Motor: " + if (GameLauncher.engineInstalled()) "libhorizon_android.so (${formatBytes(Paths.engineLibrary.length())})" else "ausente neste APK", style = Mono)
        Text("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()}", style = Mono)
    }
    Section("Como compilar o motor") {
        SelectionContainer {
            Text(
                "rustup target add aarch64-linux-android\ncargo install cargo-ndk\nhorizon-android/tools/build-native.sh\ncd horizon-android && ./gradlew assembleDebug",
                style = Mono,
            )
        }
    }
    Section("Créditos e licenças") {
        Text(
            "• Horizon, de Ludovic Marie e colaboradores (MIT): github.com/horizon-rust/horizon\n" +
                "• winit, wgpu, android-activity, GameActivity (androidx.games)\n" +
                "• Zenith: GPL-3.0\n\n" +
                "Não é um produto oficial do Minecraft. Não é aprovado nem associado à Mojang ou à Microsoft. " +
                "Minecraft é marca da Mojang Synergies AB.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Section("Limitações conhecidas") {
        Text(
            "• Voltar ao jogo depois de sair do app ainda não é garantido: o mundo é salvo ao sair.\n" +
                "• Preparar os recursos no próprio celular precisa de um Java 25 para Android (roadmap).\n" +
                "• Mods nativos (.hnmod) precisam de um motor compilado com eles.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

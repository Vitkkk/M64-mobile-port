package io.github.zenith.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zenith.BuildConfig
import io.github.zenith.DevFlags
import io.github.zenith.data.AccountStore
import io.github.zenith.data.Skins
import io.github.zenith.game.GameLauncher
import io.github.zenith.ui.LauncherViewModel
import io.github.zenith.ui.Tab
import io.github.zenith.ui.components.BlockIcon
import io.github.zenith.ui.components.Chip
import io.github.zenith.ui.components.Section
import io.github.zenith.ui.components.SkinHead
import io.github.zenith.ui.components.SkinPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(vm: LauncherViewModel, onNavigate: (Tab, String?) -> Unit) {
    val context = LocalContext.current
    val instance = vm.instance
    val account = vm.account
    var skin by remember(account) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(account) { skin = account?.let { withContext(Dispatchers.IO) { Skins.bitmapFor(it) } } }
    val problem = remember(vm.resources, account, vm.accounts) { GameLauncher.check(vm.settings, account) }
    val worlds = remember(instance, vm.busy) { instance?.worlds().orEmpty() }

    fun play(world: String? = null, server: String? = null) {
        val i = instance ?: return
        val a = account ?: return onNavigate(Tab.ACCOUNT, null)
        vm.work("Abrindo o Horizon…") { GameLauncher.launch(context, i, a, GameLauncher.Options(world, server)) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Cartaz com o horizonte em blocos, o jogador e o botão de jogar.
        Box(
            Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1B2B4A), Color(0xFF2E4A7A), Color(0xFFE79A54))))
        ) {
            HorizonScenery(Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopStart).padding(20.dp)) {
                Text("ZENITH", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Text("Horizon · Minecraft: Java Edition ${BuildConfig.GAME_VERSION} em Rust", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 12.dp)) {
                SkinPreview(skin, account?.skinModel == "slim", height = 170.dp)
            }
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Button(
                    onClick = { play() },
                    enabled = vm.busy == null && instance != null,
                    contentPadding = ButtonDefaults.ContentPadding,
                    modifier = Modifier.height(56.dp).width(200.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("JOGAR", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    instance?.let { "Instância: ${it.name}" } ?: "",
                    color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        if (problem != null) {
            Section("Antes de jogar") {
                Text(problem.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                when (problem) {
                    is GameLauncher.Problem.NoResources -> OutlinedButton(onClick = { onNavigate(Tab.SETTINGS, "resources") }) { Text("Importar recursos") }
                    is GameLauncher.Problem.NoOwner, is GameLauncher.Problem.NoAccount -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onNavigate(Tab.ACCOUNT, null) }) { Text("Ir para Conta") }
                        if (DevFlags.TEST_ACCOUNT) OutlinedButton(onClick = {
                            vm.work("Criando a conta de teste…") { DevFlags.createTestAccount(vm.settings, "Tester") }
                        }) { Text("Usar conta de teste") }
                    }
                    is GameLauncher.Problem.NoEngine -> OutlinedButton(onClick = { onNavigate(Tab.SETTINGS, "about") }) { Text("Como compilar") }
                    else -> {}
                }
            }
        }

        Section("Prontidão") {
            Check("Motor Horizon (libhorizon_android.so)", GameLauncher.engineInstalled())
            Check("Recursos do jogo: ${vm.resources.message}", vm.resources.ready) { onNavigate(Tab.SETTINGS, "resources") }
            Check(
                "Conta: " + (account?.let { "${it.name} (${if (it.isMicrosoft) "Microsoft" else "offline"})" } ?: "nenhuma"),
                account != null,
            ) { onNavigate(Tab.ACCOUNT, null) }
            Check(
                when {
                    vm.settings.ownerGateBypass -> "Posse do jogo: verificação desligada (desenvolvimento)"
                    AccountStore.anyOwner() -> "Posse do Java Edition confirmada"
                    else -> "Posse do Java Edition: entre com a conta Microsoft"
                },
                vm.settings.ownerGateBypass || AccountStore.anyOwner(),
            ) { onNavigate(Tab.ACCOUNT, null) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Section("Instância", Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onNavigate(Tab.INSTANCES, null) }) {
                    instance?.let { BlockIcon(it.icon, Color(it.color), 40.dp) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(instance?.name ?: "—", fontWeight = FontWeight.Bold)
                        Text("${worlds.size} mundos", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Section("Jogador", Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onNavigate(Tab.ACCOUNT, null) }) {
                    SkinHead(skin, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(account?.name ?: "Sem conta", fontWeight = FontWeight.Bold)
                        Chip(if (account?.isMicrosoft == true) "Microsoft" else if (account != null) "Offline" else "—")
                    }
                }
            }
        }

        if (worlds.isNotEmpty()) {
            Section("Continuar um mundo") {
                worlds.take(5).forEach { w ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = vm.busy == null) { play(world = w) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BlockIcon("grass", Color(0xFF4FB477), 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(w, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        instance?.quickServer?.takeIf { it.isNotBlank() }?.let { server ->
            Section("Servidor favorito") {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = vm.busy == null) { play(server = server) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Public, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(server, Modifier.weight(1f))
                    Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun Check(text: String, ok: Boolean, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Sol e colinas em blocos, no fundo do cartaz. */
@Composable
private fun HorizonScenery(modifier: Modifier) {
    Canvas(modifier) {
        val b = size.height / 16f
        // Sol
        drawRect(Color(0xFFFFD27A), Offset(size.width * 0.55f, size.height * 0.30f), Size(b * 3, b * 3))
        drawRect(Color(0xFFFFE9B0), Offset(size.width * 0.55f + b * 0.75f, size.height * 0.30f + b * 0.75f), Size(b * 1.5f, b * 1.5f))
        // Colinas
        val heights = listOf(4, 4, 5, 5, 6, 6, 6, 5, 4, 4, 3, 3, 4, 5, 5, 4, 3, 3, 4, 4, 5, 6, 6, 5, 4, 4, 3, 3, 3, 4, 4, 5)
        val cols = (size.width / b).toInt() + 1
        for (x in 0 until cols) {
            val h = heights[x % heights.size]
            val top = size.height - h * b
            drawRect(Color(0xFF3F8F4E), Offset(x * b, top), Size(b + 0.5f, b))
            drawRect(Color(0xFF6B4A2B), Offset(x * b, top + b), Size(b + 0.5f, size.height - top - b))
            if ((x * 7) % 5 == 0) drawRect(Color(0xFF59391E), Offset(x * b + b * 0.25f, top + b * 2.2f), Size(b * 0.4f, b * 0.4f))
        }
    }
}

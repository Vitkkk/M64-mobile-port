package io.github.zenith.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.zenith.controls.ControlLayout
import io.github.zenith.controls.ControlsEditorActivity
import io.github.zenith.controls.ControlsOverlayView
import io.github.zenith.controls.LayoutStore
import io.github.zenith.game.NoSink
import io.github.zenith.ui.LauncherViewModel
import io.github.zenith.ui.components.Chip
import io.github.zenith.ui.components.Section
import io.github.zenith.ui.components.SliderRow
import io.github.zenith.ui.components.SwitchRow
import org.json.JSONObject
import java.util.UUID

@Composable
fun ControlsScreen(vm: LauncherViewModel) {
    val context = LocalContext.current
    val s = vm.settings
    var version by remember { mutableIntStateOf(0) }
    val layouts = remember(version, vm.busy) { LayoutStore.all() }
    var active by remember(version) { mutableStateOf(s.activeLayout) }
    val current = layouts.firstOrNull { it.id == active } ?: layouts.first()

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) context.contentResolver.openOutputStream(uri)?.use { it.write(current.toJson().toString(2).toByteArray()) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
            val l = ControlLayout.fromJson(JSONObject(text))
            val saved = if (LayoutStore.isBuiltIn(l.id)) l.copy(id = "custom-" + UUID.randomUUID().toString().take(8)) else l
            LayoutStore.save(saved)
            s.activeLayout = saved.id
            version++
            vm.toast("Layout \"${saved.name}\" importado")
        }.onFailure { vm.toast("Arquivo de layout inválido: ${it.message}") }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Controles", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))

        // Prévia do layout ativo, numa "tela" 16:9.
        Column(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF78AAE6), Color(0xFF5A8C46))))
        ) {
            key(current, s.overlayOpacity) {
                AndroidView(
                    factory = { ctx ->
                        // A miniatura tem a largura da tela em retrato; o jogo usa o lado maior.
                        val dm = ctx.resources.displayMetrics
                        val scale = minOf(dm.widthPixels, dm.heightPixels).toFloat() / maxOf(dm.widthPixels, dm.heightPixels)
                        ControlsOverlayView(ctx, current, NoSink, ControlsOverlayView.Config(opacity = s.overlayOpacity, sizeScale = scale)).apply { playing = true }
                    },
                    update = { it.layout = current },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                context.startActivity(Intent(context, ControlsEditorActivity::class.java).putExtra(ControlsEditorActivity.EXTRA_LAYOUT, current.id))
            }) { Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(6.dp)); Text("Editar layout") }
            TextButton(onClick = { exporter.launch("${current.name}.json") }) { Text("Exportar") }
            TextButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text("Importar") }
        }

        Section("Layouts", subtitle = "O ativo vale para todas as instâncias que não escolheram outro.") {
            layouts.forEach { l ->
                val sel = l.id == current.id
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .let { if (sel) it.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else it }
                        .clickable { s.activeLayout = l.id; active = l.id }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, fontWeight = FontWeight.Bold)
                        Text("${l.buttons.size} botões", style = MaterialTheme.typography.bodySmall)
                    }
                    if (LayoutStore.isBuiltIn(l.id)) Chip("De fábrica")
                    else TextButton(onClick = {
                        LayoutStore.delete(l.id)
                        if (s.activeLayout == l.id) s.activeLayout = "default"
                        version++
                    }) { Text("Apagar") }
                }
            }
        }

        Section("Toque") {
            SliderRow("Sensibilidade do olhar", s.lookSensitivity, 0.1f..4f, format = { "%.1fx".format(it) }) { s.lookSensitivity = it }
            SwitchRow(
                "Gestos na tela", s.tapGestures,
                subtitle = "Toque rápido = usar/colocar · segurar = quebrar/atacar",
            ) { s.tapGestures = it; version++ }
            SliderRow("Tempo para quebrar ao segurar", s.holdToBreakMs.toFloat(), 150f..800f, format = { "${it.toInt()} ms" }) { s.holdToBreakMs = it.toInt() }
            SwitchRow("Joystick flutuante", s.floatingJoystick, subtitle = "Aparece onde o dedo toca, no lado esquerdo") { s.floatingJoystick = it; version++ }
            SwitchRow("Correr ao empurrar o joystick até o fim", s.autoSprint) { s.autoSprint = it; version++ }
            SwitchRow("Vibrar ao tocar nos botões", s.haptics) { s.haptics = it; version++ }
            SliderRow("Opacidade dos botões", s.overlayOpacity, 0.1f..1f, format = { "${(it * 100).toInt()}%" }) { s.overlayOpacity = it; version++ }
        }

        Section("Controle físico (Bluetooth/USB)", subtitle = "Repassado ao pad virtual do Horizon: menus e jogo funcionam como num controle de PC.") {
            SwitchRow("Usar controle", s.gamepadEnabled) { s.gamepadEnabled = it; version++ }
            SliderRow("Zona morta dos analógicos", s.gamepadDeadzone, 0f..0.5f, format = { "%.2f".format(it) }) { s.gamepadDeadzone = it }
            Text(
                "Os botões do controle se mudam dentro do jogo: Opções → Controles → Controle.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(80.dp))
    }
}

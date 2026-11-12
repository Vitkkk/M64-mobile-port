package io.github.zenith.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Um cartão com título, para agrupar opções. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Um controle deslizante com o valor ao lado; [onDone] recebe o valor ao soltar. */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String = { it.toInt().toString() },
    subtitle: String? = null,
    onDone: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(v), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = v, onValueChange = { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { onDone(v) })
    }
}

/** Escolha entre opções, num menu suspenso. */
@Composable
fun ChoiceRow(title: String, options: List<Pair<String, String>>, selected: String, subtitle: String? = null, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            TextButton(onClick = { open = true }) {
                Text(options.firstOrNull { it.first == selected }?.second ?: selected, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(key) })
                }
            }
        }
    }
}

@Composable
fun NavRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Ícone de instância: um bloco desenhado em pixels, na cor dela. */
@Composable
fun BlockIcon(kind: String, color: Color, size: Dp = 48.dp) {
    Canvas(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.18f))) {
        val px = this.size.width / 8f
        fun cell(x: Int, y: Int, c: Color) = drawRect(c, Offset(x * px, y * px), Size(px + 0.5f, px + 0.5f))
        val pattern: List<String> = when (kind) {
            "stone" -> listOf("aaabaaaa", "abaaaaba", "aaaaabaa", "baaabaaa", "aabaaaab", "aaaaaaba", "abaabaaa", "aaaaaaaa")
            "diamond" -> listOf("........", "..aaaa..", ".abbbba.", "abbccbba", ".abccba.", "..abba..", "...aa...", "........")
            "redstone" -> listOf("........", "...a....", "..aba...", ".abcba..", "..aba.a.", "...a.aba", "......a.", "........")
            "tnt" -> listOf("aaaaaaaa", "abababab", "cccccccc", "cdcdcdcc", "cccccccc", "abababab", "aaaaaaaa", "aaaaaaaa")
            "chest" -> listOf("aaaaaaaa", "abbbbbba", "abbbbbba", "aaacaaaa", "abbcbbba", "abbbbbba", "abbbbbba", "aaaaaaaa")
            "sword" -> listOf("......aa", ".....aba", "....aba.", "...aba..", "c.aba...", ".cca....", ".dcc....", "d..c....")
            "pickaxe" -> listOf("..aaaa..", ".a....a.", "a..c...a", "...c....", "...c....", "...c....", "...c....", "........")
            else -> listOf("aaaaaaaa", "abaabaaa", "bbbbbbbb", "cbccbccc", "cccccbcc", "ccbccccc", "cccccccc", "ccbccccc")
        }
        val palette = when (kind) {
            "stone" -> mapOf('a' to Color(0xFF8A8A8A), 'b' to Color(0xFF6E6E6E))
            "diamond" -> mapOf('a' to Color(0xFF1C9AA8), 'b' to Color(0xFF6FE3EE), 'c' to Color.White)
            "redstone" -> mapOf('a' to Color(0xFF8E1B12), 'b' to Color(0xFFE0362B), 'c' to Color(0xFFFF8A80))
            "tnt" -> mapOf('a' to Color(0xFFDB3B2B), 'b' to Color(0xFFB02A1D), 'c' to Color(0xFFEDEDED), 'd' to Color(0xFF333333))
            "chest" -> mapOf('a' to Color(0xFF5E3B17), 'b' to Color(0xFFA0692E), 'c' to Color(0xFFCFCFCF))
            "sword" -> mapOf('a' to Color(0xFF3B8EA5), 'b' to Color(0xFFB7F1FF), 'c' to Color(0xFF6B4A22), 'd' to Color(0xFF3E2A12))
            "pickaxe" -> mapOf('a' to Color(0xFF4FC3D9), 'c' to Color(0xFF6B4A22))
            else -> mapOf('a' to color, 'b' to color.copy(alpha = 0.75f), 'c' to Color(0xFF7A5230))
        }
        pattern.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> palette[ch]?.let { cell(x, y, it) } } }
    }
}

/** Uma etiqueta pequena (estado, tipo). */
@Composable
fun Chip(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun SpacedColumn(content: @Composable ColumnScope.() -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toFloat())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toFloat())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024f)
    else -> "$b B"
}

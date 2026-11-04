package io.github.zenith.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Cores de destaque que o jogador escolhe em Ajustes → Launcher. */
val ACCENTS = linkedMapOf(
    "grass" to Color(0xFF4FB477),
    "diamond" to Color(0xFF3FC6D9),
    "gold" to Color(0xFFE8B83A),
    "redstone" to Color(0xFFE0544B),
    "amethyst" to Color(0xFF9E6BE0),
    "sky" to Color(0xFF4A8FE8),
)

@Composable
fun ZenithTheme(theme: String, accent: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val a = ACCENTS[accent] ?: ACCENTS.getValue("grass")
    val colors = if (dark) darkColorScheme(
        primary = a,
        onPrimary = Color(0xFF0B1A10),
        primaryContainer = a.copy(alpha = 0.25f),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFF8FA3B8),
        background = Color(0xFF0E1116),
        onBackground = Color(0xFFE6E9EE),
        surface = Color(0xFF151A21),
        onSurface = Color(0xFFE6E9EE),
        surfaceVariant = Color(0xFF1E252E),
        onSurfaceVariant = Color(0xFFB5BFCB),
        surfaceContainer = Color(0xFF181E26),
        surfaceContainerHigh = Color(0xFF1F2630),
        surfaceContainerHighest = Color(0xFF26303B),
        outline = Color(0xFF3A4654),
        error = Color(0xFFFF6B6B),
    ) else lightColorScheme(
        primary = a,
        onPrimary = Color.White,
        primaryContainer = a.copy(alpha = 0.18f),
        background = Color(0xFFF4F6F8),
        surface = Color.White,
        surfaceVariant = Color(0xFFE8ECF0),
    )
    val base = Typography()
    val typography = base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Black),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

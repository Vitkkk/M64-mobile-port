package io.github.zenith.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * O jogador de frente (ou de costas), desenhado a partir da textura da skin
 * (layout do PlayerModel do vanilla: 64x64, ou 64x32 antigo com braço e perna
 * esquerdos espelhados). Inclui as camadas externas (chapéu, jaqueta, mangas).
 */
@Composable
fun SkinPreview(skin: Bitmap?, slim: Boolean, back: Boolean = false, height: Dp = 220.dp) {
    Canvas(Modifier.size(height / 2, height)) {
        if (skin == null) {
            drawDefault(this); return@Canvas
        }
        val legacy = skin.height == 32
        // 16 x 32 unidades de pixel do modelo.
        val u = size.height / 32f
        val ox = (size.width - 16 * u) / 2f
        fun face(sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int, mirror: Boolean = false) {
            for (y in 0 until h) for (x in 0 until w) {
                val tx = sx + (if (mirror) w - 1 - x else x)
                val ty = sy + y
                if (tx >= skin.width || ty >= skin.height) continue
                val argb = skin.getPixel(tx, ty)
                if ((argb ushr 24) == 0) continue
                drawRect(Color(argb), Offset(ox + (dx + x) * u, (dy + y) * u), Size(u + 0.6f, u + 0.6f))
            }
        }
        val arm = if (slim) 3 else 4
        if (!back) {
            face(8, 8, 8, 8, 4, 0)                       // cabeça
            face(20, 20, 8, 12, 4, 8)                    // tronco
            face(44, 20, arm, 12, 4 - arm, 8)            // braço direito (à esquerda na tela)
            if (legacy) face(44, 20, arm, 12, 12, 8, mirror = true) else face(36, 52, arm, 12, 12, 8)
            face(4, 20, 4, 12, 4, 20)                    // perna direita
            if (legacy) face(4, 20, 4, 12, 8, 20, mirror = true) else face(20, 52, 4, 12, 8, 20)
            face(40, 8, 8, 8, 4, 0)                      // chapéu
            if (!legacy) {
                face(20, 36, 8, 12, 4, 8)                // jaqueta
                face(44, 36, arm, 12, 4 - arm, 8)        // manga direita
                face(52, 52, arm, 12, 12, 8)             // manga esquerda
                face(4, 36, 4, 12, 4, 20)                // calça direita
                face(4, 52, 4, 12, 8, 20)                // calça esquerda
            }
        } else {
            face(24, 8, 8, 8, 4, 0)
            face(32, 20, 8, 12, 4, 8)
            face(44 + arm + 4, 20, arm, 12, 12, 8)
            if (legacy) face(44 + arm + 4, 20, arm, 12, 4 - arm, 8, mirror = true) else face(36 + arm + 4, 52, arm, 12, 4 - arm, 8)
            face(12, 20, 4, 12, 8, 20)
            if (legacy) face(12, 20, 4, 12, 4, 20, mirror = true) else face(28, 52, 4, 12, 4, 20)
            face(56, 8, 8, 8, 4, 0)
            if (!legacy) face(32, 36, 8, 12, 4, 8)
        }
    }
}

/** Sem skin: uma silhueta neutra (o jogo usa as skins padrão do vanilla). */
private fun drawDefault(scope: DrawScope) = with(scope) {
    val u = size.height / 32f
    val ox = (size.width - 16 * u) / 2f
    val c = Color(0xFF3A4654)
    fun r(x: Int, y: Int, w: Int, h: Int, col: Color = c) = drawRect(col, Offset(ox + x * u, y * u), Size(w * u, h * u))
    r(4, 0, 8, 8, Color(0xFF4A5868)); r(4, 8, 8, 12); r(0, 8, 4, 12); r(12, 8, 4, 12); r(4, 20, 4, 12); r(8, 20, 4, 12)
}

/** Só o rosto (para listas e para o cabeçalho). */
@Composable
fun SkinHead(skin: Bitmap?, size: Dp = 40.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 8f
        if (skin == null) {
            drawRect(Color(0xFF4A5868)); return@Canvas
        }
        for (layer in listOf(8 to 8, 40 to 8)) for (y in 0 until 8) for (x in 0 until 8) {
            val argb = skin.getPixel(layer.first + x, layer.second + y)
            if ((argb ushr 24) == 0) continue
            drawRect(Color(argb), Offset(x * u, y * u), Size(u + 0.6f, u + 0.6f))
        }
    }
}

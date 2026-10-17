package io.github.m64port

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Controle de N64 virtual com multitoque. Posições são frações da tela
 * (modo paisagem), pensadas para Super Mario 64: analógico à esquerda,
 * A/B/Z e botões C à direita.
 */
class TouchControllerView(context: Context) : View(context) {
    /** Chamado quando o estado muda: (botões, analógico X, analógico Y). */
    var onStateChanged: ((Int, Int, Int) -> Unit)? = null

    private class Btn(val label: String, val mask: Int, val fx: Float, val fy: Float, val fr: Float, val color: Int)

    private val buttons = listOf(
        Btn("A", 0x8000, 0.90f, 0.78f, 0.085f, Color.rgb(60, 110, 230)),
        Btn("B", 0x4000, 0.79f, 0.66f, 0.085f, Color.rgb(40, 170, 80)),
        Btn("Z", 0x2000, 0.68f, 0.84f, 0.075f, Color.rgb(140, 140, 140)),
        Btn("▲", 0x0008, 0.88f, 0.26f, 0.055f, Color.rgb(230, 190, 40)),
        Btn("▼", 0x0004, 0.88f, 0.50f, 0.055f, Color.rgb(230, 190, 40)),
        Btn("◀", 0x0002, 0.82f, 0.38f, 0.055f, Color.rgb(230, 190, 40)),
        Btn("▶", 0x0001, 0.94f, 0.38f, 0.055f, Color.rgb(230, 190, 40)),
        Btn("L", 0x0020, 0.06f, 0.10f, 0.065f, Color.rgb(140, 140, 140)),
        Btn("R", 0x0010, 0.94f, 0.10f, 0.065f, Color.rgb(140, 140, 140)),
        Btn("START", 0x1000, 0.50f, 0.90f, 0.06f, Color.rgb(200, 50, 50)),
    )

    private val stickFx = 0.16f
    private val stickFy = 0.68f
    private val stickFr = 0.20f

    private var stickPointer = -1
    private var stickDx = 0f
    private var stickDy = 0f
    private var lastState = Triple(0, 0, 0)
    private var pressedMask = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val unit get() = min(width, height).toFloat()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val liftedIndex = when (action) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> event.actionIndex
            else -> -1
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            stickPointer = -1
        }

        var mask = 0
        var stickSeen = false
        for (i in 0 until event.pointerCount) {
            if (i == liftedIndex || action == MotionEvent.ACTION_CANCEL) continue
            val id = event.getPointerId(i)
            val x = event.getX(i)
            val y = event.getY(i)

            val cx = stickFx * width
            val cy = stickFy * height
            val r = stickFr * unit
            val startsStick = stickPointer == -1 && x < width * 0.4f && y > height * 0.3f
            if (id == stickPointer || startsStick) {
                stickPointer = id
                stickSeen = true
                var dx = x - cx
                var dy = y - cy
                val dist = hypot(dx, dy)
                if (dist > r) {
                    dx *= r / dist
                    dy *= r / dist
                }
                stickDx = dx / r
                stickDy = dy / r
                continue
            }

            for (b in buttons) {
                if (hypot(x - b.fx * width, y - b.fy * height) <= b.fr * unit * 1.25f) {
                    mask = mask or b.mask
                }
            }
        }
        if (!stickSeen) {
            stickPointer = -1
            stickDx = 0f
            stickDy = 0f
        }

        pressedMask = mask
        // Alcance típico do analógico original: ±80. Y para cima é positivo.
        val state = Triple(mask, (stickDx * 80).roundToInt(), (-stickDy * 80).roundToInt())
        if (state != lastState) {
            lastState = state
            onStateChanged?.invoke(state.first, state.second, state.third)
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val u = unit
        text.textSize = u * 0.05f

        val cx = stickFx * width
        val cy = stickFy * height
        fill.color = Color.argb(70, 255, 255, 255)
        canvas.drawCircle(cx, cy, stickFr * u, fill)
        fill.color = Color.argb(150, 255, 255, 255)
        canvas.drawCircle(cx + stickDx * stickFr * u, cy + stickDy * stickFr * u, stickFr * u * 0.45f, fill)

        for (b in buttons) {
            val pressed = pressedMask and b.mask != 0
            fill.color = b.color
            fill.alpha = if (pressed) 230 else 110
            val x = b.fx * width
            val y = b.fy * height
            canvas.drawCircle(x, y, b.fr * u, fill)
            canvas.drawText(b.label, x, y - (text.descent() + text.ascent()) / 2, text)
        }
    }
}

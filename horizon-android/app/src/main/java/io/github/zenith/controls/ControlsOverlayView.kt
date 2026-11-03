package io.github.zenith.controls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Os controles de toque por cima do jogo. Cada dedo vira uma coisa: um botão, o
 * joystick, a hotbar ou a área de olhar. Fora dos menus os toques são do overlay;
 * num menu (inventário, pausa, título) os toques que não caem num botão passam
 * para a tela do jogo, onde o Horizon os lê como mouse (patch `embed::touch_as_mouse`).
 *
 * No modo de edição ([editing]) os botões são arrastados em vez de apertados.
 */
@SuppressLint("ViewConstructor")
class ControlsOverlayView(
    context: Context,
    layout: ControlLayout,
    private val sink: Sink,
    var config: Config = Config(),
) : View(context) {

    /** Para onde vão as ações (o socket de controle no jogo; nada no editor). */
    interface Sink {
        fun command(line: String)
        fun look(dx: Double, dy: Double)
        fun stick(x: Float, y: Float)
        fun special(action: String)
        fun selectSlot(slot: Int)
    }

    data class Config(
        val lookSensitivity: Float = 1f,
        val tapGestures: Boolean = true,
        val holdToBreakMs: Int = 300,
        val haptics: Boolean = true,
        val autoSprint: Boolean = true,
        val floatingJoystick: Boolean = false,
        val opacity: Float = 0.6f,
        /** guiScale do options.txt (0 = automático), para achar a hotbar. */
        val guiScale: Int = 0,
    )

    var layout: ControlLayout = layout
        set(value) { field = value; invalidate() }

    /** O jogo está no mundo, sem menu aberto (vem do estado do socket). */
    var playing = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) releaseAll()
                invalidate()
            }
        }

    var editing = false
        set(value) { field = value; releaseAll(); invalidate() }

    var hidden = false
        set(value) { field = value; releaseAll(); invalidate() }

    /** O próximo toque num menu vira clique direito (`click-at X Y right`). */
    var rightClickMode = false
        set(value) { field = value; invalidate() }

    /** Botão em edição (destacado) e aviso de mudança, para o editor. */
    var selectedId: String? = null
        set(value) { field = value; invalidate() }
    var onSelect: (String?) -> Unit = {}
    var onLayoutChanged: (ControlLayout) -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
        setShadowLayer(dp(2f), 0f, dp(1f), Color.BLACK)
    }

    private val toggled = mutableSetOf<String>()
    private val held = mutableSetOf<String>()

    private sealed class Pointer {
        data class Button(val id: String) : Pointer()
        data class Joystick(var cx: Float, var cy: Float) : Pointer()
        class Look(var lastX: Float, var lastY: Float, val downX: Float, val downY: Float, val downAt: Long) : Pointer() {
            var dragged = false
            var breaking = false
        }
        class RightClick(val x: Float, val y: Float) : Pointer()
        class Drag(val id: String, val offX: Float, val offY: Float) : Pointer()
    }

    private val pointers = HashMap<Int, Pointer>()
    private var knobX = 0f
    private var knobY = 0f
    private var joyActive: Pointer.Joystick? = null
    private var sprintBySticks = false

    private val holdCheck = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            for (p in pointers.values) {
                if (p is Pointer.Look && !p.dragged && !p.breaking && now - p.downAt >= config.holdToBreakMs) {
                    p.breaking = true
                    buzz()
                    sink.command("mouse left down")
                }
            }
            if (pointers.values.any { it is Pointer.Look }) postDelayed(this, 50)
        }
    }

    // --- Geometria ---

    private fun visible(b: ControlButton) = when (b.visibility) {
        Visibility.ALWAYS -> true
        Visibility.PLAYING -> playing || editing
        Visibility.MENU -> !playing || editing
    }

    private fun rectOf(b: ControlButton): RectF {
        val s = dp(b.size)
        val cx = b.x * width
        val cy = b.y * height
        return RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
    }

    private fun buttonAt(x: Float, y: Float): ControlButton? {
        val slop = dp(6f)
        return layout.buttons.lastOrNull { b ->
            visible(b) && rectOf(b).let { r -> x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop }
        }
    }

    private val joyRadius get() = dp(layout.joystickSize) / 2
    private val joyCenterX get() = layout.joystickX * width
    private val joyCenterY get() = layout.joystickY * height

    private fun inJoystickZone(x: Float, y: Float): Boolean =
        if (config.floatingJoystick) x < width * 0.4f && y > height * 0.35f
        else hypot(x - joyCenterX, y - joyCenterY) <= joyRadius * 1.5f

    /** `Window.calculateScale` do vanilla: a maior escala com a tela ≥ 320x240. */
    fun guiScale(): Int {
        var scale = 1
        val max = if (config.guiScale > 0) config.guiScale else Int.MAX_VALUE
        while (scale < max && width / (scale + 1) >= 320 && height / (scale + 1) >= 240) scale++
        return scale
    }

    /** O slot da hotbar (0..8) sob o ponto, ou -1. A hotbar tem 182x22 px de GUI, no rodapé. */
    private fun hotbarSlot(x: Float, y: Float): Int {
        val s = guiScale()
        val w = 182 * s
        val left = (width - w) / 2f
        val top = height - 22f * s
        if (y < top || x < left || x > left + w) return -1
        return ((x - left - s) / (20 * s)).toInt().coerceIn(0, 8)
    }

    // --- Desenho ---

    override fun onDraw(canvas: Canvas) {
        if (hidden && !editing) {
            // Só o botão de menu, para poder mostrar de novo.
            layout.buttons.filter { it.action.value == ControlAction.OVERLAY_MENU }.forEach { drawButton(canvas, it) }
            return
        }
        val alpha = (config.opacity.coerceIn(0.1f, 1f) * 255).toInt()
        if (playing || editing) {
            val cx = joyActive?.cx ?: joyCenterX
            val cy = joyActive?.cy ?: joyCenterY
            if (!config.floatingJoystick || joyActive != null || editing) {
                fill.color = Color.argb(alpha / 3, 0, 0, 0)
                canvas.drawCircle(cx, cy, joyRadius, fill)
                stroke.color = Color.argb(alpha, 255, 255, 255)
                canvas.drawCircle(cx, cy, joyRadius, stroke)
                fill.color = Color.argb(alpha, 230, 230, 230)
                canvas.drawCircle(cx + knobX * joyRadius, cy - knobY * joyRadius, joyRadius * 0.38f, fill)
                if (editing && selectedId == JOYSTICK_ID) {
                    stroke.color = Color.argb(255, 255, 210, 60)
                    canvas.drawCircle(cx, cy, joyRadius + dp(4f), stroke)
                }
            }
        }
        layout.buttons.filter(::visible).forEach { drawButton(canvas, it) }
        if (rightClickMode && !playing) {
            text.color = Color.YELLOW
            canvas.drawText("Toque onde quer o clique direito", width / 2f, dp(28f), text)
            text.color = Color.WHITE
        }
    }

    private fun drawButton(canvas: Canvas, b: ControlButton) {
        val r = rectOf(b)
        val alpha = (config.opacity.coerceIn(0.1f, 1f) * 255).toInt()
        val active = b.id in held || b.id in toggled ||
            (b.action.value == ControlAction.RIGHT_CLICK_MODE && rightClickMode)
        fill.color = if (active) Color.argb(min(255, alpha + 60), 79, 180, 119) else Color.argb(alpha / 2, 0, 0, 0)
        stroke.color = Color.argb(alpha, 255, 255, 255)
        if (b.round) {
            canvas.drawOval(r, fill); canvas.drawOval(r, stroke)
        } else {
            val c = dp(8f)
            canvas.drawRoundRect(r, c, c, fill); canvas.drawRoundRect(r, c, c, stroke)
        }
        if (editing && b.id == selectedId) {
            stroke.color = Color.argb(255, 255, 210, 60)
            val g = dp(4f)
            canvas.drawRect(r.left - g, r.top - g, r.right + g, r.bottom + g, stroke)
        }
        val size = text.textSize
        text.textSize = min(size, r.height() * 0.42f).let { if (b.label.length > 3) it * 0.75f else it }
        text.alpha = max(alpha, 160)
        canvas.drawText(b.label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
        text.textSize = size
    }

    // --- Toque ---

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) return editTouch(e)
        val idx = e.actionIndex
        val id = e.getPointerId(idx)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val handled = down(id, e.getX(idx), e.getY(idx))
                // Num menu, um primeiro toque fora dos botões é da tela do jogo.
                if (!handled && e.actionMasked == MotionEvent.ACTION_DOWN) return false
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(id, e.getX(idx), e.getY(idx), cancel = false)
            MotionEvent.ACTION_CANCEL -> pointers.keys.toList().forEach { up(it, 0f, 0f, cancel = true) }
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float): Boolean {
        if (hidden) {
            val b = buttonAt(x, y)?.takeIf { it.action.value == ControlAction.OVERLAY_MENU } ?: return false
            pointers[id] = Pointer.Button(b.id); press(b); return true
        }
        buttonAt(x, y)?.let { b ->
            pointers[id] = Pointer.Button(b.id)
            press(b)
            return true
        }
        if (!playing) {
            if (rightClickMode) {
                pointers[id] = Pointer.RightClick(x, y)
                return true
            }
            return false
        }
        if (joyActive == null && inJoystickZone(x, y)) {
            val j = if (config.floatingJoystick) Pointer.Joystick(x, y) else Pointer.Joystick(joyCenterX, joyCenterY)
            pointers[id] = j
            joyActive = j
            updateStick(j, x, y)
            return true
        }
        val slot = hotbarSlot(x, y)
        if (slot >= 0) {
            buzz()
            sink.selectSlot(slot + 1)
            pointers[id] = Pointer.Button("__hotbar")
            return true
        }
        pointers[id] = Pointer.Look(x, y, x, y, SystemClock.uptimeMillis())
        if (config.tapGestures) {
            removeCallbacks(holdCheck); postDelayed(holdCheck, 50)
        }
        return true
    }

    private fun move(id: Int, x: Float, y: Float) {
        when (val p = pointers[id]) {
            is Pointer.Joystick -> updateStick(p, x, y)
            is Pointer.Look -> {
                val dx = x - p.lastX
                val dy = y - p.lastY
                p.lastX = x; p.lastY = y
                if (!p.dragged && hypot(x - p.downX, y - p.downY) > dp(10f)) p.dragged = true
                if (dx != 0f || dy != 0f) {
                    val k = config.lookSensitivity * LOOK_SCALE / density
                    sink.look(dx * k.toDouble(), dy * k.toDouble())
                }
            }
            else -> {}
        }
    }

    private fun up(id: Int, x: Float, y: Float, cancel: Boolean) {
        when (val p = pointers.remove(id)) {
            is Pointer.Button -> layout.buttons.firstOrNull { it.id == p.id }?.let { release(it) }
            is Pointer.Joystick -> {
                joyActive = null
                knobX = 0f; knobY = 0f
                sink.stick(0f, 0f)
                if (sprintBySticks) { sprintBySticks = false; sink.command("key ctrl up") }
                invalidate()
            }
            is Pointer.Look -> {
                if (p.breaking) sink.command("mouse left up")
                else if (!cancel && config.tapGestures && !p.dragged &&
                    SystemClock.uptimeMillis() - p.downAt < config.holdToBreakMs
                ) {
                    sink.command("mouse right down")
                    sink.command("mouse right up")
                }
            }
            is Pointer.RightClick -> if (!cancel) {
                val s = guiScale().toFloat()
                sink.command("click-at %.1f %.1f right".format(java.util.Locale.ROOT, p.x / s, p.y / s))
                rightClickMode = false
            }
            else -> {}
        }
    }

    private fun updateStick(j: Pointer.Joystick, x: Float, y: Float) {
        var vx = (x - j.cx) / joyRadius
        var vy = -(y - j.cy) / joyRadius
        val len = hypot(vx, vy)
        if (len > 1f) { vx /= len; vy /= len }
        knobX = vx; knobY = vy
        sink.stick(vx, vy)
        if (config.autoSprint) {
            val sprint = len >= 0.97f && vy > 0.75f
            if (sprint && !sprintBySticks) { sprintBySticks = true; sink.command("key ctrl down") }
            else if (!sprint && sprintBySticks && vy < 0.55f) { sprintBySticks = false; sink.command("key ctrl up") }
        }
        invalidate()
    }

    private fun press(b: ControlButton) {
        buzz()
        if (b.action.type == ControlAction.Type.SPECIAL) {
            if (b.action.value == ControlAction.HIDE_CONTROLS) hidden = !hidden
            else sink.special(b.action.value)
            held += b.id; invalidate(); return
        }
        if (b.toggle) {
            if (b.id in toggled) {
                toggled -= b.id; b.action.releaseCommand()?.let(sink::command)
            } else {
                toggled += b.id; b.action.pressCommand()?.let(sink::command)
            }
        } else {
            held += b.id
            b.action.pressCommand()?.let(sink::command)
        }
        invalidate()
    }

    private fun release(b: ControlButton) {
        if (b.id !in held) return
        held -= b.id
        if (!b.toggle && b.action.type != ControlAction.Type.SPECIAL) b.action.releaseCommand()?.let(sink::command)
        invalidate()
    }

    /** Solta tudo (troca de tela, controles escondidos), para nada ficar preso. */
    fun releaseAll() {
        for ((_, p) in pointers) {
            when (p) {
                is Pointer.Button -> layout.buttons.firstOrNull { it.id == p.id }?.let { release(it) }
                is Pointer.Look -> if (p.breaking) sink.command("mouse left up")
                else -> {}
            }
        }
        pointers.clear()
        if (joyActive != null) { joyActive = null; knobX = 0f; knobY = 0f; sink.stick(0f, 0f) }
        if (sprintBySticks) { sprintBySticks = false; sink.command("key ctrl up") }
        // Os botões presos (agachar, correr) continuam presos dentro do jogo: só o
        // destaque é atualizado quando o jogador volta a tocar neles.
        invalidate()
    }

    private fun buzz() {
        if (config.haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // --- Edição ---

    private fun editTouch(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        val id = e.getPointerId(idx)
        val x = e.getX(idx)
        val y = e.getY(idx)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val b = buttonAt(x, y)
                when {
                    b != null -> {
                        pointers[id] = Pointer.Drag(b.id, x - b.x * width, y - b.y * height)
                        selectedId = b.id; onSelect(b.id)
                    }
                    hypot(x - joyCenterX, y - joyCenterY) <= joyRadius -> {
                        pointers[id] = Pointer.Drag(JOYSTICK_ID, x - joyCenterX, y - joyCenterY)
                        selectedId = JOYSTICK_ID; onSelect(JOYSTICK_ID)
                    }
                    else -> { selectedId = null; onSelect(null) }
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val drag = pointers[e.getPointerId(i)] as? Pointer.Drag ?: continue
                val nx = ((e.getX(i) - drag.offX) / width).coerceIn(0.02f, 0.98f)
                val ny = ((e.getY(i) - drag.offY) / height).coerceIn(0.02f, 0.98f)
                layout = if (drag.id == JOYSTICK_ID) layout.copy(joystickX = nx, joystickY = ny)
                else layout.copy(buttons = layout.buttons.map { if (it.id == drag.id) it.copy(x = snap(nx), y = snap(ny)) else it })
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (pointers.remove(id) is Pointer.Drag) onLayoutChanged(layout)
            }
        }
        return true
    }

    /** Encaixa numa grade de 1% para alinhar botões. */
    private fun snap(v: Float) = Math.round(v * 100f) / 100f

    companion object {
        const val JOYSTICK_ID = "__joystick"
        /** Pixels de "mouse" por dp arrastado com sensibilidade 1. */
        const val LOOK_SCALE = 2.2f
    }
}

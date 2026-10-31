/*
 * Multi-touch handling (one owner control per pointer, sticks/D-pad tracking their finger until it
 * lifts) follows the Dolphin/Vita3K InputOverlay and RPCSX PadOverlay designs (GPL-2.0-or-later /
 * GPL-2.0); controls are drawn as vectors instead of bitmaps.
 */
package com.kytyps5.android.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * PS-style on-screen controller, drawn in the PPSS55 "dark glass" style (GlassRenderer). In play mode every touch is consumed (so SDL's tap-to-Cross
 * fallback never fires) and the combined [PadState] goes to [onPadState]. In edit mode controls
 * are dragged to new positions and the selection is reported for the editor bar.
 */
@SuppressLint("ViewConstructor")
class TouchControllerView(
    context: Context,
    private val onPadState: (PadState) -> Unit,
) : View(context) {

    var layout: OverlayLayout = OverlayLayout.DEFAULT
        set(value) {
            field = value
            boxes = emptyMap()
            invalidate()
        }

    var editMode: Boolean = false
        set(value) {
            field = value
            boxes = emptyMap()
            releaseAll()
            selected = null
            invalidate()
        }

    var selected: OverlayControl? = null
        private set(value) {
            field = value
            onSelectionChanged(value)
        }

    var onSelectionChanged: (OverlayControl?) -> Unit = {}

    /** PS / SHARE pressed (they have no guest pad bit; GameOverlays maps them to app actions). */
    var onSystemAction: (OverlayControl) -> Unit = {}
    var onLayoutEdited: (OverlayLayout) -> Unit = {}

    private var boxes: Map<OverlayControl, ControlBox> = emptyMap()
    private val touches = LinkedHashMap<Int, Touch>()
    private var state = PadState.NEUTRAL
    private var dragDx = 0f
    private var dragDy = 0f

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dashed = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    private val rect = RectF()

    private fun currentBoxes(): Map<OverlayControl, ControlBox> {
        if (boxes.isEmpty() && width > 0 && height > 0) {
            boxes = OverlayGeometry.boxes(layout, width.toFloat(), height.toFloat(), includeHidden = editMode)
        }
        return boxes
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        boxes = emptyMap()
        glass.clear()
    }

    /** Lifts every finger, e.g. when the overlay is hidden or the activity pauses. */
    fun releaseAll() {
        touches.clear()
        publish()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) {
            onEditTouch(event)
        } else {
            onPlayTouch(event)
        }
        return true
    }

    private fun onPlayTouch(event: MotionEvent) {
        val all = currentBoxes()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // A fresh gesture: drop fingers whose UP was never delivered.
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touches.clear()
                val i = event.actionIndex
                val control = OverlayGeometry.hit(all, event.getX(i), event.getY(i))
                if (control != null) {
                    touches[event.getPointerId(i)] = Touch(control, event.getX(i), event.getY(i))
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (control.isSystemAction) onSystemAction(control)
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                touches[id]?.let { touches[id] = it.copy(x = event.getX(i), y = event.getY(i)) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> touches.remove(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> touches.clear()
        }
        publish()
    }

    private fun publish() {
        val next = OverlayGeometry.padState(currentBoxes(), touches.values)
        if (next != state) {
            state = next
            onPadState(next)
        }
        invalidate()
    }

    private fun onEditTouch(event: MotionEvent) {
        val all = currentBoxes()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val control = OverlayGeometry.hit(all, event.x, event.y)
                selected = control
                control?.let { all[it] }?.let { box ->
                    dragDx = event.x - box.centerX
                    dragDy = event.y - box.centerY
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val control = selected ?: return
                val p = layout.placement(control)
                layout = layout.with(control, p.copy(x = (event.x - dragDx) / width, y = (event.y - dragDy) / height))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (selected != null) onLayoutEdited(layout)
        }
    }

    // ---- drawing (PPSS55 glass style, see GlassRenderer) ---------------------------------------

    private val glass = GlassRenderer()
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }

    /** Animated press level 0..1 per control (and per D-pad direction), eased towards the touch state. */
    private val level = FloatArray(OverlayControl.entries.size)
    private val dpadLevel = FloatArray(4)
    private val knobX = FloatArray(2)
    private val knobY = FloatArray(2)
    private var lastFrameNanos = 0L

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glass.clear()
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dtMs = if (lastFrameNanos == 0L) 16f else ((now - lastFrameNanos) / 1_000_000f).coerceIn(0f, 100f)
        lastFrameNanos = now
        var animating = false

        val opacity = (layout.opacity * 255).toInt()
        currentBoxes().forEach { (control, box) ->
            val visible = layout.placement(control).visible
            val a = if (visible) opacity else maxOf(opacity / 2, 60)
            val i = control.ordinal
            animating = ease(level, i, if (isPressed(control)) 1f else 0f, dtMs) or animating
            val t = level[i]
            when (control.kind) {
                ControlKind.BUTTON, ControlKind.SYSTEM -> {
                    val glyph = when (control) {
                        OverlayControl.CROSS -> Glyph.CROSS
                        OverlayControl.CIRCLE -> Glyph.CIRCLE
                        OverlayControl.SQUARE -> Glyph.SQUARE
                        OverlayControl.TRIANGLE -> Glyph.TRIANGLE
                        else -> Glyph.TEXT
                    }
                    crossfade(canvas, box, t, a) { glass.button(box.width, glyph, control.label, it) }
                }
                ControlKind.SHOULDER, ControlKind.TRIGGER -> {
                    val left = box.centerX < width / 2f
                    crossfade(canvas, box, t, a) { glass.shoulder(box.width, box.height, control.label, left, it) }
                }
                ControlKind.PILL -> crossfade(canvas, box, t, a) { glass.pill(box.width, box.height, control.label, it) }
                ControlKind.SMALL_PILL -> {
                    crossfade(canvas, box, t, a) { glass.capsule(box.width, box.height, it) }
                    caption.textSize = box.height * 0.36f
                    caption.color = blend(CAPTION_IDLE, CAPTION_PRESSED, t)
                    caption.alpha = a * 200 / 255
                    canvas.drawText(control.label, box.centerX, box.top - box.height * 0.16f, caption)
                }
                ControlKind.DPAD -> animating = drawDpad(canvas, box, a, dtMs) or animating
                ControlKind.STICK -> animating = drawStick(canvas, control, box, a, t, dtMs) or animating
            }
            if (editMode && control == selected) {
                stroke.pathEffect = dashed
                stroke.shader = null
                stroke.color = SELECTION
                stroke.strokeWidth = 3f * resources.displayMetrics.density
                rect.set(box.left - 8, box.top - 8, box.right + 8, box.bottom + 8)
                canvas.drawRoundRect(rect, 16f, 16f, stroke)
                stroke.pathEffect = null
            }
        }
        if (animating) postInvalidateOnAnimation() else lastFrameNanos = 0L
    }

    private fun isPressed(control: OverlayControl): Boolean = touches.values.any { it.control == control }

    /** Moves `values[i]` towards [target]: fast attack on press, softer release. True while moving. */
    private fun ease(values: FloatArray, i: Int, target: Float, dtMs: Float): Boolean {
        val tau = if (target > values[i]) PRESS_TAU_MS else RELEASE_TAU_MS
        values[i] += (target - values[i]) * (1f - kotlin.math.exp(-dtMs / tau))
        if (kotlin.math.abs(target - values[i]) < 0.004f) values[i] = target
        return values[i] != target
    }

    /** Idle sprite fading out while the pressed one fades in, with a slight press-in scale. */
    private inline fun crossfade(canvas: Canvas, box: ControlBox, t: Float, alpha: Int, sprite: (Boolean) -> GlassRenderer.Sprite) {
        val scale = 1f - PRESS_SHRINK * t
        if (t < 1f) canvas.drawSprite(sprite(false), box.left, box.top, scale, box.centerX, box.centerY, spritePaint, (alpha * (1f - t)).toInt())
        if (t > 0f) canvas.drawSprite(sprite(true), box.left, box.top, scale, box.centerX, box.centerY, spritePaint, (alpha * t).toInt())
    }

    private fun drawDpad(canvas: Canvas, box: ControlBox, alpha: Int, dtMs: Float): Boolean {
        var animating = false
        DPAD_ORDER.forEachIndexed { dir, button ->
            animating = ease(dpadLevel, dir, if (button in state.buttons) 1f else 0f, dtMs) or animating
            val t = dpadLevel[dir]
            if (t < 1f) canvas.drawSprite(glass.dpadArm(box.width, dir, false), box.left, box.top, 1f, box.centerX, box.centerY, spritePaint, (alpha * (1f - t)).toInt())
            if (t > 0f) canvas.drawSprite(glass.dpadArm(box.width, dir, true), box.left, box.top, 1f, box.centerX, box.centerY, spritePaint, (alpha * t).toInt())
        }
        return animating
    }

    private fun drawStick(canvas: Canvas, control: OverlayControl, box: ControlBox, alpha: Int, t: Float, dtMs: Float): Boolean {
        val radius = box.width / 2f
        val baseScale = 1f
        if (t < 1f) canvas.drawSprite(glass.stickBase(box.width, false), box.left, box.top, baseScale, box.centerX, box.centerY, spritePaint, (alpha * (1f - t)).toInt())
        if (t > 0f) canvas.drawSprite(glass.stickBase(box.width, true), box.left, box.top, baseScale, box.centerX, box.centerY, spritePaint, (alpha * t).toInt())

        // The knob follows the finger 1:1 and springs back to the centre on release.
        val s = if (control == OverlayControl.LEFT_STICK) 0 else 1
        val touch = touches.values.lastOrNull { it.control == control }
        var animating = false
        if (touch != null) {
            val (kx, ky) = PadGeometry.knobOffset(touch.x - box.centerX, touch.y - box.centerY, radius * 0.55f)
            knobX[s] = kx
            knobY[s] = ky
        } else if (knobX[s] != 0f || knobY[s] != 0f) {
            val k = 1f - kotlin.math.exp(-dtMs / KNOB_RETURN_TAU_MS)
            knobX[s] -= knobX[s] * k
            knobY[s] -= knobY[s] * k
            if (kotlin.math.abs(knobX[s]) < 0.5f && kotlin.math.abs(knobY[s]) < 0.5f) {
                knobX[s] = 0f
                knobY[s] = 0f
            } else {
                animating = true
            }
        }
        val knob = box.width * 0.42f
        val kcx = box.centerX + knobX[s]
        val kcy = box.centerY + knobY[s]
        val kl = kcx - knob / 2f
        val kt = kcy - knob / 2f
        if (t < 1f) canvas.drawSprite(glass.stickKnob(knob, false), kl, kt, 1f, kcx, kcy, spritePaint, (alpha * (1f - t)).toInt())
        if (t > 0f) canvas.drawSprite(glass.stickKnob(knob, true), kl, kt, 1f, kcx, kcy, spritePaint, (alpha * t).toInt())
        return animating
    }

    private fun blend(from: Int, to: Int, t: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt(),
    )

    private companion object {
        val SELECTION = Color.rgb(255, 196, 0)
        val CAPTION_IDLE = Color.rgb(214, 224, 240)
        val CAPTION_PRESSED = Color.rgb(90, 165, 255)
        val DPAD_ORDER = arrayOf(PadButton.UP, PadButton.RIGHT, PadButton.DOWN, PadButton.LEFT)
        const val PRESS_TAU_MS = 28f
        const val RELEASE_TAU_MS = 110f
        const val KNOB_RETURN_TAU_MS = 45f
        const val PRESS_SHRINK = 0.045f
    }
}

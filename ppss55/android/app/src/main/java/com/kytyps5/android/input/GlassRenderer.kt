/*
 * PPSS55: "dark glass" look for the on-screen DualSense-style controller.
 *
 * Every control is pre-rendered once per size into two bitmaps (idle and pressed) with a software
 * canvas, because the soft glows need BlurMaskFilter and redrawing blurs every frame would cost GPU
 * time the emulator needs. At draw time the view only cross-fades the two bitmaps, so the press
 * animation is a couple of bitmap blits per control and never delays input (input is published from
 * onTouchEvent before anything is drawn).
 */
package com.kytyps5.android.input

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.roundToInt

/** Colours of one visual state. Pressed = bluer rim, stronger glow, lit inner shading. */
data class GlassStyle(
    val rimStart: Int,
    val rimEnd: Int,
    val glow: Int,
    val glowScale: Float,
    val fillCenter: Int,
    val fillEdge: Int,
    val innerGlow: Int,
    val glyphGlow: Int,
) {
    companion object {
        /** White with a slight blue cast, soft halo, dark smoked-glass body (reference screenshot). */
        val IDLE = GlassStyle(
            rimStart = Color.rgb(246, 249, 255),
            rimEnd = Color.rgb(160, 182, 218),
            glow = Color.argb(140, 185, 210, 255),
            glowScale = 1f,
            fillCenter = Color.argb(70, 6, 10, 18),
            fillEdge = Color.argb(105, 52, 66, 92),
            innerGlow = Color.argb(105, 200, 220, 255),
            glyphGlow = Color.argb(120, 190, 215, 255),
        )

        val PRESSED = GlassStyle(
            rimStart = Color.rgb(205, 228, 255),
            rimEnd = Color.rgb(52, 140, 255),
            glow = Color.argb(235, 62, 148, 255),
            glowScale = 1.45f,
            fillCenter = Color.argb(95, 24, 70, 160),
            fillEdge = Color.argb(130, 64, 136, 255),
            innerGlow = Color.argb(200, 96, 172, 255),
            glyphGlow = Color.argb(220, 70, 155, 255),
        )
    }
}

/** What to paint inside a face/system button. */
enum class Glyph { TRIANGLE, CIRCLE, CROSS, SQUARE, TEXT, NONE }

/**
 * Builds and caches the glass sprites. A sprite is a bitmap larger than the control by [pad] on
 * every side so glows are not clipped; [Sprite.draw] centres it back on the control.
 */
class GlassRenderer {

    class Sprite(val bitmap: Bitmap, val pad: Float)

    private val cache = HashMap<String, Sprite>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun clear() {
        cache.values.forEach { it.bitmap.recycle() }
        cache.clear()
    }

    private fun cached(key: String, w: Float, h: Float, unit: Float, build: (Canvas) -> Unit): Sprite {
        cache[key]?.let { return it }
        if (cache.size > MAX_SPRITES) clear()
        val pad = unit * 0.32f
        val bw = (w + 2 * pad).roundToInt().coerceAtLeast(1)
        val bh = (h + 2 * pad).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(pad, pad)
        build(canvas)
        return Sprite(bitmap, pad).also { cache[key] = it }
    }

    // ---- shapes -------------------------------------------------------------------------------

    /** Round face button (Cross, Circle, Square, Triangle, L3/R3, PS). */
    fun button(size: Float, glyph: Glyph, label: String, pressed: Boolean): Sprite {
        val key = "btn:$glyph:$label:${size.roundToInt()}:$pressed"
        return cached(key, size, size, size) { c ->
            val r = size / 2f
            val path = Path().apply { addCircle(r, r, r * 0.94f, Path.Direction.CW) }
            val style = styleOf(pressed)
            glass(c, path, RectF(0f, 0f, size, size), size, style)
            glyph(c, glyph, label, r, r, size, style)
        }
    }

    /**
     * Shoulder / trigger: a rounded slab tilted 45 degrees towards the screen centre, with the
     * outer end rounder than the inner one, like the reference's L1/L2/R1/R2.
     */
    fun shoulder(w: Float, h: Float, label: String, leftSide: Boolean, pressed: Boolean): Sprite {
        val key = "sh:$label:${w.roundToInt()}x${h.roundToInt()}:$leftSide:$pressed"
        val unit = max(w, h)
        return cached(key, w, h, unit) { c ->
            val slabW = unit * 0.92f
            val slabH = unit * 0.56f
            val cx = w / 2f
            val cy = h / 2f
            val outer = slabH * 0.5f
            val inner = slabH * 0.26f
            // Corner radii (x,y pairs): tl, tr, br, bl. The outer end is the left end for the left
            // side before rotation (+45 deg puts it top-left), and the right end for the right side.
            val radii = if (leftSide) {
                floatArrayOf(outer, outer, inner, inner, inner, inner, outer, outer)
            } else {
                floatArrayOf(inner, inner, outer, outer, outer, outer, inner, inner)
            }
            val path = Path().apply {
                addRoundRect(RectF(cx - slabW / 2, cy - slabH / 2, cx + slabW / 2, cy + slabH / 2), radii, Path.Direction.CW)
                transform(Matrix().apply { setRotate(if (leftSide) 45f else -45f, cx, cy) })
            }
            val style = styleOf(pressed)
            glass(c, path, RectF(cx - unit / 2, cy - unit / 2, cx + unit / 2, cy + unit / 2), unit, style)
            label(c, label, cx, cy, slabH * 0.62f, style)
        }
    }

    /**
     * One D-pad petal pointing at the pad centre ([direction] 0=up, 1=right, 2=down, 3=left), drawn
     * inside the whole D-pad box so the four sprites stack without offsets.
     */
    fun dpadArm(size: Float, direction: Int, pressed: Boolean): Sprite {
        val key = "dp:$direction:${size.roundToInt()}:$pressed"
        return cached(key, size, size, size * 0.42f) { c ->
            val cx = size / 2f
            val armW = size * 0.335f
            val armL = size * 0.44f
            val top = size * 0.02f
            val polygon = Path().apply {
                moveTo(cx - armW / 2, top)
                lineTo(cx + armW / 2, top)
                lineTo(cx + armW / 2, top + armL * 0.66f)
                lineTo(cx, top + armL)
                lineTo(cx - armW / 2, top + armL * 0.66f)
                close()
            }
            val rounded = Path()
            Paint().apply {
                style = Paint.Style.FILL
                pathEffect = CornerPathEffect(armW * 0.34f)
            }.getFillPath(polygon, rounded)
            rounded.transform(Matrix().apply { setRotate(90f * direction, cx, cx) })
            val bounds = RectF().also { rounded.computeBounds(it, true) }
            glass(c, rounded, bounds, armW * 1.25f, styleOf(pressed))
        }
    }

    /** Analog stick base: a soft, translucent bowl with a faint rim. */
    fun stickBase(size: Float, pressed: Boolean): Sprite {
        val key = "sb:${size.roundToInt()}:$pressed"
        return cached(key, size, size, size * 0.5f) { c ->
            val r = size / 2f
            fill.shader = RadialGradient(
                r, r, r,
                intArrayOf(Color.argb(30, 40, 60, 90), Color.argb(70, 70, 92, 130), Color.argb(120, 130, 160, 210)),
                floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawCircle(r, r, r * 0.97f, fill)
            fill.shader = null
            val style = styleOf(pressed)
            softRim(c, Path().apply { addCircle(r, r, r * 0.97f, Path.Direction.CW) }, r, style, widthScale = 0.6f)
        }
    }

    /** Analog stick knob: frosted, lighter disc. */
    fun stickKnob(size: Float, pressed: Boolean): Sprite {
        val key = "sk:${size.roundToInt()}:$pressed"
        return cached(key, size, size, size) { c ->
            val r = size / 2f
            val colors = if (pressed) {
                intArrayOf(Color.argb(235, 160, 200, 255), Color.argb(225, 74, 140, 240))
            } else {
                intArrayOf(Color.argb(225, 190, 202, 220), Color.argb(215, 128, 144, 170))
            }
            val path = Path().apply { addCircle(r, r, r * 0.95f, Path.Direction.CW) }
            outerGlow(c, path, size, styleOf(pressed))
            fill.shader = RadialGradient(r * 0.8f, r * 0.7f, r * 1.1f, colors, null, Shader.TileMode.CLAMP)
            c.drawPath(path, fill)
            fill.shader = null
            rim(c, path, RectF(0f, 0f, size, size), size * 0.7f, styleOf(pressed))
        }
    }

    /** Horizontal rounded bar (touch pad). */
    fun pill(w: Float, h: Float, label: String, pressed: Boolean): Sprite {
        val key = "pl:$label:${w.roundToInt()}x${h.roundToInt()}:$pressed"
        return cached(key, w, h, h) { c ->
            val rect = RectF(0f, 0f, w, h)
            val path = Path().apply { addRoundRect(rect, h * 0.42f, h * 0.42f, Path.Direction.CW) }
            val style = styleOf(pressed)
            glass(c, path, rect, h, style)
            label(c, label, w / 2f, h / 2f, h * 0.34f, style)
        }
    }

    /** Small vertical capsule (SHARE / OPTIONS). Its caption is drawn above it by the view. */
    fun capsule(w: Float, h: Float, pressed: Boolean): Sprite {
        val key = "cp:${w.roundToInt()}x${h.roundToInt()}:$pressed"
        return cached(key, w, h, w * 1.4f) { c ->
            val inset = w * 0.1f
            val rect = RectF(inset, 0f, w - inset, h)
            val path = Path().apply { addRoundRect(rect, rect.width() / 2f, rect.width() / 2f, Path.Direction.CW) }
            glass(c, path, rect, w * 1.4f, styleOf(pressed))
        }
    }

    // ---- painting primitives --------------------------------------------------------------------

    private fun styleOf(pressed: Boolean) = if (pressed) GlassStyle.PRESSED else GlassStyle.IDLE

    /** Glow, smoked fill, inner rim light, sheen and gradient rim, in that order. */
    private fun glass(c: Canvas, path: Path, bounds: RectF, unit: Float, style: GlassStyle) {
        outerGlow(c, path, unit, style)

        val radius = max(bounds.width(), bounds.height()) * 0.62f
        fill.shader = RadialGradient(
            bounds.centerX(), bounds.centerY(), radius.coerceAtLeast(1f),
            style.fillCenter, style.fillEdge, Shader.TileMode.CLAMP,
        )
        c.drawPath(path, fill)
        fill.shader = null

        // Inner shading: a blurred light stroke clipped to the inside reads as thick, lit glass.
        c.save()
        c.clipPath(path)
        stroke.shader = null
        stroke.color = style.innerGlow
        stroke.strokeWidth = unit * 0.16f
        stroke.maskFilter = BlurMaskFilter(unit * 0.07f, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(path, stroke)
        stroke.maskFilter = null
        // Sheen: the upper half catches a little light.
        fill.shader = LinearGradient(
            0f, bounds.top, 0f, bounds.centerY(),
            Color.argb(34, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP,
        )
        c.drawRect(bounds.left, bounds.top, bounds.right, bounds.centerY(), fill)
        fill.shader = null
        c.restore()

        rim(c, path, bounds, unit, style)
    }

    private fun outerGlow(c: Canvas, path: Path, unit: Float, style: GlassStyle) {
        stroke.shader = null
        stroke.color = style.glow
        stroke.strokeWidth = unit * 0.05f * style.glowScale
        stroke.maskFilter = BlurMaskFilter(unit * 0.085f * style.glowScale, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(path, stroke)
        stroke.maskFilter = null
    }

    /** Crisp rim with a light-to-blue diagonal gradient (the "depth" of the reference outline). */
    private fun rim(c: Canvas, path: Path, bounds: RectF, unit: Float, style: GlassStyle) {
        stroke.color = Color.WHITE
        stroke.shader = LinearGradient(
            bounds.left, bounds.top, bounds.right, bounds.bottom,
            style.rimStart, style.rimEnd, Shader.TileMode.CLAMP,
        )
        stroke.strokeWidth = unit * 0.034f
        c.drawPath(path, stroke)
        stroke.shader = null
    }

    private fun softRim(c: Canvas, path: Path, r: Float, style: GlassStyle, widthScale: Float) {
        outerGlow(c, path, r * widthScale, style)
        stroke.shader = null
        stroke.color = Color.argb(150, Color.red(style.rimStart), Color.green(style.rimStart), Color.blue(style.rimStart))
        stroke.strokeWidth = r * 0.02f
        c.drawPath(path, stroke)
    }

    private fun glyph(c: Canvas, glyph: Glyph, label: String, cx: Float, cy: Float, size: Float, style: GlassStyle) {
        val r = size * 0.22f
        val path = Path()
        when (glyph) {
            Glyph.CROSS -> {
                path.moveTo(cx - r, cy - r); path.lineTo(cx + r, cy + r)
                path.moveTo(cx - r, cy + r); path.lineTo(cx + r, cy - r)
            }
            Glyph.CIRCLE -> path.addCircle(cx, cy, r * 1.02f, Path.Direction.CW)
            Glyph.SQUARE -> path.addRect(cx - r * 0.86f, cy - r * 0.86f, cx + r * 0.86f, cy + r * 0.86f, Path.Direction.CW)
            Glyph.TRIANGLE -> {
                path.moveTo(cx, cy - r * 1.05f)
                path.lineTo(cx + r * 1.08f, cy + r * 0.78f)
                path.lineTo(cx - r * 1.08f, cy + r * 0.78f)
                path.close()
            }
            Glyph.TEXT -> return label(c, label, cx, cy, size * 0.3f, style)
            Glyph.NONE -> return
        }
        // Halo first, then a two-tone stroke: same treatment as the rim so symbols glow with it.
        stroke.shader = null
        stroke.color = style.glyphGlow
        stroke.strokeWidth = size * 0.1f
        stroke.maskFilter = BlurMaskFilter(size * 0.05f, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(path, stroke)
        stroke.maskFilter = null
        stroke.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, style.rimStart, style.rimEnd, Shader.TileMode.CLAMP)
        stroke.strokeWidth = size * 0.062f
        c.drawPath(path, stroke)
        stroke.shader = null
    }

    private fun label(c: Canvas, value: String, cx: Float, cy: Float, textSize: Float, style: GlassStyle) {
        text.textSize = textSize
        val baseline = cy - (text.ascent() + text.descent()) / 2f
        text.shader = null
        text.color = style.glyphGlow
        text.maskFilter = BlurMaskFilter(textSize * 0.18f, BlurMaskFilter.Blur.NORMAL)
        c.drawText(value, cx, baseline, text)
        text.maskFilter = null
        text.shader = LinearGradient(cx, cy - textSize / 2, cx, cy + textSize / 2, style.rimStart, style.rimEnd, Shader.TileMode.CLAMP)
        c.drawText(value, cx, baseline, text)
        text.shader = null
    }

    private companion object {
        const val MAX_SPRITES = 96
    }
}

/** Draws [sprite] centred on ([cx], [cy]) scaled by [scale], at [alpha] (0-255). */
fun Canvas.drawSprite(sprite: GlassRenderer.Sprite, left: Float, top: Float, scale: Float, cx: Float, cy: Float, paint: Paint, alpha: Int) {
    if (alpha <= 0) return
    paint.alpha = alpha.coerceIn(0, 255)
    if (scale == 1f) {
        drawBitmap(sprite.bitmap, left - sprite.pad, top - sprite.pad, paint)
    } else {
        save()
        scale(scale, scale, cx, cy)
        drawBitmap(sprite.bitmap, left - sprite.pad, top - sprite.pad, paint)
        restore()
    }
}

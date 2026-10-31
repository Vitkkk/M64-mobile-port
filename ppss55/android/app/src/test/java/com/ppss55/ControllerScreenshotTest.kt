package com.ppss55

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.kytyps5.android.input.OverlayControl
import com.kytyps5.android.input.OverlayGeometry
import com.kytyps5.android.input.OverlayLayout
import com.kytyps5.android.input.PadButton
import com.kytyps5.android.input.PadState
import com.kytyps5.android.input.TouchControllerView
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the real [TouchControllerView] (glass style) at the REDMAGIC 9S Pro's landscape resolution
 * (2480x1116) into build/screenshots/, idle and with Cross + D-pad up + R2 + left stick held, and
 * checks that pressing really drives the pad state. The PNGs are for visual review against the
 * reference screenshot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w2480dp-h1116dp-land-mdpi")
class ControllerScreenshotTest {

    private val width = 2480
    private val height = 1116
    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun render(view: View, name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        view.draw(canvas)
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun newView(onState: (PadState) -> Unit): TouchControllerView {
        val view = TouchControllerView(RuntimeEnvironment.getApplication(), onState)
        view.layout = OverlayLayout.DEFAULT
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        return view
    }

    @Test
    fun rendersIdleAndPressedStates() {
        var last = PadState.NEUTRAL
        val view = newView { last = it }
        render(view, "controller_idle")

        val boxes = OverlayGeometry.boxes(OverlayLayout.DEFAULT, width.toFloat(), height.toFloat())
        val cross = boxes.getValue(OverlayControl.CROSS)
        val dpad = boxes.getValue(OverlayControl.DPAD)
        val r2 = boxes.getValue(OverlayControl.R2)
        val stick = boxes.getValue(OverlayControl.LEFT_STICK)
        val points = listOf(
            cross.centerX to cross.centerY,
            dpad.centerX to dpad.top + dpad.height * 0.12f,
            r2.centerX to r2.centerY,
            stick.centerX + stick.width * 0.3f to stick.centerY - stick.width * 0.2f,
        )
        val t = SystemClock.uptimeMillis()
        points.forEachIndexed { i, _ ->
            val props = Array(i + 1) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(i + 1) { MotionEvent.PointerCoords().apply { x = points[it].first; y = points[it].second; pressure = 1f; size = 1f } }
            val action = if (i == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            val event = MotionEvent.obtain(t, t, action, i + 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            view.onTouchEvent(event)
            event.recycle()
        }
        assertTrue(PadButton.CROSS in last.buttons)
        assertTrue(PadButton.UP in last.buttons)
        // Let the press animation settle (eased levels advance with wall time between frames).
        repeat(12) {
            Thread.sleep(20)
            render(view, "controller_pressed")
        }
    }
}

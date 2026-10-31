package com.ppss55

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the loading card and checks that a "frame: N" title (N >= 1) dismisses it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w2480dp-h1116dp-land-mdpi")
class LaunchOverlayScreenshotTest {
    @Test
    fun rendersAndDismissesOnFirstFrame() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val log = File.createTempFile("session", ".txt").apply { writeText("--- Relocate program: eboot.bin ---\n--- Start module: libc.prx\n") }
        val overlay = LaunchOverlay(activity, log, null, "Jogo de teste")
        overlay.start()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val v = overlay.view
        v.measure(View.MeasureSpec.makeMeasureSpec(2480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1116, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 2480, 1116)
        val bmp = Bitmap.createBitmap(2480, 1116, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/launch_overlay.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        overlay.onTitle("[Kyty | Release] PvZ, frame: 0, fps: 0")
        assertEquals(View.VISIBLE, v.visibility)
        overlay.onTitle("[Kyty | Release] PvZ, frame: 1, fps: 0")
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(View.GONE, v.visibility)
        log.delete()
    }
}

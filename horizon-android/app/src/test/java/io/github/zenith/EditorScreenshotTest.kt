package io.github.zenith

import android.graphics.Bitmap
import android.graphics.Canvas
import io.github.zenith.controls.ControlsEditorActivity
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** O editor de controles em paisagem, como no jogo. Só com -Pzenith.screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w860dp-h400dp-land-night-xhdpi")
class EditorScreenshotTest {
    @Test
    fun editor() {
        assumeTrue(System.getProperty("zenith.screenshots") != null)
        val activity = Robolectric.buildActivity(ControlsEditorActivity::class.java).setup().get()
        val view = activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File("build/screens").mkdirs()
        File("build/screens/08-editor-controles.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

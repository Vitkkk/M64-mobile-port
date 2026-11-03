package io.github.zenith

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.zenith.data.AccountStore
import io.github.zenith.data.LauncherSettings
import io.github.zenith.data.Paths
import io.github.zenith.ui.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screenshots das telas do launcher, renderizadas pelo Robolectric (sem
 * emulador). Só roda com -Pzenith.screenshots; grava em app/build/screens/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-night-xhdpi")
class ScreenshotTest {
    val compose = createAndroidComposeRule<MainActivity>()

    /** Antes de abrir a activity: aviso aceito e uma conta offline. */
    private val seed = object : ExternalResource() {
        override fun before() {
            assumeTrue(System.getProperty("zenith.screenshots") != null)
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            Paths.init(ctx)
            LauncherSettings.get(ctx).acceptedNotice = true
            if (AccountStore.all().isEmpty()) {
                LauncherSettings.get(ctx).selectedAccount = AccountStore.addOffline("Steve_BR").id
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tab(label: String) {
        compose.onAllNodesWithText(label)[0].performClick()
        compose.mainClock.advanceTimeBy(600)
    }

    @Test
    fun screens() {
        shot("01-inicio")
        tab("Instâncias"); shot("02-instancias")
        tab("Mods"); shot("03-mods")
        tab("Conta"); shot("04-conta")
        tab("Controles"); shot("05-controles")
        tab("Ajustes"); shot("06-ajustes")
        compose.onAllNodesWithText("Gráficos e desempenho")[0].performClick()
        compose.mainClock.advanceTimeBy(600)
        shot("07-graficos")
    }
}

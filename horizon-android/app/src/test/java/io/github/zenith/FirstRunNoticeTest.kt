package io.github.zenith

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.zenith.data.LauncherSettings
import io.github.zenith.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** O aviso de boas-vindas fecha ao marcar a caixa e tocar em Continuar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstRunNoticeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun continueClosesTheNotice() {
        compose.onNodeWithText("Bem-vindo ao Zenith").assertExists()
        compose.onNodeWithText("Continuar").assertIsNotEnabled()
        compose.onNodeWithText("Entendi e tenho minha cópia do jogo").performClick()
        compose.onNodeWithText("Continuar").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Bem-vindo ao Zenith").assertDoesNotExist()
        assertTrue(LauncherSettings.get(ApplicationProvider.getApplicationContext<Context>()).acceptedNotice)
    }
}

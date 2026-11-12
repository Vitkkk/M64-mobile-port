package io.github.zenith

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.zenith.data.AccountStore
import io.github.zenith.data.LauncherSettings
import io.github.zenith.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A conta de teste (temporária) é criada pela aba Conta e libera o Jogar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestAccountTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    val compose = createAndroidComposeRule<MainActivity>()
    private val seed = object : ExternalResource() {
        override fun before() { LauncherSettings.get(ctx).acceptedNotice = true }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    @Test
    fun createsTestAccountFromTheAccountTab() {
        compose.onAllNodesWithText("Conta")[0].performClick()
        compose.onNodeWithText("Criar conta de teste (offline)").performScrollTo().performClick()
        compose.onNodeWithText("Criar").performClick()
        compose.waitUntil(5000) { AccountStore.all().any { it.name == "Tester" } }
        compose.waitForIdle()
        val settings = LauncherSettings.get(ctx)
        assertTrue(settings.ownerGateBypass)
        val tester = AccountStore.all().single { it.name == "Tester" }
        assertEquals(tester.id, settings.selectedAccount)
        assertTrue(!tester.isMicrosoft)
    }
}

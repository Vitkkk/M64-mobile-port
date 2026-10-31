package com.ppss55

import com.kytyps5.android.LauncherSettings
import com.kytyps5.android.RenderScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PerformanceProfileTest {
    private val settings = LauncherSettings(RuntimeEnvironment.getApplication())

    @Test
    fun compatibilityDisablesTheThreeFlickerSuspects() {
        PerformanceProfile.COMPATIBILITY.apply(settings)
        assertFalse(settings.asyncShaders)
        val env = settings.globalEnv()
        assertTrue("KYTY_RP_OPS=0" in env)
        assertTrue("TU_DEBUG=sysmem" in env)
        assertFalse(env.any { it.startsWith("KYTY_PIPELINE_GRACE_MS") })
        assertEquals(PerformanceProfile.COMPATIBILITY, settings.profile)
    }

    @Test
    fun balancedKeepsAsyncWithGraceAndLogOff() {
        PerformanceProfile.COMPATIBILITY.apply(settings)
        PerformanceProfile.BALANCED.apply(settings)
        assertTrue(settings.asyncShaders)
        assertFalse(settings.coreLog)
        assertEquals(RenderScale.THREE_QUARTER, settings.renderScale)
        val env = settings.globalEnv()
        assertTrue("KYTY_PIPELINE_GRACE_MS=8" in env)
        assertFalse("KYTY_RP_OPS=0" in env)
        assertTrue("KYTY_AUDIO_LATENCY_MS=120" in env)
    }

    @Test
    fun weakPhoneLowersCost() {
        PerformanceProfile.WEAK.apply(settings)
        assertEquals(RenderScale.HALF, settings.renderScale)
        assertFalse(settings.gpuTurbo)
        assertEquals(200, settings.audioLatencyMs)
    }
}

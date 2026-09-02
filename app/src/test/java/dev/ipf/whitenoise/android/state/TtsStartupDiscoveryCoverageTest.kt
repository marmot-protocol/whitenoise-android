package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TtsStartupDiscoveryCoverageTest {
    @Test
    fun constructorLaunchesTtsDiscoveryIndependentlyOfAccountBootstrap() {
        val source = appStateSource().readText()
        val classBody = source.substringAfter("class WhiteNoiseAppState")
        val initKeyword = classBody.indexOf("init {")
        require(initKeyword >= 0) { "Missing WhiteNoiseAppState init block" }
        val initBody = classBody.kotlinBlockFrom(classBody.indexOf('{', initKeyword), "WhiteNoiseAppState init")
        val bootstrapBody = source.functionBody("bootstrapLocked")

        assertTrue(initBody.contains("refreshTtsAvailability()"))
        assertFalse(bootstrapBody.contains("refreshTtsAvailability()"))
    }

    /** Keeps an utterance-locale voice result when discovery reuses the attached engine handle. */
    @Test
    fun sameHandleRefreshDoesNotOverwriteTheRuntimeVoiceResolution() {
        val body = appStateSource().readText().functionBody("publishTtsResolution")

        val sameHandleReturn = body.indexOf("if (handle === attachedTtsHandle) return")
        val resolutionPublish = body.indexOf("ttsVoiceResolution = handle?.voiceResolution")

        assertTrue(sameHandleReturn >= 0)
        assertTrue(resolutionPublish > sameHandleReturn)
    }

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppState.kt source file")
}

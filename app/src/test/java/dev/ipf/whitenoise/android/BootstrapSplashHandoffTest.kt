package dev.ipf.whitenoise.android

import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.awaitBootstrapAttempt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BootstrapSplashHandoffTest {
    @Test
    fun systemSplashHandsSlowBootstrapToComposeBeforeTwoSeconds() {
        assertTrue(shouldRetainSystemSplash(AppPhase.Bootstrapping, MAX_RETAINED_SYSTEM_SPLASH_MILLIS - 1L))
        assertFalse(shouldRetainSystemSplash(AppPhase.Bootstrapping, MAX_RETAINED_SYSTEM_SPLASH_MILLIS))
        assertTrue(MAX_RETAINED_SYSTEM_SPLASH_MILLIS < 2_000L)
    }

    @Test
    fun splashDeadlineStartsBeforeActivityAndApplicationSetup() {
        val source = mainActivitySource()
        val onCreate = source.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")

        assertTrue(onCreate.indexOf("splashInstalledAtMs = SystemClock.elapsedRealtime()") >= 0)
        assertTrue(
            onCreate.indexOf("splashInstalledAtMs = SystemClock.elapsedRealtime()") <
                onCreate.indexOf("super.onCreate(savedInstanceState)"),
        )
    }

    @Test
    fun releasedSplashRecordsTheComposeHandoff() {
        val source = mainActivitySource()
        val handoff = source.substringAfter("private fun holdSplashThroughBootstrap(")

        assertTrue(handoff.contains("if (!retain) appState.recordStartupSystemSplashHandoff()"))
        assertTrue(handoff.indexOf("recordStartupSystemSplashHandoff()") < handoff.indexOf("retain\n"))
    }

    @Test
    fun releaseLikeBenchmarkKeepsPrivacySafeStartupMarkers() {
        val source = appStateSource()
        val startupTiming = source.substringAfter("private fun startupTiming(")

        assertTrue(startupTiming.contains("BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS"))
        assertTrue(startupTiming.contains("uptime_ms="))
        assertTrue(source.contains("startupTiming(\"system-splash-handoff\""))
        assertTrue(source.contains("startupTiming(\"first-local-frame\""))
    }

    @Test
    fun completedAndFailedPhasesNeverRetainSystemSplash() {
        val failure = AppPhase.Failed(ErrorPresentation(AppText.Plain("safe"), "operation=TEST"))

        assertFalse(shouldRetainSystemSplash(AppPhase.Ready, 0L))
        assertFalse(shouldRetainSystemSplash(AppPhase.Onboarding, 0L))
        assertFalse(shouldRetainSystemSplash(failure, 0L))
    }

    @Test
    fun stalledAttemptTimesOutAndRetryCanAwaitTheSameAttempt() =
        runTest {
            val attempt = CompletableDeferred<Unit>()

            assertFalse(awaitBootstrapAttempt(attempt, timeoutMillis = 1L))
            assertTrue(attempt.isActive)

            attempt.complete(Unit)
            assertTrue(awaitBootstrapAttempt(attempt, timeoutMillis = 1L))
        }

    @Test
    fun normalAttemptCompletesWithoutTimeout() =
        runTest {
            val attempt = CompletableDeferred(Unit)

            assertTrue(awaitBootstrapAttempt(attempt, timeoutMillis = 1L))
        }

    private fun mainActivitySource(): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/MainActivity.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/MainActivity.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing MainActivity.kt")

    private fun appStateSource(): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing AppState.kt")
}

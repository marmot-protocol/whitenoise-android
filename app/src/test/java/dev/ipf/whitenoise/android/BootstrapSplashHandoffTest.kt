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

    /** A share launch never hands off to the ordinary startup surface or an unseeded picker. */
    @Test
    fun pendingShareKeepsSystemSplashUntilItsLocalPickerFrameIsReady() {
        assertTrue(
            shouldRetainSystemSplash(
                phase = AppPhase.Bootstrapping,
                elapsedMs = MAX_RETAINED_SYSTEM_SPLASH_MILLIS * 4,
                pendingShareFirstFrameReady = false,
            ),
        )
        assertTrue(
            shouldRetainSystemSplash(
                phase = AppPhase.Ready,
                elapsedMs = MAX_RETAINED_SYSTEM_SPLASH_MILLIS * 4,
                firstUsefulFrameReady = true,
                pendingShareFirstFrameReady = false,
            ),
        )
        assertFalse(
            shouldRetainSystemSplash(
                phase = AppPhase.Ready,
                elapsedMs = MAX_RETAINED_SYSTEM_SPLASH_MILLIS * 4,
                firstUsefulFrameReady = true,
                pendingShareFirstFrameReady = true,
            ),
        )
    }

    /** Account setup and recovery remain explicit surfaces rather than frozen share splashes. */
    @Test
    fun pendingShareDoesNotHideOnboardingOrFailureBehindSystemSplash() {
        val failure = AppPhase.Failed(ErrorPresentation(AppText.Plain("safe"), "operation=TEST"))

        assertFalse(
            shouldRetainSystemSplash(
                phase = AppPhase.Onboarding,
                elapsedMs = 0L,
                pendingShareFirstFrameReady = false,
            ),
        )
        assertFalse(
            shouldRetainSystemSplash(
                phase = failure,
                elapsedMs = 0L,
                pendingShareFirstFrameReady = false,
            ),
        )
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

        val recordHandoffIndex = handoff.indexOf("appState.recordStartupSystemSplashHandoff()")
        val schedulePeriodicWorkIndex = handoff.indexOf("schedulePeriodicWorkAfterFirstFrame()")
        val returnRetainIndex = handoff.indexOf("retain\n")

        assertTrue(handoff.contains("if (!retain) {"))
        assertTrue(recordHandoffIndex >= 0)
        assertTrue(schedulePeriodicWorkIndex > recordHandoffIndex)
        assertTrue(returnRetainIndex > schedulePeriodicWorkIndex)
    }

    @Test
    fun periodicWorkIsPostedToTheAnimationStepAfterSplashRelease() {
        val source = mainActivitySource()
        val scheduling = source.substringAfter("private fun schedulePeriodicWorkAfterFirstFrame()")

        val postAnimationIndex = scheduling.indexOf("window.decorView.postOnAnimation")
        val schedulePeriodicWorkIndex =
            scheduling.indexOf("(application as WhiteNoiseApplication).ensurePeriodicWorkScheduled()")

        assertTrue(postAnimationIndex >= 0)
        assertTrue(schedulePeriodicWorkIndex > postAnimationIndex)
    }

    @Test
    fun startupMarkersUseTheOptInTypedPerformanceEmitter() {
        val source = appStateSource()
        val startupTiming = startupPerformanceSource()

        assertTrue(startupTiming.contains("PerformanceDiagnostics.record("))
        assertTrue(source.contains("startupPerformance.record(PerformancePhase.SYSTEM_SPLASH_HANDOFF"))
        assertTrue(source.contains("startupPerformance.record(PerformancePhase.FIRST_LOCAL_FRAME"))
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

    private fun startupPerformanceSource(): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/diagnostics/StartupPerformanceDiagnostics.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/diagnostics/StartupPerformanceDiagnostics.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing StartupPerformanceDiagnostics.kt")
}

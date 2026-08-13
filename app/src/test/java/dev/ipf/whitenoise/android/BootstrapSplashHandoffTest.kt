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
}

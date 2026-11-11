package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.appModuleSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Diagnostics screen reads native events only through [streamDiagnosticEvents], which releases the
 * subscription on every exit, including cancellation (#3718).
 */
class DiagnosticsSubscriptionLifecycleCoverageTest {
    /** The screen acquires its subscription inside the stream helper and never destroys it itself. */
    @Test
    fun screenStreamsThroughTheReleasingHelper() {
        val screen = appModuleSource("$SETTINGS/DiagnosticsScreen.kt")
        assertTrue("the screen uses the helper", "streamDiagnosticEvents(" in screen)
        assertTrue(
            "the subscription is handed over inside its acquisition context",
            "acquire = { keep -> appState.marmotIo { keep(subscribeEvents()) } }" in screen,
        )
        assertFalse("no subscription is opened outside the helper", "val subscription = appState.marmotIo" in screen)
        assertTrue(
            "a cancelled stream cannot clear its replacement's Live indicator",
            "if (streamingOwner === owner) streaming = false" in screen,
        )
    }

    /** The helper releases outside the effect's cancellation, once, for any acquired subscription. */
    @Test
    fun helperReleasesOutsideCancellation() {
        // The helper file holds only this generic function, which functionBody cannot locate by name.
        val helper = appModuleSource("$SETTINGS/DiagnosticEventStream.kt")
        assertTrue("the helper is declared", "suspend fun <S : Any, E : Any> streamDiagnosticEvents(" in helper)
        assertTrue("acquisition is kept before it returns", "acquire { acquired = it }" in helper)
        assertTrue(
            "release runs in a non-cancellable context",
            "withContext(NonCancellable + Dispatchers.IO) { runCatching { release(subscription) } }" in helper,
        )
        assertTrue("release sits in finally", helper.indexOf("} finally {") < helper.indexOf("release(subscription)"))
    }

    private companion object {
        const val SETTINGS = "src/main/java/dev/ipf/whitenoise/android/ui/settings"
    }
}

package dev.ipf.whitenoise.android.ui.settings

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * How the consent sheet closes, and what it does while it is open.
 *
 * The sheet is the first thing a new install shows and it has no confirm button, so closing it *is* the
 * receipt. The close therefore happens on the gesture rather than after the writes it triggers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class UsageDiagnosticsPromptDismissTest {
    @get:Rule val composeRule = createComposeRule()

    /** Closing reports done once, and the writes it starts afterwards do not report it again. */
    @Test
    fun closingReportsDoneOnce() {
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED)
        runBlocking { state.refreshSecurityPrivacySettings() }
        var doneCount = 0
        composeRule.setContent {
            WhiteNoiseTheme { UsageDiagnosticsPrompt(state) { doneCount += 1 } }
        }

        composeRule.onNodeWithContentDescription(CLOSE).performClick()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) { idleAnd { doneCount > 0 } }
        repeat(SETTLING_PASSES) { composeRule.waitForIdle() }

        assertEquals("the sheet must report done exactly once", 1, doneCount)
    }

    /**
     * Choosing an option leaves the sheet usable rather than tearing it down.
     *
     * This does not prove the sheet was never rebuilt: that rebuild shows as an animation, and a settled
     * frame has the sheet back where it started either way. It catches the coarser failure — a choice
     * leaving the sheet gone or its close unreachable — while the rebuild itself is prevented by
     * remembering the confirm-value-change lambda, documented where that is written.
     */
    @Test
    fun choosingAnOptionLeavesTheSheetUsable() {
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED)
        runBlocking { state.refreshSecurityPrivacySettings() }
        composeRule.setContent {
            WhiteNoiseTheme { UsageDiagnosticsPrompt(state, onDone = {}) }
        }

        composeRule.onAllNodes(isToggleable())[0].performClick()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) { idleAnd { !state.diagnostics.busy } }

        composeRule.onNodeWithContentDescription(CLOSE).assertIsDisplayed()
    }

    private fun idleAnd(condition: () -> Boolean): Boolean {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    private companion object {
        const val CLOSE = "Close"
        const val TIMEOUT_MS = 5_000L
        const val SETTLING_PASSES = 3
    }
}

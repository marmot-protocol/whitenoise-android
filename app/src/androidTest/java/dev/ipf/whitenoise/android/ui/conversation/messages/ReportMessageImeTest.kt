package dev.ipf.whitenoise.android.ui.conversation.messages

import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.marmotkit.ReportReasonFfi
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.state.REPORT_EXPLANATION_LIMIT
import dev.ipf.whitenoise.android.state.REPORT_REASONS
import dev.ipf.whitenoise.android.state.reportReasonLabel
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production sheet and platform IME; only the encrypted publication boundary is substituted. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ReportMessageImeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Uses the same resized edge-to-edge host as the app; the sheet owns its own insets. */
    @Before
    @Suppress("DEPRECATION")
    fun configureWindow() {
        composeRule.runOnUiThread {
            composeRule.activity.enableEdgeToEdge()
            composeRule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    /** A report can be sent with a long explanation while the real keyboard is still docked and visible. */
    @Test
    fun sendsFromPinnedFooterAboveRealIme() {
        val submissions = mutableListOf<Pair<ReportReasonFfi, String>>()
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = 2f) {
                ReportMessageSheet({}, { reason, text -> submissions += reason to text })
            }
        }
        composeRule.onNodeWithText("Impersonation").performScrollTo().performClick()
        composeRule.onNodeWithTag("message.report.explanation").performScrollTo().performClick()
        val explanation = "Evidence " + "repeated explanation ".repeat(80)
        composeRule.onNodeWithTag("message.report.explanation").performTextReplacement(explanation)
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) { imeGeometry() != null }
        assertActionAboveIme()
        assertReasonsReachableAboveIme()
        composeRule.runOnUiThread {
            val window = WindowInspector.getGlobalWindowViews().first { it.hasWindowFocus() }
            ViewCompat.getWindowInsetsController(window)?.hide(WindowInsetsCompat.Type.ime())
        }
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) { imeGeometry() == null }
        composeRule.onNodeWithTag("message.report.send").assertIsDisplayed()
        composeRule.onNodeWithTag("message.report.explanation").performScrollTo().performClick()
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) { imeGeometry() != null }
        assertActionAboveIme()
        composeRule.onNodeWithTag("message.report.send").performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(ReportReasonFfi.IMPERSONATION to explanation.take(REPORT_EXPLANATION_LIMIT).trim()),
                submissions,
            )
        }
    }

    /** Every reason can still be reached without dismissing the keyboard or moving the Send footer. */
    private fun assertReasonsReachableAboveIme() {
        REPORT_REASONS.forEach { candidate ->
            composeRule
                .onNodeWithText(composeRule.activity.getString(reportReasonLabel(candidate)))
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            assertActionAboveIme()
        }
        composeRule.onNodeWithText("Impersonation").performScrollTo().performClick()
    }

    /** Uses the focused sheet window, rather than assuming the Activity receives its dialog's IME insets. */
    private fun imeGeometry(): Pair<Int, Int>? =
        composeRule.runOnUiThread {
            val window =
                WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() }
                    ?: return@runOnUiThread null
            val insets = ViewCompat.getRootWindowInsets(window) ?: return@runOnUiThread null
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (!insets.isVisible(WindowInsetsCompat.Type.ime()) || ime <= 0) null else window.height to ime
        }

    /** Reads the action's real dialog-window coordinates while the actual platform keyboard remains visible. */
    private fun assertActionAboveIme() {
        composeRule.onNodeWithTag("message.report.send").assertIsDisplayed()
        val (windowHeight, imeHeight) = requireNotNull(imeGeometry())
        val footer = composeRule.onNodeWithTag("message.report.send").fetchSemanticsNode().boundsInWindow
        assertTrue("Send overlaps real IME: $footer", footer.bottom <= windowHeight - imeHeight + TOLERANCE_PX)
    }

    private companion object {
        const val KEYBOARD_TIMEOUT_MS = 10_000L
        const val TOLERANCE_PX = 2
    }
}

package dev.ipf.whitenoise.android.ui.common

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Startup recovery preserves the exact native-safe report and optional retry grant in adaptive chrome. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class StartupRecoveryPresentationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var retries = 0

    /** No timer or composition effect may retry; the visible button delivers the user action once. */
    @Test fun failureWaitsForExplicitRetry() {
        show(ErrorPresentation(AppText.Plain("Safe failure"), "operation=APP_BOOTSTRAP"))
        composeRule.waitForIdle()
        assertEquals(0, retries)
        composeRule.onNodeWithTag(STARTUP_RETRY_TEST_TAG).performClick()
        assertEquals(1, retries)
    }

    /** A non-retryable native failure retains copy while withholding a retry the engine did not grant. */
    @Test fun terminalFailureStillCopiesExactSafeReport() {
        val report = "operation=APP_BOOTSTRAP\nkind=InitializationFailure"
        show(ErrorPresentation(AppText.Plain("Safe terminal failure"), report, retryable = false))
        composeRule.onNodeWithTag(STARTUP_RETRY_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(STARTUP_COPY_TEST_TAG).performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(
            report,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
        assertEquals(0, retries)
    }

    /** Long recovery messages at 200% text cannot make Retry or diagnostic-copy unreachable. */
    @Test
    @Config(qualifiers = "en-w780dp-h360dp-mdpi")
    fun shortLargeFontFailureKeepsBothActionsScrollable() {
        show(ErrorPresentation(AppText.Plain("A recoverable startup explanation. ".repeat(20)), "operation=TEST"), 2f)
        composeRule.onNodeWithTag(STARTUP_RETRY_TEST_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(STARTUP_COPY_TEST_TAG).performScrollTo().performClick()
        assertEquals(1, retries)
    }

    /** Loading keeps its trace tag and contains no retry, failure or interactive-surface marker. */
    @Test fun startupLoadingNeverClaimsAUsefulInteractiveFrame() {
        composeRule.setContent { WhiteNoiseTheme { StartupLoadingScreen() } }
        composeRule.onNodeWithTag(STARTUP_LOADING_TEST_TAG).assertExists()
        composeRule.onNodeWithTag(STARTUP_RETRY_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(STARTUP_FAILURE_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(WARM_RESUME_USEFUL_SURFACE_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Starting White Noise…").assertExists()
    }

    /** Uses only native error presentation input; choosing a profile is intentionally not invented here. */
    private fun show(
        error: ErrorPresentation,
        scale: Float = 1f,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = scale) {
                StartupFailureScreen("White Noise couldn't start", error) { retries++ }
            }
        }
    }
}

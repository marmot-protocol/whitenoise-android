package dev.ipf.whitenoise.android.ui.common

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies lock-cover behavior without substituting a successful authentication result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AppLockScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Retry is a request only: repeated requests leave the secure cover visible. */
    @Test
    fun retryNeverDismissesTheSecureCover() {
        var retries = 0
        composeRule.setContent { WhiteNoiseTheme { AppLockScreen(error = null, onRetry = { retries++ }) } }
        repeat(2) { composeRule.onNodeWithTag("app.unlock").performClick() }
        composeRule.onNodeWithTag("app.lock").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(2, retries)
            assertTrue(secure())
        }
    }

    /** The persisted decision cannot be bypassed with retry, and completion restores the native request action. */
    @Test
    fun evaluationKeepsCoverSecureAndDefersRetry() {
        val evaluating = mutableStateOf(true)
        var retries = 0
        composeRule.setContent {
            WhiteNoiseTheme { AppLockScreen(error = null, onRetry = { retries++ }, evaluating = evaluating.value) }
        }
        composeRule.onNodeWithTag("app.unlock").assertDoesNotExist()
        composeRule.onNodeWithTag("app.lock.checking").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(secure())
            assertEquals(0, retries)
            evaluating.value = false
        }
        composeRule.onNodeWithTag("app.lock.checking").assertDoesNotExist()
        composeRule.onNodeWithTag("app.unlock").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
        composeRule.onNodeWithTag("app.lock").assertIsDisplayed()
    }

    /** Every existing native failure is resolved and announced; no error path removes the lock. */
    @Test
    fun cancellationFailureAndUnavailableErrorsRemainRetryable() {
        val error = mutableStateOf<AppText?>(null)
        composeRule.setContent { WhiteNoiseTheme { AppLockScreen(error = error.value, onRetry = {}) } }
        listOf(
            R.string.app_lock_auth_cancelled,
            R.string.app_lock_auth_failed,
            R.string.app_lock_auth_locked,
            R.string.app_lock_auth_timed_out,
            R.string.app_lock_auth_unavailable,
        ).forEach { id ->
            composeRule.runOnIdle { error.value = AppText.Resource(id) }
            composeRule.onNodeWithText(composeRule.activity.getString(id)).assertIsDisplayed()
            composeRule.onNodeWithTag("app.lock.error").assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
            composeRule.onNodeWithTag("app.unlock").assertIsDisplayed()
            composeRule.runOnIdle { assertTrue(secure()) }
        }
    }

    /** A lock cover releases only its own secure-window claim when the authenticated owner removes it. */
    @Test
    fun disposingLockPreservesAnotherSecureSurface() {
        val visible = mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                WindowSecureFlag()
                if (visible.value) AppLockScreen(error = null, onRetry = {})
            }
        }
        composeRule.runOnIdle { visible.value = false }
        composeRule.onNodeWithTag("app.lock").assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(secure()) }
    }

    /** The final lock-window claim is released only after the caller removes the cover. */
    @Test
    fun disposingOnlyLockReleasesItsSecureFlag() {
        val visible = mutableStateOf(true)
        composeRule.setContent { WhiteNoiseTheme { if (visible.value) AppLockScreen(error = null, onRetry = {}) } }
        composeRule.runOnIdle {
            assertTrue(secure())
            visible.value = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertFalse(secure()) }
    }

    /** Short landscape windows retain a scroll path to both error and retry. */
    @Test
    @Config(qualifiers = "en-w640dp-h280dp-mdpi")
    fun shortWindowKeepsErrorAndRetryReachable() {
        val text = "Authentication could not finish. ".repeat(20)
        composeRule.setContent { WhiteNoiseTheme { AppLockScreen(error = AppText.Plain(text), onRetry = {}) } }
        composeRule.onNodeWithTag("app.unlock").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("app.lock.error").performScrollTo().assertExists()
    }

    /** Reads the real activity flag set by WindowSecureFlag. */
    private fun secure(): Boolean {
        val flags = composeRule.activity.window.attributes.flags
        return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }
}

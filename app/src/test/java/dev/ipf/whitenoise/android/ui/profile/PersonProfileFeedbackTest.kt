package dev.ipf.whitenoise.android.ui.profile

import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Native feedback remains visible above the profile window with the existing safe-report Copy contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonProfileFeedbackTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Already-visible global notices are not replayed as a new profile operation result. */
    @Test fun onlyFreshNativeFeedbackIsMirrored() {
        val previous = ToastMessage(AppText.Plain("Earlier error"))
        val current = ToastMessage(AppText.Plain("Current operation failed"))
        assertNull(personProfileFeedback(previous, previous))
        assertNull(personProfileFeedback(previous, null))
        assertSame(current, personProfileFeedback(previous, current))
    }

    /** Copy delivers only the native safe diagnostic report, preserving explicit secure-window policy. */
    @Test fun failureCopyUsesTheExactNativeSafeReport() {
        val feedback =
            ToastMessage(
                AppText.Plain("Could not update group"),
                AppText.Plain("Try again"),
                copyable = true,
                diagnosticReport = "safe-report: PROFILE_GROUP_INVITE",
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                PersonProfileFeedbackDialog(feedback, {}, SecureFlagPolicy.SecureOn)
            }
        }
        composeRule.onNodeWithText("Could not update group").assertExists()
        composeRule.onNodeWithTag("person-profile-feedback-copy").performClick()
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            feedback.diagnosticReport,
            context
                .getSystemService(ClipboardManager::class.java)
                .primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
        composeRule.runOnIdle {
            assertTrue(
                checkNotNull(ShadowDialog.getLatestDialog().window).attributes.flags and
                    WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }
    }

    /** The native noncopyable flag suppresses Copy even if a diagnostic string exists. */
    @Test fun noncopyableNoticeHasNoCopyAction() {
        var dismissed = false
        composeRule.setContent {
            WhiteNoiseTheme {
                PersonProfileFeedbackDialog(
                    ToastMessage(
                        AppText.Plain("Follow failed"),
                        diagnosticReport = "not-authorized-for-copy",
                    ),
                    { dismissed = true },
                )
            }
        }
        composeRule.onNodeWithTag("person-profile-feedback-copy").assertDoesNotExist()
        composeRule.onNodeWithTag("person-profile-feedback-dismiss").performClick()
        assertTrue(dismissed)
    }
}

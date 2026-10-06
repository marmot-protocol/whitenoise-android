package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.LocalDeleteNotice
import dev.ipf.whitenoise.android.state.presentLocalDeleteFailure
import dev.ipf.whitenoise.android.ui.common.LocalDeleteSnackbar
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.common.finishLocalDeleteSnackbar
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalDeleteSnackbarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun detailsAndDismissDoNotPerformTheDestructiveRetry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = NoticeData(context)
        composeRule.setContent { WhiteNoiseTheme { LocalDeleteSnackbar(data, data.visuals) } }
        composeRule.onNodeWithText(REPORT).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.details)).performClick()
        composeRule.onNodeWithText(REPORT).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.dismiss)).performClick()
        assertEquals(0, data.retries)
        assertEquals(0, data.dismissals)
        composeRule.onNodeWithContentDescription(context.getString(R.string.dismiss)).performClick()
        assertEquals(0, data.retries)
        assertEquals(1, data.dismissals)
    }

    @Test
    fun detailsDoNotOfferCopyWhenTheReportIsNotCopyable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = NoticeData(context, copyable = false)
        composeRule.setContent { WhiteNoiseTheme { LocalDeleteSnackbar(data, data.visuals) } }
        composeRule.onNodeWithText(context.getString(R.string.details)).performClick()
        composeRule.onNodeWithText(REPORT).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.copy)).assertDoesNotExist()
        assertEquals(0, data.retries)
    }

    @Test
    fun retryUsesTheSnackbarActionExactlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = NoticeData(context)
        composeRule.setContent { WhiteNoiseTheme { LocalDeleteSnackbar(data, data.visuals) } }
        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        assertEquals(1, data.retries)
        assertEquals(0, data.dismissals)
    }

    @Test
    fun aDismissedOrReplacedNoticeCannotRetryAndTheCurrentActionRunsOnlyOnce() {
        val state = emptyAppState()
        var retries = 0
        val notice = LocalDeleteNotice("account", setOf("group")) { retries++ }
        state.presentLocalDeleteFailure(R.string.toast_couldnt_delete_chat, null, notice = notice)
        val dismissed = requireNotNull(state.toast)
        finishLocalDeleteSnackbar(state, dismissed, SnackbarResult.Dismissed)
        assertEquals(0, retries)
        state.presentLocalDeleteFailure(R.string.toast_couldnt_delete_chat, null, notice = notice)
        val superseded = requireNotNull(state.toast)
        state.present(R.string.error_try_again)
        val replacement = state.toast
        finishLocalDeleteSnackbar(state, superseded, SnackbarResult.ActionPerformed)
        assertEquals(0, retries)
        assertEquals(replacement, state.toast)
        state.presentLocalDeleteFailure(R.string.toast_couldnt_delete_chat, null, notice = notice)
        val current = requireNotNull(state.toast)
        finishLocalDeleteSnackbar(state, current, SnackbarResult.ActionPerformed)
        finishLocalDeleteSnackbar(state, current, SnackbarResult.ActionPerformed)
        assertEquals(1, retries)
        assertEquals(null, state.toast)
    }

    private class NoticeData(
        context: Context,
        copyable: Boolean = true,
    ) : SnackbarData {
        override val visuals =
            ToastSnackbarVisuals(
                message = context.getString(R.string.toast_couldnt_delete_chat),
                copyable = copyable,
                copyText = REPORT,
                details = REPORT,
                actionLabel = context.getString(R.string.retry),
            )
        var retries = 0
        var dismissals = 0

        override fun performAction() {
            retries++
        }

        override fun dismiss() {
            dismissals++
        }
    }

    private companion object {
        const val REPORT = "operation=CHAT_LOCAL_DELETE"
    }
}

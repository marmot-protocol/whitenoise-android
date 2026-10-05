package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.LocalDeleteSnackbar
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
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
    @get:Rule val composeRule = createComposeRule()

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
    fun retryUsesTheSnackbarActionExactlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = NoticeData(context)
        composeRule.setContent { WhiteNoiseTheme { LocalDeleteSnackbar(data, data.visuals) } }
        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        assertEquals(1, data.retries)
        assertEquals(0, data.dismissals)
    }

    private class NoticeData(context: Context) : SnackbarData {
        override val visuals = ToastSnackbarVisuals(
            message = context.getString(R.string.toast_couldnt_delete_chat),
            copyable = true, copyText = REPORT, details = REPORT,
            actionLabel = context.getString(R.string.retry),
        )
        var retries = 0
        var dismissals = 0
        override fun performAction() { retries++ }
        override fun dismiss() { dismissals++ }
    }

    private companion object {
        const val REPORT = "operation=CHAT_LOCAL_DELETE"
    }
}

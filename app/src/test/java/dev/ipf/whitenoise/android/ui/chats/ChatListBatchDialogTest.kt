package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListBatchCommand
import dev.ipf.whitenoise.android.state.ChatListBatchOutcome
import dev.ipf.whitenoise.android.state.ChatListBatchResult
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w280dp-h700dp-mdpi")
class ChatListBatchDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun mixedRecoveryKeepsFailedRetryAvailableBesideReadOnlyCheck() {
        val outcome = mixedOutcome()
        val callbacks = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                ChatListBatchStatusDialog(
                    ChatListBatchCommand.READ,
                    2,
                    2,
                    outcome,
                    true,
                    onRetry = { callbacks += "retry" },
                    onDismiss = { callbacks += "close" },
                    onCheck = { callbacks += "check" },
                )
            }
        }
        assertEquals(listOf("failed"), outcome.retryIds)
        for (label in listOf(R.string.chat_batch_check_results, R.string.retry, R.string.close)) {
            composeRule.onNodeWithText(string(label)).assertIsDisplayed().performClick()
        }
        assertEquals(listOf("check", "retry", "close"), callbacks)
    }

    @Test fun retiredOwnerCannotDispatchEitherRecoveryAction() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListBatchStatusDialog(
                    ChatListBatchCommand.READ,
                    2,
                    2,
                    mixedOutcome(),
                    false,
                    onRetry = { error("retired retry") },
                    onDismiss = {},
                    onCheck = { error("retired check") },
                )
            }
        }
        composeRule.onNodeWithText(string(R.string.retry)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.chat_batch_check_results)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.close)).assertIsDisplayed()
    }

    private fun mixedOutcome() =
        ChatListBatchResult(
            mapOf("failed" to ChatListBatchOutcome.FAILED, "unknown" to ChatListBatchOutcome.UNCONFIRMED),
            attemptedIds = setOf("unknown"),
        )

    private fun string(id: Int): String {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return context.getString(id)
    }
}

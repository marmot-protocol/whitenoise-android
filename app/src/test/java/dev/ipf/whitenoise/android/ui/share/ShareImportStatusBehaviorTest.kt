package dev.ipf.whitenoise.android.ui.share

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.ShareImportError
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareImportStatusBehaviorTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun progressCancelCallsOnlyCancellationAndNeverExposesRecipients() {
        var cancels = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ShareImportStatus(true, null, null, { cancels++ }) { if (it) Text("Recipients") }
            }
        }
        composeRule.onNodeWithText("Recipients").assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.cancel)).performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun aPartialBatchRequiresExplicitAcknowledgementBeforeRecipientSelection() {
        val request =
            ShareRequest(
                SharePayload(
                    null,
                    listOf(Uri.parse("content://private/file")),
                    null,
                    true,
                    listOf(ShareImportError.Unreadable),
                    1,
                ),
                shortcutId = null,
                requestId = "partial",
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                ShareImportStatus(false, null, request, {}) { if (it) Text("Recipients") }
            }
        }
        composeRule.onNodeWithText("Recipients").assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.share_to)).performClick()
        composeRule.onNode(isDialog()).assertDoesNotExist()
        composeRule.onNodeWithText("Recipients").assertExists()
    }

    @Test
    fun noAcceptedItemOffersCloseWithoutAnEmptyComposer() {
        var cancels = 0
        val request =
            ShareRequest(
                SharePayload(
                    null,
                    emptyList(),
                    null,
                    true,
                    listOf(ShareImportError.Interrupted),
                ),
                shortcutId = null,
                requestId = "interrupted",
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                ShareImportStatus(false, null, request, { cancels++ }) { if (it) Text("Recipients") }
            }
        }
        composeRule.onNodeWithText(text(R.string.share_to)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.close)).performClick()
        assertEquals(1, cancels)
        composeRule.onNodeWithText("Recipients").assertDoesNotExist()
    }

    private fun text(id: Int): String = RuntimeEnvironment.getApplication().getString(id)
}

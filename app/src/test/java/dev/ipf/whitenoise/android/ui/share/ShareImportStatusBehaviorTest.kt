package dev.ipf.whitenoise.android.ui.share

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    /** Clicks cancel during progress while recipient content remains gated behind unfinished import. */
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
        composeRule.waitForIdle()
        assertEquals(1, cancels)
    }

    /** Keeps accepted content hidden until the user acknowledges the visible partial-import failure. */
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

    /** Restores an interrupted empty batch and permits only closing, without exposing recipient selection. */
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
        composeRule.waitForIdle()
        assertEquals(1, cancels)
        composeRule.onNodeWithText("Recipients").assertDoesNotExist()
    }

    /** A real import completion cannot dispose the accepted cancellation before its terminal frame. */
    @Test
    fun progressCancellationSurvivesImportCompletionDuringExit() {
        var importing by mutableStateOf(true)
        var request by mutableStateOf<ShareRequest?>(null)
        var cancels = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ShareImportStatus(
                    importing = importing,
                    progress = null,
                    request = request,
                    onCancel = {
                        cancels++
                        request = null
                    },
                    importRequestId = "finishing-import",
                ) { if (it) Text("Recipients") }
            }
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text(R.string.cancel)).performClick()
        composeRule.runOnIdle {
            assertEquals(0, cancels)
            // The producer completes before the popup's 280 ms close animation.
            request = ShareRequest(SharePayload("accepted", emptyList(), null, true), null, "finishing-import")
            importing = false
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Recipients").assertDoesNotExist()
        composeRule.onNode(isDialog()).assertExists()
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
        assertEquals(1, cancels)
        composeRule.onNodeWithText("Recipients").assertDoesNotExist()
    }

    /** Replacing the producer owner cancels the old accepted action without blocking the new import. */
    @Test
    fun replacingImportOwnerRevokesPendingCancellation() {
        var owner by mutableStateOf("first-import")
        var importing by mutableStateOf(true)
        var request by mutableStateOf<ShareRequest?>(null)
        val cancelled = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                val currentOwner = owner
                ShareImportStatus(
                    importing = importing,
                    progress = null,
                    request = request,
                    onCancel = { cancelled += currentOwner },
                    importRequestId = currentOwner,
                ) { if (it) Text("Recipients") }
            }
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onNodeWithText(text(R.string.cancel)).performClick()
        composeRule.runOnIdle {
            owner = "second-import"
            importing = false
            request = ShareRequest(SharePayload("accepted", emptyList(), null, true), null, owner)
        }
        composeRule.mainClock.advanceTimeBy(800)
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), cancelled)
        composeRule.onNodeWithText("Recipients").assertExists()
        composeRule.onNode(isDialog()).assertDoesNotExist()
    }

    private fun text(id: Int): String = RuntimeEnvironment.getApplication().getString(id)
}

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The kept-message card: titled, collapsed to a short preview until it is
 * long-pressed, paginated only when more than one message is kept, and absent
 * entirely once the stack empties.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-mdpi")
class KeptMessageCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** An empty stack draws no card at all. */
    @Test
    fun anEmptyStackDrawsNothing() {
        render(KeptMessagesController())

        composeRule.onNodeWithTag(KEPT_MESSAGE_CARD_TAG).assertDoesNotExist()
    }

    /** One kept message shows the title and a collapsed preview, with no pager gutter. */
    @Test
    fun oneKeptMessageShowsTitleAndPreview() {
        render(controllerWith("aa"))

        composeRule.onNodeWithTag(KEPT_MESSAGE_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.floating_title)).assertIsDisplayed()
        composeRule.onNodeWithTag(KEPT_MESSAGE_TEXT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(KEPT_MESSAGE_PAGINATION_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(KEPT_MESSAGE_AUTHOR_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(KEPT_MESSAGE_SOURCE_TAG).assertDoesNotExist()
    }

    /** A second kept message brings in the pagination gutter. */
    @Test
    fun severalKeptMessagesShowPagination() {
        render(controllerWith("aa", "bb"))

        composeRule.onNodeWithTag(KEPT_MESSAGE_PAGINATION_TAG).assertExists()
    }

    /** Long-pressing the card expands it into author, body and source. */
    @Test
    fun longPressExpandsTheCard() {
        render(controllerWith("aa"))

        composeRule.onNodeWithTag(KEPT_MESSAGE_CARD_TAG).performTouchInput { longClick() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(KEPT_MESSAGE_EXPANDED_TAG).assertExists()
        composeRule.onNodeWithTag(KEPT_MESSAGE_AUTHOR_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(KEPT_MESSAGE_SOURCE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(KEPT_MESSAGE_COLLAPSE_TAG).assertExists()
    }

    /** The overflow menu offers going to the message, removing it and clearing the stack. */
    @Test
    fun theOverflowMenuOffersTheKeptMessageActions() {
        render(controllerWith("aa"))

        composeRule.onNodeWithTag(KEPT_MESSAGE_MENU_TAG).performClick()
        composeRule.waitForIdle()

        listOf(
            R.string.go_to_message,
            R.string.floating_remove,
            R.string.floating_clear,
            R.string.floating_move_side,
            R.string.floating_move_top,
            R.string.floating_move_bottom,
        ).forEach { label ->
            composeRule.onNodeWithText(app.getString(label), substring = false).assertExists()
        }
    }

    /** Removing the last kept message takes the card away with it. */
    @Test
    fun removingTheLastMessageDismissesTheCard() {
        val controller = controllerWith("aa")
        render(controller)

        composeRule.runOnIdle { controller.remove(KeptMessageKey(ACCOUNT, GROUP, "aa")) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(KEPT_MESSAGE_CARD_TAG).assertDoesNotExist()
        assertEquals(emptyList<KeptMessageKey>(), controller.keys(ACCOUNT))
    }

    private fun controllerWith(vararg ids: String): KeptMessagesController =
        KeptMessagesController().apply { ids.forEach { keep(KeptMessageKey(ACCOUNT, GROUP, it)) } }

    private fun render(controller: KeptMessagesController) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxSize()) {
                    val entries =
                        rememberKeptMessageEntries(controller, ACCOUNT) { key ->
                            timelineMessage(key.messageIdHex)
                        }
                    KeptMessagesOverlay(
                        state = KeptMessagesOverlayState(entries, controller, ACCOUNT),
                        composerHeight = 96.dp,
                        presentation = { entry ->
                            KeptMessagePresentation(
                                authorName = "Alice",
                                body = "kept body ${entry.key.messageIdHex}",
                                chatTitle = "Design",
                                timeLabel = "12:34",
                            )
                        },
                        onOpenMessage = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun timelineMessage(id: String): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "received",
                    groupIdHex = GROUP,
                    sender = "alice",
                    plaintext = "kept body $id",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = 9uL,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 1_700_000_000uL,
                    receivedAt = 1_700_000_000uL,
                ),
            status = MessageStatus.Received,
        )

    private companion object {
        const val ACCOUNT = "account-a"
        const val GROUP = "group"
    }
}

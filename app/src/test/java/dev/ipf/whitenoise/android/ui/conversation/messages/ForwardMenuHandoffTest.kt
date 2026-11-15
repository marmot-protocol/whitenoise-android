package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.conversation.TimelineRowMessageBubble
import dev.ipf.whitenoise.android.ui.conversation.emptyPaintDocument
import dev.ipf.whitenoise.android.ui.conversation.paintTestAppState
import dev.ipf.whitenoise.android.ui.conversation.paintTestGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the production row's action and real picker window, including their teardown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ForwardMenuHandoffTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun forwardingDoesNotDismissAndRestoreTheComposer() = assertHandoff(MessageMenuKeyboardOwner.Composer)

    @Test fun forwardingDoesNotDismissAndRestoreConversationSearch() = assertHandoff(MessageMenuKeyboardOwner.Search)

    @Suppress("LongMethod") // Keeps the production row, callbacks and picker assertions in one test fixture.
    private fun assertHandoff(owner: MessageMenuKeyboardOwner) {
        val account = "11".repeat(32)
        val group = "22".repeat(32)
        val appState = paintTestAppState(ApplicationProvider.getApplicationContext(), "local", account)
        val controller = ConversationController(appState, paintTestGroup(group, account))
        val record = forwardRecord(group)
        val item = TimelineMessage(id = "msg:${record.messageIdHex}", record = record, status = MessageStatus.Received)
        val keyboard = MessageMenuKeyboardPolicy().apply { open(true, null, null, owner) }
        var menuOpen by mutableStateOf(true)
        var handoffs = 0
        var restorations = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                TimelineRowMessageBubble(
                    messageIdHex = record.messageIdHex,
                    item = item,
                    controller = controller,
                    appState = appState,
                    highlighted = false,
                    selectionMode = false,
                    textSelectionMode = false,
                    onTextSelectionModeChange = {},
                    onTextSelectionBoundsChange = {},
                    batchSelectable = false,
                    selected = false,
                    onToggleSelection = {},
                    rangeDragActive = false,
                    onDragSelectionStart = {},
                    onDragSelection = { false },
                    onDragSelectionEnd = {},
                    onDragSelectionCancel = {},
                    quickReactionEmojis = emptyList(),
                    recentEmojis = emptyList(),
                    onEmojiUsed = {},
                    isActionMenuOpen = menuOpen,
                    onActionMenuOpenChange = { open ->
                        menuOpen = open
                        if (!open && keyboard.close(null, null) != null) restorations += 1
                    },
                    onForwardMenuHandoff = {
                        keyboard.cancel()
                        handoffs += 1
                    },
                    onQuickReactionsSave = {},
                    onReplyPreviewClick = {},
                    showSenderName = false,
                    showSenderAvatar = false,
                    collapseLongMessages = false,
                    readOnly = false,
                    parseMarkdown = { record.contentTokens },
                )
            }
        }
        composeRule.onNodeWithText("Forward").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).assertExists()
        composeRule.runOnIdle {
            assertEquals(1, handoffs)
            assertEquals(0, restorations)
            assertNull(keyboard.close(null, null))
        }
        composeRule.onNode(hasContentDescription("Close") and hasAnyAncestor(isDialog())).performClick()
        composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, restorations) }
    }

    private fun forwardRecord(group: String) =
        AppMessageRecordFfi(
            messageIdHex = "33".repeat(32),
            direction = "received",
            groupIdHex = group,
            sender = "44".repeat(32),
            plaintext = "Forward this message",
            contentTokens = emptyPaintDocument(),
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
            sticker = null,
        )
}

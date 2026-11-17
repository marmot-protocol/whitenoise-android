package dev.ipf.whitenoise.android.ui.conversation.messages

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.conversation.TimelineRowMessageBubble
import dev.ipf.whitenoise.android.ui.conversation.emptyPaintDocument
import dev.ipf.whitenoise.android.ui.conversation.paintTestAppState
import dev.ipf.whitenoise.android.ui.conversation.paintTestGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the production row's action and real picker window, including their teardown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ForwardMenuHandoffTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun forwardingDoesNotDismissAndRestoreTheComposer() = assertHandoff(MessageMenuKeyboardOwner.Composer)

    @Test fun forwardingDoesNotDismissAndRestoreConversationSearch() = assertHandoff(MessageMenuKeyboardOwner.Search)

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun mediaEligibilityLossClosesThePickerAndMenuWithoutRestoringTheKeyboard() {
        assertHandoff(MessageMenuKeyboardOwner.Composer, media = true)
    }

    @Test fun textSnapshotSurvivesLiveEligibilityLoss() {
        assertHandoff(MessageMenuKeyboardOwner.Composer, loseTextEligibility = true)
    }

    @Suppress("LongMethod") // Keeps the production row, callbacks and picker assertions in one test fixture.
    private fun assertHandoff(
        owner: MessageMenuKeyboardOwner,
        media: Boolean = false,
        loseTextEligibility: Boolean = false,
    ) {
        val account = "11".repeat(32)
        val group = "22".repeat(32)
        val appState = paintTestAppState(ApplicationProvider.getApplicationContext(), "local", account)
        val controller = ConversationController(appState, paintTestGroup(group, account))
        val record =
            forwardRecord(group).let {
                if (media) it.copy(tags = listOf(MessageTagFfi(listOf("imeta", "m application/pdf")))) else it
            }
        val projection =
            timelineRecord(record.messageIdHex, record.recordedAt, record.plaintext).copy(
                groupIdHex = group,
                sender = record.sender,
                tags = record.tags,
                media = if (media) MessageAttachments.acceptedOutcomes(listOf(mediaReference())) else emptyList(),
            )
        var item by mutableStateOf(
            TimelineMessage(
                id = "msg:${record.messageIdHex}",
                record = record,
                status = MessageStatus.Received,
                projected = projection,
            ),
        )
        val keyboard = MessageMenuKeyboardPolicy().apply { open(true, null, null, owner) }
        var menuOpen by mutableStateOf(true)
        var handoffs = 0
        var restorations = 0
        lateinit var sourceView: View
        composeRule.setContent {
            sourceView = LocalView.current
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
        if (media) drawMediaSource(sourceView)
        composeRule
            .onNodeWithText("Forward")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).assertExists()
        composeRule.runOnIdle {
            assertEquals(1, handoffs)
            assertEquals(0, restorations)
            assertNull(keyboard.close(null, null))
        }
        val pickerClose = composeRule.onNode(hasContentDescription("Close") and hasAnyAncestor(isDialog()))
        pickerClose.assertExists()
        if (media || loseTextEligibility) {
            // Real row eligibility consumes delivery status: no injected forward-policy result.
            composeRule.runOnIdle { item = item.copy(status = MessageStatus.Failed) }
            composeRule.waitForIdle()
        }
        if (media) {
            pickerClose.assertDoesNotExist()
            composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).assertDoesNotExist()
            composeRule.runOnIdle { assertFalse(menuOpen) }
            // Restoring eligibility must not reopen the orphaned picker. A fresh menu still dismisses normally.
            composeRule.runOnIdle {
                item = item.copy(status = MessageStatus.Received)
                menuOpen = true
            }
            drawMediaSource(sourceView)
            composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).assertExists()
            pickerClose.assertDoesNotExist()
            composeRule.onNodeWithText("Copy").performClick()
        } else {
            // A text snapshot stays usable even when its source is no longer eligible.
            pickerClose.assertExists().performClick()
        }
        composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, restorations) }
    }

    private fun drawMediaSource(sourceView: View) {
        // Each menu opening creates a source layer, which idle synchronization alone does not draw.
        composeRule.runOnIdle {
            val bitmap = Bitmap.createBitmap(sourceView.width, sourceView.height, Bitmap.Config.ARGB_8888)
            try {
                sourceView.draw(Canvas(bitmap))
            } finally {
                bitmap.recycle()
            }
        }
        composeRule.waitForIdle()
    }

    private fun mediaReference() =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi("blossom-v1", "https://media.example/document.pdf")),
            ciphertextSha256 = "a".repeat(64),
            plaintextSha256 = "b".repeat(64),
            nonceHex = "c".repeat(24),
            fileName = "document.pdf",
            mediaType = "application/pdf",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 1uL,
            dim = null,
            thumbhash = null,
        )

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

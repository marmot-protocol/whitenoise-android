package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPreviewSendBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Preparation and durable send acceptance control the real composer pointer and IME affordances. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ComposerAttachmentPreparationCoverageTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Processing prevents pointer and keyboard send, preserves the caption, and restores exactly one send. */
    @Test
    fun preparationBlocksPointerAndImeUntilReady() {
        var preparing by mutableStateOf(true)
        var attachmentsSent = 0
        var textSent = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                ComposerBar(
                    replyingTo = null,
                    messageTextCopy = MessageTextCopy.Default,
                    onCancelReply = {},
                    onSend = { _, _ -> textSent++ },
                    initialDraft = TextFieldValue("Photo caption"),
                    hasPendingAttachments = true,
                    attachmentsPreparing = preparing,
                    onSendAttachments = { _, accepted ->
                        attachmentsSent++
                        accepted(true)
                    },
                )
            }
        }
        val send = composeRule.onNodeWithContentDescription(context.getString(R.string.send))
        send.assertIsNotEnabled().performTouchInput { click() }
        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.onNode(hasSetTextAction()).assertTextContains("Photo caption")
        composeRule.runOnIdle {
            assertEquals(0, attachmentsSent)
            assertEquals(0, textSent)
            preparing = false
        }
        send.assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, attachmentsSent) }
    }

    /** A delayed native acceptance cannot dispatch twice; a rejection restores the same caption and action. */
    @Test
    fun pendingAcceptanceDisablesSendAndRejectionRestoresDraft() {
        var sent = 0
        var complete: ((Boolean) -> Unit)? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                ComposerBar(
                    replyingTo = null,
                    messageTextCopy = MessageTextCopy.Default,
                    onCancelReply = {},
                    onSend = { _, _ -> error("attachment draft must not send plain text") },
                    initialDraft = TextFieldValue("Keep this caption"),
                    hasPendingAttachments = true,
                    onSendAttachments = { _, accepted ->
                        sent++
                        complete = accepted
                    },
                )
            }
        }
        val send = composeRule.onNodeWithContentDescription(context.getString(R.string.send))
        send.assertIsEnabled().performClick()
        send.assertIsNotEnabled().performTouchInput { click() }
        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.onNode(hasSetTextAction()).assertTextContains("Keep this caption")
        composeRule.runOnIdle {
            assertEquals(1, sent)
            requireNotNull(complete)(false)
        }
        send.assertIsEnabled()
        composeRule.onNode(hasSetTextAction()).assertTextContains("Keep this caption")
    }

    /** The legacy full-screen staging bar uses a truly disabled pointer target during preparation. */
    @Test
    fun legacyPreviewSendIsDisabledUntilProcessingFinishes() {
        var preparing by mutableStateOf(true)
        var sent = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                MediaPreviewSendBar(
                    initialCaption = "Photo caption",
                    sending = false,
                    sendEnabled = !preparing,
                    preparing = preparing,
                    onSend = { sent++ },
                )
            }
        }
        val send = composeRule.onNodeWithContentDescription(context.getString(R.string.send))
        send.assertIsNotEnabled().performTouchInput { click() }
        composeRule.onNode(hasSetTextAction()).assertTextContains("Photo caption")
        composeRule.runOnIdle {
            assertEquals(0, sent)
            preparing = false
        }
        send.assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, sent) }
    }

    /** Editing a text message does not inherit an unrelated staged attachment's preparation hold. */
    @Test
    fun textEditRemainsSendableDuringStagedPreparation() {
        var edits = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ComposerBar(
                    replyingTo = null,
                    messageTextCopy = MessageTextCopy.Default,
                    onCancelReply = {},
                    onSend = { _, _ -> edits++ },
                    editingMessageId = "edit-target",
                    editingInitialText = "Edited caption",
                    hasPendingAttachments = true,
                    attachmentsPreparing = true,
                    onSendAttachments = { _, _ -> error("editing must not send staged media") },
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, edits) }
    }
}

package dev.ipf.whitenoise.android.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ComposerEditMentionPickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val candidate =
        MentionComposer.Candidate(
            accountIdHex = "aa".repeat(32),
            npub = ALICE_NPUB,
            displayName = "Alice",
            nip05 = "alice@example.com",
        )

    @Test
    fun editPickerInsertsCanonicalMentionAndSaveReopenKeepsExactlyOneToken() {
        var editingMessageId by mutableStateOf<String?>("message-1")
        var editingInitialText by mutableStateOf("Hello")
        var savedText: String? = null
        render(
            editingMessageId = { editingMessageId },
            editingInitialText = { editingInitialText },
            onSend = { body ->
                savedText = body
                editingMessageId = null
            },
        )
        val editor = composeRule.onNode(hasSetTextAction())

        editor.performClick()
        editor.performTextInput(" @al")
        pickerTitle().assertExists()
        composeRule.onNodeWithText(candidate.displayName).performClick()

        val insertedCanonical = "Hello @$ALICE_NPUB "
        val insertedVisual = "Hello @${candidate.displayName} "
        assertEditorValue(editor, insertedVisual, TextRange(insertedCanonical.length))
        pickerTitle().assertDoesNotExist()

        val chipStart = "Hello ".length
        editor.performTextInputSelection(TextRange(chipStart + 2))
        assertEditorValue(editor, insertedVisual, TextRange(chipStart))
        editor.performTextInputSelection(TextRange(insertedCanonical.length))
        editor.performTextInput("again")

        val canonical = insertedCanonical + "again"
        val visual = insertedVisual + "again"
        assertEditorValue(editor, visual, TextRange(canonical.length))
        pickerTitle().assertDoesNotExist()

        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(canonical, savedText) }
        pickerTitle().assertDoesNotExist()

        composeRule.runOnIdle {
            editingInitialText = checkNotNull(savedText)
            editingMessageId = "message-1"
        }
        composeRule.waitForIdle()

        assertEditorValue(editor, visual, TextRange(canonical.length))
        assertEquals(1, checkNotNull(savedText).windowed(ALICE_NPUB.length).count { it == ALICE_NPUB })
        pickerTitle().assertDoesNotExist()
    }

    @Test
    fun cancelAndEditTargetSwitchClearThePreviousQuery() {
        var editingMessageId by mutableStateOf<String?>("message-1")
        var editingInitialText by mutableStateOf("First")
        render(
            editingMessageId = { editingMessageId },
            editingInitialText = { editingInitialText },
            onCancelEdit = { editingMessageId = null },
        )
        val editor = composeRule.onNode(hasSetTextAction())

        editor.performTextInput(" @")
        pickerTitle().assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.cancel_edit)).performClick()
        composeRule.waitForIdle()
        pickerTitle().assertDoesNotExist()

        composeRule.runOnIdle {
            editingInitialText = "Second"
            editingMessageId = "message-2"
        }
        composeRule.waitForIdle()
        pickerTitle().assertDoesNotExist()

        editor.performTextInput(" @al")
        pickerTitle().assertExists()
        composeRule.runOnIdle {
            editingInitialText = "Third"
            editingMessageId = "message-3"
        }
        composeRule.waitForIdle()

        assertEditorValue(editor, "Third", TextRange("Third".length))
        pickerTitle().assertDoesNotExist()
    }

    @Test
    fun editPickerKeepsRangedSelectionSuppression() {
        val editingMessageId by mutableStateOf<String?>("message-1")
        val editingInitialText by mutableStateOf("Hello @al")
        render(
            editingMessageId = { editingMessageId },
            editingInitialText = { editingInitialText },
        )
        val editor = composeRule.onNode(hasSetTextAction())

        pickerTitle().assertExists()
        editor.performTextInputSelection(TextRange(6, 9))
        pickerTitle().assertDoesNotExist()
    }

    @Test
    fun editPickerKeepsDmSuppression() {
        renderDmEdit()
        composeRule.onNode(hasSetTextAction()).performTextInput(" @")
        pickerTitle().assertDoesNotExist()
    }

    private fun render(
        editingMessageId: () -> String?,
        editingInitialText: () -> String,
        onSend: (String) -> Unit = {},
        onCancelEdit: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { body, _ -> onSend(body) },
                        initialDraft = TextFieldValue("Unsent draft"),
                        editingMessageId = editingMessageId(),
                        editingInitialText = editingInitialText(),
                        onCancelEdit = onCancelEdit,
                        mentionCandidates = listOf(candidate),
                        mentionPickerEnabled = true,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun renderDmEdit() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> },
                        initialDraft = TextFieldValue(""),
                        editingMessageId = "dm-message",
                        editingInitialText = "Hello",
                        mentionCandidates = listOf(candidate),
                        mentionPickerEnabled = false,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun pickerTitle() = composeRule.onNodeWithText(context.getString(R.string.mention_picker_title))

    private fun assertEditorValue(
        editor: androidx.compose.ui.test.SemanticsNodeInteraction,
        text: String,
        selection: TextRange,
    ) {
        val semantics = editor.fetchSemanticsNode().config
        assertEquals(text, semantics[SemanticsProperties.EditableText].text)
        assertEquals(selection, semantics[SemanticsProperties.TextSelectionRange])
    }

    private companion object {
        val ALICE_NPUB = "npub1" + "q".repeat(58)
    }
}

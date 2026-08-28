package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.TimelineReplyDisplay
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerExpansionMode
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerPill
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerSelectionLayout
import dev.ipf.whitenoise.android.ui.conversation.composer.composerCaretScrollTarget
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ComposerCaretVisibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun clipboardStyleBulkReplacementKeepsTheFinalCaretVisible() {
        val harness = render(TextFieldValue("Short"))
        val field = composeRule.onNode(hasSetTextAction())

        field.performClick()
        field.performTextReplacement(longDraft())
        composeRule.waitForIdle()

        val scroll = field.verticalScroll()
        assertTrue("a clipped bulk replacement must scroll to its end caret", scroll.value() > 0f)
        assertEquals(scroll.maxValue(), scroll.value(), 1f)
        assertEquals(TextRange(harness.value.text.length), harness.value.selection)
    }

    @Test
    fun midTextInsertionMovesToItsResultCaretInsteadOfForcingTheEnd() {
        val draft = longDraft()
        val harness = render(TextFieldValue(draft, TextRange(draft.length)))
        val field = composeRule.onNode(hasSetTextAction())

        composeRule.waitForIdle()
        assertTrue(field.verticalScroll().value() > 0f)

        val insertionOffset = draft.indexOf("Line 3")
        field.performTextInputSelection(TextRange(insertionOffset))
        field.performTextInput("Dictated middle insertion\nwith another inserted line\n")
        composeRule.waitForIdle()

        val scroll = field.verticalScroll()
        assertTrue("mid-text caret must move the viewport away from the final line", scroll.value() < scroll.maxValue())
        assertEquals(harness.value.selection, field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        field.assertActiveCaretVisible()
    }

    @Test
    fun bulkTextAddedAfterAnAlreadyVisibleCaretDoesNotMoveTheViewport() {
        val harness = render(TextFieldValue("Line 1\nLine 2", TextRange(2)))
        val field = composeRule.onNode(hasSetTextAction())
        composeRule.waitForIdle()
        val before = field.verticalScroll().value()

        composeRule.runOnIdle {
            harness.value =
                TextFieldValue(
                    text = harness.value.text + "\n" + longDraft(),
                    selection = TextRange(2),
                )
        }
        composeRule.waitForIdle()

        assertEquals("an already-visible caret must not jump", before, field.verticalScroll().value(), 0f)
        field.assertActiveCaretVisible()
    }

    @Test
    fun scrollPolicyUsesMinimumDeltaAndActiveEdgeForOversizedSelections() {
        val fitting = ComposerSelectionLayout(20f, 40f, 20f, 40f)
        assertEquals(10, composerCaretScrollTarget(10, 50, 200, fitting))
        assertEquals(20, composerCaretScrollTarget(30, 50, 200, fitting))
        assertEquals(30, composerCaretScrollTarget(0, 50, 200, ComposerSelectionLayout(60f, 80f, 60f, 80f)))

        val oversized = ComposerSelectionLayout(0f, 160f, 140f, 160f)
        assertEquals(110, composerCaretScrollTarget(0, 50, 200, oversized))
        val reversedOversized = ComposerSelectionLayout(0f, 160f, 0f, 20f)
        assertEquals(0, composerCaretScrollTarget(110, 50, 200, reversedOversized))
    }

    @Test
    fun replyAppearanceAndExpansionToggleReanchorWithoutLosingTheCaret() {
        val draft = longDraft()
        val selection = TextRange(draft.indexOf("Line 8"))
        var value by mutableStateOf(TextFieldValue(draft, selection))
        var replyingTo by mutableStateOf<AppMessageRecordFfi?>(null)
        var bottomInputChanges = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp).height(720.dp)) {
                    ComposerBar(
                        replyingTo = replyingTo,
                        replyingToDisplay =
                            replyingTo?.let {
                                TimelineReplyDisplay(
                                    sender = "alice",
                                    body = "Parent message",
                                )
                            },
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> },
                        initialDraft = value,
                        onDraftChange = { value = it },
                        onBottomInputChanged = { bottomInputChanges++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val field = composeRule.onNode(hasSetTextAction())
        val initialChangeCount = bottomInputChanges
        field.assertActiveCaretVisible()

        composeRule.runOnIdle { replyingTo = replyRecord() }
        composeRule.waitForIdle()

        assertEquals(initialChangeCount + 1, bottomInputChanges)
        assertEquals(selection, field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        field.assertActiveCaretVisible()

        composeRule
            .onNodeWithContentDescription(app.getString(R.string.composer_resize))
            .performClick()
        composeRule.waitForIdle()

        assertEquals(initialChangeCount + 2, bottomInputChanges)
        assertEquals(selection, field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        field.assertActiveCaretVisible()
    }

    private fun render(initial: TextFieldValue): Harness {
        val harness = Harness(initial)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    Box(Modifier.width(300.dp).height(140.dp)) {
                        ComposerPill(
                            textFieldValue = harness.value,
                            composerFocus = harness.focusRequester,
                            emojiPickerOpen = false,
                            onValueChange = { harness.value = it },
                            onEmojiPickerToggle = {},
                            onAttachmentsToggle = {},
                            attachmentSheetOpen = false,
                            onPickFromGallery = null,
                            onPickDocument = null,
                            expansionMode = ComposerExpansionMode.Manual,
                            modifier = Modifier.height(140.dp),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return harness
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.verticalScroll() = fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertActiveCaretVisible() {
        val node = fetchSemanticsNode()
        val selection = node.config[SemanticsProperties.TextSelectionRange]
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        val caret = layouts.single().getCursorRect(selection.end)
        val scrollTop = verticalScroll().value()
        val scrollBottom = scrollTop + node.boundsInRoot.height
        assertTrue("active caret top must stay inside the editor viewport", caret.top >= scrollTop - 1f)
        assertTrue("active caret bottom must stay inside the editor viewport", caret.bottom <= scrollBottom + 1f)
    }

    private fun replyRecord() =
        AppMessageRecordFfi(
            messageIdHex = "parent",
            direction = "received",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "Parent message",
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
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    private class Harness(
        initial: TextFieldValue,
    ) {
        var value by mutableStateOf(initial)
        val focusRequester = FocusRequester()
    }

    private fun longDraft(): String = (1..18).joinToString("\n", transform = ::longDraftLine)

    private fun longDraftLine(line: Int): String = "Line $line keeps the composer tall enough to scroll"
}

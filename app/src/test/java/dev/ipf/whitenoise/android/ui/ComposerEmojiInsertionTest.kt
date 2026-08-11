package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.ui.conversation.composer.deleteComposerSelectionOrPreviousCodePoint
import dev.ipf.whitenoise.android.ui.conversation.composer.insertEmojiAtSelection
import dev.ipf.whitenoise.android.ui.conversation.composer.repairComposerMentionEdit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerEmojiInsertionTest {
    @Test
    fun insertsEmojiAtCollapsedCaret() {
        val result =
            insertEmojiAtSelection(
                TextFieldValue(text = "hello world", selection = TextRange(6)),
                "🙂",
            )

        assertEquals("hello 🙂world", result.text)
        assertEquals("hello 🙂".length, result.selection.start)
        assertEquals(result.selection.start, result.selection.end)
    }

    @Test
    fun insertionMovesAStaleCaretPastASurrogatePair() {
        val result =
            insertEmojiAtSelection(
                TextFieldValue(text = "🙂", selection = TextRange(1)),
                "👍",
            )

        assertEquals("🙂👍", result.text)
        assertEquals(result.text.length, result.selection.start)
    }

    @Test
    fun insertionMovesAStaleCaretPastAnExtendedGraphemeCluster() {
        val family = "👨‍👩‍👧"

        val result =
            insertEmojiAtSelection(
                TextFieldValue(text = "A${family}B", selection = TextRange(4)),
                "👍",
            )

        assertEquals("A$family👍B", result.text)
        assertEquals(TextRange("A$family👍".length), result.selection)
    }

    @Test
    fun insertionExpandsASelectionThatSplitsASurrogatePair() {
        val result =
            insertEmojiAtSelection(
                TextFieldValue(text = "🙂", selection = TextRange(0, 1)),
                "👍",
            )

        assertEquals("👍", result.text)
        assertEquals(result.text.length, result.selection.start)
    }

    @Test
    fun replacesSelectedTextWithEmoji() {
        val result =
            insertEmojiAtSelection(
                TextFieldValue(text = "hello world", selection = TextRange(6, 11)),
                "🌍",
            )

        assertEquals("hello 🌍", result.text)
        assertEquals("hello 🌍".length, result.selection.start)
        assertEquals(result.selection.start, result.selection.end)
    }

    @Test
    fun clampsStaleSelectionToTheTextBounds() {
        val result =
            insertEmojiAtSelection(
                TextFieldValue(text = "hello", selection = TextRange(99)),
                "👋",
            )

        assertEquals("hello👋", result.text)
        assertEquals("hello👋".length, result.selection.start)
        assertEquals(result.selection.start, result.selection.end)
    }

    @Test
    fun backspaceClampsAStaleCaretToTheTextEnd() {
        val result =
            deleteComposerSelectionOrPreviousCodePoint(
                TextFieldValue(text = "hello", selection = TextRange(99)),
            )!!

        assertEquals("hell", result.text)
        assertEquals(4, result.selection.start)
        assertEquals(result.selection.start, result.selection.end)
    }

    @Test
    fun backspaceDeletesOneWholeUnicodeCodePoint() {
        val value = TextFieldValue(text = "hello🙂", selection = TextRange("hello🙂".length))

        val result = deleteComposerSelectionOrPreviousCodePoint(value)!!

        assertEquals("hello", result.text)
        assertEquals(5, result.selection.start)
    }

    @Test
    fun backspaceMovesAStaleCaretPastASurrogatePair() {
        val result =
            deleteComposerSelectionOrPreviousCodePoint(
                TextFieldValue(text = "🙂", selection = TextRange(1)),
            )!!

        assertEquals("", result.text)
        assertEquals(0, result.selection.start)
    }

    @Test
    fun backspaceExpandsASelectionThatSplitsASurrogatePair() {
        val result =
            deleteComposerSelectionOrPreviousCodePoint(
                TextFieldValue(text = "🙂", selection = TextRange(0, 1)),
            )!!

        assertEquals("", result.text)
        assertEquals(0, result.selection.start)
    }

    @Test
    fun backspaceAtTheStartIsANoOp() {
        val result =
            deleteComposerSelectionOrPreviousCodePoint(
                TextFieldValue(text = "hello", selection = TextRange.Zero),
            )

        assertEquals(null, result)
    }

    @Test
    fun emojiBackspaceRemovesAWholeMentionChip() {
        val npub = "npub1" + "a".repeat(58)
        val oldValue = TextFieldValue(text = "hello @$npub ", selection = TextRange("hello @$npub ".length))
        val proposedValue = deleteComposerSelectionOrPreviousCodePoint(oldValue)!!

        val result = repairComposerMentionEdit(oldValue, proposedValue, clampMentionSelection = true)

        assertEquals("hello ", result.text)
        assertEquals("hello ".length, result.selection.start)
    }

    @Test
    fun selectedProseDeletionPreservesAdjacentMentionChip() {
        val npub = "npub1" + "a".repeat(58)
        val chipText = "hello @$npub"
        val oldText = "$chipText following words"
        val oldValue =
            TextFieldValue(
                text = oldText,
                selection = TextRange(chipText.length, oldText.length),
            )
        val proposedValue = TextFieldValue(text = chipText, selection = TextRange(chipText.length))

        val result = repairComposerMentionEdit(oldValue, proposedValue, clampMentionSelection = true)

        assertEquals(chipText, result.text)
        assertEquals(chipText.length, result.selection.start)
    }

    @Test
    fun collapsedCaretImeWordDeleteConsumesOwnedSeparatorAndMention() {
        val npub = "npub1" + "a".repeat(58)
        val chipText = "hello @$npub"
        val oldText = "$chipText following words"
        val oldValue = TextFieldValue(text = oldText, selection = TextRange(chipText.length))
        val proposedValue = TextFieldValue(text = chipText, selection = TextRange(chipText.length))

        val result = repairComposerMentionEdit(oldValue, proposedValue, clampMentionSelection = true)

        assertEquals("hello ", result.text)
        assertEquals("hello ".length, result.selection.start)
    }
}

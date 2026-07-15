package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.ui.conversation.composer.deleteComposerSelectionOrPreviousCodePoint
import dev.ipf.whitenoise.android.ui.conversation.composer.insertComposerEmoji
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerEmojiInsertionTest {
    @Test
    fun insertsEmojiAtCollapsedCaret() {
        val result =
            insertComposerEmoji(
                TextFieldValue(text = "hello world", selection = TextRange(6)),
                "🙂",
            )

        assertEquals("hello 🙂world", result.text)
        assertEquals("hello 🙂".length, result.selection.start)
        assertEquals(result.selection.start, result.selection.end)
    }

    @Test
    fun replacesSelectedTextWithEmoji() {
        val result =
            insertComposerEmoji(
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
            insertComposerEmoji(
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
    fun backspaceAtTheStartIsANoOp() {
        val result =
            deleteComposerSelectionOrPreviousCodePoint(
                TextFieldValue(text = "hello", selection = TextRange.Zero),
            )

        assertEquals(null, result)
    }
}

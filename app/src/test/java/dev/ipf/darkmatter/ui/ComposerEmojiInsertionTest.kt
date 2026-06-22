package dev.ipf.darkmatter.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
}

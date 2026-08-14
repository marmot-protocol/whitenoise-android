package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsReadAloudHighlightTest {
    @Test
    fun halfOpenIntRangeConvertsToExclusiveTextRangeEnd() {
        val textRange = ttsHighlightTextRange(6 until 12, textLength = 20)

        assertEquals(TextRange(6, 12), textRange)
    }

    @Test
    fun singleCharacterHighlightUsesNonCollapsedTextRange() {
        val textRange = ttsHighlightTextRange(3 until 4, textLength = 10)

        assertEquals(TextRange(3, 4), textRange)
        assertTrue(!textRange.collapsed)
    }

    @Test
    fun supplementaryUnicodeHighlightRespectsUtf16CodeUnitBoundaries() {
        val emoji = "a\uD83D\uDE00b"
        val textRange = ttsHighlightTextRange(1 until 3, textLength = emoji.length)

        assertEquals(TextRange(1, 3), textRange)
    }

    @Test
    fun highlightRangeClampsToTextLength() {
        val textRange = ttsHighlightTextRange(8 until 20, textLength = 12)

        assertEquals(TextRange(8, 12), textRange)
    }
}

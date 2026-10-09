package dev.ipf.whitenoise.android.ui.conversation.composer

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Queries follow accepted edits without waiting for Compose's input-session snapshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerInputConnectionTest {
    private val target = mockk<InputConnection>(relaxed = true)
    private var accepted = TextFieldValue("First mispelled", TextRange(15))
    private val input = ComposerInputConnection(target) { accepted }

    @Test
    fun queriesObserveAcceptedHardwareDeletionWithoutAFrame() {
        every { target.getTextBeforeCursor(any(), any()) } returns "First mispelled"
        accepted = TextFieldValue("First mispelle", TextRange(14))
        assertEquals("First mispelle", input.getTextBeforeCursor(40, InputConnection.GET_TEXT_WITH_STYLES))
        assertEquals("", input.getTextAfterCursor(40, 0))
        assertNull(input.getSelectedText(0))
        assertEquals(6, input.getTextBeforeCursor(40, 0).toString().lastIndexOf(' ') + 1)
    }

    @Test
    fun reversedSelectionAndBoundedSurroundingTextKeepOriginalOffsets() {
        accepted = TextFieldValue("alpha beta gamma", TextRange(10, 6))
        assertEquals("ha ", input.getTextBeforeCursor(3, 0))
        assertEquals(" g", input.getTextAfterCursor(2, 0))
        assertEquals("beta", input.getSelectedText(0))
        val surrounding = checkNotNull(input.getSurroundingText(3, 2, 0))
        assertEquals("ha beta g", surrounding.text)
        assertEquals(3, surrounding.selectionStart)
        assertEquals(7, surrounding.selectionEnd)
        assertEquals(3, surrounding.offset)
    }

    @Test
    fun supplementaryCharactersKeepUtf16QueryAndSelectionOffsets() {
        accepted = TextFieldValue("a😀b", TextRange(3))
        assertEquals("😀", input.getTextBeforeCursor(2, 0))
        assertEquals("b", input.getTextAfterCursor(1, 0))
        val surrounding = checkNotNull(input.getSurroundingText(2, 1, 0))
        assertEquals("😀b", surrounding.text)
        assertEquals(2, surrounding.selectionStart)
        assertEquals(1, surrounding.offset)
    }

    @Test
    fun extractedTextReadsAcceptedOwnerAndPreservesMonitorRegistration() {
        val request = ExtractedTextRequest()
        every { target.getExtractedText(request, InputConnection.GET_EXTRACTED_TEXT_MONITOR) } returns ExtractedText()
        accepted = TextFieldValue("first\nsecond", TextRange(12, 6))
        val result = checkNotNull(input.getExtractedText(request, InputConnection.GET_EXTRACTED_TEXT_MONITOR))
        assertEquals(accepted.text, result.text)
        assertEquals(6, result.selectionStart)
        assertEquals(12, result.selectionEnd)
        assertEquals(0, result.startOffset)
        assertEquals(-1, result.partialStartOffset)
        assertEquals(0, result.flags)
        verify(exactly = 1) { target.getExtractedText(request, InputConnection.GET_EXTRACTED_TEXT_MONITOR) }
    }

    @Test
    fun editorStillOwnsCommandsAndBatchBoundaries() {
        input.beginBatchEdit()
        input.deleteSurroundingText(1, 0)
        input.setComposingRegion(6, 14)
        input.setComposingText("mispell", 1)
        input.endBatchEdit()
        verify(exactly = 1) { target.beginBatchEdit() }
        verify(exactly = 1) { target.deleteSurroundingText(1, 0) }
        verify(exactly = 1) { target.setComposingRegion(6, 14) }
        verify(exactly = 1) { target.setComposingText("mispell", 1) }
        verify(exactly = 1) { target.endBatchEdit() }
        assertEquals("First mispelled", accepted.text)
    }

    @Test
    fun closedConnectionCannotReadAReplacementDraftOwner() {
        input.closeConnection()
        accepted = TextFieldValue("replacement draft", TextRange(17))
        assertNull(input.getTextBeforeCursor(100, 0))
        assertNull(input.getTextAfterCursor(100, 0))
        assertNull(input.getSelectedText(0))
        assertNull(input.getSurroundingText(100, 100, 0))
        assertNull(input.getExtractedText(ExtractedTextRequest(), 0))
        verify(exactly = 1) { target.closeConnection() }
    }
}

package dev.ipf.whitenoise.android.ui.conversation.composer

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Queries follow accepted edits without waiting for Compose's input-session snapshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerInputConnectionTest {
    private val target = RecordingConnection()
    private var accepted = TextFieldValue("First mispelled", TextRange(15))
    private val input = ComposerInputConnection(target) { accepted }

    @Test
    fun queriesObserveAcceptedHardwareDeletionWithoutAFrame() {
        accepted = TextFieldValue("First mispelle", TextRange(14))
        assertEquals("First mispelle", input.getTextBeforeCursor(40, InputConnection.GET_TEXT_WITH_STYLES))
        assertEquals("", input.getTextAfterCursor(40, 0))
        assertNull(input.getSelectedText(0))
        assertEquals(6, input.getTextBeforeCursor(40, 0).toString().lastIndexOf(' ') + 1)
    }

    @Test
    @Config(sdk = [30])
    fun minimumSupportedAndroidQueriesUseAcceptedValue() {
        accepted = TextFieldValue("First mispelle", TextRange(14))
        assertEquals("First mispelle", input.getTextBeforeCursor(40, 0))
        assertEquals("", input.getTextAfterCursor(40, 0))
        assertNull(input.getSelectedText(0))
        assertEquals(accepted.text, checkNotNull(input.getExtractedText(ExtractedTextRequest(), 0)).text)
        input.closeConnection()
        assertNull(input.getTextBeforeCursor(40, 0))
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
        accepted = TextFieldValue("first\nsecond", TextRange(12, 6))
        val result = checkNotNull(input.getExtractedText(request, InputConnection.GET_EXTRACTED_TEXT_MONITOR))
        assertEquals(accepted.text, result.text)
        assertEquals(6, result.selectionStart)
        assertEquals(12, result.selectionEnd)
        assertEquals(0, result.startOffset)
        assertEquals(-1, result.partialStartOffset)
        assertEquals(0, result.flags)
        assertEquals(request, target.extractRequest)
        assertEquals(InputConnection.GET_EXTRACTED_TEXT_MONITOR, target.extractFlags)
    }

    @Test
    fun editorStillOwnsCommandsAndBatchBoundaries() {
        input.beginBatchEdit()
        input.deleteSurroundingText(1, 0)
        input.setComposingRegion(6, 14)
        input.setComposingText("mispell", 1)
        input.endBatchEdit()
        assertEquals(
            listOf("begin", "delete:1,0", "region:6,14", "compose:mispell,1", "end"),
            target.calls,
        )
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
        assertEquals(listOf("close"), target.calls)
    }

    private class RecordingConnection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), false) {
        val calls = mutableListOf<String>()
        var extractRequest: ExtractedTextRequest? = null
        var extractFlags = 0

        override fun getTextBeforeCursor(
            length: Int,
            flags: Int,
        ): CharSequence = "First mispelled"

        override fun getExtractedText(
            request: ExtractedTextRequest?,
            flags: Int,
        ): ExtractedText {
            extractRequest = request
            extractFlags = flags
            return ExtractedText()
        }

        override fun beginBatchEdit(): Boolean {
            calls += "begin"
            return true
        }

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            calls += "delete:$beforeLength,$afterLength"
            return true
        }

        override fun setComposingRegion(
            start: Int,
            end: Int,
        ): Boolean {
            calls += "region:$start,$end"
            return true
        }

        override fun setComposingText(
            text: CharSequence?,
            newCursorPosition: Int,
        ): Boolean {
            calls += "compose:$text,$newCursorPosition"
            return true
        }

        override fun endBatchEdit(): Boolean {
            calls += "end"
            return false
        }

        override fun closeConnection() {
            calls += "close"
        }
    }
}

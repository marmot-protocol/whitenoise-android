package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Plain clipboard insertion preserves controlled draft text and places the caret after inserted content. */
class ComposerClipboardTextTest {
    /** Empty and whitespace-only clips leave the draft unchanged. */
    @Test fun blankPasteDoesNotMutateDraft() {
        assertNull(insertComposerClipboardText(TextFieldValue("draft"), ""))
        assertNull(insertComposerClipboardText(TextFieldValue("draft"), "  \n"))
    }

    /** Replaces only the selected range, including reversed and stale selections. */
    @Test fun selectedRangeIsReplacedAndCaretIsClamped() {
        val selected = TextFieldValue("hello world", selection = TextRange(11, 6))
        val replacement = insertComposerClipboardText(selected, "Android")!!
        assertEquals("hello Android", replacement.text)
        assertEquals(TextRange(13), replacement.selection)
        val stale = TextFieldValue("abc", selection = TextRange(30))
        val clamped = insertComposerClipboardText(stale, "Z")!!
        assertEquals("abcZ", clamped.text)
        assertEquals(TextRange(4), clamped.selection)
    }

    /** Large multiline payloads stay complete for the composer's automatic height calculation. */
    @Test fun multilinePasteKeepsExactPlainText() {
        val payload = (1..100).joinToString("\n") { "line $it" }
        val insertion = insertComposerClipboardText(TextFieldValue(""), payload)!!
        assertEquals(payload, insertion.text)
        assertEquals(TextRange(payload.length), insertion.selection)
    }
}

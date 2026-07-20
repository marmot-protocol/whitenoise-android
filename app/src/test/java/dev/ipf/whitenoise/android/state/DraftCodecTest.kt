package dev.ipf.whitenoise.android.state

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftCodecTest {
    @Test
    fun versionedDraftRoundTripsTextAndSelection() {
        val value = TextFieldValue("hello world", TextRange(6, 11))
        val encoded = encodeComposerDraft(value)
        val decoded = decodeComposerDraftStored(encoded)
        assertEquals(value, decoded.textFieldValue)
        assertTrue(decoded.focusOnRestore)
    }

    @Test
    fun versionedDraftPreservesReversedSelectionDirection() {
        val value = TextFieldValue("hello world", TextRange(11, 6))
        val decoded = decodeComposerDraftStored(encodeComposerDraft(value))
        assertEquals(value, decoded.textFieldValue)
    }

    @Test
    fun arbitraryTextRoundTripsThroughVersionedEncoding() {
        val text = "line1\nline2\u001Eseparators and 日本語"
        val value = TextFieldValue(text, TextRange(3, 7))
        val encoded = encodeComposerDraft(value)
        val decoded = decodeComposerDraftStored(encoded)
        assertEquals(value, decoded.textFieldValue)
        assertTrue(decoded.focusOnRestore)
    }

    @Test
    fun legacyRawStringDraftUsesEndSelectionWithoutFocus() {
        val decoded = decodeComposerDraftStored("legacy draft")
        assertEquals(TextFieldValue("legacy draft", TextRange("legacy draft".length)), decoded.textFieldValue)
        assertFalse(decoded.focusOnRestore)
        assertFalse(shouldFocusComposerOnDraftRestore(decoded))
    }

    @Test
    fun malformedVersionedDraftFallsBackSafelyWithoutFocus() {
        val malformed = "${COMPOSER_DRAFT_VERSION_PREFIX}not\u001Evalid"
        val decoded = decodeComposerDraftStored(malformed)
        assertEquals(malformed, decoded.textFieldValue.text)
        assertEquals(TextRange(malformed.length), decoded.textFieldValue.selection)
        assertFalse(decoded.focusOnRestore)
        assertTrue(decoded.textFieldValue.selection.start in 0..decoded.textFieldValue.text.length)
        assertTrue(decoded.textFieldValue.selection.end in 0..decoded.textFieldValue.text.length)
    }

    @Test
    fun malformedVersionedLengthMismatchFallsBackSafely() {
        val malformed =
            buildString {
                append(COMPOSER_DRAFT_VERSION_PREFIX)
                append("1\u001E3\u001E99\u001Ehi")
            }
        val decoded = decodeComposerDraftStored(malformed)
        assertFalse(decoded.focusOnRestore)
        assertEquals(malformed, decoded.textFieldValue.text)
    }

    @Test
    fun outOfRangeVersionedSelectionIsClamped() {
        val encoded =
            buildString {
                append(COMPOSER_DRAFT_VERSION_PREFIX)
                append("-5\u001E999\u001E2\u001Ehi")
            }
        val decoded = decodeComposerDraftStored(encoded)
        assertEquals(TextFieldValue("hi", TextRange(0, 2)), decoded.textFieldValue)
        assertTrue(decoded.focusOnRestore)
    }

    @Test
    fun shouldFocusComposerOnDraftRestoreOnlyForVersionedNonBlankDrafts() {
        val versioned = decodeComposerDraftStored(encodeComposerDraft(TextFieldValue("typed", TextRange(2))))
        val legacy = decodeComposerDraftStored("typed")
        assertTrue(shouldFocusComposerOnDraftRestore(versioned))
        assertFalse(shouldFocusComposerOnDraftRestore(legacy))
        assertFalse(shouldFocusComposerOnDraftRestore(null))
    }

    @Test
    fun selectionOnlyEncodingDiffersForSameText() {
        val first = encodeComposerDraft(TextFieldValue("hello", TextRange(1)))
        val second = encodeComposerDraft(TextFieldValue("hello", TextRange(4)))
        assertNotEquals(first, second)
    }
}

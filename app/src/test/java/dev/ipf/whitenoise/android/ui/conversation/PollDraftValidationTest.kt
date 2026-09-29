package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PollDraftValidationTest {
    /** Pasted formatting is normalized, and an unused final option does not block posting. */
    @Test
    fun normalizesQuestionAndOptions() {
        val result = validatePollDraft("  Where\nto eat?  ", listOf(" Soup ", "Salad\u202e", " "))
        assertNull(result.issue)
        assertEquals("Where to eat?", result.question)
        assertEquals(listOf("Soup", "Salad"), result.options)
    }

    /** The composer rejects invalid byte lengths and labels before a native call. */
    @Test
    fun rejectsInvalidDraftWithSpecificIssue() {
        assertEquals(PollDraftIssue.MISSING_QUESTION, validatePollDraft(" ", listOf("A", "B")).issue)
        assertEquals(PollDraftIssue.QUESTION_TOO_LONG, validatePollDraft("é".repeat(513), listOf("A", "B")).issue)
        assertEquals(PollDraftIssue.TOO_FEW_OPTIONS, validatePollDraft("Q", listOf("A", " ")).issue)
        assertEquals(PollDraftIssue.OPTION_TOO_LONG, validatePollDraft("Q", listOf("A", "é".repeat(129))).issue)
        assertEquals(PollDraftIssue.DUPLICATE_OPTION, validatePollDraft("Q", listOf("Café", "cafe")).issue)
    }
}

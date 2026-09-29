package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.PollOptionResultFfi
import dev.ipf.marmotkit.PollProjectionFfi
import dev.ipf.marmotkit.PollTypeFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PollSelectionTest {
    /** Builds the native selection state used by replacement-vote cases. */
    private fun poll(
        type: PollTypeFfi,
        selection: List<String>,
    ) = PollProjectionFfi(
        question = "Lunch?",
        options = listOf(PollOptionResultFfi("a", "Soup", 1uL), PollOptionResultFfi("b", "Salad", 0uL)),
        pollType = type,
        participants = 1uL,
        localSelection = selection,
        creator = "creator",
        endsAt = null,
        open = true,
    )

    /** Single-choice taps replace the selected id and ignore a redundant tap. */
    @Test
    fun singleChoiceReplacesById() {
        assertEquals(listOf("b"), replacementPollSelection(poll(PollTypeFfi.SINGLE_CHOICE, listOf("a")), "b"))
        assertNull(replacementPollSelection(poll(PollTypeFfi.SINGLE_CHOICE, listOf("a")), "a"))
    }

    /** Multiple-choice replacement cannot submit the forbidden empty selection. */
    @Test
    fun multipleChoiceCannotClearLastVote() {
        assertEquals(listOf("a", "b"), replacementPollSelection(poll(PollTypeFfi.MULTIPLE_CHOICE, listOf("a")), "b"))
        assertNull(replacementPollSelection(poll(PollTypeFfi.MULTIPLE_CHOICE, listOf("a")), "a"))
        assertEquals(listOf("b"), replacementPollSelection(poll(PollTypeFfi.MULTIPLE_CHOICE, listOf("a", "b")), "a"))
    }

    /** A stale or fabricated option id never reaches the native sender. */
    @Test
    fun rejectsUnprojectedOptionId() {
        assertNull(replacementPollSelection(poll(PollTypeFfi.SINGLE_CHOICE, emptyList()), "unknown"))
    }
}

package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the group-recovery card's list row (#2621).
 *
 * The transcript emits that card as a lazy row above the timeline whenever the
 * conversation carries visible recovery state, but the structural-row count
 * used to omit it. Every timeline-index to list-index mapping was therefore one
 * row short while the card was on screen, which anchored scroll restores,
 * read receipts and the tail follower to the wrong message.
 */
class ConversationGroupRecoveryRowIndexTest {
    /** Counts the recovery row alongside the older header and the inline top error. */
    @Test
    fun structuralRowCountIncludesTheGroupRecoveryRow() {
        assertEquals(
            0,
            conversationTimelineLeadingStructuralRowCount(
                hasOlderHeader = false,
                hasInlineTopError = false,
                hasGroupRecovery = false,
            ),
        )
        assertEquals(
            1,
            conversationTimelineLeadingStructuralRowCount(
                hasOlderHeader = false,
                hasInlineTopError = false,
                hasGroupRecovery = true,
            ),
        )
        assertEquals(
            3,
            conversationTimelineLeadingStructuralRowCount(
                hasOlderHeader = true,
                hasInlineTopError = true,
                hasGroupRecovery = true,
            ),
        )
    }

    /** Keeps the historical count unchanged for callers that render no recovery row. */
    @Test
    fun structuralRowCountDefaultsToTheRecoveryFreeLayout() {
        assertEquals(
            conversationTimelineLeadingStructuralRowCount(
                hasOlderHeader = true,
                hasInlineTopError = true,
            ),
            conversationTimelineLeadingStructuralRowCount(
                hasOlderHeader = true,
                hasInlineTopError = true,
                hasGroupRecovery = false,
            ),
        )
    }

    /** Shifts the resolved final message row by the recovery card's own row. */
    @Test
    fun tailIndexAccountsForAVisibleRecoveryRow() {
        val withoutRecovery =
            conversationTimelineTailListIndex(
                timelineSize = 4,
                leadingStructuralRowCount =
                    conversationTimelineLeadingStructuralRowCount(
                        hasOlderHeader = false,
                        hasInlineTopError = false,
                        hasGroupRecovery = false,
                    ),
            )
        val withRecovery =
            conversationTimelineTailListIndex(
                timelineSize = 4,
                leadingStructuralRowCount =
                    conversationTimelineLeadingStructuralRowCount(
                        hasOlderHeader = false,
                        hasInlineTopError = false,
                        hasGroupRecovery = true,
                    ),
            )
        assertEquals(4, withoutRecovery)
        assertEquals(5, withRecovery)
    }

    /** Leaves an empty timeline without a tail row whatever structural rows are shown. */
    @Test
    fun emptyTimelineHasNoTailRowEvenWithARecoveryRow() {
        assertEquals(
            null,
            conversationTimelineTailListIndex(
                timelineSize = 0,
                leadingStructuralRowCount =
                    conversationTimelineLeadingStructuralRowCount(
                        hasOlderHeader = true,
                        hasInlineTopError = false,
                        hasGroupRecovery = true,
                    ),
            ),
        )
    }
}

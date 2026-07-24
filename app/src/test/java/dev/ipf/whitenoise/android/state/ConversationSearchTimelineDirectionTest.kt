package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.ConversationSearchMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationSearchTimelineDirectionTest {
    @Test
    fun olderMatchPagesBackward() {
        assertEquals(
            ConversationSearchPageDirection.OLDER,
            conversationSearchPageDirection(
                match = ConversationSearchMatch("old", 10uL),
                oldestTimelineAt = 20uL,
                oldestMessageId = "oldest-loaded",
                newestTimelineAt = 40uL,
                newestMessageId = "newest-loaded",
                hasMoreBefore = true,
                hasMoreAfter = true,
            ),
        )
    }

    @Test
    fun newerMatchPagesForwardAfterBoundedWindowShiftedBack() {
        assertEquals(
            ConversationSearchPageDirection.NEWER,
            conversationSearchPageDirection(
                match = ConversationSearchMatch("new", 50uL),
                oldestTimelineAt = 20uL,
                oldestMessageId = "oldest-loaded",
                newestTimelineAt = 40uL,
                newestMessageId = "newest-loaded",
                hasMoreBefore = true,
                hasMoreAfter = true,
            ),
        )
    }

    @Test
    fun exhaustedOrInWindowMatchDoesNotRequestAnotherPage() {
        assertNull(
            conversationSearchPageDirection(
                match = ConversationSearchMatch("old", 10uL),
                oldestTimelineAt = 20uL,
                oldestMessageId = "oldest-loaded",
                newestTimelineAt = 40uL,
                newestMessageId = "newest-loaded",
                hasMoreBefore = false,
                hasMoreAfter = true,
            ),
        )
        assertNull(
            conversationSearchPageDirection(
                match = ConversationSearchMatch("inside", 30uL),
                oldestTimelineAt = 20uL,
                oldestMessageId = "oldest-loaded",
                newestTimelineAt = 40uL,
                newestMessageId = "newest-loaded",
                hasMoreBefore = true,
                hasMoreAfter = true,
            ),
        )
    }
}

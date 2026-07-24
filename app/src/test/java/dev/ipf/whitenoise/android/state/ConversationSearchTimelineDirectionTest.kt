package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.ConversationMessageSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationSearchTimelineDirectionTest {
    @Test
    fun olderMatchPagesBackward() {
        assertEquals(
            ConversationSearchPageDirection.OLDER,
            conversationSearchPageDirection(
                match = ConversationMessageSearch.Match("old", 10uL),
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
    fun newerMatchPagesForwardAfterTheBoundedWindowShiftedBack() {
        assertEquals(
            ConversationSearchPageDirection.NEWER,
            conversationSearchPageDirection(
                match = ConversationMessageSearch.Match("new", 50uL),
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
                match = ConversationMessageSearch.Match("old", 10uL),
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
                match = ConversationMessageSearch.Match("inside", 30uL),
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

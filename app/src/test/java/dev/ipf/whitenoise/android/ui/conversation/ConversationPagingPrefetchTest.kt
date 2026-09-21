package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll-driven decisions behind an older page: when to fetch, and which row to report as the
 * window anchor. Both are pure so they can be pinned without a composition.
 */
class ConversationPagingPrefetchTest {
    /** The page behind the oldest row is fetched once the reader is within the prefetch margin. */
    @Test
    fun prefetchArmsWithinTheMargin() {
        assertTrue(prefetch(oldestVisibleIndex = OLDEST_ROW - OLDER_PAGE_PREFETCH_ROWS))
        assertTrue(prefetch(oldestVisibleIndex = OLDEST_ROW))
    }

    /** A reader still far from the oldest row does not fetch. */
    @Test
    fun prefetchStaysOffOutsideTheMargin() {
        assertFalse(prefetch(oldestVisibleIndex = OLDEST_ROW - OLDER_PAGE_PREFETCH_ROWS - 1))
    }

    /** Gives a fast fling half of a 50-row page for the next bounded window to arrive. */
    @Test
    fun prefetchMarginKeepsHalfAPageOfRunway() {
        assertEquals(25, OLDER_PAGE_PREFETCH_ROWS)
    }

    /** Nothing is fetched before the timeline has an anchor, while a page is in flight, or at the end. */
    @Test
    fun prefetchRespectsTheLoadingAndExhaustedGuards() {
        assertFalse(prefetch(anchored = false))
        assertFalse(prefetch(hasMoreBefore = false))
        assertFalse(prefetch(isLoadingOlder = true))
        assertFalse(prefetch(oldestVisibleIndex = -1))
    }

    /**
     * A page the engine never answered holds the prefetch off, so the reader's retry drives it
     * rather than the scroll frame — the silent stall this screen used to show.
     */
    @Test
    fun prefetchStaysOffWhileBlocked() {
        assertFalse(prefetch(olderPageBlocked = true))
    }

    /** An ordinary message row yields its id, so MDK can place the page against the reader's row. */
    @Test
    fun messageRowKeyYieldsItsAnchorId() {
        assertEquals(MESSAGE_ID, conversationAnchorMessageId("msg:$MESSAGE_ID"))
    }

    /**
     * Only ids MDK issued may be reported. An optimistic row's local UUID, a stream row, a header
     * and a malformed key all resolve to no anchor, so the page runs from the current revision.
     */
    @Test
    fun onlyAuthoritativeMessageIdsResolveToAnAnchor() {
        assertNull(conversationAnchorMessageId("msg:0d2b6c1e-4f6a-4b1e-9c1d-3a7f0b2e5c88"))
        assertNull(conversationAnchorMessageId("stream:reply"))
        assertNull(conversationAnchorMessageId("older-header"))
        assertNull(conversationAnchorMessageId("msg:" + "zz".repeat(32)))
        assertNull(conversationAnchorMessageId(null))
        assertNull(conversationAnchorMessageId(7))
    }

    /**
     * The reversed list emits the older-loading row, the top error row and the top spacer after the
     * messages, so they hold the highest indexes and sit at the oldest end — precisely what is on
     * screen when a page is due. Only a real message row may be chosen as the anchor, so the
     * selection must skip past them rather than take the last visible item.
     */
    @Test
    fun structuralRowsAtTheOldestEndNeverBecomeTheAnchor() {
        val visible =
            listOf(
                "msg:$MESSAGE_ID",
                "stream:reply",
                "older-messages-loading",
                "conversation-load-error-top",
            )

        val chosen = visible.lastOrNull { conversationAnchorMessageId(it) != null }

        assertEquals("msg:$MESSAGE_ID", chosen)
        assertEquals(MESSAGE_ID, conversationAnchorMessageId(chosen))
    }

    /** Evaluates the predicate with one field varied from a prefetch-ready baseline. */
    private fun prefetch(
        anchored: Boolean = true,
        hasMoreBefore: Boolean = true,
        isLoadingOlder: Boolean = false,
        olderPageBlocked: Boolean = false,
        oldestVisibleIndex: Int = OLDEST_ROW,
    ) = shouldPrefetchOlder(
        anchored = anchored,
        hasMoreBefore = hasMoreBefore,
        isLoadingOlder = isLoadingOlder,
        olderPageBlocked = olderPageBlocked,
        oldestVisibleIndex = oldestVisibleIndex,
        oldestMessageListIndex = OLDEST_ROW,
    )

    private companion object {
        const val OLDEST_ROW = 40
        val MESSAGE_ID = "ab".repeat(32)
    }
}

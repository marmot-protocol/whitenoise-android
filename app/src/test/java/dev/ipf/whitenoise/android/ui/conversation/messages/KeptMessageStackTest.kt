package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The kept-message stack is a list of references plus the one the card is
 * showing. It de-duplicates, it never keeps a reference that no longer resolves,
 * and it always leaves the card pointing at something while anything remains.
 */
class KeptMessageStackTest {
    private val first = key("aa")
    private val second = key("bb")
    private val third = key("cc")

    /** Keeping a message appends it and brings it to the front of the card. */
    @Test
    fun keepingAppendsAndSelects() {
        val stack = KeptMessageStack().add(first).add(second)

        assertEquals(listOf(first, second), stack.keys)
        assertEquals(second, stack.selected)
    }

    /** Keeping the same message twice re-selects it rather than duplicating it. */
    @Test
    fun keepingTheSameMessageTwiceOnlySelectsIt() {
        val stack = KeptMessageStack().add(first).add(second).add(first)

        assertEquals(listOf(first, second), stack.keys)
        assertEquals(first, stack.selected)
    }

    /** Selecting a message the stack does not hold changes nothing. */
    @Test
    fun selectingAnAbsentMessageIsInert() {
        val stack = KeptMessageStack().add(first)

        assertEquals(stack, stack.select(second))
    }

    /** Removing the shown message advances the card to the nearest survivor. */
    @Test
    fun removingTheShownMessageAdvancesTheCard() {
        val stack =
            KeptMessageStack()
                .add(first)
                .add(second)
                .add(third)
                .select(second)

        val remaining = stack.remove(second)

        assertEquals(listOf(first, third), remaining.keys)
        assertEquals(third, remaining.selected)
    }

    /** Removing a message the card is not showing leaves the selection alone. */
    @Test
    fun removingAnotherMessageKeepsTheSelection() {
        val stack = KeptMessageStack().add(first).add(second).select(first)

        assertEquals(first, stack.remove(second).selected)
    }

    /** A message that is deleted or expires drops out of the stack on its own. */
    @Test
    fun retainDropsReferencesThatNoLongerResolve() {
        val stack = KeptMessageStack().add(first).add(second).add(third)

        val remaining = stack.retain(setOf(first))

        assertEquals(listOf(first), remaining.keys)
        assertEquals(first, remaining.selected)
    }

    /** Losing every message empties the stack and clears the selection. */
    @Test
    fun retainingNothingEmptiesTheStack() {
        val stack = KeptMessageStack().add(first).add(second).retain(emptySet())

        assertEquals(emptyList<KeptMessageKey>(), stack.keys)
        assertNull(stack.selected)
    }

    private fun key(id: String) = KeptMessageKey(accountRef = "account", groupIdHex = "group", messageIdHex = id)
}

/**
 * The pager wraps: a stack of more than one message gains a boundary copy at
 * each end so a swipe past either edge continues instead of stopping, and the
 * pager silently recentres once it settles on one.
 */
class KeptMessagePagesTest {
    /** A single kept message needs no wrap-around pages at all. */
    @Test
    fun aSingleMessageHasOnePage() {
        val pages = KeptMessagePages(1)

        assertEquals(1, pages.pageCount)
        assertEquals(0, pages.pageFor(0))
        assertEquals(0, pages.messageAt(0))
        assertEquals(false, pages.isBoundary(0))
    }

    /** Three messages occupy five pages: the three real ones between two copies. */
    @Test
    fun severalMessagesGainABoundaryCopyAtEachEnd() {
        val pages = KeptMessagePages(3)

        assertEquals(5, pages.pageCount)
        assertEquals(listOf(1, 2, 3), listOf(0, 1, 2).map(pages::pageFor))
        assertEquals(listOf(2, 0, 1, 2, 0), (0 until 5).map(pages::messageAt))
    }

    /** Only the two copies are boundaries, and only those trigger a recentre. */
    @Test
    fun onlyTheCopiesAreBoundaries() {
        val pages = KeptMessagePages(3)

        assertEquals(listOf(true, false, false, false, true), (0 until 5).map(pages::isBoundary))
    }
}

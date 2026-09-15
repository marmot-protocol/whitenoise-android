package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kept-message holder partitions its stacks by account, so signing into a
 * second identity never surfaces the first one's content, and prunes references
 * that no longer resolve so a deleted or expired message leaves on its own.
 */
class KeptMessagesControllerTest {
    private val controller = KeptMessagesController()
    private val mine = key(ACCOUNT, "aa")
    private val alsoMine = key(ACCOUNT, "bb")
    private val theirs = key(OTHER_ACCOUNT, "cc")

    /** Keeping a message makes it the stack's only entry and the visible card. */
    @Test
    fun keepingRecordsAndSelects() {
        controller.keep(mine)

        assertEquals(listOf(mine), controller.keys(ACCOUNT))
        assertEquals(mine, controller.selected(ACCOUNT))
        assertTrue(controller.isKept(mine))
    }

    /** One account never sees another account's kept messages. */
    @Test
    fun stacksArePartitionedByAccount() {
        controller.keep(mine)
        controller.keep(theirs)

        assertEquals(listOf(mine), controller.keys(ACCOUNT))
        assertEquals(listOf(theirs), controller.keys(OTHER_ACCOUNT))
        assertFalse(controller.isKept(key(ACCOUNT, "cc")))
    }

    /** An account with nothing kept reports an empty stack and no selection. */
    @Test
    fun anUntouchedAccountIsEmpty() {
        assertEquals(emptyList<KeptMessageKey>(), controller.keys(ACCOUNT))
        assertNull(controller.selected(ACCOUNT))
    }

    /** Removing the last kept message empties that account's stack entirely. */
    @Test
    fun removingTheLastMessageEmptiesTheStack() {
        controller.keep(mine)

        controller.remove(mine)

        assertEquals(emptyList<KeptMessageKey>(), controller.keys(ACCOUNT))
        assertNull(controller.selected(ACCOUNT))
    }

    /** Clearing drops one account's stack and leaves every other account alone. */
    @Test
    fun clearingOnlyAffectsOneAccount() {
        controller.keep(mine)
        controller.keep(alsoMine)
        controller.keep(theirs)

        controller.clear(ACCOUNT)

        assertEquals(emptyList<KeptMessageKey>(), controller.keys(ACCOUNT))
        assertEquals(listOf(theirs), controller.keys(OTHER_ACCOUNT))
    }

    /** A reference that stops resolving — deleted or expired — is pruned. */
    @Test
    fun retainDropsUnresolvableReferences() {
        controller.keep(mine)
        controller.keep(alsoMine)

        controller.retain(ACCOUNT, setOf(alsoMine))

        assertEquals(listOf(alsoMine), controller.keys(ACCOUNT))
        assertEquals(alsoMine, controller.selected(ACCOUNT))
    }

    /** Selection falls back to the first entry when nothing was chosen explicitly. */
    @Test
    fun selectionFallsBackToTheFirstEntry() {
        controller.keep(mine)
        controller.keep(alsoMine)
        controller.select(mine)

        assertEquals(mine, controller.selected(ACCOUNT))
    }

    private fun key(
        accountRef: String,
        id: String,
    ) = KeptMessageKey(accountRef = accountRef, groupIdHex = "group", messageIdHex = id)

    private companion object {
        const val ACCOUNT = "account-a"
        const val OTHER_ACCOUNT = "account-b"
    }
}

package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlinkedAccountRemovalTest {
    /** A failed removal puts back the account that was in view, which is none on the sign-in screen. */
    @Test
    fun aFailedRemovalRestoresWhatWasInView() {
        val torn = prepareDestructiveAccountWipeRuntimeState(DestructiveAccountWipeRuntimeState("main", null, null, 4))

        assertEquals("main", restoreFailedDestructiveAccountWipeRuntimeState(torn, "main").activeAccountRef)
        assertNull(restoreFailedDestructiveAccountWipeRuntimeState(torn, null).activeAccountRef)
    }

    /** The unlink is announced unless the prompt will say it, which needs the account list to have been re-read. */
    @Test
    fun theUnlinkIsAnnouncedWheneverThePromptCannotSayIt() {
        assertFalse(unlinkNoticeNeeded(listed = true, active = true, hasNext = false))
        assertTrue(unlinkNoticeNeeded(listed = false, active = true, hasNext = false))
        assertTrue(unlinkNoticeNeeded(listed = true, active = false, hasNext = false))
        assertTrue(unlinkNoticeNeeded(listed = true, active = true, hasNext = true))
    }

    /** Removing an account's copy outdates what was captured for it, and leaves every other account's capture alone. */
    @Test
    fun removingACopyOutdatesOnlyThatAccountsEarlierCaptures() {
        val copies = AccountCopyLifetimes()
        val removed = copies.capture("work")
        val other = copies.capture("home")
        var retired = 0

        copies.copyRemoved("work") { retired++ }

        assertEquals(1, retired)
        assertFalse(copies.isCurrent("work", removed))
        assertFalse(copies.runIfCurrent("work", removed) { retired++ })
        assertTrue(copies.runIfCurrent("work", copies.capture("work")) { retired++ })
        assertTrue(copies.isCurrent("home", other))
        assertEquals(2, retired)
    }

    /** A missing account means its copy is gone only on a list that was re-read and published, never on a stale one. */
    @Test
    fun aMissingAccountIsBelievedOnlyOfAListThatWasReRead() {
        assertTrue(unlinkedCopyGone(listed = true, listLoaded = true, isListed = false))
        assertFalse(unlinkedCopyGone(listed = false, listLoaded = true, isListed = false))
        assertFalse(unlinkedCopyGone(listed = true, listLoaded = false, isListed = false))
        assertFalse(unlinkedCopyGone(listed = true, listLoaded = true, isListed = true))
    }

    /** A read that fails is tried again, and one that never succeeds stops after the last attempt. */
    @Test
    fun aFailedReadIsRetriedAFewTimes() =
        runTest {
            var reads = 0

            assertTrue(readWithRetries(attempts = 3, firstPauseMs = 1_000L) { ++reads == 2 })
            assertEquals(2, reads)
            assertEquals(1_000L, currentTime)

            reads = 0
            assertFalse(readWithRetries(attempts = 3, firstPauseMs = 1_000L) { ++reads > 3 })
            assertEquals(3, reads)
            assertEquals(4_000L, currentTime)
        }
}

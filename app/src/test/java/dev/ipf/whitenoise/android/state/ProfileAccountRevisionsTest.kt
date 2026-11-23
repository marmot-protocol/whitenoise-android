package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileAccountRevisionsTest {
    @Test
    fun normalizedUpdatesOnlyInvalidateTheirAccountAndEvictLeastRecentlyUpdated() {
        val revisions = ProfileAccountRevisions(maxEntries = 2)
        revisions.bump(" A ")
        revisions.bump("b")
        val beforeB = revisions.revision("b")
        revisions.bump("a")
        assertEquals(ProfileAccountRevision(0, 3), revisions.revision(" A "))
        assertEquals(beforeB, revisions.revision("b"))
        revisions.bump("c")
        assertEquals(ProfileAccountRevision(0, 0), revisions.revision("b"))
        assertEquals(ProfileAccountRevision(0, 3), revisions.revision("a"))
    }

    @Test
    fun resetInvalidatesAllAccountsAndRestartsTheSequence() {
        val revisions = ProfileAccountRevisions(maxEntries = 2)
        revisions.bump("a")
        val before = revisions.revision("a")
        revisions.reset()
        assertNotEquals(before, revisions.revision("a"))
        assertEquals(ProfileAccountRevision(1, 0), revisions.revision("a"))
        revisions.bump(" ")
        revisions.bump("a")
        assertEquals(ProfileAccountRevision(1, 1), revisions.revision("a"))
    }
}

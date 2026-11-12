package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ordering rules that keep a committed rename on screen without outranking newer engine state. */
class LocalGroupFieldAuthorityTest {
    /** A snapshot still carrying the replaced name is answered with the committed one. */
    @Test
    fun stalePreRenameSnapshotKeepsTheCommittedName() {
        val authority = LocalGroupFieldAuthority()
        authority.record(GROUP, previous = "Old", committed = "New")

        assertEquals("New", authority.reconcile(GROUP, "Old"))
    }

    /** A snapshot that already agrees passes through and retires the record. */
    @Test
    fun agreeingSnapshotRetiresTheRecord() {
        val authority = LocalGroupFieldAuthority()
        authority.record(GROUP, previous = "Old", committed = "New")

        assertEquals("New", authority.reconcile(GROUP, "New"))
        assertEquals("Old", authority.reconcile(GROUP, "Old"))
    }

    /** A different name is a newer authoritative commit and wins outright. */
    @Test
    fun newerAuthoritativeCommitWins() {
        val authority = LocalGroupFieldAuthority()
        authority.record(GROUP, previous = "Old", committed = "New")

        assertEquals("Renamed elsewhere", authority.reconcile(GROUP, "Renamed elsewhere"))
        assertEquals("Old", authority.reconcile(GROUP, "Old"))
    }

    /** Rapid renames converge on the newest value and still recognise every older name. */
    @Test
    fun rapidRenamesConvergeOnTheNewestName() {
        val authority = LocalGroupFieldAuthority()
        authority.record(GROUP, previous = "One", committed = "Two")
        authority.record(GROUP, previous = "Two", committed = "Three")

        assertEquals("Three", authority.reconcile(GROUP, "One"))
        assertEquals("Three", authority.reconcile(GROUP, "Two"))
    }

    /** Renaming back to an earlier name lets the agreeing snapshot retire the record (#3703). */
    @Test
    fun renamingBackToAnEarlierNameRetiresOnAgreement() {
        val authority = LocalGroupFieldAuthority()
        authority.record(GROUP, previous = "A", committed = "B")
        authority.record(GROUP, previous = "B", committed = "A")

        assertNull(authority.retainedFor(GROUP, "A"))
        assertEquals("A", authority.reconcile(GROUP, "B"))
        assertEquals("A", authority.reconcile(GROUP, "A"))
        assertEquals("B", authority.reconcile(GROUP, "B"))
    }

    /** A rename made elsewhere after the cycle settles is not overwritten by a stale local commit. */
    @Test
    fun laterRemoteRenameWinsAfterACycleSettles() {
        val authority = LocalGroupFieldAuthority()
        authority.record(GROUP, previous = "A", committed = "B")
        authority.record(GROUP, previous = "B", committed = "A")

        assertEquals("A", authority.reconcile(GROUP, "A"))
        assertNull(authority.retainedFor(GROUP, "B"))
        assertEquals("B", authority.reconcile(GROUP, "B"))
    }

    /** A commit that does not change the name installs nothing. */
    @Test
    fun unchangedNameIsNotRecorded() {
        val authority = LocalGroupFieldAuthority()
        authority.record(GROUP, previous = "Same", committed = "Same")

        assertNull(authority.retainedFor(GROUP, "Same"))
    }

    /** Group ids are matched case-insensitively, as everywhere else hex is compared. */
    @Test
    fun groupIdsMatchRegardlessOfHexCase() {
        val authority = LocalGroupFieldAuthority()
        authority.record(GROUP.uppercase(), previous = "Old", committed = "New")

        assertEquals("New", authority.reconcile(GROUP, "Old"))
    }

    /** Forgetting a group hands the next snapshot straight through. */
    @Test
    fun forgettingAGroupReleasesItsRetention() {
        val authority = LocalGroupFieldAuthority()
        authority.record(GROUP, previous = "Old", committed = "New")
        authority.forget(GROUP)

        assertEquals("Old", authority.reconcile(GROUP, "Old"))
    }

    /** A blank group id is never keyed, so it cannot collide with a real row. */
    @Test
    fun blankGroupIdIsIgnored() {
        val authority = LocalGroupFieldAuthority()
        authority.record(" ", previous = "Old", committed = "New")

        assertEquals("Old", authority.reconcile(" ", "Old"))
    }

    /** Peeking never retires a record, so a row and its presentation share one decision. */
    @Test
    fun retainedForDoesNotRetireTheRecord() {
        val authority = LocalGroupFieldAuthority()
        authority.record(GROUP, previous = "Old", committed = "New")

        assertEquals("New", authority.retainedFor(GROUP, "Old"))
        assertEquals("New", authority.retainedFor(GROUP, "Old"))
        assertNull(authority.retainedFor(GROUP, "New"))
    }

    /** A held clear stays fenced after only the row agrees; the group record can still restore it. */
    @Test
    fun clearOutlivesAnAgreeingBlankRow() {
        val authority = LocalGroupFieldAuthority(holdClearAfterAgreement = true)
        authority.record(GROUP, previous = "Old", committed = "")

        assertEquals("", authority.reconcile(GROUP, ""))
        assertEquals("", authority.reconcile(GROUP, "Old"))
        assertEquals("", authority.reconcileRecord(GROUP, "Old"))
        assertEquals("Remote", authority.reconcile(GROUP, "Remote"))
        assertEquals("Old", authority.reconcile(GROUP, "Old"))
    }

    /** Once both the row and the group record agree with a held clear, a remote restore is shown. */
    @Test
    fun clearRetiresOnceBothStreamsAgree() {
        val authority = LocalGroupFieldAuthority(holdClearAfterAgreement = true)
        authority.record(GROUP, previous = "Old", committed = "")

        assertEquals("", authority.reconcileRecord(GROUP, ""))
        assertEquals("", authority.reconcile(GROUP, "Old"))
        assertEquals("", authority.reconcile(GROUP, ""))
        assertEquals("Old", authority.reconcileRecord(GROUP, "Old"))
        assertEquals("Old", authority.reconcile(GROUP, "Old"))
    }

    /** By default an agreeing blank snapshot retires a clear, so restoring the old value wins. */
    @Test
    fun agreeingBlankSnapshotRetiresAnUnheldClear() {
        val authority = LocalGroupFieldAuthority()
        authority.record(GROUP, previous = "Old", committed = "")

        assertEquals("", authority.reconcile(GROUP, ""))
        assertEquals("Old", authority.reconcile(GROUP, "Old"))
    }

    private companion object {
        const val GROUP = "ab" + "cd"
    }
}

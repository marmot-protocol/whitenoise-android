package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupRenameNameSnapshotsTest {
    @Test
    fun recordReturnsPreviousNameForCurrentGroupRename() {
        val snapshots = GroupRenameNameSnapshots(nowMillis = { 1_000L })
        snapshots.remember(accountRef = "acct", groupIdHex = "group", name = "Marmot Lab")

        val previous = snapshots.record(accountRef = "acct", groupIdHex = "group", name = "Marmot Protocol")

        assertEquals("Marmot Lab", previous?.name)
        assertEquals("Marmot Lab", snapshots.previousFor("acct", "group", "Marmot Protocol")?.name)
    }

    @Test
    fun blankPreviousNameRemainsKnownForFirstNameSet() {
        val snapshots = GroupRenameNameSnapshots(nowMillis = { 1_000L })
        snapshots.remember(accountRef = "acct", groupIdHex = "group", name = "")

        val previous = snapshots.record(accountRef = "acct", groupIdHex = "group", name = "Marmot Lab")

        assertEquals("", previous?.name)
    }

    @Test
    fun rememberDoesNotOverwriteExistingNameFromNonRenameNotifications() {
        val snapshots = GroupRenameNameSnapshots(nowMillis = { 1_000L })
        snapshots.remember(accountRef = "acct", groupIdHex = "group", name = "Marmot Lab")
        snapshots.remember(accountRef = "acct", groupIdHex = "group", name = "Marmot Protocol")

        val previous = snapshots.record(accountRef = "acct", groupIdHex = "group", name = "Marmot Protocol")

        assertEquals("Marmot Lab", previous?.name)
    }

    @Test
    fun previousNameExpires() {
        var now = 1_000L
        val snapshots = GroupRenameNameSnapshots(pendingTtlMillis = 100L, nowMillis = { now })
        snapshots.remember(accountRef = "acct", groupIdHex = "group", name = "Marmot Lab")
        snapshots.record(accountRef = "acct", groupIdHex = "group", name = "Marmot Protocol")

        now = 1_101L

        assertNull(snapshots.previousFor("acct", "group", "Marmot Protocol"))
    }

    @Test
    fun repeatedNewNameMatchesOldestPendingRenamePerEvent() {
        val snapshots = GroupRenameNameSnapshots(nowMillis = { 1_000L })
        snapshots.remember(accountRef = "acct", groupIdHex = "group", name = "A")
        snapshots.record(accountRef = "acct", groupIdHex = "group", name = "B")
        snapshots.record(accountRef = "acct", groupIdHex = "group", name = "C")
        snapshots.record(accountRef = "acct", groupIdHex = "group", name = "B")

        assertEquals("A", snapshots.previousFor("acct", "group", "B", eventId = "row-a-to-b")?.name)
        assertEquals("C", snapshots.previousFor("acct", "group", "B", eventId = "row-c-to-b")?.name)
        assertEquals("A", snapshots.previousFor("acct", "group", "B", eventId = "row-a-to-b")?.name)
    }

    @Test
    fun payloadBackedCurrentUpdateDoesNotCreatePendingRename() {
        val snapshots = GroupRenameNameSnapshots(nowMillis = { 1_000L })
        snapshots.remember(accountRef = "acct", groupIdHex = "group", name = "A")
        snapshots.setCurrent(accountRef = "acct", groupIdHex = "group", name = "B")

        val previous = snapshots.record(accountRef = "acct", groupIdHex = "group", name = "C")

        assertEquals("B", previous?.name)
        assertNull(snapshots.previousFor("acct", "group", "B", eventId = "late-row-a-to-b"))
    }
}

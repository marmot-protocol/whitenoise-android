package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.AppGroupRecordFfi
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatsControllerInvitationSettlementTest {
    /** An invitation answered on another device moves the row first, so the cached record disagrees. */
    @Test
    fun aRowAnsweredElsewhereDisagreesWithAPendingRecord() {
        assertTrue(invitationSettlementDisagrees(rowPendingConfirmation = false, cachedPendingConfirmation = true))
        assertTrue(invitationSettlementDisagrees(rowPendingConfirmation = true, cachedPendingConfirmation = false))
    }

    /** A row and record that agree, or a chat with no cached record, need no read. */
    @Test
    fun agreeingOrUncachedChatsNeedNoRead() {
        assertFalse(invitationSettlementDisagrees(rowPendingConfirmation = true, cachedPendingConfirmation = true))
        assertFalse(invitationSettlementDisagrees(rowPendingConfirmation = false, cachedPendingConfirmation = false))
        assertFalse(invitationSettlementDisagrees(rowPendingConfirmation = false, cachedPendingConfirmation = null))
    }

    /** Duplicate snapshots share a read; a newer Welcome keeps its own decision when the old read returns. */
    @Test
    fun lateSettlementCannotReplaceANewerWelcome() {
        val data = InviteAcceptanceTestData
        val chats = ChatsController(data.appState(), data.ACCOUNT) { _, _ -> emptyList() }
        val old = data.group(true, data.OLD_WELCOME)
        val fresh = data.group(true, data.NEW_WELCOME)
        val row = data.chatListRow(false)
        val held = CompletableDeferred<AppGroupRecordFfi>()
        var reads = 0
        val read: suspend (String, String) -> AppGroupRecordFfi = { _, _ ->
            reads++
            held.await()
        }
        try {
            chats.groupRecordsById = mapOf(data.GROUP_ID to old)
            chats.chatRowsByGroup[data.GROUP_ID] = row
            chats.settleInvitationFromMdk(row, read)
            chats.settleInvitationFromMdk(row.copy(unreadCount = 1uL), read)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, reads)

            val replacement = row.copy(pendingConfirmation = true)
            chats.chatRowsByGroup[data.GROUP_ID] = replacement
            chats.foldGroupRecordForTest(fresh)
            held.complete(old.copy(pendingConfirmation = false))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(fresh, chats.groupRecordsById[data.GROUP_ID])
            assertEquals(replacement, chats.chatRowsByGroup[data.GROUP_ID])
            assertEquals(1, reads)
        } finally {
            chats.onCleared()
        }
    }

    /** A failed lookup releases its slot. The retry settles only the group and preserves newer row metadata. */
    @Test
    fun failedSettlementCanRetryWithoutOverwritingTheStreamedRow() {
        val data = InviteAcceptanceTestData
        val chats = ChatsController(data.appState(), data.ACCOUNT) { _, _ -> emptyList() }
        val old = data.group(true, data.OLD_WELCOME)
        val row = data.chatListRow(false).copy(archived = true, groupName = "Newer title")
        val settled = old.copy(pendingConfirmation = false)
        var reads = 0
        val read: suspend (String, String) -> AppGroupRecordFfi = { _, _ ->
            reads++
            if (reads == 1) error("Read unavailable")
            settled
        }
        try {
            chats.groupRecordsById = mapOf(data.GROUP_ID to old)
            chats.chatRowsByGroup[data.GROUP_ID] = row
            chats.settleInvitationFromMdk(row, read)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(old, chats.groupRecordsById[data.GROUP_ID])
            chats.settleInvitationFromMdk(row, read)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, reads)
            assertEquals(settled, chats.groupRecordsById[data.GROUP_ID])
            assertEquals(row, chats.chatRowsByGroup[data.GROUP_ID])
        } finally {
            chats.onCleared()
        }
    }
}

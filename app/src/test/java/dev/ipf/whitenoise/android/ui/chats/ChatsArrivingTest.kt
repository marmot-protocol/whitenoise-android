package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.ChatHistoryImportStateFfi
import dev.ipf.marmotkit.HistoryImportSourceFfi
import dev.ipf.marmotkit.LinkChatProgressFfi
import dev.ipf.whitenoise.android.state.ChatHistoryTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatsArrivingTest {
    private val joining = LinkChatProgressFfi("this", true, 12u, 5u)
    private val imported = ChatHistoryImportStateFfi.Imported(HistoryImportSourceFfi("slot", "Mac"), 1uL)

    /** Joining chats is reported first, even while some chats' history is still arriving. */
    @Test
    fun joiningChatsComesBeforeHistory() {
        val arriving = chatsArriving(joining, listOf(ChatHistoryImportStateFfi.Receiving(retrying = false)))
        assertEquals(ChatsArriving.Joining(joining), arriving)
    }

    /** Once every chat has joined, a chat still receiving its history keeps the notice up. */
    @Test
    fun historyStillReceivingKeepsTheNotice() {
        val imports = listOf(imported, ChatHistoryImportStateFfi.Receiving(retrying = false))
        assertEquals(ChatsArriving.History(ChatHistoryTransfer.Receiving), chatsArriving(null, imports))
    }

    /** MDK's retry shows only when no other chat is still receiving. */
    @Test
    fun aRetryShowsOnlyWhenNothingElseIsReceiving() {
        val retrying = ChatHistoryImportStateFfi.Receiving(retrying = true)
        assertEquals(ChatsArriving.History(ChatHistoryTransfer.Retrying), chatsArriving(null, listOf(retrying)))
        assertEquals(
            ChatsArriving.History(ChatHistoryTransfer.Receiving),
            chatsArriving(null, listOf(retrying, ChatHistoryImportStateFfi.Receiving(retrying = false))),
        )
    }

    /** Settled imports and no link progress leave nothing to say. */
    @Test
    fun settledImportsShowNothing() {
        assertNull(chatsArriving(null, listOf(imported)))
        assertNull(chatsArriving(null, emptyList()))
    }
}

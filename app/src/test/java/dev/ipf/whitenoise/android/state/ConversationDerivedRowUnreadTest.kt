package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Derived-state rows (kind-1009 in-place edits and kind-1210 group system
 * events) arrive as `received` but are not new chat: they must inflate
 * neither the unread count nor shift the first-unread anchor. Complements
 * [ConversationUnreadTest], which covers the base anchor/count invariants,
 * by pinning the derived-kind skips for [firstUnreadReceivedIndex] and the
 * kind-1009 skip for [countUnreadIncoming] (the 1210 skip is covered there).
 */
class ConversationDerivedRowUnreadTest {
    @Test
    fun firstUnread_skipsReceivedEditRows() {
        // Newest-last timeline: chat, edit, chat. With unreadCount=2 the
        // anchor must land on the older *chat* row (index 0), stepping over
        // the received kind-1009 edit at index 1.
        val timeline = listOf(chat("c1"), edit("e1"), chat("c2"))

        assertEquals(0, firstUnreadReceivedIndex(timeline, unreadCount = 2))
    }

    @Test
    fun firstUnread_skipsReceivedGroupSystemRows() {
        val timeline = listOf(chat("c1"), groupSystem("g1"), chat("c2"))

        assertEquals(0, firstUnreadReceivedIndex(timeline, unreadCount = 2))
    }

    @Test
    fun firstUnread_windowOfOnlyDerivedRowsFallsBackToBottom() {
        // A window holding nothing but derived rows can't satisfy any unread
        // count — signal "use the bottom" (-1), never anchor on an edit.
        val timeline = listOf(edit("e1"), groupSystem("g1"))

        assertEquals(-1, firstUnreadReceivedIndex(timeline, unreadCount = 1))
    }

    @Test
    fun unreadCount_editRowsAfterTheAnchorAreNotCounted() {
        val timeline = listOf(chat("c1"), edit("e1"), chat("c2"), edit("e2"))

        assertEquals(1, countUnreadIncoming(timeline, readAnchorMessageId = "c1"))
    }

    @Test
    fun mentions_blankMessageIdsAreDropped() {
        // A row that would otherwise qualify but carries a blank id must not
        // produce a jump target the UI can't scroll to.
        val timeline = listOf(chat(""), chat("c2"))

        assertEquals(
            listOf("c2"),
            unreadReceivedMentionIds(timeline, readAnchorMessageId = null) { true },
        )
    }

    // ---- helpers ------------------------------------------------------------

    private fun chat(id: String): TimelineMessage = message(id, kind = 9uL)

    private fun edit(id: String): TimelineMessage = message(id, kind = 1009uL)

    private fun groupSystem(id: String): TimelineMessage = message(id, kind = 1210uL)

    private fun message(
        id: String,
        kind: ULong,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "received",
                    groupIdHex = "group",
                    sender = "peer",
                    plaintext = "body-$id",
                    contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                    kind = kind,
                    tags = emptyList(),
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                ),
            status = MessageStatus.Received,
        )
}

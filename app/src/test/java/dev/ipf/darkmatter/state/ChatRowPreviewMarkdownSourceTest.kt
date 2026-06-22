package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-predicate coverage for [chatRowPreviewMarkdownSource]: the markdown
 * parse gate must only surface source text when the chat-list preview line is
 * the message body itself. Edit (1009) and group-system (1210) rows render
 * derived copy via [ChatListItem.projectedPreviewText], so their payloads must
 * not be parsed into preview tokens and styled in place (issue #577).
 */
class ChatRowPreviewMarkdownSourceTest {
    @Test
    fun regularChatRowReturnsNonBlankBody() {
        assertEquals(
            "hello **world**",
            chatRowPreviewMarkdownSource(rowWith(kind = 9uL, plaintext = "hello **world**")),
        )
    }

    @Test
    fun editRowReturnsNull() {
        // Kind-1009 edit payloads fall back to the projected latest message in
        // the preview line; their raw payload must never be styled.
        assertNull(chatRowPreviewMarkdownSource(rowWith(kind = 1009uL, plaintext = "edited content")))
    }

    @Test
    fun groupSystemRowReturnsNull() {
        // Kind-1210 group-system rows render synthetic copy; raw JSON must not
        // leak into the preview as markdown tokens.
        assertNull(
            chatRowPreviewMarkdownSource(
                rowWith(kind = 1210uL, plaintext = "{\"event\":\"member_added\"}"),
            ),
        )
    }

    @Test
    fun agentStreamStartRowReturnsNull() {
        // Kind-1200 agent-stream-start rows surface fallback copy when blank and
        // are otherwise synthetic; they are not a regular message body.
        assertNull(chatRowPreviewMarkdownSource(rowWith(kind = 1200uL, plaintext = "streaming...")))
    }

    @Test
    fun deletedRowReturnsNull() {
        assertNull(chatRowPreviewMarkdownSource(rowWith(kind = 9uL, plaintext = "gone", deleted = true)))
    }

    @Test
    fun blankBodyReturnsNull() {
        assertNull(chatRowPreviewMarkdownSource(rowWith(kind = 9uL, plaintext = "   ")))
    }

    @Test
    fun rowWithoutLastMessageReturnsNull() {
        assertNull(chatRowPreviewMarkdownSource(rowWith(lastMessage = null)))
    }

    private fun rowWith(
        kind: ULong = 9uL,
        plaintext: String = "body",
        deleted: Boolean = false,
        lastMessage: ChatListMessagePreviewFfi? =
            ChatListMessagePreviewFfi(
                messageIdHex = "message-1",
                sender = "sender",
                senderDisplayName = "Sender",
                plaintext = plaintext,
                contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
                kind = kind,
                timelineAt = 1uL,
                deleted = deleted,
            ),
    ) = ChatListRowFfi(
        groupIdHex = "group-1",
        archived = false,
        pendingConfirmation = false,
        title = "Group",
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage = lastMessage,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        updatedAt = 1uL,
    )
}

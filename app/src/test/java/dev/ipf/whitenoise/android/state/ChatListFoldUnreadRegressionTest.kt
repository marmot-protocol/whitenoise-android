package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.audio.kotlinFunctionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for issue #1383: a stale chat-list subscription row must not
 * overwrite a fresher in-memory row and resurrect an inflated [unreadCount].
 * [foldChatRow] delegates to [mergeMarkReadChatListRow]; these tests pin the
 * merge contract subscription folding relies on.
 */
class ChatListFoldUnreadRegressionTest {
    @Test
    fun staleSubscriptionWithOlderReadWatermarkIsRejected() {
        val current =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 200uL,
                lastReadMessageIdHex = idTail,
            )
        val staleSubscription =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 47uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
            )

        assertNull(mergeMarkReadChatListRow(current, staleSubscription))
    }

    @Test
    fun delayedBackfillWithSameProjectionTuplesCanIncreaseUnread() {
        val current =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 1uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
            )
        val delayedBackfill =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 2uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
            )

        val merged = requireNotNull(mergeMarkReadChatListRow(current, delayedBackfill))
        assertEquals(2uL, merged.unreadCount)
        assertEquals(true, merged.hasUnread)
    }

    @Test
    fun freshSubscriptionWithNewLastMessageAdoptsUnread() {
        val current =
            row(
                messageId = idMid,
                lastMessageAt = 100uL,
                unreadCount = 1uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
            )
        val incoming =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 2uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
            )

        val merged = requireNotNull(mergeMarkReadChatListRow(current, incoming))
        assertEquals(2uL, merged.unreadCount)
        assertEquals(idTail, merged.lastMessage?.messageIdHex)
    }

    @Test
    fun unreadCountDivergenceReport_flagsInflatedProjectionWithinLoadedWindow() {
        val timeline =
            listOf(
                received("r1"),
                received("r2"),
                received("r3"),
            )
        val report =
            unreadCountDivergenceReport(
                projectionUnread = 3,
                timeline = timeline,
                readAnchorMessageId = "r2",
            )

        requireNotNull(report)
        assertEquals(3, report.projectionUnread)
        assertEquals(1, report.timelineUnread)
        assertEquals(3, report.loadedReceivedCount)
    }

    @Test
    fun unreadCountDivergenceReport_isNullWhenProjectionExceedsLoadedWindow() {
        val timeline = listOf(received("r1"), received("r2"))
        assertEquals(
            null,
            unreadCountDivergenceReport(
                projectionUnread = 47,
                timeline = timeline,
                readAnchorMessageId = "r1",
            ),
        )
    }

    @Test
    fun unreadCountDivergenceReport_isNullWhenProjectionIsNotAboveTimeline() {
        val timeline = listOf(received("r1"), received("r2"))
        assertEquals(
            null,
            unreadCountDivergenceReport(
                projectionUnread = 1,
                timeline = timeline,
                readAnchorMessageId = "r1",
            ),
        )
    }

    @Test
    fun foldChatRow_usesMonotonicMergeForSubscriptionRows() {
        val body = controllersSource().readText().kotlinFunctionBody("foldChatRow")

        assertTrue(
            "subscription folds must merge against the current row before replacing it",
            "mergeMarkReadChatListRow" in body,
        )
    }

    private fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing Controllers.kt source file")

    private val idAnchor = "a".repeat(64)
    private val idMid = "b".repeat(64)
    private val idTail = "c".repeat(64)

    private fun row(
        messageId: String,
        lastMessageAt: ULong,
        unreadCount: ULong,
        lastReadTimelineAt: ULong?,
        lastReadMessageIdHex: String?,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = "group",
        archived = false,
        pendingConfirmation = false,
        title = "Chat",
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage =
            ChatListMessagePreviewFfi(
                messageIdHex = messageId,
                sender = "sender",
                senderDisplayName = "Sender",
                plaintext = "hello",
                contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                kind = 9uL,
                timelineAt = lastMessageAt,
                deleted = false,
            ),
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex = messageId,
        lastReadMessageIdHex = lastReadMessageIdHex,
        lastReadTimelineAt = lastReadTimelineAt,
        updatedAt = lastMessageAt,
    )

    private fun received(id: String): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "received",
                    groupIdHex = "group",
                    sender = "peer",
                    plaintext = "hi",
                    contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                    kind = 9uL,
                    tags = emptyList(),
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                ),
            status = MessageStatus.Received,
        )
}

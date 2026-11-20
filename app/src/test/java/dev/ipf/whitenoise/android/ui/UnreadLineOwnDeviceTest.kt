package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.conversation.firstUnreadAfterReadAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the unread line goes when the account's own message from another device sits after the read position. */
class UnreadLineOwnDeviceTest {
    /** A message the account sent from another of its devices arrives as received, and never takes the unread line. */
    @Test
    fun theUnreadLineSkipsTheAccountsOwnMessageFromAnotherDevice() {
        val own = received("own-elsewhere").copy(fromOtherOwnDevice = true)
        val timeline = listOf(received("old-unread"), received("read"), own, received("new-1"))

        assertEquals("new-1", firstUnreadAfterReadAnchor("old-unread", "read", timeline))
        assertEquals(null, firstUnreadAfterReadAnchor("old-unread", "read", timeline.take(3)))
    }

    /** A received chat message with [id]. */
    private fun received(id: String): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    invalidated = false,
                    messageIdHex = id,
                    direction = "received",
                    groupIdHex = "group",
                    sender = "bob",
                    plaintext = "text-$id",
                    contentTokens =
                        MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
                    kind = 9uL,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                    sticker = null,
                ),
            status = MessageStatus.Received,
        )
}

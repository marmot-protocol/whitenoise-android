package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisappearingReadAnchorTest {
    @Test
    fun ownSendNeverDefersSendTimeExpiry() {
        assertFalse(
            isDisappearingSendTimeExpiryDeferred(
                record = message(direction = "sent", id = "s1", timelineAt = 100uL),
                lastReadMessageId = null,
                lastReadTimelineAt = null,
                orderedMessageIds = listOf("s1"),
            ),
        )
    }

    @Test
    fun unreadReceivedDefersWhenNoReadWatermark() {
        assertTrue(
            isDisappearingSendTimeExpiryDeferred(
                record = message(direction = "received", id = "r1", timelineAt = 100uL),
                lastReadMessageId = null,
                lastReadTimelineAt = null,
                orderedMessageIds = listOf("r1"),
            ),
        )
    }

    @Test
    fun receivedAfterReadMessageIdDefers() {
        assertTrue(
            isDisappearingSendTimeExpiryDeferred(
                record = message(direction = "received", id = "r2", timelineAt = 200uL),
                lastReadMessageId = "r1",
                lastReadTimelineAt = 150uL,
                orderedMessageIds = listOf("r1", "r2"),
            ),
        )
        assertFalse(
            isDisappearingSendTimeExpiryDeferred(
                record = message(direction = "received", id = "r1", timelineAt = 100uL),
                lastReadMessageId = "r1",
                lastReadTimelineAt = 150uL,
                orderedMessageIds = listOf("r1", "r2"),
            ),
        )
    }

    @Test
    fun receivedAfterLastReadTimelineAtDefers() {
        assertTrue(
            isDisappearingSendTimeExpiryDeferred(
                record = message(direction = "received", id = "r2", timelineAt = 200uL),
                lastReadMessageId = null,
                lastReadTimelineAt = 150uL,
                orderedMessageIds = listOf("r1", "r2"),
            ),
        )
    }

    private fun message(
        direction: String,
        id: String,
        timelineAt: ULong,
    ): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = direction,
            groupIdHex = "group",
            sender = "sender",
            plaintext = "hi",
            contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
            kind = 9uL,
            tags = emptyList(),
            recordedAt = timelineAt,
            receivedAt = timelineAt,
        )
}

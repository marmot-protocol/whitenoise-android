package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationAutoReadResumeTest {
    @Test
    fun returnsOnlyRowsThatArrivedAfterBackgroundCursor() {
        val beforePause = listOf(message("one", 1uL), message("two", 2uL))
        val cursor = conversationAutoReadCursor(beforePause)
        val afterResume = beforePause + listOf(message("three", 3uL), message("four", 4uL))

        assertEquals(
            listOf("three", "four"),
            conversationMessagesAfterAutoReadCursor(afterResume, cursor).map { it.record.messageIdHex },
        )
    }

    @Test
    fun emptyConversationCursorIncludesFirstBackgroundArrivals() {
        val cursor = conversationAutoReadCursor(emptyList())

        assertEquals(
            listOf("first"),
            conversationMessagesAfterAutoReadCursor(listOf(message("first", 1uL)), cursor)
                .map { it.record.messageIdHex },
        )
    }

    @Test
    fun timelineOrderRecoversWhenBoundedWindowTrimmedCursorRow() {
        val cursor = conversationAutoReadCursor(listOf(message("trimmed", 10uL)))
        val reloadedWindow = listOf(message("new-11", 11uL), message("new-12", 12uL))

        assertEquals(
            listOf("new-11", "new-12"),
            conversationMessagesAfterAutoReadCursor(reloadedWindow, cursor).map { it.record.messageIdHex },
        )
    }

    @Test
    fun prependingOlderHistoryDoesNotLookLikeBackgroundArrival() {
        val current = listOf(message("one", 1uL), message("two", 2uL))
        val cursor = conversationAutoReadCursor(current)
        val withOlderHistory = listOf(message("older", 0uL)) + current

        assertEquals(emptyList<TimelineMessage>(), conversationMessagesAfterAutoReadCursor(withOlderHistory, cursor))
    }

    private fun message(
        id: String,
        timelineOrder: ULong,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "received",
                    groupIdHex = "group",
                    sender = "alice",
                    plaintext = "text-$id",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = 9uL,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = timelineOrder,
                    receivedAt = timelineOrder,
                ),
            status = MessageStatus.Received,
            timelineOrder = timelineOrder,
        )
}

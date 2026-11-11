package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationAutoReadResumeTest {
    /** Each consumed native arrival advances the anchor even though its optimistic order is zero. */
    @Test
    fun successiveNativeArrivalsDoNotReplayPreviouslyConsumedReplies() {
        val before = listOf(nativeMessage("existing", 10uL))
        var cursor = conversationAutoReadCursor(before)
        val firstArrival = before + nativeMessage("first", 11uL)
        assertEquals(
            listOf("first"),
            conversationMessagesAfterAutoReadCursor(firstArrival, cursor).map { it.record.messageIdHex },
        )
        cursor = advanceConversationAutoReadCursor(cursor, firstArrival)
        val secondArrival = firstArrival + nativeMessage("second", 12uL)
        assertEquals(
            listOf("second"),
            conversationMessagesAfterAutoReadCursor(secondArrival, cursor).map { it.record.messageIdHex },
        )
    }

    /** Window-local native ordinals cannot establish arrival order after the identity anchor is lost. */
    @Test
    fun rebasedNativeWindowsCannotRewindOrInventArrivalsWithoutTheAnchor() {
        val cursor = conversationAutoReadCursor(listOf(nativeMessage("trimmed", 10uL)))
        val olderWindow = listOf(nativeMessage("older", 50uL), nativeMessage("older-tail", 51uL))
        assertEquals(cursor, advanceConversationAutoReadCursor(cursor, olderWindow))
        val replacement = listOf(nativeMessage("replacement", 1uL), nativeMessage("replacement-tail", 2uL))
        assertEquals(cursor, advanceConversationAutoReadCursor(cursor, replacement))
        assertEquals(
            emptyList<TimelineMessage>(),
            conversationMessagesAfterAutoReadCursor(olderWindow, cursor),
        )
        assertEquals(emptyList<TimelineMessage>(), conversationMessagesAfterAutoReadCursor(replacement, cursor))
    }

    @Test
    fun browsingOlderHistoryDoesNotRewindTheArrivalWatermark() {
        val live = listOf(message("live", 20uL))
        val cursor = conversationAutoReadCursor(live)
        val historicalWindow = listOf(message("old", 5uL), message("older-tail", 10uL))
        val advanced = advanceConversationAutoReadCursor(cursor, historicalWindow)
        assertEquals(cursor, advanced)
        assertEquals(
            listOf("new"),
            conversationMessagesAfterAutoReadCursor(live + message("new", 21uL), advanced)
                .map { it.record.messageIdHex },
        )
        assertEquals(cursor, advanceConversationAutoReadCursor(cursor, emptyList()))
    }

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

    /** Mirrors native rows: only authoritativeOrder carries the native sequence. */
    private fun nativeMessage(
        id: String,
        order: ULong,
    ): TimelineMessage = message(id, 0uL).copy(authoritativeOrder = order)

    /** Builds a received message with the given id and timeline order. */
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
                    sticker = null,
                ),
            status = MessageStatus.Received,
            timelineOrder = timelineOrder,
        )
}

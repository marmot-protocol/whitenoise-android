package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.audio.tts.TTS_AUTO_READ_MAX_MESSAGES
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsSpeakFromHereTest {
    @Test
    fun selectedBubbleMissingFromTimelineFallsBackToThatBubble() {
        val selected = message("selected")

        val candidates =
            ttsSpeakFromHereCandidates(
                timeline = listOf(timelineMessage("other")),
                selected = selected,
            )

        assertEquals(listOf("selected"), candidates.map(AppMessageRecordFfi::messageIdHex))
    }

    @Test
    fun selectedTimelineBubbleStartsBoundedCatchUpAtThatBubble() {
        val timeline =
            listOf(timelineMessage("before")) +
                List(TTS_AUTO_READ_MAX_MESSAGES * 2 + 1) { index -> timelineMessage("selected-$index") }
        val selected = timeline[1].record

        val candidates = ttsSpeakFromHereCandidates(timeline, selected)

        assertEquals(TTS_AUTO_READ_MAX_MESSAGES * 2, candidates.size)
        assertEquals("selected-0", candidates.first().messageIdHex)
        assertEquals("selected-${TTS_AUTO_READ_MAX_MESSAGES * 2 - 1}", candidates.last().messageIdHex)
    }

    private fun timelineMessage(id: String): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record = message(id),
            status = MessageStatus.Received,
        )

    private fun message(id: String): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = "received",
            groupIdHex = "group",
            sender = "alice",
            plaintext = id,
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = byteArrayOf(),
                ),
            kind = 9uL,
            tags = emptyList<MessageTagFfi>(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )
}

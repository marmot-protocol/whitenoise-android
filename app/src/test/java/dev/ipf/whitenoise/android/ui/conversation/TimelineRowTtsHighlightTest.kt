package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineRowTtsHighlightTest {
    @Test
    fun matchingSpeakingPassageIsScopedToMessageId() {
        val passage =
            TtsPassage(
                messageIdHex = MESSAGE_ID,
                sentenceIndex = 0,
                projectionId = "projection",
            )
        val state = speakingState(passage = passage)

        assertEquals(passage, timelineRowTtsHighlightPassage(MESSAGE_ID, state))
        assertNull(timelineRowTtsHighlightPassage(OTHER_MESSAGE_ID, state))
    }

    @Test
    fun matchingPausedPassageKeepsReadAloudProgress() {
        val passage =
            TtsPassage(
                messageIdHex = MESSAGE_ID,
                sentenceIndex = 1,
                projectionId = "projection",
            )
        val state = pausedState(passage = passage)

        assertEquals(passage, timelineRowTtsHighlightPassage(MESSAGE_ID, state))
        assertEquals(
            TtsReadAloudProgress(
                sentenceIndex = 1,
                sentenceCount = 3,
                messageIndex = 2,
                messageCount = 4,
            ),
            timelineRowTtsReadAloudProgress(MESSAGE_ID, state),
        )
        assertNull(timelineRowTtsReadAloudProgress(OTHER_MESSAGE_ID, state))
    }

    @Test
    fun idleStateSuppressesRowScopedProjection() {
        val idle = TtsState.Idle()

        assertNull(timelineRowTtsHighlightPassage(MESSAGE_ID, idle))
        assertNull(timelineRowTtsReadAloudProgress(MESSAGE_ID, idle))
    }

    private fun speakingState(passage: TtsPassage) =
        TtsState.Speaking(
            chunkIndex = 0,
            chunkCount = 1,
            messageIndex = 2,
            messageCount = 4,
            sentenceIndexWithinMessage = 1,
            sentenceCountWithinMessage = 3,
            messagePreview = "preview",
            passage = passage,
        )

    private fun pausedState(passage: TtsPassage) =
        TtsState.Paused(
            chunkIndex = 0,
            chunkCount = 1,
            messageIndex = 2,
            messageCount = 4,
            sentenceIndexWithinMessage = 1,
            sentenceCountWithinMessage = 3,
            messagePreview = "preview",
            passage = passage,
        )

    private companion object {
        val MESSAGE_ID = "05" + "00".repeat(31)
        val OTHER_MESSAGE_ID = "06" + "00".repeat(31)
    }
}

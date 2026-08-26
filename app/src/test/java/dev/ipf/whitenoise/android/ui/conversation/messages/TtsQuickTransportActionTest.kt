package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.audio.tts.TtsError
import dev.ipf.whitenoise.android.audio.tts.errorTts
import dev.ipf.whitenoise.android.audio.tts.idleTts
import dev.ipf.whitenoise.android.audio.tts.pausedTts
import dev.ipf.whitenoise.android.audio.tts.speakingTts
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsQuickTransportActionTest {
    @Test
    fun swipingWhileThisConversationIsBeingReadHoldsIt() {
        assertEquals(
            TtsQuickTransportAction.Pause,
            ttsQuickTransportActionFor(
                state = speaking,
                ownsSession = true,
                canSpeakMessage = true,
            ),
        )
    }

    @Test
    fun swipingWhileThisConversationIsParkedCarriesOn() {
        assertEquals(
            TtsQuickTransportAction.Resume,
            ttsQuickTransportActionFor(
                state = paused,
                ownsSession = true,
                canSpeakMessage = true,
            ),
        )
    }

    @Test
    fun swipingWhileNothingIsReadingStartsAtThisMessage() {
        assertEquals(
            TtsQuickTransportAction.StartReadingMessage,
            ttsQuickTransportActionFor(
                state = idle,
                ownsSession = false,
                canSpeakMessage = true,
            ),
        )
    }

    @Test
    fun aSessionReadingSomeOtherConversationIsTakenOverNotToggled() {
        // Read-aloud is process-wide, so a session started elsewhere is still
        // playing while this conversation is on screen. The listener is looking
        // at THIS message; pausing something they cannot see would be a
        // different gesture's job.
        for (elsewhere in listOf(speaking, paused)) {
            assertEquals(
                TtsQuickTransportAction.StartReadingMessage,
                ttsQuickTransportActionFor(
                    state = elsewhere,
                    ownsSession = false,
                    canSpeakMessage = true,
                ),
            )
        }
    }

    @Test
    fun anErroredSessionIsRetriedFromThisMessageRatherThanResumed() {
        // The transport bar stays up after a synthesis failure, but there is
        // nothing to resume. Swiping again should try the message under the
        // fingers rather than do nothing at all.
        assertEquals(
            TtsQuickTransportAction.StartReadingMessage,
            ttsQuickTransportActionFor(
                state = errorTts(TtsError.Synthesis, chunkIndex = 0, chunkCount = 1),
                ownsSession = true,
                canSpeakMessage = true,
            ),
        )
    }

    @Test
    fun holdingWhatIsPlayingDoesNotNeedThisMessageToBeSpeakable() {
        // A message with nothing to say - deleted, or media with no text - is
        // still a fine place to put two fingers when the point is to pause.
        assertEquals(
            TtsQuickTransportAction.Pause,
            ttsQuickTransportActionFor(
                state = speaking,
                ownsSession = true,
                canSpeakMessage = false,
            ),
        )
        assertEquals(
            TtsQuickTransportAction.Resume,
            ttsQuickTransportActionFor(
                state = paused,
                ownsSession = true,
                canSpeakMessage = false,
            ),
        )
    }

    @Test
    fun anUnspeakableMessageWithNothingPlayingHasNothingToDo() {
        assertEquals(
            TtsQuickTransportAction.Ignore,
            ttsQuickTransportActionFor(
                state = idle,
                ownsSession = false,
                canSpeakMessage = false,
            ),
        )
    }

    private companion object {
        val speaking = speakingTts(chunkIndex = 0, chunkCount = 2)
        val paused = pausedTts(chunkIndex = 1, chunkCount = 2)
        val idle = idleTts(chunkIndex = 0, chunkCount = 0)
    }
}

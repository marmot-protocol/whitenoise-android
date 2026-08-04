package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackQueueTest {
    @Test
    fun onDoneAdvancesTheChunkAndCompletesAtChunkCount() {
        val harness = TtsQueueHarness()
        val queue = harness.queue

        queue.start(ttsMessages(ttsMessage("", "", "First.", "Second.")))
        assertEquals(
            speakingTts(0, 2, 0, 1, "First. Second.", sentenceIndex = 0, sentenceCount = 2),
            queue.state.value,
        )
        assertEquals(listOf(0, 1), harness.enqueued.map { it.first.index })

        queue.onDone(harness.utteranceId(0))
        assertEquals(
            speakingTts(1, 2, 0, 1, "First. Second.", sentenceIndex = 1, sentenceCount = 2),
            queue.state.value,
        )

        queue.onDone(harness.utteranceId(1))
        assertEquals(
            idleTts(2, 2, 1, 1, "First. Second.", sentenceIndex = 2, sentenceCount = 2),
            queue.state.value,
        )
        assertEquals(1, harness.terminalCalls)
    }

    @Test
    fun pauseThenResumeRestartsAtThePausedChunk() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("", "", "One.", "Two.", "Three.")))
        queue.onDone(harness.utteranceId(0))

        queue.pause()
        assertEquals(
            pausedTts(1, 3, 0, 1, "One. Two. Three.", sentenceIndex = 1, sentenceCount = 3),
            queue.state.value,
        )
        queue.resume()

        assertEquals(
            speakingTts(1, 3, 0, 1, "One. Two. Three.", sentenceIndex = 1, sentenceCount = 3),
            queue.state.value,
        )
        assertEquals(listOf(1, 2), harness.enqueued.takeLast(2).map { it.first.index })
        assertEquals(2, harness.stopCalls)
    }

    @Test
    fun resumeAtChangedSpeakerMessageRestoresItsAnnouncement() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "First."),
                ttsMessage("bob", "Bob", "Second."),
            ),
        )
        queue.onDone(harness.utteranceId(0))

        queue.pause()
        queue.resume()

        assertEquals(
            "Bob: Second.",
            harness.enqueued
                .last()
                .first.text,
        )
    }

    @Test
    fun networkFailureIsDistinctFromOtherSynthesisFailures() {
        listOf(TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT).forEach { errorCode ->
            val harness = TtsQueueHarness()
            val queue = harness.queue
            queue.start(ttsMessages(ttsMessage("", "", "One.")))

            queue.onError(harness.utteranceId(0), errorCode)

            val failure = queue.state.value
            assertTrue(failure is TtsState.Error)
            assertEquals(TtsError.Network, (failure as TtsState.Error).error)
            assertEquals(0, failure.chunkIndex)
            assertEquals(1, failure.chunkCount)
            assertEquals(0, failure.sentenceIndexWithinMessage)
            assertEquals(1, failure.sentenceCountWithinMessage)
        }
    }

    @Test
    fun immediateEnqueueFailureDoesNotLeavePlaybackStuck() {
        val harness = TtsQueueHarness(enqueueResult = TextToSpeech.ERROR)
        val queue = harness.queue

        queue.start(ttsMessages(ttsMessage("", "", "One.")))

        val failure = queue.state.value
        assertTrue(failure is TtsState.Error)
        assertEquals(TtsError.Synthesis, (failure as TtsState.Error).error)
        assertEquals(2, harness.stopCalls)
        assertEquals(1, harness.terminalCalls)
    }

    @Test
    fun outOfOrderCompletionCannotSkipTheCurrentChunk() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("", "", "One.", "Two.")))

        queue.onDone(harness.utteranceId(1))

        assertEquals(
            speakingTts(0, 2, 0, 1, "One. Two.", sentenceIndex = 0, sentenceCount = 2),
            queue.state.value,
        )
        queue.onDone(harness.utteranceId(0))
        assertEquals(
            speakingTts(1, 2, 0, 1, "One. Two.", sentenceIndex = 1, sentenceCount = 2),
            queue.state.value,
        )
    }

    @Test
    fun lateErrorFromACompletedChunkCannotRegressPlayback() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("", "", "One.", "Two.")))
        queue.onDone(harness.utteranceId(0))

        queue.onError(harness.utteranceId(0), TextToSpeech.ERROR_NETWORK)

        assertEquals(
            speakingTts(1, 2, 0, 1, "One. Two.", sentenceIndex = 1, sentenceCount = 2),
            queue.state.value,
        )
    }
}

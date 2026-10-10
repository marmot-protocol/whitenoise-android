package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackRecoveryTest {
    @Test
    fun laterPrebufferFailureRetainsTheEarliestUnfinishedSentenceAndRejectsOldCallbacks() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(listOf(ttsMessageWithId("m1", "alice", "Alice", "First.", "Second.")))
        val first = harness.utteranceId(0)
        val later = harness.utteranceId(1)

        queue.onError(later, TextToSpeech.ERROR_NETWORK)

        val paused = queue.state.value as TtsState.Paused
        assertEquals(0, paused.chunkIndex)
        assertEquals(TtsError.Network, paused.error)
        assertNull(paused.passage)
        assertEquals(listOf("m1"), queue.queuedMessagesSnapshot().map { it.messageIdHex })
        queue.onDone(first)
        queue.onError(first, TextToSpeech.ERROR_SYNTHESIS)
        assertEquals(paused, queue.state.value)
        assertEquals(0, harness.terminalCalls)

        queue.resume()
        assertTrue(queue.state.value is TtsState.Speaking)
        assertEquals(paused.sessionId, queue.state.value.sessionId)
        assertEquals(listOf("Alice: First.", "Second."), harness.lastSpokenTexts(2))
        assertNotEquals(first, harness.utteranceId(2))
        queue.onDone(later)
        assertEquals(0, queue.state.value.chunkIndex)
        queue.onDone(harness.utteranceId(2))
        queue.onDone(harness.utteranceId(3))
        assertTrue(queue.state.value is TtsState.Idle)
        assertEquals(1, harness.terminalCalls)
    }

    @Test
    fun arrivalsAfterFailureStayQueuedAndOnlySpeakOnExplicitResume() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(listOf(ttsMessageWithId("m1", "alice", "Alice", "First.")))
        queue.onError(harness.utteranceId(0), TextToSpeech.ERROR_SYNTHESIS)

        assertTrue(queue.append(listOf(ttsMessageWithId("m2", "bob", "Bob", "Second."))))
        assertEquals(1, harness.enqueued.size)
        assertEquals(TtsError.Synthesis, (queue.state.value as TtsState.Paused).error)
        queue.resume()
        assertEquals(listOf("Alice: First.", "Bob: Second."), harness.lastSpokenTexts(2))
    }

    @Test
    fun repeatedImmediateFailureRemainsRetryableAndStopRevokesIt() {
        val harness = TtsQueueHarness(enqueueResult = TextToSpeech.ERROR)
        val queue = harness.queue
        queue.start(listOf(ttsMessageWithId("m1", "alice", "Alice", "First.")))
        val session = queue.state.value.sessionId
        repeat(3) {
            queue.resume()
            assertTrue(queue.state.value is TtsState.Paused)
            assertEquals(session, queue.state.value.sessionId)
            assertEquals(1, queue.queuedMessagesSnapshot().size)
        }
        queue.stop()
        val idle = queue.state.value
        harness.enqueueResult = TextToSpeech.SUCCESS
        queue.resume()
        queue.onError(harness.utteranceId(0), TextToSpeech.ERROR_NETWORK)
        assertEquals(idle, queue.state.value)
        assertTrue(queue.queuedMessagesSnapshot().isEmpty())
    }

    @Test
    fun stopCallbacksDeliveredSynchronouslyCannotFinishTheInterruptedQueue() {
        var stoppedUtterance: String? = null
        lateinit var queue: TtsPlaybackQueue
        queue =
            TtsPlaybackQueue(
                stopEngine = { stoppedUtterance?.let(queue::onDone) },
                enqueue = { _, id ->
                    stoppedUtterance = id
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(listOf(ttsMessage("alice", "Alice", "First.")))
        queue.onError(stoppedUtterance, TextToSpeech.ERROR_SYNTHESIS)
        assertTrue(queue.state.value is TtsState.Paused)
        assertEquals(1, queue.queuedMessagesSnapshot().size)
    }
}

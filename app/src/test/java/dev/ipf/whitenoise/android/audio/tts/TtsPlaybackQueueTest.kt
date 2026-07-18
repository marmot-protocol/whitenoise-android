package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackQueueTest {
    @Test
    fun onDoneAdvancesTheChunkAndCompletesAtChunkCount() {
        val enqueued = mutableListOf<Pair<TtsChunk, String>>()
        var terminalCalls = 0
        val queue =
            TtsPlaybackQueue(
                stopEngine = {},
                enqueue = { chunk, utteranceId ->
                    enqueued += chunk to utteranceId
                    TextToSpeech.SUCCESS
                },
                onTerminal = { terminalCalls += 1 },
            )

        queue.start(chunks("First.", "Second."))
        assertEquals(TtsState.Speaking(chunkIndex = 0, chunkCount = 2), queue.state.value)
        assertEquals(listOf(0, 1), enqueued.map { it.first.index })

        queue.onDone(enqueued[0].second)
        assertEquals(TtsState.Speaking(chunkIndex = 1, chunkCount = 2), queue.state.value)

        queue.onDone(enqueued[1].second)
        assertEquals(TtsState.Idle(chunkIndex = 2, chunkCount = 2), queue.state.value)
        assertEquals(1, terminalCalls)
    }

    @Test
    fun pauseThenResumeRestartsAtThePausedChunk() {
        val enqueued = mutableListOf<Pair<TtsChunk, String>>()
        var stopCalls = 0
        val queue =
            TtsPlaybackQueue(
                stopEngine = { stopCalls += 1 },
                enqueue = { chunk, utteranceId ->
                    enqueued += chunk to utteranceId
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(chunks("One.", "Two.", "Three."))
        queue.onDone(enqueued[0].second)

        queue.pause()
        assertEquals(TtsState.Paused(chunkIndex = 1, chunkCount = 3), queue.state.value)
        queue.resume()

        assertEquals(TtsState.Speaking(chunkIndex = 1, chunkCount = 3), queue.state.value)
        assertEquals(listOf(1, 2), enqueued.takeLast(2).map { it.first.index })
        assertEquals(2, stopCalls)
    }

    @Test
    fun skipRequeuesFromTheAdjacentChunkAndIgnoresStaleCallbacks() {
        val enqueued = mutableListOf<Pair<TtsChunk, String>>()
        val queue =
            TtsPlaybackQueue(
                stopEngine = {},
                enqueue = { chunk, utteranceId ->
                    enqueued += chunk to utteranceId
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(chunks("One.", "Two.", "Three."))
        val staleFirstId = enqueued.first().second

        queue.skipNext()
        assertEquals(TtsState.Speaking(chunkIndex = 1, chunkCount = 3), queue.state.value)
        assertEquals(listOf(1, 2), enqueued.takeLast(2).map { it.first.index })

        queue.onDone(staleFirstId)
        assertEquals(TtsState.Speaking(chunkIndex = 1, chunkCount = 3), queue.state.value)

        queue.skipPrevious()
        assertEquals(TtsState.Speaking(chunkIndex = 0, chunkCount = 3), queue.state.value)
        assertEquals(listOf(0, 1, 2), enqueued.takeLast(3).map { it.first.index })
    }

    @Test
    fun networkFailureIsDistinctFromOtherSynthesisFailures() {
        listOf(TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT).forEach { errorCode ->
            val enqueued = mutableListOf<Pair<TtsChunk, String>>()
            val queue =
                TtsPlaybackQueue(
                    stopEngine = {},
                    enqueue = { chunk, utteranceId ->
                        enqueued += chunk to utteranceId
                        TextToSpeech.SUCCESS
                    },
                )
            queue.start(chunks("One."))

            queue.onError(enqueued.single().second, errorCode)

            val failure = queue.state.value
            assertTrue(failure is TtsState.Error)
            assertEquals(TtsError.Network, (failure as TtsState.Error).error)
            assertEquals(0, failure.chunkIndex)
            assertEquals(1, failure.chunkCount)
        }
    }

    @Test
    fun immediateEnqueueFailureDoesNotLeavePlaybackStuck() {
        var stopCalls = 0
        var terminalCalls = 0
        val queue =
            TtsPlaybackQueue(
                stopEngine = { stopCalls += 1 },
                enqueue = { _, _ -> TextToSpeech.ERROR },
                onTerminal = { terminalCalls += 1 },
            )

        queue.start(chunks("One."))

        val failure = queue.state.value
        assertTrue(failure is TtsState.Error)
        assertEquals(TtsError.Synthesis, (failure as TtsState.Error).error)
        assertEquals(2, stopCalls)
        assertEquals(1, terminalCalls)
    }

    @Test
    fun outOfOrderCompletionCannotSkipTheCurrentChunk() {
        val enqueued = mutableListOf<Pair<TtsChunk, String>>()
        val queue =
            TtsPlaybackQueue(
                stopEngine = {},
                enqueue = { chunk, utteranceId ->
                    enqueued += chunk to utteranceId
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(chunks("One.", "Two."))

        queue.onDone(enqueued[1].second)

        assertEquals(TtsState.Speaking(chunkIndex = 0, chunkCount = 2), queue.state.value)
        queue.onDone(enqueued[0].second)
        assertEquals(TtsState.Speaking(chunkIndex = 1, chunkCount = 2), queue.state.value)
    }

    @Test
    fun lateErrorFromACompletedChunkCannotRegressPlayback() {
        val enqueued = mutableListOf<Pair<TtsChunk, String>>()
        val queue =
            TtsPlaybackQueue(
                stopEngine = {},
                enqueue = { chunk, utteranceId ->
                    enqueued += chunk to utteranceId
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(chunks("One.", "Two."))
        queue.onDone(enqueued[0].second)

        queue.onError(enqueued[0].second, TextToSpeech.ERROR_NETWORK)

        assertEquals(TtsState.Speaking(chunkIndex = 1, chunkCount = 2), queue.state.value)
    }

    private fun chunks(vararg texts: String): List<TtsChunk> = texts.mapIndexed { index, text -> TtsChunk(text = text, index = index) }
}

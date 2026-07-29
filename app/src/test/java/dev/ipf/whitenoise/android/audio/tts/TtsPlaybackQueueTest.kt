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

        queue.start(messages(message("", "", "First.", "Second.")))
        assertEquals(
            speakingTts(0, 2, 0, 1, "First. Second."),
            queue.state.value,
        )
        assertEquals(listOf(0, 1), enqueued.map { it.first.index })

        queue.onDone(enqueued[0].second)
        assertEquals(
            speakingTts(1, 2, 0, 1, "First. Second."),
            queue.state.value,
        )

        queue.onDone(enqueued[1].second)
        assertEquals(
            idleTts(2, 2, 1, 1, "First. Second."),
            queue.state.value,
        )
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
        queue.start(messages(message("", "", "One.", "Two.", "Three.")))
        queue.onDone(enqueued[0].second)

        queue.pause()
        assertEquals(
            pausedTts(1, 3, 0, 1, "One. Two. Three."),
            queue.state.value,
        )
        queue.resume()

        assertEquals(
            speakingTts(1, 3, 0, 1, "One. Two. Three."),
            queue.state.value,
        )
        assertEquals(listOf(1, 2), enqueued.takeLast(2).map { it.first.index })
        assertEquals(2, stopCalls)
    }

    @Test
    fun resumeAtChangedSpeakerMessageRestoresItsAnnouncement() {
        val enqueued = mutableListOf<Pair<TtsChunk, String>>()
        val queue =
            TtsPlaybackQueue(
                stopEngine = {},
                enqueue = { chunk, utteranceId ->
                    enqueued += chunk to utteranceId
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(
            messages(
                message("alice", "Alice", "First."),
                message("bob", "Bob", "Second."),
            ),
        )
        queue.onDone(enqueued[0].second)

        queue.pause()
        queue.resume()

        assertEquals("Bob: Second.", enqueued.last().first.text)
    }

    @Test
    fun skipPreviousFromLaterSentenceOfMessageStartsPreviousMessage() {
        val enqueued = mutableListOf<Pair<TtsChunk, String>>()
        val queue =
            TtsPlaybackQueue(
                stopEngine = {},
                enqueue = { chunk, utteranceId ->
                    enqueued += chunk to utteranceId
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(
            messages(
                message("alice", "Alice", "Alpha."),
                message("bob", "Bob", "Beta one.", "Beta two.", "Beta three."),
                message("carol", "Carol", "Gamma."),
            ),
        )
        repeat(3) { queue.onDone(enqueued[it].second) }
        assertEquals(
            TtsState.Speaking(
                chunkIndex = 3,
                chunkCount = 5,
                messageIndex = 1,
                messageCount = 3,
                messagePreview = "Beta one. Beta two. Beta three.",
            ),
            queue.state.value,
        )

        queue.skipPrevious()

        assertEquals(
            speakingTts(0, 5, 0, 3, "Alpha."),
            queue.state.value,
        )
        assertEquals(listOf(0, 1, 2, 3, 4), enqueued.takeLast(5).map { it.first.index })
        assertEquals(
            "Alice: Alpha.",
            enqueued
                .takeLast(5)
                .first()
                .first.text,
        )
    }

    @Test
    fun skipNextFromFirstSentenceOfMessageStartsFollowingMessage() {
        val enqueued = mutableListOf<Pair<TtsChunk, String>>()
        val queue =
            TtsPlaybackQueue(
                stopEngine = {},
                enqueue = { chunk, utteranceId ->
                    enqueued += chunk to utteranceId
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(
            messages(
                message("alice", "Alice", "Alpha."),
                message("bob", "Bob", "Beta one.", "Beta two."),
                message("carol", "Carol", "Gamma."),
            ),
        )
        queue.onDone(enqueued[0].second)
        assertEquals(
            speakingTts(1, 4, 1, 3, "Beta one. Beta two."),
            queue.state.value,
        )

        queue.skipNext()

        assertEquals(
            speakingTts(3, 4, 2, 3, "Gamma."),
            queue.state.value,
        )
        assertEquals(listOf(3), enqueued.takeLast(1).map { it.first.index })
        assertEquals("Carol: Gamma.", enqueued.last().first.text)
    }

    @Test
    fun skipRequeuesFromTheAdjacentMessageAndIgnoresStaleCallbacks() {
        val enqueued = mutableListOf<Pair<TtsChunk, String>>()
        val queue =
            TtsPlaybackQueue(
                stopEngine = {},
                enqueue = { chunk, utteranceId ->
                    enqueued += chunk to utteranceId
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(
            messages(
                message("alice", "Alice", "One."),
                message("bob", "Bob", "Two."),
                message("carol", "Carol", "Three."),
            ),
        )
        val staleFirstId = enqueued.first().second

        queue.skipNext()
        assertEquals(
            speakingTts(1, 3, 1, 3, "Two."),
            queue.state.value,
        )
        assertEquals(listOf(1, 2), enqueued.takeLast(2).map { it.first.index })

        queue.onDone(staleFirstId)
        assertEquals(
            speakingTts(1, 3, 1, 3, "Two."),
            queue.state.value,
        )

        queue.skipPrevious()
        assertEquals(
            speakingTts(0, 3, 0, 3, "One."),
            queue.state.value,
        )
        assertEquals(listOf(0, 1, 2), enqueued.takeLast(3).map { it.first.index })
    }

    @Test
    fun skipNextAtLastMessageCompletesPlayback() {
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
        queue.start(
            messages(
                message("alice", "Alice", "One."),
                message("bob", "Bob", "Two."),
            ),
        )
        queue.onDone(enqueued[0].second)

        queue.skipNext()

        assertEquals(
            idleTts(2, 2, 2, 2, "Two."),
            queue.state.value,
        )
        assertEquals(1, terminalCalls)
    }

    @Test
    fun skipPreviousAtFirstMessageRestartsThatMessage() {
        val enqueued = mutableListOf<Pair<TtsChunk, String>>()
        val queue =
            TtsPlaybackQueue(
                stopEngine = {},
                enqueue = { chunk, utteranceId ->
                    enqueued += chunk to utteranceId
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(
            messages(
                message("alice", "Alice", "One."),
                message("bob", "Bob", "Two."),
            ),
        )

        queue.skipPrevious()

        assertEquals(
            speakingTts(0, 2, 0, 2, "One."),
            queue.state.value,
        )
        assertEquals(
            "Alice: One.",
            enqueued
                .takeLast(2)
                .first()
                .first.text,
        )
    }

    @Test
    fun pausedNavigationResumesFromSelectedMessage() {
        val enqueued = mutableListOf<Pair<TtsChunk, String>>()
        val queue =
            TtsPlaybackQueue(
                stopEngine = {},
                enqueue = { chunk, utteranceId ->
                    enqueued += chunk to utteranceId
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(
            messages(
                message("alice", "Alice", "One."),
                message("bob", "Bob", "Two.", "Three."),
            ),
        )
        queue.pause()
        queue.skipNext()

        assertEquals(
            speakingTts(1, 3, 1, 2, "Two. Three."),
            queue.state.value,
        )
        assertEquals(listOf(1, 2), enqueued.takeLast(2).map { it.first.index })
        assertEquals(
            "Bob: Two.",
            enqueued
                .takeLast(2)
                .first()
                .first.text,
        )
    }

    @Test
    fun appendAddsANavigableMessageAndUpdatesPreview() {
        val enqueued = mutableListOf<Pair<TtsChunk, String>>()
        val queue =
            TtsPlaybackQueue(
                stopEngine = {},
                enqueue = { chunk, utteranceId ->
                    enqueued += chunk to utteranceId
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(messages(message("alice", "Alice", "First.")))
        queue.append(messages(message("bob", "Bob", "Second.")))

        assertEquals(
            speakingTts(0, 2, 0, 2, "First."),
            queue.state.value,
        )

        queue.skipNext()
        assertEquals(
            speakingTts(1, 2, 1, 2, "Second."),
            queue.state.value,
        )
        assertEquals("Bob: Second.", enqueued.last().first.text)

        queue.skipPrevious()
        assertEquals(
            speakingTts(0, 2, 0, 2, "First."),
            queue.state.value,
        )
        assertEquals(
            "Alice: First.",
            enqueued
                .takeLast(2)
                .first()
                .first.text,
        )
    }

    @Test
    fun sameSenderJumpStillAnnouncesTheDestinationSpeaker() {
        val enqueued = mutableListOf<Pair<TtsChunk, String>>()
        val queue =
            TtsPlaybackQueue(
                stopEngine = {},
                enqueue = { chunk, utteranceId ->
                    enqueued += chunk to utteranceId
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(
            messages(
                message("alice", "Alice", "First."),
                message("alice", "Alice", "Second."),
            ),
        )
        queue.onDone(enqueued[0].second)
        queue.skipPrevious()

        assertEquals(
            "Alice: First.",
            enqueued
                .takeLast(2)
                .first()
                .first.text,
        )
    }

    @Test
    fun sameSenderForwardJumpStillAnnouncesTheDestinationSpeaker() {
        val enqueued = mutableListOf<Pair<TtsChunk, String>>()
        val queue =
            TtsPlaybackQueue(
                stopEngine = {},
                enqueue = { chunk, utteranceId ->
                    enqueued += chunk to utteranceId
                    TextToSpeech.SUCCESS
                },
            )
        queue.start(
            messages(
                message("alice", "Alice", "First."),
                message("alice", "Alice", "Second."),
            ),
        )

        queue.skipNext()

        assertEquals("Alice: Second.", enqueued.last().first.text)
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
            queue.start(messages(message("", "", "One.")))

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

        queue.start(messages(message("", "", "One.")))

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
        queue.start(messages(message("", "", "One.", "Two.")))

        queue.onDone(enqueued[1].second)

        assertEquals(
            speakingTts(0, 2, 0, 1, "One. Two."),
            queue.state.value,
        )
        queue.onDone(enqueued[0].second)
        assertEquals(
            speakingTts(1, 2, 0, 1, "One. Two."),
            queue.state.value,
        )
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
        queue.start(messages(message("", "", "One.", "Two.")))
        queue.onDone(enqueued[0].second)

        queue.onError(enqueued[0].second, TextToSpeech.ERROR_NETWORK)

        assertEquals(
            speakingTts(1, 2, 0, 1, "One. Two."),
            queue.state.value,
        )
    }

    private fun message(
        senderKey: String,
        senderDisplayName: String,
        vararg sentences: String,
    ): TtsQueuedMessage =
        TtsQueuedMessage(
            senderKey = senderKey,
            senderDisplayName = senderDisplayName,
            preview = sentences.joinToString(separator = " "),
            chunks = sentences.mapIndexed { index, text -> TtsChunk(text = text, index = index) },
        )

    private fun messages(vararg messages: TtsQueuedMessage): List<TtsQueuedMessage> = messages.toList()
}

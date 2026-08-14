package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackQueueTest {
    @Test
    fun restartingTheSamePassagePublishesANewSessionAndRetainsItsTimelinePosition() {
        val harness = TtsQueueHarness()
        val message = mappedMessage("m1", "Alice", "Hello.", timelineAt = 42uL)

        harness.queue.start(listOf(message))
        val first = harness.queue.state.value
        harness.queue.start(listOf(message))
        val restarted = harness.queue.state.value

        assertNotEquals(first.sessionId, restarted.sessionId)
        assertEquals(42uL, restarted.passage?.timelineAt)
    }

    @Test
    fun activeRangePublishesAStableVisibleWordAfterSenderPrefixNormalization() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(listOf(mappedMessage("m1", "Alice", "Hello world.")))
        val submitted = harness.enqueued.single().first

        assertEquals("Alice: Hello world.", submitted.text)
        assertEquals(TtsTextRange(0, 7), submitted.senderPrefix)
        assertEquals(TtsPassage("m1", 0), queue.state.value.passage)

        queue.onRangeStart(harness.utteranceId(0), start = 13, end = 18, frame = 0)

        assertEquals(
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                visibleWord = listOf(TtsVisibleTextSpan("plain", 6, 11)),
            ),
            queue.state.value.passage,
        )
    }

    @Test
    fun oneVisibleWordCanSpanFormattingLeaves() {
        val harness = TtsQueueHarness()
        val queued = mappedMessage("m1", "", "important.")
        val message =
            queued.copy(
                chunks =
                    listOf(
                        queued.chunks.single().copy(
                            visibleSpans =
                                listOf(
                                    TtsSpokenTextSpan(
                                        TtsTextRange(0, 5),
                                        TtsVisibleTextSpan("b0/n0", 0, 5),
                                    ),
                                    TtsSpokenTextSpan(
                                        TtsTextRange(5, 9),
                                        TtsVisibleTextSpan("b0/n1/n0", 0, 4),
                                    ),
                                ),
                        ),
                    ),
            )
        harness.queue.start(listOf(message))

        harness.queue.onRangeStart(harness.utteranceId(0), 0, 9, 0)

        assertEquals(
            listOf(
                TtsVisibleTextSpan("b0/n0", 0, 5),
                TtsVisibleTextSpan("b0/n1/n0", 0, 4),
            ),
            harness.queue.state.value.passage
                ?.visibleWord,
        )
    }

    @Test
    fun invalidActiveRangesFallBackWhileStaleRangesAreInert() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(listOf(mappedMessage("m1", "", "Hello e\u0301clair world.")))
        val staleId = harness.utteranceId(0)

        queue.onRangeStart(staleId, 0, 5, 0)
        val hello = queue.state.value.passage
        assertTrue(hello?.visibleWord?.isNotEmpty() == true)
        queue.onRangeStart("malformed", 0, 1, 0)
        assertEquals(hello, queue.state.value.passage)

        listOf(
            -1 to 2,
            0 to 100,
            0 to 11,
            5 to 6,
            6 to 7,
            13 to 14,
        ).forEach { (start, end) ->
            queue.onRangeStart(staleId, start, end, 0)
            assertEquals(TtsPassage("m1", 0), queue.state.value.passage)
        }

        queue.pause()
        val frozen = queue.state.value.passage
        queue.resume()
        assertEquals(TtsPassage("m1", 0), queue.state.value.passage)
        queue.onRangeStart(staleId, 0, 5, 0)
        assertEquals(TtsPassage("m1", 0), queue.state.value.passage)
        assertTrue(frozen != null)
    }

    @Test
    fun pauseFreezesTheLastWordWhileResumeNavigationAndAppendResetToSentenceFallback() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(listOf(mappedMessage("m1", "", "First. Second.")))
        val firstId = harness.utteranceId(0)
        queue.onRangeStart(firstId, 0, 5, 0)
        val word = queue.state.value.passage

        queue.pause()
        assertEquals(word, queue.state.value.passage)
        queue.resume()
        assertEquals(TtsPassage("m1", 0), queue.state.value.passage)
        queue.onRangeStart(firstId, 0, 5, 0)
        assertEquals(TtsPassage("m1", 0), queue.state.value.passage)

        assertEquals(TtsNavigationOutcome.Moved, queue.skipNextSentence())
        assertEquals(TtsPassage("m1", 1), queue.state.value.passage)
        queue.onRangeStart(harness.enqueued.last().second, 0, 6, 0)
        assertTrue(
            queue.state.value.passage
                ?.visibleWord
                ?.isNotEmpty() == true,
        )

        assertTrue(queue.append(listOf(mappedMessage("m2", "Bob", "Third."))))
        assertEquals(TtsPassage("m1", 1), queue.state.value.passage)
    }

    @Test
    fun completionErrorStopAndRequeueClearWordRangeState() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(listOf(mappedMessage("m1", "", "One. Two.")))
        queue.onRangeStart(harness.utteranceId(0), 0, 3, 0)
        assertTrue(
            queue.state.value.passage
                ?.visibleWord
                ?.isNotEmpty() == true,
        )

        queue.onDone(harness.utteranceId(0))
        assertEquals(TtsPassage("m1", 1), queue.state.value.passage)
        val secondId = harness.utteranceId(1)
        queue.onRangeStart(secondId, 0, 3, 0)
        queue.refreshPendingChunksAtNextBoundary()
        queue.onDone(secondId)
        assertTrue(queue.state.value is TtsState.Idle)
        assertEquals(null, queue.state.value.passage)

        queue.start(listOf(mappedMessage("m2", "", "Error.")))
        queue.onRangeStart(harness.utteranceId(2), 0, 5, 0)
        queue.onError(harness.utteranceId(2), TextToSpeech.ERROR)
        assertEquals(null, queue.state.value.passage)

        queue.start(listOf(mappedMessage("m3", "", "Stop.")))
        queue.onRangeStart(harness.utteranceId(3), 0, 4, 0)
        queue.stop()
        assertEquals(null, queue.state.value.passage)
    }

    @Test
    fun hardSplitFragmentsNeverPublishAsCompleteVisibleWords() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            listOf(
                mappedMessage(
                    id = "m1",
                    sender = "",
                    text = "supercalifragilisticexpialidocious",
                    maxChunkLength = 8,
                    projectionId = "projection-m1",
                ),
            ),
        )

        val first = harness.enqueued.first()
        assertEquals("supercal", first.first.text)
        queue.onRangeStart(first.second, 0, first.first.text.length, 0)

        assertEquals(TtsPassage("m1", 0, "projection-m1"), queue.state.value.passage)
    }

    @Test
    fun hardSplitThroughACombiningClusterNeverPublishesThePartialCluster() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            listOf(
                mappedMessage(
                    id = "m1",
                    sender = "",
                    text = "abcdefge\u0301",
                    maxChunkLength = 8,
                    projectionId = "projection-m1",
                ),
            ),
        )

        val first = harness.enqueued.first()
        assertEquals("abcdefge", first.first.text)
        queue.onRangeStart(first.second, 0, first.first.text.length, 0)

        assertEquals(TtsPassage("m1", 0, "projection-m1"), queue.state.value.passage)
    }

    @Test
    fun rangeThatBisectsASurrogatePairUsesTheSentenceFallback() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            listOf(
                mappedMessage(
                    id = "m1",
                    sender = "",
                    text = "\uD801\uDC00word.",
                    projectionId = "projection-m1",
                ),
            ),
        )

        queue.onRangeStart(harness.utteranceId(0), 0, 1, 0)

        assertEquals(TtsPassage("m1", 0, "projection-m1"), queue.state.value.passage)
    }

    @Test
    fun rateRequeueRejectsOldRangesAndPublishesTheNewGenerationFallback() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(listOf(mappedMessage("m1", "", "One. Two. Three.", projectionId = "projection-m1")))
        val firstGenerationSecond = harness.enqueued[1].second

        queue.refreshPendingChunksAtNextBoundary()
        queue.onDone(harness.utteranceId(0))

        assertEquals(TtsPassage("m1", 1, "projection-m1"), queue.state.value.passage)
        queue.onRangeStart(firstGenerationSecond, 0, 3, 0)
        assertEquals(TtsPassage("m1", 1, "projection-m1"), queue.state.value.passage)

        val requeuedSecond = harness.enqueued[harness.enqueued.size - 2]
        queue.onRangeStart(requeuedSecond.second, 0, 3, 0)
        assertTrue(
            queue.state.value.passage
                ?.visibleWord
                ?.isNotEmpty() == true,
        )
    }

    @Test
    fun windowReplacementChangesProjectionIdentityAndRejectsTheOldRange() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(listOf(mappedMessage("m1", "", "Old word.", projectionId = "projection-m1-original")))
        val staleId = harness.enqueued.single().second
        queue.onRangeStart(staleId, 0, 3, 0)
        assertTrue(
            queue.state.value.passage
                ?.visibleWord
                ?.isNotEmpty() == true,
        )

        val replacement = mappedMessage("m1", "", "New word.", projectionId = "projection-m1-edited")
        assertTrue(
            queue.replaceWindow(
                window = listOf(replacement),
                targetMessageIdHex = "m1",
                targetSentence = TtsWindowSentenceTarget.First,
            ),
        )

        assertEquals(TtsPassage("m1", 0, "projection-m1-edited"), queue.state.value.passage)
        queue.onRangeStart(staleId, 0, 3, 0)
        assertEquals(TtsPassage("m1", 0, "projection-m1-edited"), queue.state.value.passage)
    }

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

    @Test
    fun pauseClearsAPendingRateRefreshSoResumeDoesNotRestartAtTheBoundary() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("", "", "One.", "Two.", "Three.")))
        queue.refreshPendingChunksAtNextBoundary()
        queue.pause()
        queue.resume()
        val enqueuedAfterResume = harness.enqueued.size
        val stopsAfterResume = harness.stopCalls

        // First utterance of the resumed generation completes — a leaked
        // refresh flag would needlessly stop and re-enqueue everything here.
        queue.onDone(harness.utteranceId(3))

        assertEquals(
            speakingTts(1, 3, 0, 1, "One. Two. Three.", sentenceIndex = 1, sentenceCount = 3),
            queue.state.value,
        )
        assertEquals(enqueuedAfterResume, harness.enqueued.size)
        assertEquals(stopsAfterResume, harness.stopCalls)
    }

    @Test
    fun aMessageWithoutChunksCannotEnterTheQueue() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        val empty =
            TtsQueuedMessage(
                senderKey = "alice",
                senderDisplayName = "Alice",
                preview = "",
                chunks = emptyList(),
            )

        assertThrows(IllegalArgumentException::class.java) { queue.start(listOf(empty)) }

        val appendHarness = TtsQueueHarness()
        appendHarness.queue.start(ttsMessages(ttsMessage("bob", "Bob", "One.")))
        assertThrows(IllegalArgumentException::class.java) { appendHarness.queue.append(listOf(empty)) }
    }

    @Test
    fun staleCallbacksAreIgnoredAfterRestartingAtLaterSentence() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        val message = ttsMessage("alice", "Alice", "One.", "Two.", "Three.")
        queue.start(listOf(message))
        val staleUtterance = harness.utteranceId(0)

        queue.start(listOf(message), startSentenceIndex = 2)

        queue.onDone(staleUtterance)

        assertEquals(
            speakingTts(
                2,
                3,
                0,
                1,
                "One. Two. Three.",
                sentenceIndex = 2,
                sentenceCount = 3,
                messageProgressGeneration = 2,
            ).copy(sessionId = 1),
            queue.state.value,
        )
    }

    private fun mappedMessage(
        id: String,
        sender: String,
        text: String,
        maxChunkLength: Int = 4_000,
        projectionId: String = "",
        timelineAt: ULong = 0uL,
    ): TtsQueuedMessage {
        val chunks = TtsChunker.chunk(text, java.util.Locale.US, maxChunkLength = maxChunkLength)
        return TtsQueuedMessage(
            senderKey = "alice",
            senderDisplayName = sender,
            preview = text,
            messageIdHex = id,
            projectionId = projectionId,
            timelineAt = timelineAt,
            chunks =
                chunks.map { chunk ->
                    chunk.copy(
                        messageIdHex = id,
                        projectionId = projectionId,
                        timelineAt = timelineAt,
                        visibleSpans =
                            listOf(
                                TtsSpokenTextSpan(
                                    spoken = TtsTextRange(0, chunk.text.length),
                                    visible = TtsVisibleTextSpan("plain", chunk.sourceStart, chunk.sourceEnd),
                                ),
                            ),
                    )
                },
        )
    }
}

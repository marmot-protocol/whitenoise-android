package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsPlaybackQueueNavigationTest {
    @Test
    fun skipPreviousMessageFromLaterSentenceStartsPreviousMessage() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "Alpha."),
                ttsMessage("bob", "Bob", "Beta one.", "Beta two.", "Beta three."),
                ttsMessage("carol", "Carol", "Gamma."),
            ),
        )
        repeat(3) { queue.onDone(harness.utteranceId(it)) }
        assertEquals(
            TtsState.Speaking(
                chunkIndex = 3,
                chunkCount = 5,
                messageIndex = 1,
                messageCount = 3,
                sentenceIndexWithinMessage = 2,
                sentenceCountWithinMessage = 3,
                messagePreview = "Beta one. Beta two. Beta three.",
            ),
            queue.state.value,
        )

        queue.skipPreviousMessage()

        assertEquals(
            speakingTts(0, 5, 0, 3, "Alpha.", sentenceIndex = 0, sentenceCount = 1),
            queue.state.value,
        )
        assertEquals(listOf(0, 1, 2, 3, 4), harness.enqueued.takeLast(5).map { it.first.index })
        assertEquals("Alice: Alpha.", harness.lastSpokenTexts(5).first())
    }

    @Test
    fun skipNextMessageFromFirstSentenceStartsFollowingMessage() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "Alpha."),
                ttsMessage("bob", "Bob", "Beta one.", "Beta two."),
                ttsMessage("carol", "Carol", "Gamma."),
            ),
        )
        queue.onDone(harness.utteranceId(0))
        assertEquals(
            speakingTts(1, 4, 1, 3, "Beta one. Beta two.", sentenceIndex = 0, sentenceCount = 2),
            queue.state.value,
        )

        queue.skipNextMessage()

        assertEquals(
            speakingTts(3, 4, 2, 3, "Gamma.", sentenceIndex = 0, sentenceCount = 1),
            queue.state.value,
        )
        assertEquals(listOf(3), harness.enqueued.takeLast(1).map { it.first.index })
        assertEquals(
            "Carol: Gamma.",
            harness.enqueued
                .last()
                .first.text,
        )
    }

    @Test
    fun skipRequeuesFromTheAdjacentMessageAndIgnoresStaleCallbacks() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "One."),
                ttsMessage("bob", "Bob", "Two."),
                ttsMessage("carol", "Carol", "Three."),
            ),
        )
        val staleFirstId = harness.utteranceId(0)

        queue.skipNextMessage()
        assertEquals(
            speakingTts(1, 3, 1, 3, "Two."),
            queue.state.value,
        )
        assertEquals(listOf(1, 2), harness.enqueued.takeLast(2).map { it.first.index })

        queue.onDone(staleFirstId)
        assertEquals(
            speakingTts(1, 3, 1, 3, "Two."),
            queue.state.value,
        )

        queue.skipPreviousMessage()
        assertEquals(
            speakingTts(0, 3, 0, 3, "One."),
            queue.state.value,
        )
        assertEquals(listOf(0, 1, 2), harness.enqueued.takeLast(3).map { it.first.index })
    }

    @Test
    fun skipNextMessageAtLastMessageCompletesPlayback() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "One."),
                ttsMessage("bob", "Bob", "Two."),
            ),
        )
        queue.onDone(harness.utteranceId(0))

        queue.skipNextMessage()

        assertEquals(
            idleTts(2, 2, 2, 2, "Two.", sentenceIndex = 1, sentenceCount = 1),
            queue.state.value,
        )
        assertEquals(1, harness.terminalCalls)
    }

    @Test
    fun skipPreviousMessageAtFirstMessageRestartsThatMessage() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "One."),
                ttsMessage("bob", "Bob", "Two."),
            ),
        )

        queue.skipPreviousMessage()

        assertEquals(
            speakingTts(0, 2, 0, 2, "One."),
            queue.state.value,
        )
        assertEquals("Alice: One.", harness.lastSpokenTexts(2).first())
    }

    @Test
    fun skipNextSentenceMovesOneSentenceWithinTheMessageWithoutAnnouncement() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("alice", "Alice", "One.", "Two.", "Three.")))

        queue.skipNextSentence()

        assertEquals(
            speakingTts(1, 3, 0, 1, "One. Two. Three.", sentenceIndex = 1, sentenceCount = 3),
            queue.state.value,
        )
        assertEquals(listOf(1, 2), harness.enqueued.takeLast(2).map { it.first.index })
        assertEquals("Two.", harness.lastSpokenTexts(2).first())
    }

    @Test
    fun skipPreviousSentenceBackToTheMessageFirstSentenceDoesNotReannounce() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "Alpha."),
                ttsMessage("bob", "Bob", "Beta one.", "Beta two."),
            ),
        )
        queue.onDone(harness.utteranceId(0))
        queue.onDone(harness.utteranceId(1))
        assertEquals(
            speakingTts(2, 3, 1, 2, "Beta one. Beta two.", sentenceIndex = 1, sentenceCount = 2),
            queue.state.value,
        )

        queue.skipPreviousSentence()

        assertEquals(
            speakingTts(1, 3, 1, 2, "Beta one. Beta two.", sentenceIndex = 0, sentenceCount = 2),
            queue.state.value,
        )
        assertEquals("Beta one.", harness.lastSpokenTexts(2).first())
    }

    @Test
    fun aSentenceSplitAcrossChunksIsOneNavigationStepEachWay() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "Alpha."),
                ttsMessageWithChunks(
                    senderKey = "bob",
                    senderDisplayName = "Bob",
                    preview = "Long sentence. Short.",
                    chunks = listOf("Long part one" to 0, "long part two" to 0, "Short." to 1),
                ),
            ),
        )
        queue.onDone(harness.utteranceId(0))
        queue.onDone(harness.utteranceId(1))
        // Mid split sentence: both chunks report the same logical sentence.
        assertEquals(
            speakingTts(2, 4, 1, 2, "Long sentence. Short.", sentenceIndex = 0, sentenceCount = 2),
            queue.state.value,
        )

        queue.skipNextSentence()
        assertEquals(
            speakingTts(3, 4, 1, 2, "Long sentence. Short.", sentenceIndex = 1, sentenceCount = 2),
            queue.state.value,
        )
        assertEquals(
            "Short.",
            harness.enqueued
                .last()
                .first.text,
        )

        queue.skipPreviousSentence()
        assertEquals(
            speakingTts(1, 4, 1, 2, "Long sentence. Short.", sentenceIndex = 0, sentenceCount = 2),
            queue.state.value,
        )
        assertEquals("Long part one", harness.lastSpokenTexts(3).first())
    }

    @Test
    fun skipNextSentenceAcrossTheMessageBoundaryAnnouncesTheNewSender() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "A one.", "A two."),
                ttsMessage("alice", "Alice", "B one."),
            ),
        )
        queue.onDone(harness.utteranceId(0))

        // Crossing from a non-first sentence still announces exactly once.
        queue.skipNextSentence()

        assertEquals(
            speakingTts(2, 3, 1, 2, "B one.", sentenceIndex = 0, sentenceCount = 1),
            queue.state.value,
        )
        assertEquals(
            "Alice: B one.",
            harness.enqueued
                .last()
                .first.text,
        )
    }

    @Test
    fun skipPreviousSentenceAcrossTheBoundaryTargetsTheFinalSentenceAndAnnounces() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "A one.", "A two."),
                ttsMessage("bob", "Bob", "B one."),
            ),
        )
        queue.onDone(harness.utteranceId(0))
        queue.onDone(harness.utteranceId(1))
        assertEquals(
            speakingTts(2, 3, 1, 2, "B one.", sentenceIndex = 0, sentenceCount = 1),
            queue.state.value,
        )

        queue.skipPreviousSentence()

        assertEquals(
            speakingTts(1, 3, 0, 2, "A one. A two.", sentenceIndex = 1, sentenceCount = 2),
            queue.state.value,
        )
        // The announcement was consumed by the mid-message target; the next
        // message keeps its own announcement.
        assertEquals(listOf("Alice: A two.", "Bob: B one."), harness.lastSpokenTexts(2))
    }

    @Test
    fun skipNextSentenceAtTheFinalQueueSentenceCompletesPlayback() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "One."),
                ttsMessage("bob", "Bob", "Two.", "Three."),
            ),
        )
        repeat(2) { queue.onDone(harness.utteranceId(it)) }

        queue.skipNextSentence()

        assertEquals(
            idleTts(3, 3, 2, 2, "Two. Three.", sentenceIndex = 2, sentenceCount = 2),
            queue.state.value,
        )
        assertEquals(1, harness.terminalCalls)
    }

    @Test
    fun skipPreviousSentenceAtTheFirstQueueSentenceRestartsTheFirstChunk() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("alice", "Alice", "One.", "Two.")))

        queue.skipPreviousSentence()

        assertEquals(
            speakingTts(0, 2, 0, 1, "One. Two.", sentenceIndex = 0, sentenceCount = 2),
            queue.state.value,
        )
        assertEquals("Alice: One.", harness.lastSpokenTexts(2).first())
    }

    @Test
    fun sentenceSkipRequeuesIgnoreStaleDoneAndErrorCallbacks() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("alice", "Alice", "One.", "Two.", "Three.")))
        val staleFirstId = harness.utteranceId(0)

        queue.skipNextSentence()
        val repositioned = speakingTts(1, 3, 0, 1, "One. Two. Three.", sentenceIndex = 1, sentenceCount = 3)
        assertEquals(repositioned, queue.state.value)

        queue.onDone(staleFirstId)
        assertEquals(repositioned, queue.state.value)

        queue.onError(staleFirstId, TextToSpeech.ERROR_NETWORK)
        assertEquals(repositioned, queue.state.value)
    }

    @Test
    fun pausedMessageNavigationRepositionsWithoutSpeakingUntilResume() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "One."),
                ttsMessage("bob", "Bob", "Two.", "Three."),
            ),
        )
        queue.pause()
        val enqueuedBeforeNavigation = harness.enqueued.size

        queue.skipNextMessage()

        assertEquals(
            pausedTts(1, 3, 1, 2, "Two. Three.", sentenceIndex = 0, sentenceCount = 2),
            queue.state.value,
        )
        assertEquals(enqueuedBeforeNavigation, harness.enqueued.size)

        queue.resume()

        assertEquals(
            speakingTts(1, 3, 1, 2, "Two. Three.", sentenceIndex = 0, sentenceCount = 2),
            queue.state.value,
        )
        assertEquals(listOf(1, 2), harness.enqueued.takeLast(2).map { it.first.index })
        assertEquals("Bob: Two.", harness.lastSpokenTexts(2).first())
    }

    @Test
    fun pausedSentenceNavigationWithinTheMessageStaysUnannouncedOnResume() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("alice", "Alice", "One.", "Two.", "Three.")))
        queue.onDone(harness.utteranceId(0))
        queue.pause()
        val enqueuedBeforeNavigation = harness.enqueued.size

        queue.skipPreviousSentence()

        assertEquals(
            pausedTts(0, 3, 0, 1, "One. Two. Three.", sentenceIndex = 0, sentenceCount = 3),
            queue.state.value,
        )
        assertEquals(enqueuedBeforeNavigation, harness.enqueued.size)

        queue.resume()

        assertEquals(
            speakingTts(0, 3, 0, 1, "One. Two. Three.", sentenceIndex = 0, sentenceCount = 3),
            queue.state.value,
        )
        assertEquals("One.", harness.lastSpokenTexts(3).first())
    }

    @Test
    fun pausedCrossMessageAnnouncementSurvivesALaterSameMessageReposition() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "One."),
                ttsMessage("bob", "Bob", "Two.", "Three."),
            ),
        )
        queue.pause()

        queue.skipNextMessage()
        queue.skipNextSentence()
        assertEquals(
            pausedTts(2, 3, 1, 2, "Two. Three.", sentenceIndex = 1, sentenceCount = 2),
            queue.state.value,
        )

        queue.resume()

        // Bob's sender was never spoken, so the deferred announcement lands
        // on the resumed sentence even though the last step stayed in-message.
        assertEquals(
            "Bob: Three.",
            harness.enqueued
                .last()
                .first.text,
        )
    }

    @Test
    fun pausedNavigationToTheFinalMessageEndCompletesWithoutSpeaking() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("alice", "Alice", "One.")))
        queue.pause()
        val enqueuedBeforeNavigation = harness.enqueued.size

        queue.skipNextMessage()

        assertEquals(
            idleTts(1, 1, 1, 1, "One.", sentenceIndex = 1, sentenceCount = 1),
            queue.state.value,
        )
        assertEquals(enqueuedBeforeNavigation, harness.enqueued.size)
        assertEquals(1, harness.terminalCalls)
    }

    @Test
    fun appendAddsANavigableMessageAndUpdatesPreview() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("alice", "Alice", "First.")))
        queue.append(ttsMessages(ttsMessage("bob", "Bob", "Second.")))

        assertEquals(
            speakingTts(0, 2, 0, 2, "First."),
            queue.state.value,
        )

        queue.skipNextMessage()
        assertEquals(
            speakingTts(1, 2, 1, 2, "Second."),
            queue.state.value,
        )
        assertEquals(
            "Bob: Second.",
            harness.enqueued
                .last()
                .first.text,
        )

        queue.skipPreviousMessage()
        assertEquals(
            speakingTts(0, 2, 0, 2, "First."),
            queue.state.value,
        )
        assertEquals("Alice: First.", harness.lastSpokenTexts(2).first())
    }

    @Test
    fun appendedMessagesParticipateInSentenceNavigationWithoutStaleCounts() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("alice", "Alice", "First.")))
        queue.append(ttsMessages(ttsMessage("bob", "Bob", "Second.", "Third.")))

        queue.skipNextSentence()
        assertEquals(
            speakingTts(1, 3, 1, 2, "Second. Third.", sentenceIndex = 0, sentenceCount = 2),
            queue.state.value,
        )
        assertEquals(listOf("Bob: Second.", "Third."), harness.lastSpokenTexts(2))

        queue.skipNextSentence()
        assertEquals(
            speakingTts(2, 3, 1, 2, "Second. Third.", sentenceIndex = 1, sentenceCount = 2),
            queue.state.value,
        )
        assertEquals(
            "Third.",
            harness.enqueued
                .last()
                .first.text,
        )
    }

    @Test
    fun sameSenderJumpStillAnnouncesTheDestinationSpeaker() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "First."),
                ttsMessage("alice", "Alice", "Second."),
            ),
        )
        queue.onDone(harness.utteranceId(0))
        queue.skipPreviousMessage()

        assertEquals("Alice: First.", harness.lastSpokenTexts(2).first())
    }

    @Test
    fun sameSenderForwardJumpStillAnnouncesTheDestinationSpeaker() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "First."),
                ttsMessage("alice", "Alice", "Second."),
            ),
        )

        queue.skipNextMessage()

        assertEquals(
            "Alice: Second.",
            harness.enqueued
                .last()
                .first.text,
        )
    }
}

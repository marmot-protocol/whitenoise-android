package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Edge deferral and identity-keyed window replacement for the playback queue —
 * the affordances history paging builds on.
 */
class TtsPlaybackQueueWindowTest {
    @Test
    fun deferredNextMessageAtTailReportsTheEdgeWithoutCompleting() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessageWithId("a", "alice", "Alice", "One."),
                ttsMessageWithId("b", "bob", "Bob", "Two."),
            ),
        )
        queue.onDone(harness.utteranceId(0))
        val stateAtTail = queue.state.value

        assertEquals(TtsNavigationOutcome.AtNewerEdge, queue.skipNextMessage(deferAtEdge = true))

        assertEquals(stateAtTail, queue.state.value)
        assertEquals(0, harness.terminalCalls)

        // Without deferral the same position keeps its natural completion.
        assertEquals(TtsNavigationOutcome.Completed, queue.skipNextMessage())
        assertEquals(1, harness.terminalCalls)
    }

    @Test
    fun deferredPreviousMessageAtHeadReportsTheEdgeWithoutMoving() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessageWithId("a", "alice", "Alice", "One."),
                ttsMessageWithId("b", "bob", "Bob", "Two."),
            ),
        )
        val stateAtHead = queue.state.value
        val enqueuedBefore = harness.enqueued.size

        assertEquals(TtsNavigationOutcome.AtOlderEdge, queue.skipPreviousMessage(deferAtEdge = true))

        assertEquals(stateAtHead, queue.state.value)
        assertEquals(enqueuedBefore, harness.enqueued.size)
    }

    @Test
    fun deferredSentenceNavigationCrossesOnlyAtRealBoundaries() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessageWithChunks(
                    senderKey = "alice",
                    senderDisplayName = "Alice",
                    preview = "One long. Two.",
                    chunks = listOf("One" to 0, "long." to 0, "Two." to 1),
                ),
            ),
        )

        // Mid-first-sentence: previous restarts the sentence within the window.
        queue.onDone(harness.utteranceId(0))
        assertEquals(TtsNavigationOutcome.Moved, queue.skipPreviousSentence(deferAtEdge = true))
        assertEquals(0, queue.state.value.chunkIndex)

        // The very first chunk is a genuine older boundary.
        assertEquals(TtsNavigationOutcome.AtOlderEdge, queue.skipPreviousSentence(deferAtEdge = true))
        assertEquals(0, queue.state.value.chunkIndex)

        // The last sentence is a genuine newer boundary.
        assertEquals(TtsNavigationOutcome.Moved, queue.skipNextSentence(deferAtEdge = true))
        assertEquals(TtsNavigationOutcome.AtNewerEdge, queue.skipNextSentence(deferAtEdge = true))
        assertEquals(0, harness.terminalCalls)
    }

    @Test
    fun aDeferredTerminalParksOnlyAfterTheMessagesLastSentence() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        val preview = "One. Two. Three."
        queue.start(ttsMessages(ttsMessageWithId("a", "alice", "Alice", "One.", "Two.", "Three.")))
        assertEquals(TtsNavigationOutcome.AtNewerEdge, queue.skipNextMessage(deferAtEdge = true))

        queue.onDone(harness.utteranceId(0))
        assertEquals(speakingTts(1, 3, 0, 1, preview, sentenceIndex = 1, sentenceCount = 3), queue.state.value)
        // The sentence before the last still advances normally: parking here
        // would report sentence 2 of 3 while the third one plays.
        queue.onDone(harness.utteranceId(1))
        assertEquals(speakingTts(2, 3, 0, 1, preview, sentenceIndex = 2, sentenceCount = 3), queue.state.value)

        queue.onDone(harness.utteranceId(2))

        // Only the real terminal parks, and parking is not completing.
        assertEquals(speakingTts(2, 3, 0, 1, preview, sentenceIndex = 2, sentenceCount = 3), queue.state.value)
        assertEquals(0, harness.terminalCalls)
    }

    @Test
    fun appendingBehindAParkedTerminalResumesOnTheFirstAppendedSentence() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessageWithId("a", "alice", "Alice", "One.")))
        assertEquals(TtsNavigationOutcome.AtNewerEdge, queue.skipNextMessage(deferAtEdge = true))
        queue.onDone(harness.utteranceId(0))

        val appended = queue.append(ttsMessages(ttsMessageWithId("b", "bob", "Bob", "Two.", "Three.", "Four.")))

        // The parked chunk was already spoken, so progress moves onto the
        // arrival's FIRST sentence — its last would skip everything before it.
        assertEquals(true, appended)
        assertEquals(
            speakingTts(1, 4, 1, 2, "Two. Three. Four.", sentenceIndex = 0, sentenceCount = 3),
            queue.state.value,
        )
        assertEquals(listOf("Bob: Two.", "Three.", "Four."), harness.lastSpokenTexts(3))
        assertEquals(0, harness.terminalCalls)
    }

    @Test
    fun aRetainedSettlePausesAParkedTerminalAndKeepsItsCursor() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessageWithId("a", "alice", "Alice", "One.", "Two.")))
        assertEquals(TtsNavigationOutcome.AtNewerEdge, queue.skipNextMessage(deferAtEdge = true))
        queue.onDone(harness.utteranceId(0))
        queue.onDone(harness.utteranceId(1))

        queue.settleEdgeRequest(TtsEdgeSettlement.Retained)

        // Nothing is left to speak, so the session parks as a pause the user
        // can resume or re-tap from — never as silent playback.
        assertEquals(
            pausedTts(1, 2, 0, 1, "One. Two.", sentenceIndex = 1, sentenceCount = 2),
            queue.state.value,
        )
        assertEquals(0, harness.terminalCalls)

        queue.resume()

        assertEquals(speakingTts(1, 2, 0, 1, "One. Two.", sentenceIndex = 1, sentenceCount = 2), queue.state.value)
        assertEquals(listOf("Two."), harness.lastSpokenTexts(1))
    }

    @Test
    fun aSettleForARequestTheQueueMovedPastIsInert() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessageWithId("a", "alice", "Alice", "One.", "Two.")))
        assertEquals(TtsNavigationOutcome.AtNewerEdge, queue.skipNextMessage(deferAtEdge = true))
        // An undeferred tap ends the session while that request is still armed.
        assertEquals(TtsNavigationOutcome.Completed, queue.skipNextMessage())
        val completed = queue.state.value
        val enqueuedBefore = harness.enqueued.size

        // Every reset advances the generation, so a verdict for a request the
        // queue has already moved past restarts and ends nothing.
        queue.settleEdgeRequest(TtsEdgeSettlement.RestartedWindow)

        assertEquals(completed, queue.state.value)
        assertEquals(enqueuedBefore, harness.enqueued.size)
        assertEquals(1, harness.terminalCalls)
    }

    @Test
    fun replaceWindowWhileSpeakingLandsOnTargetAndKeepsAnnouncementIdentity() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessageWithId("b", "bob", "Bob", "Beta."),
                ttsMessageWithId("c", "carol", "Carol", "Gamma."),
            ),
        )

        val replaced =
            queue.replaceWindow(
                window =
                    ttsMessages(
                        ttsMessageWithId("a", "alice", "Alice", "Alpha."),
                        ttsMessageWithId("b", "bob", "Bob", "Beta."),
                        ttsMessageWithId("c", "carol", "Carol", "Gamma."),
                    ),
                targetMessageIdHex = "a",
                targetSentence = TtsWindowSentenceTarget.First,
            )

        assertEquals(true, replaced)
        assertEquals(speakingTts(0, 3, 0, 3, "Alpha."), queue.state.value)
        // Same contract as message navigation: the engine restarts at the
        // target and each sender change announces afresh.
        assertEquals(listOf("Alice: Alpha.", "Bob: Beta.", "Carol: Gamma."), harness.lastSpokenTexts(3))
    }

    @Test
    fun replaceWindowWhilePausedMovesTheCursorAndAnnouncesOnResume() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessageWithId("b", "bob", "Bob", "Beta."),
                ttsMessageWithId("c", "carol", "Carol", "Gamma."),
            ),
        )
        queue.pause()
        val enqueuedBefore = harness.enqueued.size

        val replaced =
            queue.replaceWindow(
                window =
                    ttsMessages(
                        ttsMessageWithId("a", "alice", "Alice", "Alpha."),
                        ttsMessageWithId("b", "bob", "Bob", "Beta."),
                        ttsMessageWithId("c", "carol", "Carol", "Gamma."),
                    ),
                targetMessageIdHex = "a",
                targetSentence = TtsWindowSentenceTarget.First,
            )

        assertEquals(true, replaced)
        assertEquals(pausedTts(0, 3, 0, 3, "Alpha."), queue.state.value)
        assertEquals(enqueuedBefore, harness.enqueued.size)

        queue.resume()

        assertEquals(speakingTts(0, 3, 0, 3, "Alpha."), queue.state.value)
        assertEquals("Alice: Alpha.", harness.lastSpokenTexts(3).first())
    }

    @Test
    fun replaceWindowLastSentenceTargetLandsOnTheTargetsLastSentence() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        val window =
            ttsMessages(
                ttsMessageWithId("a", "alice", "Alice", "Alpha."),
                ttsMessageWithId("b", "bob", "Bob", "Beta one.", "Beta two."),
            )
        queue.start(ttsMessages(window[0], window[1]))

        val replaced =
            queue.replaceWindow(
                window = window,
                targetMessageIdHex = "b",
                targetSentence = TtsWindowSentenceTarget.Last,
            )

        assertEquals(true, replaced)
        assertEquals(
            speakingTts(2, 3, 1, 2, "Beta one. Beta two.", sentenceIndex = 1, sentenceCount = 2),
            queue.state.value,
        )
        assertEquals("Bob: Beta two.", harness.lastSpokenTexts(1).first())
    }

    @Test
    fun replaceWindowRefusesAWindowMissingTheTarget() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessageWithId("a", "alice", "Alice", "One.")))
        val stateBefore = queue.state.value

        val replaced =
            queue.replaceWindow(
                window = ttsMessages(ttsMessageWithId("b", "bob", "Bob", "Two.")),
                targetMessageIdHex = "missing",
                targetSentence = TtsWindowSentenceTarget.First,
            )

        assertEquals(false, replaced)
        assertEquals(stateBefore, queue.state.value)
    }

    @Test
    fun appendDropsMessagesAlreadyInTheWindow() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessageWithId("a", "alice", "Alice", "One."),
                ttsMessageWithId("b", "bob", "Bob", "Two."),
            ),
        )

        val appended =
            queue.append(
                ttsMessages(
                    ttsMessageWithId("b", "bob", "Bob", "Two."),
                    ttsMessageWithId("c", "carol", "Carol", "Three."),
                ),
            )

        assertEquals(true, appended)
        assertEquals(3, queue.state.value.messageCount)
        assertEquals("Carol: Three.", harness.lastSpokenTexts(1).first())

        val duplicateOnly = queue.append(ttsMessages(ttsMessageWithId("c", "carol", "Carol", "Three.")))

        assertEquals(false, duplicateOnly)
        assertEquals(3, queue.state.value.messageCount)
    }
}

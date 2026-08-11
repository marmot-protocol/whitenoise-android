package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackQueueProgressTest {
    @Test
    fun multiSentenceMessageStartsBelowFirstSentenceCompletion() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        val message =
            ttsMessage(
                senderKey = "alice",
                senderDisplayName = "Alice",
                "Sentence one is long.",
                "Sentence two.",
                "Sentence three.",
                "Sentence four.",
            )
        queue.start(ttsMessages(message))

        val state = queue.state.value as TtsState.Speaking
        assertEquals(0, state.sentenceIndexWithinMessage)
        assertEquals(4, state.sentenceCountWithinMessage)
        assertTrue(
            "Progress must stay within the first sentence, not near completion",
            state.messageProgressFraction < 0.25f,
        )
    }

    @Test
    fun rangeCallbackAdvancesProgressWithinTheActiveMessage() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("", "", "Alpha beta.", "Gamma delta."),
            ),
        )

        val utteranceId = harness.utteranceId(0)
        val spokenLength = harness.spokenTextLength()
        queue.onRangeStart(utteranceId, spokenLength / 2, spokenLength)

        val progress = (queue.state.value as TtsState.Speaking).messageProgressFraction
        assertTrue(progress > 0.1f)
        assertTrue(progress < 0.5f)
    }

    @Test
    fun senderPrefixDoesNotInflateMessageProgress() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("alice", "Alice", "Hello world.")))

        val spoken = harness.spokenText()
        val prefixLength = "Alice: ".length
        queue.onRangeStart(harness.utteranceId(0), prefixLength + 6, spoken.length)

        val progress = (queue.state.value as TtsState.Speaking).messageProgressFraction
        assertEquals(0.5f, progress, 0.01f)
    }

    @Test
    fun progressResetsWhenMovingToTheNextMessage() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("alice", "Alice", "First."),
                ttsMessage("bob", "Bob", "Second."),
            ),
        )
        queue.onDone(harness.utteranceId(0))

        val state = queue.state.value as TtsState.Speaking
        assertEquals(1, state.messageIndex)
        assertEquals(0f, state.messageProgressFraction, 0.001f)
    }

    @Test
    fun rangeCallbackAdvancesAfterAnAutomaticMessageTransition() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessage("", "", "First."),
                ttsMessage("", "", "Second message."),
            ),
        )
        queue.onDone(harness.utteranceId(0))
        val spokenLength = harness.spokenTextLength(1)

        queue.onRangeStart(harness.utteranceId(1), spokenLength / 2, spokenLength)

        val state = queue.state.value as TtsState.Speaking
        assertEquals(1, state.messageIndex)
        assertTrue(state.messageProgressFraction > 0f)
    }

    @Test
    fun pauseFreezesProgressAndResumeDoesNotMoveBackward() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("", "", "One two three four.")))
        val utteranceId = harness.utteranceId(0)
        val spokenLength = harness.spokenTextLength()
        queue.onRangeStart(utteranceId, spokenLength / 2, spokenLength)
        val pausedAt = (queue.state.value as TtsState.Speaking).messageProgressFraction

        queue.pause()
        queue.onRangeStart(utteranceId, 0, spokenLength)
        assertEquals(pausedAt, (queue.state.value as TtsState.Paused).messageProgressFraction, 0.001f)

        queue.resume()
        val resumed = queue.state.value as TtsState.Speaking
        assertTrue(resumed.messageProgressFraction >= pausedAt - 0.001f)
    }

    @Test
    fun appendedAutoReadDoesNotChangeTheCurrentMessageDenominator() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("", "", "Alpha.", "Beta.")))
        val utteranceId = harness.utteranceId(0)
        val spokenLength = harness.spokenTextLength()
        queue.onRangeStart(utteranceId, spokenLength / 2, spokenLength)
        val beforeAppend = (queue.state.value as TtsState.Speaking).messageProgressFraction

        queue.append(ttsMessages(ttsMessage("", "", "Later.")))
        val afterAppend = (queue.state.value as TtsState.Speaking).messageProgressFraction
        assertEquals(beforeAppend, afterAppend, 0.001f)
    }

    @Test
    fun staleRangeCallbacksAreIgnored() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("", "", "One.", "Two.")))
        val staleId = harness.utteranceId(0)
        queue.onDone(staleId)

        queue.onRangeStart(staleId, 0, 3)
        val state = queue.state.value as TtsState.Speaking
        assertEquals(1, state.sentenceIndexWithinMessage)
        assertEquals(0.5f, state.messageProgressFraction, 0.001f)
    }

    @Test
    fun splitSentenceProgressDoesNotRegressAtAChunkBoundary() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(
            ttsMessages(
                ttsMessageWithChunks(
                    senderKey = "",
                    senderDisplayName = "",
                    preview = "One long sentence.",
                    chunks = listOf("Long first part" to 0, "and its second part." to 0),
                ),
            ),
        )
        val firstUtterance = harness.utteranceId(0)
        val firstPayloadLength = harness.spokenTextLength(0)
        queue.onRangeStart(firstUtterance, firstPayloadLength - 1, firstPayloadLength)
        val beforeBoundary = (queue.state.value as TtsState.Speaking).messageProgressFraction

        queue.onDone(firstUtterance)

        val afterBoundary = (queue.state.value as TtsState.Speaking).messageProgressFraction
        assertEquals(0, (queue.state.value as TtsState.Speaking).sentenceIndexWithinMessage)
        assertTrue(afterBoundary >= beforeBoundary)
    }

    @Test
    fun previousSentenceDoesNotRegressProgressWithinTheMessage() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("", "", "First.", "Second.")))
        queue.onDone(harness.utteranceId(0))
        val beforeNavigation = (queue.state.value as TtsState.Speaking).messageProgressFraction

        queue.skipPreviousSentence()

        assertTrue((queue.state.value as TtsState.Speaking).messageProgressFraction >= beforeNavigation)
    }

    @Test
    fun replacingTheCurrentSlotWithAnotherMessageResetsProgress() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessageWithId("old", "", "", "Old message.")))
        val staleUtterance = harness.utteranceId(0)
        queue.onRangeStart(staleUtterance, 4, harness.spokenTextLength())
        assertTrue((queue.state.value as TtsState.Speaking).messageProgressFraction > 0f)

        queue.replaceWindow(
            window = ttsMessages(ttsMessageWithId("new", "", "", "New message.")),
            targetMessageIdHex = "new",
            targetSentence = TtsWindowSentenceTarget.First,
        )

        assertEquals(0f, (queue.state.value as TtsState.Speaking).messageProgressFraction, 0.001f)
        queue.onRangeStart(staleUtterance, 8, harness.spokenTextLength())
        assertEquals(0f, (queue.state.value as TtsState.Speaking).messageProgressFraction, 0.001f)
    }

    @Test
    fun editingTheCurrentMessageResetsProgress() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessageWithId("same", "", "", "Original text.")))
        queue.onRangeStart(harness.utteranceId(0), 4, harness.spokenTextLength())
        assertTrue((queue.state.value as TtsState.Speaking).messageProgressFraction > 0f)

        queue.replaceWindow(
            window = ttsMessages(ttsMessageWithId("same", "", "", "Edited text.")),
            targetMessageIdHex = "same",
            targetSentence = TtsWindowSentenceTarget.First,
        )

        assertEquals(0f, (queue.state.value as TtsState.Speaking).messageProgressFraction, 0.001f)
    }

    @Test
    fun malformedRangeFallsBackToSentenceProgress() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("", "", "One.", "Two.", "Three.")))
        val utteranceId = harness.utteranceId(0)
        val spokenLength = harness.spokenTextLength()

        queue.onRangeStart(utteranceId, spokenLength, spokenLength - 1)

        assertEquals(0f, (queue.state.value as TtsState.Speaking).messageProgressFraction, 0.001f)
    }

    @Test
    fun outOfBoundsRangeFallsBackToSentenceProgress() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("", "", "One.", "Two.")))
        val utteranceId = harness.utteranceId(0)
        val spokenLength = harness.spokenTextLength()

        queue.onRangeStart(utteranceId, 0, spokenLength + 1)

        assertEquals(0f, (queue.state.value as TtsState.Speaking).messageProgressFraction, 0.001f)
    }

    @Test
    fun nonMonotonicRangeKeepsTheHighestObservedProgress() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("", "", "Alpha beta gamma delta.")))
        val utteranceId = harness.utteranceId(0)
        val spokenLength = harness.spokenTextLength()
        queue.onRangeStart(utteranceId, spokenLength / 2, spokenLength)
        val midProgress = (queue.state.value as TtsState.Speaking).messageProgressFraction

        queue.onRangeStart(utteranceId, 0, spokenLength / 4)

        assertEquals(midProgress, (queue.state.value as TtsState.Speaking).messageProgressFraction, 0.001f)
    }

    @Test
    fun errorKeepsTheHighestObservedProgress() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("", "", "Alpha beta gamma delta.")))
        val utteranceId = harness.utteranceId(0)
        val spokenLength = harness.spokenTextLength()
        queue.onRangeStart(utteranceId, spokenLength / 2, spokenLength)
        val progressBeforeError = (queue.state.value as TtsState.Speaking).messageProgressFraction

        queue.onError(utteranceId, TextToSpeech.ERROR_SYNTHESIS)

        assertEquals(
            progressBeforeError,
            (queue.state.value as TtsState.Error).messageProgressFraction,
            0.001f,
        )
    }

    @Test
    fun speechRateBoundaryRequeueDoesNotRegressProgress() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("", "", "First sentence.", "Second sentence.")))
        val completedUtterance = harness.utteranceId(0)
        val staleNextUtterance = harness.utteranceId(1)
        val firstPayloadLength = harness.spokenTextLength(0)
        queue.onRangeStart(completedUtterance, firstPayloadLength - 1, firstPayloadLength)
        val beforeRequeue = (queue.state.value as TtsState.Speaking).messageProgressFraction

        queue.refreshPendingChunksAtNextBoundary()
        queue.onDone(completedUtterance)

        val afterRequeue = (queue.state.value as TtsState.Speaking).messageProgressFraction
        assertTrue(afterRequeue >= beforeRequeue)
        queue.onRangeStart(staleNextUtterance, 0, harness.spokenTextLength(1))
        assertEquals(afterRequeue, (queue.state.value as TtsState.Speaking).messageProgressFraction, 0.001f)
    }

    @Test
    fun requeueInvalidatesRangeCallbacksFromThePreviousGeneration() {
        val harness = TtsQueueHarness()
        val queue = harness.queue
        queue.start(ttsMessages(ttsMessage("", "", "One.", "Two.")))
        val staleId = harness.utteranceId(0)
        queue.skipNextSentence()
        val spokenLength = harness.spokenTextLength(harness.enqueued.lastIndex)

        queue.onRangeStart(staleId, 0, spokenLength)

        assertEquals(0.5f, (queue.state.value as TtsState.Speaking).messageProgressFraction, 0.001f)
    }
}

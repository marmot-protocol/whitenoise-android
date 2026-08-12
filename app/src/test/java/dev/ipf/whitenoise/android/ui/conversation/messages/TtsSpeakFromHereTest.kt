package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.audio.tts.TTS_AUTO_READ_MAX_MESSAGES
import dev.ipf.whitenoise.android.audio.tts.TtsChunker
import dev.ipf.whitenoise.android.audio.tts.speakingTts
import dev.ipf.whitenoise.android.audio.tts.ttsMessage
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsSpeakFromHereTest {
    @Test
    fun selectedBubbleMissingFromTimelineFallsBackToThatBubble() {
        val selected = message("selected")

        val candidates =
            ttsSpeakFromHereCandidates(
                timeline = listOf(timelineMessage("other")),
                selected = selected,
            )

        assertEquals(listOf("selected"), candidates.map(AppMessageRecordFfi::messageIdHex))
    }

    @Test
    fun selectedTimelineBubbleStartsBoundedCatchUpAtThatBubble() {
        val timeline =
            listOf(timelineMessage("before")) +
                List(TTS_AUTO_READ_MAX_MESSAGES * 2 + 1) { index -> timelineMessage("selected-$index") }
        val selected = timeline[1].record

        val candidates = ttsSpeakFromHereCandidates(timeline, selected)

        assertEquals(TTS_AUTO_READ_MAX_MESSAGES * 2, candidates.size)
        assertEquals("selected-0", candidates.first().messageIdHex)
        assertEquals("selected-${TTS_AUTO_READ_MAX_MESSAGES * 2 - 1}", candidates.last().messageIdHex)
    }

    @Test
    fun speakableSentenceIndexUsesVisibleOffsetWithinMatchingText() {
        val text = "Alpha. Beta. Gamma."
        val offset = text.indexOf("Beta")

        assertEquals(1, speakableSentenceIndexAtVisibleOffset(text, text, offset, Locale.US))
    }

    @Test
    fun startAtSecondSentenceUsesFirstChunkForThatSentence() {
        val harness =
            dev.ipf.whitenoise.android.audio.tts
                .TtsQueueHarness()
        val message =
            ttsMessage(
                senderKey = "alice",
                senderDisplayName = "Alice",
                "One.",
                "Two.",
                "Three.",
            )
        harness.queue.start(listOf(message), startSentenceIndex = 1)

        assertEquals(
            speakingTts(1, 3, 0, 1, "One. Two. Three.", sentenceIndex = 1, sentenceCount = 3),
            harness.queue.state.value,
        )
        assertTrue(
            harness.enqueued
                .first()
                .first.text
                .endsWith("Two."),
        )
    }

    @Test
    fun replacingQueueAdvancesGenerationAndStartsAtRequestedSentence() {
        val harness =
            dev.ipf.whitenoise.android.audio.tts
                .TtsQueueHarness()
        val first =
            ttsMessage(
                senderKey = "alice",
                senderDisplayName = "Alice",
                "First one.",
                "First two.",
            )
        val second =
            ttsMessage(
                senderKey = "bob",
                senderDisplayName = "Bob",
                "Second one.",
            ).copy(messageIdHex = "m2")
        harness.queue.start(listOf(first))
        val firstGeneration = harness.utteranceId(0).substringAfter("whitenoise.tts.").substringBefore('.')

        harness.queue.start(listOf(first, second), startSentenceIndex = 1)
        val secondGeneration =
            harness.utteranceId(harness.enqueued.lastIndex).substringAfter("whitenoise.tts.").substringBefore('.')

        assertTrue(secondGeneration.toLong() > firstGeneration.toLong())
        assertEquals(
            speakingTts(
                1,
                3,
                0,
                2,
                "First one. First two.",
                sentenceIndex = 1,
                sentenceCount = 2,
                messageProgressGeneration = 2,
            ).copy(sessionId = 1),
            harness.queue.state.value,
        )
    }

    @Test
    fun midMessageStartAnnouncesSenderOnce() {
        val harness =
            dev.ipf.whitenoise.android.audio.tts
                .TtsQueueHarness()
        val message =
            ttsMessage(
                senderKey = "alice",
                senderDisplayName = "Alice",
                "One.",
                "Two.",
            )
        harness.queue.start(listOf(message), startSentenceIndex = 1)

        assertEquals(listOf("Alice: Two."), harness.enqueued.map { it.first.text })
    }

    @Test
    fun midMessageStartKeepsExistingCrossMessageSenderRules() {
        val harness =
            dev.ipf.whitenoise.android.audio.tts
                .TtsQueueHarness()
        harness.queue.start(
            messages =
                listOf(
                    ttsMessage("alice", "Alice", "One.", "Two."),
                    ttsMessage("alice", "Alice", "Three."),
                    ttsMessage("bob", "Bob", "Four."),
                ),
            startSentenceIndex = 1,
        )

        assertEquals(
            listOf("Alice: Two.", "Three.", "Bob: Four."),
            harness.enqueued.map { it.first.text },
        )
    }

    @Test
    fun restartingAboveOrBelowCurrentPlaybackReplacesTheWindow() {
        val aboveHarness =
            dev.ipf.whitenoise.android.audio.tts
                .TtsQueueHarness()
        aboveHarness.queue.start(listOf(ttsMessage("bob", "Bob", "Current.")))
        aboveHarness.queue.start(
            listOf(
                ttsMessage("alice", "Alice", "Above one.", "Above two."),
                ttsMessage("bob", "Bob", "Current."),
            ),
            startSentenceIndex = 1,
        )

        assertEquals(
            speakingTts(
                1,
                3,
                0,
                2,
                "Above one. Above two.",
                sentenceIndex = 1,
                sentenceCount = 2,
                messageProgressGeneration = 2,
            ).copy(sessionId = 1),
            aboveHarness.queue.state.value,
        )
        assertTrue(
            aboveHarness.enqueued
                .last()
                .first.text
                .endsWith("Current."),
        )

        val belowHarness =
            dev.ipf.whitenoise.android.audio.tts
                .TtsQueueHarness()
        belowHarness.queue.start(listOf(ttsMessage("alice", "Alice", "Current.")))
        belowHarness.queue.start(listOf(ttsMessage("bob", "Bob", "Below.")))

        assertEquals(
            speakingTts(
                0,
                1,
                0,
                1,
                "Below.",
                sentenceIndex = 0,
                sentenceCount = 1,
                messageProgressGeneration = 2,
            ).copy(sessionId = 1),
            belowHarness.queue.state.value,
        )
        assertEquals(
            "Bob: Below.",
            belowHarness.enqueued
                .last()
                .first.text,
        )
    }

    @Test
    fun chunkerSentenceIndexMatchesLogicalSentences() {
        val text = "Dr. Smith arrived. Then he spoke."

        assertEquals(0, TtsChunker.sentenceIndexAtOffset(text, text.indexOf("Smith"), Locale.US))
        assertEquals(1, TtsChunker.sentenceIndexAtOffset(text, text.indexOf("Then"), Locale.US))
    }

    private fun timelineMessage(id: String): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record = message(id),
            status = MessageStatus.Received,
        )

    private fun message(id: String): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = "received",
            groupIdHex = "group",
            sender = "alice",
            plaintext = id,
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = byteArrayOf(),
                ),
            kind = 9uL,
            tags = emptyList<MessageTagFfi>(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )
}

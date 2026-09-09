package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.whitenoise.android.audio.tts.speech.PRIMARY_LEAF_ID
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedRenderedHit
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedSeekResolver
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedSeekTarget
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedSpeechMessage
import dev.ipf.whitenoise.android.audio.tts.speech.prepareMessage
import dev.ipf.whitenoise.android.audio.tts.speech.proseRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One prepared utterance feeds queueing, highlighting and inverse seeking. The
 * queue never reconstructs speech text or offsets on its own.
 */
class TtsPreparedSpeechQueueTest {
    @Test
    fun everyPreparedSentenceBecomesANavigableQueueSentence() {
        val harness = TtsQueueHarness()
        val prepared = prepareMessage(proseRun("Yes. No! OK. Done."), messageIdHex = MESSAGE_ID)

        harness.queue.start(listOf(queued(prepared)))

        assertEquals(4, harness.queue.state.value.sentenceCountWithinMessage)
        assertEquals(
            listOf("Yes.", "No!", "OK.", "Done."),
            harness.enqueued.map { it.first.text.trim() }.take(4),
        )
    }

    @Test
    fun anExpandedAmountHighlightsItsWholeDisplayedAtom() {
        val source = "Pay $12.50."
        val harness = TtsQueueHarness()
        val prepared = prepareMessage(proseRun(source), messageIdHex = MESSAGE_ID)
        harness.queue.start(listOf(queued(prepared)))
        val spoken = harness.spokenText(0)
        val expansionStart = spoken.indexOf("twelve")

        val applied =
            harness.queue.onRangeStart(
                harness.utteranceId(0),
                expansionStart,
                expansionStart + "twelve".length,
            )

        val amountStart = source.indexOf("$12.50")
        assertEquals(TtsPlaybackQueue.RangeApplication.VisibleWord, applied)
        assertEquals(
            listOf(TtsVisibleTextSpan(PRIMARY_LEAF_ID, amountStart, amountStart + "$12.50".length)),
            harness.queue.state.value.passage
                ?.visibleWord,
        )
    }

    @Test
    fun aRepeatedAmountHighlightsOnlyItsOwnOccurrence() {
        val source = "Pay $5.00, then $5.00."
        val harness = TtsQueueHarness()
        val prepared = prepareMessage(proseRun(source), messageIdHex = MESSAGE_ID)
        harness.queue.start(listOf(queued(prepared)))
        val spoken = harness.spokenText(0)
        val secondExpansion = spoken.lastIndexOf("five dollars")

        harness.queue.onRangeStart(
            harness.utteranceId(0),
            secondExpansion,
            secondExpansion + "five dollars".length,
        )

        val highlighted =
            harness.queue.state.value.passage
                ?.visibleWord
                .orEmpty()
        assertEquals(1, highlighted.size)
        assertEquals(source.lastIndexOf("$5.00"), highlighted.single().start)
    }

    @Test
    fun aDoubleTapOnTransformedTextSeeksTheSentenceThePreparedTableNames() {
        val source = "Ship it. Pay $12.50."
        val harness = TtsQueueHarness()
        val prepared = prepareMessage(proseRun(source), messageIdHex = MESSAGE_ID)
        harness.queue.start(listOf(queued(prepared)))

        val target =
            PreparedSeekResolver.resolve(
                message = prepared,
                hit = PreparedRenderedHit(PRIMARY_LEAF_ID, source, source.indexOf("12.50")),
            )

        assertTrue(target is PreparedSeekTarget.Sentence)
        assertEquals(
            TtsSeekResult.Repositioned,
            harness.queue.seekTo(MESSAGE_ID, (target as PreparedSeekTarget.Sentence).ordinal),
        )
        assertEquals(1, harness.queue.state.value.sentenceIndexWithinMessage)
    }

    @Test
    fun aTargetOutsideTheQueueWindowIsReportedWithoutReplacingTheSession() {
        val harness = TtsQueueHarness()
        val prepared = prepareMessage(proseRun("Yes. No."), messageIdHex = MESSAGE_ID)
        harness.queue.start(listOf(queued(prepared)))
        val before = harness.queue.state.value

        val result = harness.queue.seekTo("other-message", 0)

        assertEquals(TtsSeekResult.MessageNotInWindow, result)
        assertEquals(before.sessionId, harness.queue.state.value.sessionId)
        assertEquals(before.chunkIndex, harness.queue.state.value.chunkIndex)
        assertEquals(before.passage, harness.queue.state.value.passage)
    }

    @Test
    fun aLateRangeFromTheSupersededUtteranceCannotMoveTheNewCursor() {
        val harness = TtsQueueHarness()
        val prepared = prepareMessage(proseRun("Yes. No."), messageIdHex = MESSAGE_ID)
        harness.queue.start(listOf(queued(prepared)))
        val staleUtteranceId = harness.utteranceId(0)
        harness.queue.seekTo(MESSAGE_ID, 1)
        val afterSeek = harness.queue.state.value

        val applied = harness.queue.onRangeStart(staleUtteranceId, 0, 3)

        assertEquals(TtsPlaybackQueue.RangeApplication.Stale, applied)
        assertEquals(afterSeek.sentenceIndexWithinMessage, harness.queue.state.value.sentenceIndexWithinMessage)
        assertEquals(afterSeek.passage, harness.queue.state.value.passage)
    }

    @Test
    fun thePreviewKeepsTheOriginalDisplayTextWhileSpeechIsExpanded() {
        val source = "Pay $12.50."
        val prepared = prepareMessage(proseRun(source), messageIdHex = MESSAGE_ID)

        val message = queued(prepared)

        assertEquals(source, message.preview)
        assertTrue(
            message.chunks
                .first()
                .text
                .contains("twelve dollars"),
        )
    }

    @Test
    fun aSenderAnnouncementIsReservedAfterExpansionNotBeforeIt() {
        val prepared =
            prepareMessage(
                proseRun("Pay $12.50."),
                messageIdHex = MESSAGE_ID,
                senderAnnouncement = "Alice",
            )

        val message = queued(prepared)

        val first = message.chunks.first()
        assertNotNull("the synthetic prefix must be marked on the chunk", first.senderPrefix)
        assertTrue(first.text.startsWith("Alice"))
        assertTrue(first.text.contains("twelve dollars"))
    }

    private fun queued(prepared: PreparedSpeechMessage): TtsQueuedMessage =
        requireNotNull(
            preparedQueuedMessage(
                prepared = prepared,
                senderKey = "alice",
                senderDisplayName = "Alice",
                maxChunkLength = 4_000,
            ),
        )

    private companion object {
        const val MESSAGE_ID = "m1"
    }
}

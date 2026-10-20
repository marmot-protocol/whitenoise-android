package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Roster context and audible identity must survive every bounded playback operation. */
class TtsSpeakerAnnouncementCoverageTest {
    /** Two real participants differ from a window containing only two authors. */
    @Test
    fun onlyExactlyTwoKnownMembersSuppressTheFreshIntroduction() {
        for (members in listOf(null, 1, 2, 3)) {
            val harness = TtsQueueHarness()
            harness.queue.start(listOf(message("a1", "alice", "First.", members)))
            assertEquals(if (members == 2) "First." else "alice: First.", harness.spokenText())
        }
    }

    /** Both sent/received directions compare stable identity, even after a name changes. */
    @Test
    fun naturalPlaybackAnnouncesOnlyStableSenderChangesInBothDirections() {
        val harness = TtsQueueHarness()
        harness.queue.start(
            listOf(
                message("a1", "alice", "First."),
                message("a2", "ALICE", "Second.").copy(senderDisplayName = "Renamed"),
                message("b1", "bob", "Reply."),
                message("a3", "alice", "Return."),
            ),
        )
        assertEquals(listOf("First.", "Second.", "bob: Reply.", "alice: Return."), harness.lastSpokenTexts(4))
    }

    /** A fresh selection in the middle of a message still begins with its body. */
    @Test
    fun freshSentenceSelectionAndNewSessionHaveNoPreviousSpeaker() {
        val harness = TtsQueueHarness()
        val entry = ttsMessageWithId("a1", "alice", "Alice", "First.", "Second.").copy(conversationMemberCount = 2)
        harness.queue.start(listOf(entry), startSentenceIndex = 1)
        assertEquals("Second.", harness.enqueued.last().first.text)
        harness.queue.onStarted(harness.enqueued.last().second)
        harness.queue.start(listOf(message("b1", "bob", "Fresh.")))
        assertEquals("Fresh.", harness.enqueued.last().first.text)
    }

    /** Prebuffered speakers are never mistaken for the one the listener has heard. */
    @Test
    fun navigationComparesTheAudibleSpeakerRatherThanTheQueuedTail() {
        val harness = TtsQueueHarness()
        harness.queue.start(
            listOf(
                message("a1", "alice", "First."),
                message("a2", "alice", "Second."),
                message("b1", "bob", "Reply."),
            ),
        )
        harness.queue.onStarted(harness.utteranceId(0))
        var first = harness.enqueued.size
        harness.queue.skipNextMessage()
        assertEquals("Second.", harness.spokenText(first))
        harness.queue.onStarted(harness.utteranceId(first))
        first = harness.enqueued.size
        harness.queue.seekTo("b1", 0)
        assertEquals("bob: Reply.", harness.spokenText(first))
        harness.queue.onStarted(harness.utteranceId(first))
        first = harness.enqueued.size
        harness.queue.skipPreviousMessage()
        assertEquals("alice: Second.", harness.spokenText(first))
    }

    /** Paused navigation is silent and an unheard sender earns one introduction on resume. */
    @Test
    fun pauseResumeAndPausedSeekKeepTheAudibleContext() {
        val harness = TtsQueueHarness()
        harness.queue.start(
            listOf(
                message("a1", "alice", "First."),
                message("a2", "alice", "Second."),
                message("b1", "bob", "Reply."),
            ),
        )
        harness.queue.onStarted(harness.utteranceId(0))
        harness.queue.pause()
        var first = harness.enqueued.size
        harness.queue.skipNextMessage()
        assertEquals(first, harness.enqueued.size)
        harness.queue.resume()
        assertEquals("Second.", harness.spokenText(first))
        harness.queue.pause()
        first = harness.enqueued.size
        harness.queue.seekTo("b1", 0)
        harness.queue.resume()
        assertEquals("bob: Reply.", harness.spokenText(first))
    }

    /** App-owned auto-read may append after its screen and roster projection have gone away. */
    @Test
    fun unknownBackgroundAppendRetainsResolvedRosterAndSpeakerContext() {
        val harness = TtsQueueHarness()
        harness.queue.start(listOf(message("a1", "alice", "First.")))
        harness.queue.onStarted(harness.utteranceId(0))
        assertTrue(
            harness.queue.append(listOf(message("a2", "alice", "Second.", null), message("b1", "bob", "Reply.", null))),
        )
        assertEquals(listOf("Second.", "bob: Reply."), harness.lastSpokenTexts(2))
    }

    /** A new known larger roster restores the existing conservative introduction policy. */
    @Test
    fun resolvedLargerRosterRestoresIntroductions() {
        val harness = TtsQueueHarness()
        harness.queue.start(listOf(message("a1", "alice", "First.")))
        harness.queue.onStarted(harness.utteranceId(0))
        harness.queue.append(listOf(message("a2", "alice", "Second.", 3)))
        val first = harness.enqueued.size
        harness.queue.skipNextMessage()
        assertEquals("alice: Second.", harness.spokenText(first))
    }

    /** Index zero after paging is not the start of a new audible session. */
    @Test
    fun historyReplacementAtIndexZeroPreservesBothSpeakerDirections() {
        val harness = TtsQueueHarness()
        harness.queue.start(listOf(message("a1", "alice", "First.")))
        harness.queue.onStarted(harness.utteranceId(0))
        assertTrue(harness.queue.replaceWindow(listOf(message("a2", "alice", "Second.")), "a2", TtsWindowSentenceTarget.First))
        assertEquals("Second.", harness.enqueued.last().first.text)
        assertTrue(harness.queue.replaceWindow(listOf(message("b1", "bob", "Reply.")), "b1", TtsWindowSentenceTarget.First))
        assertEquals("bob: Reply.", harness.enqueued.last().first.text)
    }

    /** Old callback generations cannot overwrite the speaker heard in the current generation. */
    @Test
    fun staleStartsCannotChangeTheSpeakerAfterNavigation() {
        val harness = TtsQueueHarness()
        harness.queue.start(listOf(message("a1", "alice", "First."), message("b1", "bob", "Reply.")))
        val stale = harness.utteranceId(0)
        harness.queue.onStarted(stale)
        harness.queue.seekTo("b1", 0)
        harness.queue.onStarted(harness.enqueued.last().second)
        harness.queue.onStarted(stale)
        val first = harness.enqueued.size
        harness.queue.seekTo("b1", 0)
        assertEquals("Reply.", harness.spokenText(first))
    }

    /** A rate refresh uses the completed speaker and never repeats a same-sender prefix. */
    @Test
    fun rateRefreshPreservesTheCompletedSpeaker() {
        val harness = TtsQueueHarness()
        harness.queue.start(listOf(message("a1", "alice", "First."), message("a2", "alice", "Second.")))
        harness.queue.refreshPendingChunksAtNextBoundary()
        val first = harness.enqueued.size
        harness.queue.onDone(harness.utteranceId(0))
        assertEquals("Second.", harness.spokenText(first))
    }

    /** Document metadata does not become a speaker or a spoken filename. */
    @Test
    fun documentsRemainBodyFirstAndDoNotReplaceTheLastMessageSpeaker() {
        val harness = TtsQueueHarness()
        harness.queue.start(
            listOf(
                message("a1", "alice", "First."),
                message("doc", "", "Document.").copy(attachmentDisplayName = "notes.txt"),
                message("a2", "alice", "Second."),
            ),
        )
        assertEquals(listOf("First.", "Document.", "Second."), harness.lastSpokenTexts(3))
    }

    /** Prepared rendered text and legacy previews carry the same actual roster size. */
    @Test
    fun queuePreparationPreservesRosterForBothTextPaths() {
        val text = "Body."
        val entry = TtsSpeakableEntry("alice", "Alice", text, conversationMemberCount = 2)
        val mapped =
            entry.copy(spokenTextSpans = listOf(TtsSpokenTextSpan(TtsTextRange(0, 4), TtsVisibleTextSpan("body", 0, 4))))
        for (source in listOf(entry, mapped)) {
            val queued = with(TtsQueuePreparation(4_000)) { source.toQueuedMessage(Locale.US) }!!
            assertEquals(2, queued.conversationMemberCount)
            val harness = TtsQueueHarness()
            harness.queue.start(listOf(queued))
            assertEquals(text, harness.spokenText())
        }
    }

    /** Builds a known two-participant projection without inferring a roster from authored messages. */
    private fun message(
        id: String,
        key: String,
        text: String,
        members: Int? = 2,
    ): TtsQueuedMessage = ttsMessageWithId(id, key, key, text).copy(conversationMemberCount = members)
}

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.geometry.Rect
import dev.ipf.whitenoise.android.audio.tts.SessionHarness
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsFollowPolicy
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsFollowRequest
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsFollowTarget
import dev.ipf.whitenoise.android.ui.conversation.TtsFollowDirection
import dev.ipf.whitenoise.android.ui.conversation.messages.RenderedTextHit
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsHighlightProjectionResolver
import dev.ipf.whitenoise.android.ui.conversation.messages.preparedHitFromRenderedHit
import dev.ipf.whitenoise.android.ui.conversation.messages.speakableProjection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TextAttachmentTtsFollowTest {
    @Test
    fun windowCoordinatesIncludeInsetsAndClampToAvailableScroll() {
        assertEquals(300, textAttachmentFollowOffset(100, 1_000, viewport, Rect(0f, 300f, 300f, 325f), request))
        assertEquals(0, textAttachmentFollowOffset(0, 1_000, viewport, Rect(0f, 50f, 300f, 75f), request))
        assertEquals(400, textAttachmentFollowOffset(350, 400, viewport, Rect(0f, 800f, 300f, 830f), request))
    }

    @Test
    fun alreadyVisibleSentenceStaysUnlessExplicitlyRevealed() {
        val sentence = Rect(0f, 120f, 300f, 150f)
        assertNull(textAttachmentFollowOffset(100, 1_000, viewport, sentence, request.copy(anchorAtTop = false)))
        assertEquals(120, textAttachmentFollowOffset(100, 1_000, viewport, sentence, request))
    }

    @Test
    fun currentProjectionOwnsSpeakingAndPausedPassagesButNeverASelectedTextQueue() =
        runTest {
            val harness = SessionHarness(this)
            val entry = textAttachmentTtsEntry(preview, "alice", "Alice", "message", 0)
            harness.speakEntries(listOf(entry))
            val playback = playback(entry, harness.controller.state.value)
            assertNotNull(textAttachmentPlaybackPassage(playback))
            harness.controller.pause()
            assertNotNull(textAttachmentPlaybackPassage(playback.copy(state = harness.controller.state.value)))
            assertNull(textAttachmentPlaybackPassage(playback.copy(isCurrent = { false })))
            assertNull(textAttachmentPlaybackPassage(playback.copy(entry = entry.copy(projectionId = "different"))))
            val otherAttachment = playback.copy(entry = entry.copy(messageIdHex = "attachment:message:1"))
            assertNull(textAttachmentPlaybackPassage(otherAttachment))
            val selected =
                textAttachmentTtsEntry(
                    preview.copy(text = "Second sentence."),
                    "alice",
                    "Alice",
                    "message",
                    0,
                )
            harness.speakEntries(listOf(selected))
            assertNull(textAttachmentPlaybackPassage(playback.copy(state = harness.controller.state.value)))
        }

    @Test
    fun realPreparedPlainAttachmentSeeksExactSentenceWithoutReplacingSession() =
        runTest {
            val harness = SessionHarness(this)
            val entry = textAttachmentTtsEntry(preview, "alice", "Alice", "message", 0)
            val hit = RenderedTextHit("plain", preview.text, preview.text.indexOf("Second"))
            assertTrue(
                harness.controller.speakAsync(
                    listOf(entry),
                    Locale.US,
                    startRenderedHit = preparedHitFromRenderedHit(entry, hit),
                ) { true },
            )
            val state = harness.controller.state.value
            val prepared = requireNotNull(harness.controller.preparedSpeechFor(entry.messageIdHex, entry.projectionId))
            val resolver = TtsHighlightProjectionResolver(entry.speakableProjection(), prepared)
            assertEquals(1, resolver.sentenceIndexAtRenderedOffset(hit))
            assertEquals(1, state.passage?.sentenceIndex)
            harness.controller.seekToSentence(entry.messageIdHex, 0, entry.projectionId)
            assertEquals(state.sessionId, harness.controller.state.value.sessionId)
            assertNull(resolver.sentenceIndexAtRenderedOffset(hit.copy(renderedText = "changed")))
        }

    @Test
    fun pausedReaderReopenAndExplicitResumeRevealDoNotStartNewSpeech() =
        runTest {
            val harness = SessionHarness(this)
            val entry = textAttachmentTtsEntry(preview, "alice", "Alice", "message", 0)
            harness.speakEntries(listOf(entry))
            val spoken = harness.spokenTexts().size
            harness.controller.pause()
            val policy = ConversationTtsFollowPolicy()
            policy.observe(harness.controller.state.value, true)
            assertNull(policy.claimPendingRequest())
            assertTrue(policy.requestExplicitReveal())
            assertTrue(requireNotNull(policy.claimPendingRequest()).anchorAtTop)
            assertEquals(spoken, harness.spokenTexts().size)
            assertTrue(harness.controller.state.value is TtsState.Paused)
        }

    @Test
    fun visibleTextBeyondTheSpeechBoundNeverGuessesTheLastSentence() =
        runTest {
            val harness = SessionHarness(this)
            val bounded = preview.copy(text = "Long ".repeat(6_500) + "Unloaded tail.")
            val entry = textAttachmentTtsEntry(bounded, "alice", "Alice", "message", 0)
            harness.speakEntries(listOf(entry))
            val prepared = requireNotNull(harness.controller.preparedSpeechFor(entry.messageIdHex, entry.projectionId))
            val resolver = TtsHighlightProjectionResolver(entry.speakableProjection(), prepared)
            val tail = RenderedTextHit("plain", bounded.text, bounded.text.indexOf("Unloaded"))
            assertNull(preparedHitFromRenderedHit(entry, tail))
            assertNull(resolver.sentenceIndexAtRenderedOffset(tail))
        }

    private fun playback(
        entry: dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry,
        state: TtsState,
    ) = TextAttachmentPlayback(entry, state, null, { true }, { _, _ -> null }, {})

    private companion object {
        val preview =
            TextAttachmentPreview(
                TextAttachmentCandidate("notes.txt", "text/plain", TextAttachmentFormat.PlainText),
                "First sentence. Second sentence.",
            )
        val viewport = Rect(0f, 100f, 300f, 600f)
        val request =
            ConversationTtsFollowRequest(
                ConversationTtsFollowTarget(1, "attachment:message:0", 0, 2, "projection", 0uL),
                TtsFollowDirection.Forward,
                anchorAtTop = true,
            )
    }
}

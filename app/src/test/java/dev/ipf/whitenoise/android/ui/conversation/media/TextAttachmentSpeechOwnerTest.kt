package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.audio.tts.SessionHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TextAttachmentSpeechOwnerTest {
    @Test
    fun staleSessionAccountSourceAndRevisionCannotSeekAnotherQueue() =
        runTest {
            val harness = SessionHarness(this)
            var currentSource = true
            var currentAccount = true
            val owner = TextAttachmentSpeechOwner(harness.controller, { currentSource }, { currentAccount })
            val entry = textAttachmentTtsEntry(preview, "alice", "Alice", "message", 0)
            harness.speakEntries(listOf(entry))
            val playback = owner.playback(entry, harness.controller.state.value) {}
            assertNotNull(playback.seek(1, entry.projectionId))
            assertEquals(
                1,
                harness.controller.state.value.passage
                    ?.sentenceIndex,
            )
            assertNull(playback.seek(0, "stale"))
            currentSource = false
            assertNull(playback.seek(0, entry.projectionId))
            currentSource = true
            currentAccount = false
            assertNull(playback.seek(0, entry.projectionId))
            assertNull(textAttachmentPlaybackPassage(owner.playback(entry, harness.controller.state.value) {}))
            currentAccount = true
            harness.speakEntries(listOf(entry))
            val replacement = harness.controller.state.value
            assertNull(playback.seek(1, entry.projectionId))
            assertEquals(replacement, harness.controller.state.value)
        }

    @Test
    fun otherAttachmentNeverOwnsTheStopOrSeekAction() =
        runTest {
            val harness = SessionHarness(this)
            val owner = TextAttachmentSpeechOwner(harness.controller, { true }, { true })
            val entry = textAttachmentTtsEntry(preview, "alice", "Alice", "message", 1)
            harness.speakEntries(listOf(entry))
            org.junit.Assert.assertFalse(owner.ownsAttachment(harness.controller.state.value, "message", 0))
        }

    @Test
    fun staleInactiveCallbackCannotReplaceANewlyStartedSession() =
        runTest {
            val harness = SessionHarness(this)
            val owner = TextAttachmentSpeechOwner(harness.controller, { true }, { true })
            val entry = textAttachmentTtsEntry(preview, "alice", "Alice", "message", 0)
            var starts = 0
            val inactive = owner.playback(entry, harness.controller.state.value) { starts++ }
            harness.speakEntries(listOf(entry))
            inactive.startAt(
                dev.ipf.whitenoise.android.ui.conversation.messages
                    .RenderedTextHit("plain", preview.text, 2),
            )
            assertEquals(0, starts)
            assertEquals(0, harness.controller.state.value.sentenceIndexWithinMessage)
        }

    private companion object {
        val preview =
            TextAttachmentPreview(
                TextAttachmentCandidate("notes.txt", "text/plain", TextAttachmentFormat.PlainText),
                "First sentence. Second sentence.",
            )
    }
}

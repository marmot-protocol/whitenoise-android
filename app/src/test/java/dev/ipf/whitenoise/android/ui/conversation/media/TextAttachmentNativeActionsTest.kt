package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies reader adapters without exporting a real document or starting a speech engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class TextAttachmentNativeActionsTest {
    /** An accepted download cannot publish once its source owner is gone, and repeated clicks stay single flight. */
    @Test
    fun saveRejectsDuplicateClicksAndOwnerLossDuringMaterialization() =
        runTest {
            val materialized = CompletableDeferred<String>()
            var downloads = 0
            var published = 0
            lateinit var actions: TextAttachmentNativeActions
            actions =
                TextAttachmentNativeActions({ true }) {
                    saveOwnedTextAttachment(
                        actions::isCurrent,
                        {
                            downloads += 1
                            materialized.await()
                        },
                        { published += 1 },
                    )
                }
            val save = launch { actions.save() }
            runCurrent()
            actions.save()
            assertEquals(1, downloads)
            actions.release()
            materialized.complete("owned-native-cache-file")
            save.join()
            assertTrue(save.isCancelled)
            assertEquals(0, published)
            actions.save()
            assertEquals(1, downloads)
        }

    /**
     * User destination cancellation is propagated without a success/error callback and permits a later explicit
     * retry.
     */
    @Test
    fun cancelledDestinationDoesNotStickTheSaveAdmission() =
        runTest {
            var calls = 0
            val actions =
                TextAttachmentNativeActions({ true }) {
                    calls += 1
                    if (calls == 1) throw CancellationException("destination cancelled")
                }
            val first = launch { actions.save() }
            first.join()
            assertTrue(first.isCancelled)
            actions.save()
            assertEquals(2, calls)
        }

    /** Selection speaks only its real visible text while the unselected path retains native Markdown projection. */
    @Test
    fun selectedSpeechNeverIncludesUnselectedDocumentText() {
        val candidate = requireNotNull(textAttachmentCandidate("text/markdown", "note.md"))
        val preview = TextAttachmentPreview(candidate, "first paragraph\nsecond paragraph")
        assertSame(preview, textAttachmentSelectedPreview(preview, preview.text))
        val selected = textAttachmentSelectedPreview(preview, "second paragraph")
        assertEquals("second paragraph", selected.text)
        assertNull(selected.markdownDocument)
        val spoken = textAttachmentTtsEntry(selected, "alice", "Alice", "message", 2).text
        assertEquals("second paragraph", spoken.removeSuffix("."))
        assertFalse(spoken.contains("first paragraph"))
    }

    /** Metadata reports bytes actually read, rather than guessing file size from character count. */
    @Test
    fun byteMetadataUsesLoadedUtf8Bytes() =
        runTest {
            val bytes = "héllo".toByteArray(Charsets.UTF_8)
            val candidate = requireNotNull(textAttachmentCandidate("text/plain", "note.txt"))
            val state = loadTextAttachmentPreview(candidate, bytes) { error("Plain text must not parse Markdown") }
            assertEquals(bytes.size.toLong(), (state as TextAttachmentReaderState.Ready).preview.byteCount)
        }

    /** Stop toggling is admitted only for the exact native attachment source, never another message or index. */
    @Test
    fun speechToggleRetainsExactMessageAndAttachmentIdentity() {
        val speaking =
            TtsState.Speaking(
                chunkIndex = 0,
                chunkCount = 1,
                messageIndex = 0,
                messageCount = 1,
                sentenceIndexWithinMessage = 0,
                sentenceCountWithinMessage = 1,
                messagePreview = "selected",
                passage = TtsPassage("attachment:message:2", 0),
            )
        assertTrue(textAttachmentOwnsSpeech(speaking, "message", 2))
        assertFalse(textAttachmentOwnsSpeech(speaking, "other", 2))
        assertFalse(textAttachmentOwnsSpeech(speaking, "message", 1))
        assertFalse(textAttachmentOwnsSpeech(TtsState.Idle(), "message", 2))
    }
}

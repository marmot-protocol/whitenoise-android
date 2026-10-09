package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SelectedMessageDraftAttachmentFfi
import dev.ipf.marmotkit.SelectedMessageDraftContentFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the production native seam without interpreting opaque revision internals in Android. */
class MediaReplyDraftTest {
    /** Voice, file, video and image replies use the typed draft target, including an initially empty slot. */
    @Test
    fun missingDraftStagesExactTargetAndBytes() {
        for (mime in listOf("audio/mp4", "application/pdf", "video/mp4", "image/jpeg", "image/gif", "text/vcard")) {
            val original = revision()
            val staged = revision()
            val attachment = PendingAttachment(byteArrayOf(1, 2, 3), mime, "pick")
            val engine =
                nativeBoundary { method, args ->
                    when (method) {
                        "selectedMessageDraft" -> SelectedMessageDraftFfi(original, null)
                        "saveMessageDraftIfRevision" -> {
                            assertSame(original, args[1])
                            assertEquals("poll-original", args[3])
                            val bytes = (args[4] as List<*>).single() as MessageDraftAttachmentFfi
                            assertEquals(mime, bytes.mediaType)
                            assertTrue(bytes.plaintext.contentEquals(attachment.plaintextBytes))
                            SelectedMessageDraftFfi(staged, null)
                        }
                        else -> error(method)
                    }
                }
            assertSame(staged, engine.stageMediaReply("account", "group", "poll-original", null, listOf(attachment)))
        }
    }

    /** A newer text draft or a different reply is never overwritten to manufacture an outbound slot. */
    @Test
    fun conflictingComposerDraftIsPreserved() {
        for (draft in listOf(content("new text"), content("", "new-target"))) {
            val calls = mutableListOf<String>()
            val engine =
                nativeBoundary { method, _ ->
                    calls += method
                    check(method == "selectedMessageDraft")
                    SelectedMessageDraftFfi(revision(), draft)
                }
            assertNull(engine.stageMediaReply("account", "group", "poll-original", null, listOf(attachment())))
            assertEquals(listOf("selectedMessageDraft"), calls)
        }
    }

    /** An identically named replacement file cannot masquerade as the original selection. */
    @Test
    fun sameNameAndMimeWithDifferentBytesDoesNotOverwriteDraft() {
        val calls = mutableListOf<String>()
        val engine =
            nativeBoundary { method, _ ->
                calls += method
                when (method) {
                    "selectedMessageDraft" -> SelectedMessageDraftFfi(revision(), content("", attachments = listOf(descriptor())))
                    "messageDraftAttachmentIfRevision" -> byteArrayOf(9)
                    else -> error(method)
                }
            }
        assertNull(engine.stageMediaReply("account", "group", "poll-original", null, listOf(attachment())))
        assertFalse(calls.contains("saveMessageDraftIfRevision"))
    }

    /** Matching bytes are read through the same revision used by the conditional reply write. */
    @Test
    fun matchingDraftKeepsNativeAttachmentIdentity() {
        val original = revision()
        val saved = revision()
        val engine =
            nativeBoundary { method, args ->
                when (method) {
                    "selectedMessageDraft" -> SelectedMessageDraftFfi(original, content("caption", attachments = listOf(descriptor())))
                    "messageDraftAttachmentIfRevision" -> {
                        assertSame(original, args[1])
                        assertEquals("id", args[2])
                        byteArrayOf(1)
                    }
                    "saveMessageDraftIfRevision" -> {
                        assertSame(original, args[1])
                        assertEquals("poll-original", args[3])
                        assertEquals("id", ((args[4] as List<*>).single() as MessageDraftAttachmentFfi).id)
                        SelectedMessageDraftFfi(saved, null)
                    }
                    else -> error(method)
                }
            }
        assertSame(saved, engine.stageMediaReply("account", "group", "poll-original", "caption", listOf(attachment())))
    }

    /** A later reply selection is not consulted after upload; native receives the captured revision and token. */
    @Test
    fun mediaPublishUsesCapturedRevisionWithoutRereadingComposer() =
        runTest {
            val captured = revision()
            val calls = mutableListOf<String>()
            val engine =
                nativeBoundary { method, args ->
                    calls += method
                    when (method) {
                        "localSendStatus" -> null
                        "sendMessageDraftWithClientToken" -> {
                            assertSame(captured, args[1])
                            assertEquals("same-token", args[3])
                            LocalSendAcceptanceFfi("same-token", "sent")
                        }
                        else -> error("Newer draft must not be read or sent: $method")
                    }
                }
            engine.sendComposerMedia("account", "group", emptyList(), null, "same-token", captured)
            assertEquals(1, calls.count { it == "sendMessageDraftWithClientToken" })
            assertFalse(calls.contains("selectedMessageDraft"))
        }

    /** A changed revision is rejected without trying an unkeyed or draftless send. */
    @Test
    fun staleRevisionDoesNotFallBack() =
        runTest {
            val calls = mutableListOf<String>()
            val engine =
                nativeBoundary { method, _ ->
                    calls += method
                    when (method) {
                        "localSendStatus" -> null
                        "sendMessageDraftWithClientToken" -> error("revision changed")
                        else -> error("Unexpected fallback: $method")
                    }
                }
            assertTrue(
                runCatching { engine.sendComposerMedia("account", "group", emptyList(), null, "token", revision()) }.isFailure,
            )
            assertFalse(calls.contains("sendMediaAttachments"))
            assertFalse(calls.contains("selectedMessageDraft"))
        }

    /** A selected file fixture uses its opaque native attachment identity. */
    private fun descriptor() = SelectedMessageDraftAttachmentFfi("id", "pick", "image/jpeg", 1uL, null, null, null, emptyList())

    /** Minimal staged plaintext whose identity is compared using the selected revision. */
    private fun attachment() = PendingAttachment(byteArrayOf(1), "image/jpeg", "pick")

    /** Builds descriptor-only composer state without a native runtime. */
    private fun content(
        text: String,
        reply: String? = null,
        attachments: List<SelectedMessageDraftAttachmentFfi> = emptyList(),
    ) = SelectedMessageDraftContentFfi("group", text, reply, attachments, 1L, 1L)

    /** Allocates an opaque token without invoking the native constructor. */
    private fun revision(): MessageDraftRevisionFfi {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, MessageDraftRevisionFfi::class.java)
            as MessageDraftRevisionFfi
    }
}

package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaVideoShareParityTest {
    @Test
    fun directVideoUsesTheSharedMediaViewerWithTheTappedAttachmentIdentity() {
        val body = mediaVideoSource().readText().functionBody("MediaVideoBubble")

        assertTrue(
            "direct videos must use the viewer that owns the Save and Share actions",
            "ConversationMediaViewer(" in body,
        )
        assertFalse(
            "direct videos must not bypass the shared actions through a second fullscreen player",
            "FullscreenVideoPlayer(" in body,
        )
        listOf(
            "messageIdHex = record.messageIdHex",
            "attachments = listOf(IndexedValue(attachmentIndex, reference))",
            "tappedAttachmentIndex = attachmentIndex",
            "sender = record.sender",
            "recordedAt = record.recordedAt",
            "mine = mine",
        ).forEach { expected ->
            assertTrue("direct viewer route must preserve `$expected`", expected in body)
        }
    }

    private fun mediaVideoSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
        ).firstOrNull(File::exists) ?: error("Missing MediaVideo.kt")
}

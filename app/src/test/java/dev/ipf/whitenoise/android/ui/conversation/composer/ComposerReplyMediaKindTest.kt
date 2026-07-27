package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.MediaPreviewFallback
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.core.replyMediaKindFromMime
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerReplyMediaKindTest {
    @Test
    fun noTypedOrCompatibilityMediaReturnsNone() {
        assertEquals(
            ReplyMediaKind.None,
            composerReplyMediaKind(mediaFallback = null, tags = emptyList(), sourceEpoch = null),
        )
    }

    @Test
    fun compatibilityTagWithoutNativeParserFailsSafeToNone() {
        // JVM tests run without the ABI-specific native library, so MarmotKit's
        // compatibility parser is unavailable here — the kind must fail safe to
        // None instead of crashing. Positive wire-parse coverage lives in the
        // marmot-uniffi media fixtures.
        assertEquals(
            ReplyMediaKind.None,
            composerReplyMediaKind(
                mediaFallback = null,
                tags = listOf(MessageTagFfi(listOf("imeta", "url https://media.example/blob", "m image/jpeg"))),
                sourceEpoch = 7uL,
            ),
        )
    }

    @Test
    fun mimeClassificationCoversEachReplyMediaKind() {
        assertEquals(ReplyMediaKind.Voice, replyMediaKindFromMime("audio/mp4"))
        assertEquals(ReplyMediaKind.Photo, replyMediaKindFromMime("IMAGE/heic"))
        assertEquals(ReplyMediaKind.Video, replyMediaKindFromMime("video/mp4"))
        assertEquals(ReplyMediaKind.Document, replyMediaKindFromMime("application/pdf"))
        assertEquals(ReplyMediaKind.None, replyMediaKindFromMime(null))
        assertEquals(ReplyMediaKind.None, replyMediaKindFromMime("  "))
    }

    @Test
    fun typedMediaTakesPriorityWithoutParsingCompatibilityTags() {
        assertEquals(
            ReplyMediaKind.Document,
            composerReplyMediaKind(
                mediaFallback = MediaPreviewFallback(filename = "archive.zip", kind = ReplyMediaKind.Document),
                tags = listOf(MessageTagFfi(listOf("imeta", "invalid"))),
                sourceEpoch = 4uL,
            ),
        )
    }
}

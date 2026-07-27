package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.MediaPreviewFallback
import dev.ipf.whitenoise.android.core.ReplyMediaKind
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

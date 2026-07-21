package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.core.MediaPreviewFallback
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.media.MediaReferenceParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerReplyMediaKindTest {
    @Test
    fun fallsBackToLegacyImetaWhenTypedMediaIsUnavailable() {
        val legacyVideoTag = MediaReferenceParser.toImetaTag(attachment("clip.mp4", "video/mp4"))

        assertEquals(
            ReplyMediaKind.Video,
            composerReplyMediaKind(mediaFallback = null, tags = listOf(legacyVideoTag)),
        )
    }

    @Test
    fun typedMediaTakesPriorityOverLegacyImeta() {
        val legacyVideoTag = MediaReferenceParser.toImetaTag(attachment("clip.mp4", "video/mp4"))

        assertEquals(
            ReplyMediaKind.Document,
            composerReplyMediaKind(
                mediaFallback = MediaPreviewFallback(filename = "archive.zip", kind = ReplyMediaKind.Document),
                tags = listOf(legacyVideoTag),
            ),
        )
    }

    private fun attachment(
        fileName: String,
        mediaType: String,
    ) = MediaAttachmentReferenceFfi(
        locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://media.example/blob")),
        ciphertextSha256 = "aa".repeat(32),
        plaintextSha256 = "bb".repeat(32),
        nonceHex = "cc".repeat(12),
        fileName = fileName,
        mediaType = mediaType,
        version = "encrypted-media-v1",
        sourceEpoch = 1uL,
        dim = null,
        thumbhash = null,
    )
}

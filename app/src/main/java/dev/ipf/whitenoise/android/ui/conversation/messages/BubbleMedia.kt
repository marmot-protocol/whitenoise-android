package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.PendingAttachment

/**
 * Stable projection of confirmed and optimistic media for one message bubble.
 *
 * Partitioning here keeps protocol-index bookkeeping and MIME classification out
 * of the much larger [MessageBubble] composition scope. Indexed values retain
 * the attachment's position in the original protocol list for cache lookups.
 */
@Immutable
internal data class BubbleMedia(
    val images: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    val audio: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    val videos: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    val files: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    val visuals: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    val pendingAudio: List<IndexedValue<PendingAttachment>>,
    val pendingVisuals: List<IndexedValue<MediaAttachmentReferenceFfi>>,
) {
    val hasConfirmedMedia: Boolean
        get() = images.isNotEmpty() || audio.isNotEmpty() || videos.isNotEmpty() || files.isNotEmpty()
}

@Composable
internal fun rememberBubbleMedia(
    mediaReferences: List<MediaAttachmentReferenceFfi>,
    pendingAttachments: List<PendingAttachment>,
): BubbleMedia =
    remember(mediaReferences, pendingAttachments) {
        bubbleMedia(mediaReferences, pendingAttachments)
    }

internal fun bubbleMedia(
    mediaReferences: List<MediaAttachmentReferenceFfi>,
    pendingAttachments: List<PendingAttachment>,
): BubbleMedia {
    val indexedMedia = mediaReferences.withIndex().toList()
    val images = indexedMedia.filter { (_, reference) -> MediaReferenceSupport.isImageMedia(reference) }
    val audio = indexedMedia.filter { (_, reference) -> MediaReferenceSupport.isAudioMedia(reference) }
    val videos = indexedMedia.filter { (_, reference) -> MediaReferenceSupport.isVideoMedia(reference) }
    val files =
        indexedMedia.filter { (_, reference) ->
            !MediaReferenceSupport.isImageMedia(reference) &&
                !MediaReferenceSupport.isAudioMedia(reference) &&
                !MediaReferenceSupport.isVideoMedia(reference)
        }
    val indexedPending = pendingAttachments.withIndex().toList()
    val pendingAudio =
        indexedPending.filter { (_, attachment) ->
            attachment.mediaType.startsWith("audio/", ignoreCase = true)
        }
    val pendingVisuals =
        indexedPending
            .filter { (_, attachment) ->
                attachment.mediaType.startsWith("image/", ignoreCase = true) ||
                    attachment.mediaType.startsWith("video/", ignoreCase = true)
            }.map { (index, attachment) ->
                IndexedValue(
                    index,
                    MediaAttachmentReferenceFfi(
                        locators = emptyList(),
                        ciphertextSha256 = "",
                        plaintextSha256 = "",
                        nonceHex = "",
                        fileName = attachment.fileName,
                        mediaType = attachment.mediaType,
                        version = EncryptedMediaVersionFfi.V1,
                        sourceEpoch = 0uL,
                        dim = attachment.dim,
                        thumbhash = attachment.thumbhash,
                    ),
                )
            }

    return BubbleMedia(
        images = images,
        audio = audio,
        videos = videos,
        files = files,
        visuals = (images + videos).sortedBy { it.index },
        pendingAudio = pendingAudio,
        pendingVisuals = pendingVisuals,
    )
}

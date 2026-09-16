package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.IndexedAttachment
import dev.ipf.whitenoise.android.core.IndexedRejection
import dev.ipf.whitenoise.android.core.MessageAttachments
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
    val rejected: List<IndexedRejection> = emptyList(),
) {
    val hasConfirmedMedia: Boolean
        get() = images.isNotEmpty() || audio.isNotEmpty() || videos.isNotEmpty() || files.isNotEmpty()
}

/**
 * The attachments of one message as MarmotKit reported them: accepted references and rejected slots,
 * both keyed by protocol attachment index, plus the compact reference list older surfaces consume.
 */
@Immutable
internal data class MessageAttachmentSet(
    val accepted: List<IndexedAttachment>,
    val rejected: List<IndexedRejection>,
) {
    /** Accepted references in protocol order, for callers that never derive an index from position. */
    val references: List<MediaAttachmentReferenceFfi> = accepted.map { it.value }

    companion object {
        val Empty = MessageAttachmentSet(emptyList(), emptyList())

        /** Positional references with no rejected slots, for optimistic rows and previews. */
        fun of(references: List<MediaAttachmentReferenceFfi>): MessageAttachmentSet {
            val accepted = MessageAttachments.indexed(references)
            return MessageAttachmentSet(accepted, emptyList())
        }
    }
}

/** Projected outcomes win; rows without a projection fall back to parsing their own `imeta` tags positionally. */
internal fun messageAttachmentSet(
    tags: List<MessageTagFfi>,
    sourceEpoch: ULong?,
    projectedMedia: List<MediaAttachmentOutcomeFfi>?,
): MessageAttachmentSet =
    if (projectedMedia != null) {
        MessageAttachmentSet(MessageAttachments.accepted(projectedMedia), MessageAttachments.rejected(projectedMedia))
    } else {
        MessageAttachmentSet.of(MediaReferenceSupport.parseAllImetaTags(tags, sourceEpoch ?: 0uL))
    }

/** Remembers one message's attachment set across recompositions keyed by its projection inputs. */
@Composable
internal fun rememberMessageAttachments(
    tags: List<MessageTagFfi>,
    messageIdHex: String,
    sourceEpoch: ULong?,
    projectedMedia: List<MediaAttachmentOutcomeFfi>?,
): MessageAttachmentSet =
    remember(tags, messageIdHex, sourceEpoch, projectedMedia) {
        messageAttachmentSet(tags, sourceEpoch, projectedMedia)
    }

/** Remembers the bucketed bubble media for one attachment set and its optimistic pending list. */
@Composable
internal fun rememberBubbleMedia(
    attachments: MessageAttachmentSet,
    pendingAttachments: List<PendingAttachment>,
): BubbleMedia =
    remember(attachments, pendingAttachments) {
        bubbleMedia(attachments, pendingAttachments)
    }

/** Positional-reference convenience for previews and tests without rejected attachments. */
internal fun bubbleMedia(
    mediaReferences: List<MediaAttachmentReferenceFfi>,
    pendingAttachments: List<PendingAttachment>,
): BubbleMedia = bubbleMedia(MessageAttachmentSet.of(mediaReferences), pendingAttachments)

/** Buckets accepted attachments by MIME family, keeping protocol indexes and rejected slots. */
internal fun bubbleMedia(
    attachments: MessageAttachmentSet,
    pendingAttachments: List<PendingAttachment>,
): BubbleMedia {
    val indexedMedia = attachments.accepted
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
        rejected = attachments.rejected,
    )
}

/** Keeps optimistic file chrome exclusive with confirmed, audio, and visual media renderers. */
internal fun shouldShowPendingFilePlaceholder(
    deleted: Boolean,
    hasConfirmedMedia: Boolean,
    pendingAudioCount: Int,
    pendingVisualCount: Int,
    hasPendingMediaMarker: Boolean,
): Boolean =
    !deleted &&
        !hasConfirmedMedia &&
        pendingAudioCount == 0 &&
        pendingVisualCount == 0 &&
        hasPendingMediaMarker

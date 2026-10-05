package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IndexedAttachment
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.refreshAttachmentTransferState
import dev.ipf.whitenoise.android.ui.conversation.media.TileTransfer
import dev.ipf.whitenoise.android.ui.conversation.media.rememberTileTransfer
import dev.ipf.whitenoise.android.ui.medialibrary.mediaAttachments

/** The small visual vocabulary used by a kept attachment, including an explicit unsupported fallback. */
internal enum class KeptAttachmentKind { Image, Audio, Video, File }

/** Metadata and an already-decoded thumbnail; keeping a card never owns bytes or initiates a download. */
@Immutable
internal data class KeptAttachmentPresentation(
    val kind: KeptAttachmentKind,
    val name: String,
    val typeLabel: String,
    val statusLabel: String,
    val thumbnail: ImageBitmap? = null,
)

/** Resolves live attachment metadata and observes existing transfers without requesting media. */
@Composable
internal fun keptAttachmentPresentations(
    message: TimelineMessage,
    controller: ConversationController?,
    thumbnailRevision: Long,
): List<KeptAttachmentPresentation> {
    val accepted = remember(message) { message.mediaAttachments() }
    val result =
        accepted.map { attachment ->
            key(message.record.messageIdHex, attachment.index) {
                keptAcceptedAttachmentPresentation(message, attachment, controller, thumbnailRevision)
            }
        }
    val rejected =
        message.projected
            ?.media
            ?.let(MessageAttachments::rejected)
            .orEmpty()
    return when {
        rejected.isNotEmpty() ->
            result +
                keptAttachmentPresentation(
                    KeptAttachmentKind.File,
                    "",
                    stringResource(R.string.media_attachment_unavailable),
                    null,
                )
        result.isNotEmpty() -> result
        else -> keptPendingAttachmentPresentations(message, controller)
    }
}

/** Reads one existing transfer and cached thumbnail; neither path admits a new media request. */
@Composable
private fun keptAcceptedAttachmentPresentation(
    message: TimelineMessage,
    attachment: IndexedAttachment,
    controller: ConversationController?,
    thumbnailRevision: Long,
): KeptAttachmentPresentation {
    val (index, reference) = attachment
    val kind =
        when {
            MediaReferenceSupport.isImageMedia(reference) -> KeptAttachmentKind.Image
            MediaReferenceSupport.isAudioMedia(reference) -> KeptAttachmentKind.Audio
            MediaReferenceSupport.isVideoMedia(reference) -> KeptAttachmentKind.Video
            else -> KeptAttachmentKind.File
        }
    val transfer =
        controller?.let {
            rememberTileTransfer(
                controller = it,
                messageIdHex = message.record.messageIdHex,
                attachmentIndex = index,
                reference = reference,
                mine = it.isMessageMine(message.record),
                suppressed = false,
                observeNative = false,
            )
        }
    LaunchedEffect(controller, message.record.messageIdHex, index, thumbnailRevision) {
        controller?.refreshAttachmentTransferState(message.record.messageIdHex, index)
    }
    val thumbnail =
        remember(controller, message.record.messageIdHex, index, thumbnailRevision) {
            controller?.thumbnailFor(message.record.messageIdHex, index)?.asImageBitmap()
        }
    return keptAttachmentPresentation(
        kind,
        reference.fileName.orEmpty(),
        stringResource(keptTransferLabel(transfer)),
        thumbnail,
    )
}

/** Uses current observable availability instead of inferring success from a thumbnail or ciphertext. */
private fun keptTransferLabel(transfer: TileTransfer?): Int =
    when {
        transfer?.failed == true -> R.string.floating_attachment_failed
        transfer?.active == true -> R.string.media_downloading
        transfer?.state == AttachmentTransferState.Resolving -> R.string.floating_attachment_resolving
        transfer?.state == AttachmentTransferState.Available -> R.string.floating_attachment_available
        transfer?.state == AttachmentTransferState.NotRetained -> R.string.media_attachment_unavailable
        else -> R.string.floating_attachment_remote
    }

/** Pending uploads still identify their files before MDK has supplied an accepted media reference. */
@Composable
private fun keptPendingAttachmentPresentations(
    message: TimelineMessage,
    controller: ConversationController?,
): List<KeptAttachmentPresentation> {
    val pending = controller?.pendingAttachmentsList(message.record.messageIdHex).orEmpty()
    val status =
        stringResource(
            if (message.status == MessageStatus.Failed) R.string.media_upload_failed else R.string.media_uploading,
        )
    if (pending.isNotEmpty()) {
        return pending.map {
            keptAttachmentPresentation(keptAttachmentKind(it.mediaType), it.fileName, status, null)
        }
    }
    val marker = message.record.tags.firstOrNull { it.values.firstOrNull() == "_media_pending" }
    return if (marker != null) {
        listOf(keptAttachmentPresentation(KeptAttachmentKind.File, marker.values.getOrNull(1).orEmpty(), status, null))
    } else {
        emptyList()
    }
}

/** MIME-family classification for optimistic metadata that does not yet have a reference. */
private fun keptAttachmentKind(mediaType: String): KeptAttachmentKind =
    when {
        mediaType.startsWith("image/", true) -> KeptAttachmentKind.Image
        mediaType.startsWith("audio/", true) -> KeptAttachmentKind.Audio
        mediaType.startsWith("video/", true) -> KeptAttachmentKind.Video
        else -> KeptAttachmentKind.File
    }

/** Localizes the type and supplies a meaningful title when the source has no file name. */
@Composable
private fun keptAttachmentPresentation(
    kind: KeptAttachmentKind,
    name: String,
    status: String,
    thumbnail: ImageBitmap?,
): KeptAttachmentPresentation {
    val label =
        stringResource(
            when (kind) {
                KeptAttachmentKind.Image -> R.string.attachment_type_image
                KeptAttachmentKind.Audio -> R.string.attachment_type_audio
                KeptAttachmentKind.Video -> R.string.attachment_type_video
                KeptAttachmentKind.File -> R.string.attachment_type_file
            },
        )
    return KeptAttachmentPresentation(kind, name.ifBlank { label }, label, status, thumbnail)
}

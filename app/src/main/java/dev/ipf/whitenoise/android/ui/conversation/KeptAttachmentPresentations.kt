package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IndexedAttachment
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.attachmentCancellationState
import dev.ipf.whitenoise.android.state.attachmentFilePresentationState
import dev.ipf.whitenoise.android.state.attachmentNativeProgress
import dev.ipf.whitenoise.android.state.hasCachedAttachmentInMemory
import dev.ipf.whitenoise.android.state.refreshAttachmentTransferState
import dev.ipf.whitenoise.android.ui.conversation.media.TileTransfer
import dev.ipf.whitenoise.android.ui.conversation.media.rememberAttachmentFirstFrameCacheResolution
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
    val transfer = controller?.let { rememberKeptAttachmentTransfer(it, message, attachment, thumbnailRevision) }
    val decodedRevision =
        controller
            ?.appState
            ?.mediaThumbnailRevision
            ?.collectAsStateWithLifecycle()
            ?.value
    val thumbnail =
        remember(controller, message.record.messageIdHex, index, decodedRevision) {
            controller?.thumbnailFor(message.record.messageIdHex, index)?.asImageBitmap()
        }
    return keptAttachmentPresentation(
        kind,
        reference.fileName.orEmpty(),
        stringResource(keptTransferLabel(transfer)),
        thumbnail,
    )
}

/** Observes existing host/native work for every media type without acquiring or materializing bytes. */
@Composable
private fun rememberKeptAttachmentTransfer(
    controller: ConversationController,
    message: TimelineMessage,
    attachment: IndexedAttachment,
    cacheRevision: Long,
): TileTransfer {
    val id = message.record.messageIdHex
    val (index, reference) = attachment
    val initiallyAvailable = remember(controller, id, index) { controller.hasCachedAttachmentInMemory(id, index) }
    val host by remember(controller, id, index) {
        controller.attachmentTransferState(id, index, initiallyAvailable)
    }.collectAsStateWithLifecycle()
    DisposableEffect(controller, id, index) {
        onDispose { controller.releaseAttachmentTransferState(id, index) }
    }
    val native by remember(controller, id, index, reference.ciphertextSha256, reference.sourceEpoch) {
        controller.attachmentNativeProgress(id, index)
    }.collectAsStateWithLifecycle(initialValue = null)
    val cancellation by remember(controller, id, index) {
        controller.attachmentCancellationState(id, index)
    }.collectAsStateWithLifecycle()
    val cacheResolved =
        rememberAttachmentFirstFrameCacheResolution(controller, "$id#$index", initiallyAvailable) {
            controller.refreshAttachmentTransferState(id, index)
        }
    LaunchedEffect(controller, id, index, cacheRevision) {
        controller.refreshAttachmentTransferState(id, index)
    }
    val progress = if (controller.isMessageMine(message.record) && !cacheResolved) null else native
    return TileTransfer(
        state = attachmentFilePresentationState(host, progress, cancellation),
        progress = progress,
        cancellation = cancellation,
        suppressed = false,
        onCancel = {},
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

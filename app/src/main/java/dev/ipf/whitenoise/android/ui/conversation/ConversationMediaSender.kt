package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.ImageAnimationStatus
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.Thumbhash
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.media.documentPickTreatAsImage
import dev.ipf.whitenoise.android.ui.conversation.media.queryContentSize
import dev.ipf.whitenoise.android.ui.conversation.media.queryDisplayName
import dev.ipf.whitenoise.android.ui.conversation.media.safeGetType
import dev.ipf.whitenoise.android.ui.conversation.share.SharedContact
import dev.ipf.whitenoise.android.ui.conversation.share.VCARD_MIME_TYPE
import dev.ipf.whitenoise.android.ui.conversation.share.buildVCard
import dev.ipf.whitenoise.android.ui.conversation.share.contactVCardFileName
import dev.ipf.whitenoise.android.ui.conversation.share.formatContactShareText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Keep picker limits aligned with the retained-upload LRU. A larger value
// could evict an attachment during its own insert and make retries impossible.
private const val MEDIA_ATTACHMENT_MAX_BYTES = ConversationController.MEDIA_RETAINED_MAX_BYTES
private const val MEDIA_ALBUM_MAX_TOTAL_BYTES = ConversationController.MEDIA_RETAINED_MAX_BYTES

private data class DocumentReadOutcome(
    val attachments: List<PendingAttachment>,
    val rejected: Boolean,
    val albumOverflowed: Boolean,
    val totalBytes: Long,
)

private data class VisualReadOutcome(
    val attachments: List<PendingAttachment>,
    val albumOverflowed: Boolean,
)

private class ConversationAttachmentReader(
    private val appState: WhiteNoiseAppState,
    private val context: Context,
) {
    fun readImageAttachment(
        uri: android.net.Uri,
        remainingBytes: Long,
    ): ImageAttachmentReadOutcome {
        val quality = appState.mediaQuality
        // Animated images cannot survive JPEG recompression. Preserve their
        // original bytes at every quality setting rather than flattening them.
        // An indeterminate provider/header probe also fails closed.
        val animationStatus = MediaPipeline.imageAnimationStatus(context.contentResolver, uri)
        val requiresOriginalBytes = animationStatus != ImageAnimationStatus.STATIC
        val original =
            if (quality.preservesOriginalImageBytes || requiresOriginalBytes) {
                readOriginalImageAttachment(uri, remainingBytes, requiresOriginalBytes)
            } else {
                null
            }
        return original ?: readRecompressedImageAttachment(uri, remainingBytes)
    }

    private fun readOriginalImageAttachment(
        uri: android.net.Uri,
        remainingBytes: Long,
        requiresOriginalBytes: Boolean,
    ): ImageAttachmentReadOutcome? {
        val cap = remainingBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return when (val original = MediaPipeline.readOriginalImageForUpload(context.contentResolver, uri, cap)) {
            is MediaPipeline.OriginalImageReadResult.Success ->
                ImageAttachmentReadOutcome(
                    PendingAttachment(
                        plaintextBytes = original.image.bytes,
                        mediaType = original.image.mediaType,
                        fileName = original.image.fileName,
                        dim = original.image.dim,
                        thumbhash = original.image.thumbhash,
                    ),
                )
            MediaPipeline.OriginalImageReadResult.TooLarge ->
                ImageAttachmentReadOutcome(null, overflowed = true)
            MediaPipeline.OriginalImageReadResult.Failed,
            MediaPipeline.OriginalImageReadResult.Unsupported,
            -> if (requiresOriginalBytes) ImageAttachmentReadOutcome(null) else null
        }
    }

    private fun readRecompressedImageAttachment(
        uri: android.net.Uri,
        remainingBytes: Long,
    ): ImageAttachmentReadOutcome {
        val quality = appState.mediaQuality
        val jpeg =
            MediaPipeline.readDownscaledJpeg(
                context.contentResolver,
                uri,
                maxEdgePx = quality.imageMaxEdgePx,
                quality = quality.imageJpegQuality,
            )
        return when {
            jpeg == null -> ImageAttachmentReadOutcome(null)
            jpeg.bytes.size.toLong() > remainingBytes -> ImageAttachmentReadOutcome(null, overflowed = true)
            else -> {
                val sourceName = queryDisplayName(context.contentResolver, uri) ?: "image.jpg"
                ImageAttachmentReadOutcome(
                    PendingAttachment(
                        plaintextBytes = jpeg.bytes,
                        mediaType = MediaPipeline.RECOMPRESSED_MIME,
                        fileName = MediaPipeline.swapExtensionToJpg(sourceName),
                        dim = "${jpeg.width}x${jpeg.height}",
                        thumbhash = jpeg.thumbhash,
                    ),
                )
            }
        }
    }

    // Read picked document URIs into attachments. Non-image documents are kept
    // as raw bytes; image/* picks from Files use the same media-quality and
    // metadata-stripping path as visual image picks before joining the document
    // send path. MIME comes from the content resolver; filename from
    // `OpenableColumns.DISPLAY_NAME`.
    //
    // Two-layer size guard:
    //   1. Per-attachment ceiling: skip any single pick that already declares
    //      a `OpenableColumns.SIZE` greater than [MEDIA_ATTACHMENT_MAX_BYTES],
    //      OR overruns the cap during a bounded streaming read (no fully-
    //      buffered `readBytes()` so a 500 MB pick can't OOM the JVM heap
    //      before the retained-uploads LRU has anything to evict).
    //   2. Album-total ceiling: stop accumulating once the cumulative payload
    //      crosses [MEDIA_ALBUM_MAX_TOTAL_BYTES]; remaining picks are dropped.
    //
    // Any reject surfaces a single user-visible toast; the rest of the album
    // continues. If NOTHING survives the gates we bail without an empty send.
    // Decoded outcome of the document read pass, surfaced so the unified
    // sendStagedAttachments path can blend its results with the image decode.
    private data class DocumentReadAccumulator(
        val attachments: MutableList<PendingAttachment> = mutableListOf(),
        var rejected: Boolean = false,
        var albumOverflowed: Boolean = false,
        var totalBytes: Long = 0L,
    ) {
        fun outcome(): DocumentReadOutcome = DocumentReadOutcome(attachments, rejected, albumOverflowed, totalBytes)
    }

    suspend fun readPickedDocuments(
        uris: List<android.net.Uri>,
        bytesBudget: Long = MEDIA_ALBUM_MAX_TOTAL_BYTES,
    ): DocumentReadOutcome =
        withContext(Dispatchers.IO) {
            val state = DocumentReadAccumulator()
            for (uri in uris) {
                if (state.totalBytes >= bytesBudget) {
                    state.albumOverflowed = true
                    break
                }
                readPickedDocument(uri, bytesBudget, state)
            }
            state.outcome()
        }

    private fun readPickedDocument(
        uri: android.net.Uri,
        bytesBudget: Long,
        state: DocumentReadAccumulator,
    ) {
        val reportedMime = safeGetType(context.contentResolver, uri)
        val resolvedMime = reportedMime.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val remainingBytes = (bytesBudget - state.totalBytes).coerceAtLeast(0L)
        val sniffedImageMime =
            if (reportedMime.isBlank() || reportedMime.equals("application/octet-stream", ignoreCase = true)) {
                MediaPipeline.sniffImageMediaType(context.contentResolver, uri)
            } else {
                null
            }
        if (documentPickTreatAsImage(reportedMime, sniffedImageMime)) {
            readImageDocument(uri, remainingBytes, bytesBudget, state)
        } else {
            readRawDocument(uri, resolvedMime, remainingBytes, bytesBudget, state)
        }
    }

    private fun readImageDocument(
        uri: android.net.Uri,
        remainingBytes: Long,
        bytesBudget: Long,
        state: DocumentReadAccumulator,
    ) {
        val image = readImageAttachment(uri, remainingBytes)
        val attachment = image.attachment
        when {
            image.overflowed -> state.albumOverflowed = true
            attachment == null -> state.rejected = true
            attachment.plaintextBytes.isEmpty() -> Unit
            state.totalBytes + attachment.plaintextBytes.size > bytesBudget -> state.albumOverflowed = true
            else -> {
                state.totalBytes += attachment.plaintextBytes.size
                state.attachments += attachment
            }
        }
    }

    private fun readRawDocument(
        uri: android.net.Uri,
        mediaType: String,
        remainingBytes: Long,
        bytesBudget: Long,
        state: DocumentReadAccumulator,
    ) {
        val declaredSize = queryContentSize(context.contentResolver, uri)
        if (declaredSize > 0L && declaredSize > MEDIA_ATTACHMENT_MAX_BYTES) {
            state.rejected = true
        } else {
            val perFileCap =
                minOf(MEDIA_ATTACHMENT_MAX_BYTES, remainingBytes)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            val bytes =
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        MediaPipeline.readBoundedBytes(stream, perFileCap)
                    }
                }.getOrNull()
            when {
                bytes == null -> state.rejected = true
                bytes.isEmpty() -> Unit
                state.totalBytes + bytes.size > bytesBudget -> state.albumOverflowed = true
                else -> {
                    state.totalBytes += bytes.size
                    state.attachments +=
                        PendingAttachment(
                            plaintextBytes = bytes,
                            mediaType = mediaType,
                            fileName = queryDisplayName(context.contentResolver, uri) ?: "file",
                            dim = null,
                        )
                }
            }
        }
    }

    suspend fun readPickedImages(uris: List<android.net.Uri>): VisualReadOutcome =
        withContext(Dispatchers.Default) {
            val attachments = mutableListOf<PendingAttachment>()
            var consumedBytes = 0L
            var overflowed = false
            for (uri in uris) {
                val remainingBytes = (MEDIA_ALBUM_MAX_TOTAL_BYTES - consumedBytes).coerceAtLeast(0L)
                if (remainingBytes == 0L) {
                    overflowed = true
                    break
                }
                val outcome = readVisualAttachment(uri, remainingBytes)
                overflowed = overflowed || outcome.overflowed
                outcome.attachment?.let { attachment ->
                    consumedBytes += attachment.plaintextBytes.size
                    attachments += attachment
                }
            }
            VisualReadOutcome(attachments, overflowed)
        }

    private fun readVisualAttachment(
        uri: android.net.Uri,
        remainingBytes: Long,
    ): ImageAttachmentReadOutcome {
        val mime = safeGetType(context.contentResolver, uri)
        return if (mime.startsWith("video/", ignoreCase = true)) {
            when (val result = MediaPipeline.readVideoForUpload(context, uri, remainingBytes)) {
                is MediaPipeline.VideoReadResult.Success ->
                    ImageAttachmentReadOutcome(
                        PendingAttachment(
                            plaintextBytes = result.video.bytes,
                            mediaType = result.video.mediaType,
                            fileName = result.video.fileName,
                            dim = "${result.video.width}x${result.video.height}",
                            thumbhash = result.video.thumbhash,
                        ),
                    )
                MediaPipeline.VideoReadResult.TooLarge -> ImageAttachmentReadOutcome(null, overflowed = true)
                MediaPipeline.VideoReadResult.Failed -> ImageAttachmentReadOutcome(null)
            }
        } else {
            readImageAttachment(uri, remainingBytes)
        }
    }
}

/**
 * Media read/transform/send operations owned by a conversation.
 *
 * Keeping this holder outside [ConversationScreen] removes blocking I/O and
 * attachment policy from the screen's already busy composition scope while
 * preserving the controller and app-state lifetime of each operation.
 */
internal class ConversationMediaSender(
    private val appState: WhiteNoiseAppState,
    private val controller: ConversationController,
    private val context: Context,
    private val onRevealSent: () -> Unit,
) {
    private val attachmentReader = ConversationAttachmentReader(appState, context)

    fun sendSharedContact(contact: SharedContact) {
        appState.launchMutation {
            val vcardBytes =
                withContext(Dispatchers.IO) {
                    buildVCard(contact).toByteArray(Charsets.UTF_8)
                }
            // The vCard rides the existing media pipeline as a text/vcard
            // attachment (portable — any client can save it), and the caption
            // carries the human-readable name/phone so a peer with no contact
            // renderer still reads it, and our own bubble draws a card from it.
            val attachment =
                PendingAttachment(
                    plaintextBytes = vcardBytes,
                    mediaType = VCARD_MIME_TYPE,
                    fileName = contactVCardFileName(contact),
                )
            val caption = formatContactShareText(contact).ifBlank { null }
            val seeded = controller.queueAttachments(listOf(attachment), caption) ?: return@launchMutation
            onRevealSent()
            controller.uploadQueued(seeded)
        }
    }

    fun sendVoiceAttachment(
        file: java.io.File,
        durationMs: Long,
    ) {
        appState.launchMutation {
            val bytes =
                withContext(Dispatchers.IO) {
                    runCatching { file.readBytes() }.getOrNull()
                }
            withContext(Dispatchers.IO) { runCatching { file.delete() } }
            if (bytes == null || bytes.isEmpty()) return@launchMutation
            val attachment =
                PendingAttachment(
                    plaintextBytes = bytes,
                    mediaType = dev.ipf.whitenoise.android.audio.VoiceRecorder.MIME_TYPE,
                    fileName = "voice-${durationMs}ms.${dev.ipf.whitenoise.android.audio.VoiceRecorder.FILE_EXTENSION}",
                )
            val seeded = controller.queueAttachments(listOf(attachment), null) ?: return@launchMutation
            onRevealSent()
            controller.uploadQueued(seeded)
        }
    }

    private data class BudgetedAttachments(
        val attachments: List<PendingAttachment>,
        val totalBytes: Long,
        val overflowed: Boolean,
    )

    private data class PreparedStagedAttachments(
        val images: List<PendingAttachment>,
        val documents: DocumentReadOutcome,
        val imageOverflowed: Boolean,
        val visualFailureToast: Int,
    ) {
        val isEmpty: Boolean
            get() = images.isEmpty() && documents.attachments.isEmpty()
    }

    fun sendStagedAttachments(
        imageUris: List<android.net.Uri>,
        documentUris: List<android.net.Uri>,
        caption: String,
        onAccepted: () -> Unit = {},
        onRejected: () -> Unit = {},
        onAfterSend: () -> Unit = {},
    ) {
        if (imageUris.isEmpty() && documentUris.isEmpty()) {
            onRejected()
            return
        }
        val trimmedCaption = caption.trim().takeIf { it.isNotBlank() }
        appState.launchMutation {
            val prepared = prepareStagedAttachments(imageUris, documentUris)
            if (!acceptPreparedAttachments(prepared, imageUris.size)) {
                onRejected()
                return@launchMutation
            }
            val readyDocuments =
                prepared.documents.copy(
                    attachments = addMissingThumbhashes(prepared.documents.attachments),
                )
            val seeded =
                seedPreparedAttachments(
                    prepared.copy(documents = readyDocuments),
                    trimmedCaption,
                )
            if (seeded.isEmpty()) {
                onRejected()
                return@launchMutation
            }
            onAccepted()
            onAfterSend()
            seeded.forEach { controller.uploadQueued(it) }
        }
    }

    private suspend fun prepareStagedAttachments(
        imageUris: List<android.net.Uri>,
        documentUris: List<android.net.Uri>,
    ): PreparedStagedAttachments {
        val rawImages = attachmentReader.readPickedImages(imageUris)
        val images = limitAttachmentsToBudget(rawImages.attachments, MEDIA_ALBUM_MAX_TOTAL_BYTES)
        val documentBudget = (MEDIA_ALBUM_MAX_TOTAL_BYTES - images.totalBytes).coerceAtLeast(0L)
        val documents =
            if (documentUris.isEmpty()) {
                DocumentReadOutcome(emptyList(), rejected = false, albumOverflowed = false, totalBytes = 0L)
            } else {
                attachmentReader.readPickedDocuments(documentUris, documentBudget)
            }
        val pickHasVideo =
            imageUris.any {
                safeGetType(context.contentResolver, it).startsWith("video/", ignoreCase = true)
            }
        return PreparedStagedAttachments(
            images = images.attachments,
            documents = documents,
            imageOverflowed = rawImages.albumOverflowed || images.overflowed,
            visualFailureToast =
                if (pickHasVideo) R.string.toast_couldnt_process_video else R.string.toast_couldnt_decode_image,
        )
    }

    private fun limitAttachmentsToBudget(
        attachments: List<PendingAttachment>,
        bytesBudget: Long,
    ): BudgetedAttachments {
        val accepted = mutableListOf<PendingAttachment>()
        var totalBytes = 0L
        var overflowed = false
        attachments.forEach { attachment ->
            val nextTotal = totalBytes + attachment.plaintextBytes.size
            if (nextTotal > bytesBudget) {
                overflowed = true
            } else {
                totalBytes = nextTotal
                accepted += attachment
            }
        }
        return BudgetedAttachments(accepted, totalBytes, overflowed)
    }

    private suspend fun addMissingThumbhashes(attachments: List<PendingAttachment>): List<PendingAttachment> =
        if (attachments.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                attachments.map { attachment ->
                    if (!attachment.mediaType.startsWith("image/", ignoreCase = true) || attachment.thumbhash != null) {
                        attachment
                    } else {
                        val bitmap =
                            MediaPipeline.decodeSampledBitmap(
                                attachment.plaintextBytes,
                                MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
                            )
                        val hash = bitmap?.let { Thumbhash.encodeFromBitmap(it) }
                        bitmap?.recycle()
                        attachment.copy(thumbhash = hash)
                    }
                }
            }
        }

    private fun acceptPreparedAttachments(
        prepared: PreparedStagedAttachments,
        imagePickCount: Int,
    ): Boolean {
        if (prepared.isEmpty && imagePickCount > 0) {
            val toast =
                if (prepared.imageOverflowed) R.string.media_album_too_large else prepared.visualFailureToast
            appState.present(toast, copyable = !prepared.imageOverflowed)
            return false
        }
        if (prepared.images.size < imagePickCount && !prepared.imageOverflowed) {
            appState.present(prepared.visualFailureToast, copyable = true)
        }
        if (prepared.imageOverflowed || prepared.documents.albumOverflowed) {
            appState.present(R.string.media_album_too_large)
        } else if (prepared.documents.rejected) {
            appState.present(R.string.media_file_too_large)
        }
        return !prepared.isEmpty
    }

    private suspend fun seedPreparedAttachments(
        prepared: PreparedStagedAttachments,
        caption: String?,
    ): List<ConversationController.QueuedAttachmentSend> {
        val seeded = mutableListOf<ConversationController.QueuedAttachmentSend>()
        if (prepared.images.isNotEmpty()) {
            controller.queueAttachments(prepared.images, caption)?.let(seeded::add)
        }
        val captionConsumedByImages = prepared.images.isNotEmpty()
        prepared.documents.attachments.forEachIndexed { index, attachment ->
            val itemCaption = if (!captionConsumedByImages && index == 0) caption else null
            controller.queueAttachments(listOf(attachment), itemCaption)?.let(seeded::add)
        }
        return seeded
    }
}

@Composable
internal fun rememberConversationMediaSender(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    context: Context,
    onRevealSent: () -> Unit,
): ConversationMediaSender {
    val currentOnRevealSent = rememberUpdatedState(onRevealSent)
    return remember(appState, controller, context) {
        ConversationMediaSender(
            appState = appState,
            controller = controller,
            context = context,
            onRevealSent = { currentOnRevealSent.value() },
        )
    }
}

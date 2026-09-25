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
import dev.ipf.whitenoise.android.ui.conversation.media.BoundedDocumentRead
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.normalizeDocumentMime
import dev.ipf.whitenoise.android.ui.conversation.media.queryContentSize
import dev.ipf.whitenoise.android.ui.conversation.media.queryDisplayName
import dev.ipf.whitenoise.android.ui.conversation.media.readBoundedDocument
import dev.ipf.whitenoise.android.ui.conversation.media.safeDocumentDisplayName
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

internal data class DocumentReadOutcome(
    val attachments: List<PendingAttachment>,
    val failures: Set<DocumentReadFailure>,
    val albumOverflowed: Boolean,
    val totalBytes: Long,
)

internal enum class DocumentReadFailure {
    TOO_LARGE,
    UNREADABLE,
    EMPTY,
    UNPROCESSABLE_IMAGE,
}

private val DocumentReadFailure.messageResource: Int
    get() =
        when (this) {
            DocumentReadFailure.TOO_LARGE -> R.string.media_file_too_large
            DocumentReadFailure.UNREADABLE -> R.string.media_file_unreadable
            DocumentReadFailure.EMPTY -> R.string.media_file_empty
            DocumentReadFailure.UNPROCESSABLE_IMAGE -> R.string.toast_couldnt_decode_image
        }

internal data class VisualReadOutcome(
    val attachments: List<PendingAttachment>,
    val albumOverflowed: Boolean,
)

@Suppress("TooManyFunctions") // MIME-specific readers share one byte-budget and sanitization policy.
internal class ConversationAttachmentReader(
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
        val animationStatus = MediaPipeline.imageAnimationStatus(context.contentResolver, uri)
        val preserveOriginalSource = animationStatus != ImageAnimationStatus.STATIC
        val original =
            if (quality.preservesOriginalImageBytes || preserveOriginalSource) {
                readOriginalImageAttachment(uri, remainingBytes, animationStatus)
            } else {
                null
            }
        return original ?: readRecompressedImageAttachment(uri, remainingBytes)
    }

    private fun readOriginalImageAttachment(
        uri: android.net.Uri,
        remainingBytes: Long,
        animationStatus: ImageAnimationStatus,
    ): ImageAttachmentReadOutcome? {
        val mustPreserveSource = animationStatus != ImageAnimationStatus.STATIC
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
            MediaPipeline.OriginalImageReadResult.Unsupported ->
                if (animationStatus == ImageAnimationStatus.ANIMATED) {
                    readRawAnimatedImageAttachment(uri, cap)
                } else if (mustPreserveSource) {
                    ImageAttachmentReadOutcome(null)
                } else {
                    null
                }
            MediaPipeline.OriginalImageReadResult.Failed ->
                if (mustPreserveSource) ImageAttachmentReadOutcome(null) else null
        }
    }

    /** Preserve animation only after a lossless metadata rewrite succeeds. */
    @Suppress("ReturnCount") // Provider and size failures must stop before allocating or flattening animation.
    private fun readRawAnimatedImageAttachment(
        uri: android.net.Uri,
        maxBytes: Int,
    ): ImageAttachmentReadOutcome {
        val mediaType = safeGetType(context.contentResolver, uri)
        if (!mediaType.startsWith("image/", ignoreCase = true)) return ImageAttachmentReadOutcome(null)
        val bytes =
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    MediaPipeline.readBoundedBytes(stream, maxBytes)
                }
            }.getOrNull() ?: return ImageAttachmentReadOutcome(null)
        val sanitized = MediaPipeline.sanitizeAnimatedImageMetadata(bytes) ?: return ImageAttachmentReadOutcome(null)
        val sanitizedMediaType = MediaPipeline.sniffImageMediaType(sanitized) ?: return ImageAttachmentReadOutcome(null)
        return ImageAttachmentReadOutcome(
            PendingAttachment(
                plaintextBytes = sanitized,
                mediaType = sanitizedMediaType,
                fileName =
                    MediaPipeline.safeDisplayName(
                        queryDisplayName(context.contentResolver, uri) ?: "animated-image",
                    ),
                dim = MediaPipeline.imageDimOrNull(sanitized),
            ),
        )
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

    // Read document-picker URIs as files. Static or animated images selected
    // through Files follow the same metadata-safe image policy as Photo Picker
    // selections. Non-image documents remain byte-for-byte file attachments.
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
        val failures: MutableSet<DocumentReadFailure> = mutableSetOf(),
        var albumOverflowed: Boolean = false,
        var totalBytes: Long = 0L,
    ) {
        fun outcome(): DocumentReadOutcome = DocumentReadOutcome(attachments, failures, albumOverflowed, totalBytes)
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
        val resolvedMime = normalizeDocumentMime(safeGetType(context.contentResolver, uri))
        val remainingBytes = (bytesBudget - state.totalBytes).coerceAtLeast(0L)
        val sniffedImageMime = MediaPipeline.sniffImageMediaType(context.contentResolver, uri)
        if (isImageDocumentPick(resolvedMime, sniffedImageMime)) {
            readSanitizedImageDocument(uri, remainingBytes, state)
        } else {
            readRawDocument(uri, resolvedMime, remainingBytes, state)
        }
    }

    private fun readSanitizedImageDocument(
        uri: android.net.Uri,
        remainingBytes: Long,
        state: DocumentReadAccumulator,
    ) {
        val outcome = readImageAttachment(uri, remainingBytes)
        val attachment = outcome.attachment
        when {
            outcome.overflowed && remainingBytes < MEDIA_ATTACHMENT_MAX_BYTES -> state.albumOverflowed = true
            outcome.overflowed -> state.failures += DocumentReadFailure.TOO_LARGE
            attachment == null -> state.failures += DocumentReadFailure.UNPROCESSABLE_IMAGE
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
        state: DocumentReadAccumulator,
    ) {
        val declaredSize = queryContentSize(context.contentResolver, uri)
        if (declaredSize > MEDIA_ATTACHMENT_MAX_BYTES) {
            state.failures += DocumentReadFailure.TOO_LARGE
        } else {
            val perFileCap =
                minOf(MEDIA_ATTACHMENT_MAX_BYTES, remainingBytes)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            when (val read = readBoundedDocument(perFileCap) { context.contentResolver.openInputStream(uri) }) {
                BoundedDocumentRead.TooLarge -> {
                    if (remainingBytes < MEDIA_ATTACHMENT_MAX_BYTES) {
                        state.albumOverflowed = true
                    } else {
                        state.failures += DocumentReadFailure.TOO_LARGE
                    }
                }
                BoundedDocumentRead.Empty -> state.failures += DocumentReadFailure.EMPTY
                BoundedDocumentRead.Unreadable -> state.failures += DocumentReadFailure.UNREADABLE
                is BoundedDocumentRead.Success -> {
                    state.totalBytes += read.bytes.size
                    state.attachments +=
                        PendingAttachment(
                            plaintextBytes = read.bytes,
                            mediaType = mediaType,
                            fileName = safeDocumentDisplayName(queryDisplayName(context.contentResolver, uri)),
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

    /** Reads one visual pick through the same transform and byte limits used at send time. */
    suspend fun readVisualDraft(uri: android.net.Uri): PendingAttachment? =
        withContext(Dispatchers.Default) {
            readVisualAttachment(uri, MEDIA_ALBUM_MAX_TOTAL_BYTES).attachment
        }

    /** Reads one document pick through the same MIME and byte limits used at send time. */
    suspend fun readDocumentDraft(uri: android.net.Uri): PendingAttachment? {
        val outcome = readPickedDocuments(listOf(uri))
        return outcome.attachments.singleOrNull()
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

internal fun isImageDocumentPick(
    reportedMime: String,
    sniffedImageMime: String?,
): Boolean = reportedMime.startsWith("image/", ignoreCase = true) || sniffedImageMime != null

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

    /** Keeps the reviewed file until native optimistic acceptance; upload retries then retain the native bytes. */
    fun sendVoiceAttachment(
        file: java.io.File,
        durationMs: Long,
        canSend: () -> Boolean,
        onQueued: (Boolean) -> Unit,
    ) {
        appState.launchMutation {
            var accepted = false
            try {
                if (!canSend()) return@launchMutation
                val bytes =
                    withContext(Dispatchers.IO) {
                        runCatching { file.readBytes() }.getOrNull()
                    }
                if (!canSend() || bytes == null || bytes.isEmpty()) return@launchMutation
                val attachment =
                    PendingAttachment(
                        plaintextBytes = bytes,
                        mediaType = dev.ipf.whitenoise.android.audio.VoiceRecorder.MIME_TYPE,
                        fileName =
                            "voice-${durationMs}ms.${dev.ipf.whitenoise.android.audio.VoiceRecorder.FILE_EXTENSION}",
                    )
                val seeded =
                    controller.queueAttachments(
                        listOf(attachment),
                        null,
                        canQueue = { canSend() && controller.canSendMessages },
                    ) ?: return@launchMutation
                accepted = true
                onQueued(true)
                onRevealSent()
                controller.uploadQueued(seeded)
            } finally {
                if (!accepted) onQueued(false)
            }
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

    /** Sends the staged images and documents with the caption as one message. */
    fun sendStagedAttachments(
        imageSlots: List<PendingMediaSlot>,
        documentUris: List<android.net.Uri>,
        caption: String,
        preparedImageAttachments: Map<String, PendingAttachment> = emptyMap(),
        preparedDocumentAttachments: Map<android.net.Uri, PendingAttachment> = emptyMap(),
        onAccepted: () -> Unit = {},
        onRejected: () -> Unit = {},
        onAfterSend: () -> Unit = {},
    ) {
        if (imageSlots.isEmpty() && documentUris.isEmpty()) {
            onRejected()
            return
        }
        val pendingDraftClear =
            appState.captureDraftForSend(controller.boundAccountRef, controller.group.groupIdHex)
        val trimmedCaption = caption.trim().takeIf { it.isNotBlank() }
        appState.launchMutation {
            var accepted = false
            try {
                val prepared =
                    prepareStagedAttachments(
                        imageSlots,
                        documentUris,
                        preparedImageAttachments,
                        preparedDocumentAttachments,
                    )
                if (!acceptPreparedAttachments(prepared, imageSlots.size)) {
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
                    return@launchMutation
                }
                accepted = true
                onAccepted()
                onAfterSend()
                val clearDraftAfterDurableAcceptance: (() -> Unit)? =
                    pendingDraftClear?.let { pendingClear ->
                        { appState.clearDraftAfterSuccessfulSend(pendingClear) }
                    }
                seeded.forEachIndexed { index, queued ->
                    controller.uploadQueued(
                        seeded = queued,
                        onDurablyAccepted = if (index == 0) clearDraftAfterDurableAcceptance else null,
                    )
                }
            } finally {
                if (!accepted) onRejected()
            }
        }
    }

    private suspend fun prepareStagedAttachments(
        imageSlots: List<PendingMediaSlot>,
        documentUris: List<android.net.Uri>,
        preparedImageAttachments: Map<String, PendingAttachment>,
        preparedDocumentAttachments: Map<android.net.Uri, PendingAttachment>,
    ): PreparedStagedAttachments {
        val rawImages = readStagedImages(imageSlots, preparedImageAttachments)
        val images = limitAttachmentsToBudget(rawImages.attachments, MEDIA_ALBUM_MAX_TOTAL_BYTES)
        val documentBudget = (MEDIA_ALBUM_MAX_TOTAL_BYTES - images.totalBytes).coerceAtLeast(0L)
        val documents =
            if (documentUris.isEmpty()) {
                DocumentReadOutcome(emptyList(), emptySet(), albumOverflowed = false, totalBytes = 0L)
            } else {
                readStagedDocuments(documentUris, preparedDocumentAttachments, documentBudget)
            }
        val pickHasVideo =
            imageSlots.any {
                safeGetType(context.contentResolver, it.uri).startsWith("video/", ignoreCase = true)
            }
        return PreparedStagedAttachments(
            images = images.attachments,
            documents = documents,
            imageOverflowed = rawImages.albumOverflowed || images.overflowed,
            visualFailureToast =
                if (pickHasVideo) R.string.toast_couldnt_process_video else R.string.toast_couldnt_decode_image,
        )
    }

    /** Reuses native-draft bytes and reads only picks that have not finished staging. */
    private suspend fun readStagedDocuments(
        documentUris: List<android.net.Uri>,
        preparedDocumentAttachments: Map<android.net.Uri, PendingAttachment>,
        bytesBudget: Long,
    ): DocumentReadOutcome {
        val attachments = mutableListOf<PendingAttachment>()
        var totalBytes = 0L
        val failures = mutableSetOf<DocumentReadFailure>()
        var overflowed = false
        documentUris.forEach { uri ->
            val remaining = (bytesBudget - totalBytes).coerceAtLeast(0L)
            if (remaining == 0L) {
                overflowed = true
                return@forEach
            }
            val staged = preparedDocumentAttachments[uri]
            if (staged != null) {
                if (staged.plaintextBytes.size.toLong() > remaining) {
                    overflowed = true
                } else {
                    attachments += staged
                    totalBytes += staged.plaintextBytes.size
                }
            } else {
                val read = attachmentReader.readPickedDocuments(listOf(uri), remaining)
                attachments += read.attachments
                totalBytes += read.totalBytes
                failures += read.failures
                overflowed = overflowed || read.albumOverflowed
            }
        }
        return DocumentReadOutcome(attachments, failures, overflowed, totalBytes)
    }

    private suspend fun readStagedImages(
        imageSlots: List<PendingMediaSlot>,
        preparedImageAttachments: Map<String, PendingAttachment>,
    ): VisualReadOutcome {
        val attachments = mutableListOf<PendingAttachment>()
        var overflowed = false
        imageSlots.forEach { slot ->
            val prepared = preparedImageAttachments[slot.id]
            if (prepared != null) {
                attachments += prepared
            } else {
                val read = attachmentReader.readPickedImages(listOf(slot.uri))
                attachments += read.attachments
                overflowed = overflowed || read.albumOverflowed
            }
        }
        return VisualReadOutcome(attachments, overflowed)
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
        } else {
            val failure = prepared.documents.failures.firstOrNull()
            if (failure != null) appState.present(failure.messageResource)
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

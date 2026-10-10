package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.ImageAnimationStatus
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.Thumbhash
import dev.ipf.whitenoise.android.share.PrivateShareSendLease
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.media.BoundedDocumentRead
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedPickBudget
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedPickFailure
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendLimits
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendStaging
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import dev.ipf.whitenoise.android.ui.conversation.media.closeQuietly
import dev.ipf.whitenoise.android.ui.conversation.media.nativeFileBackedSendLimits
import dev.ipf.whitenoise.android.ui.conversation.media.normalizeDocumentMime
import dev.ipf.whitenoise.android.ui.conversation.media.queryContentSize
import dev.ipf.whitenoise.android.ui.conversation.media.queryDisplayName
import dev.ipf.whitenoise.android.ui.conversation.media.readBoundedDocument
import dev.ipf.whitenoise.android.ui.conversation.media.releaseUnadoptedStagedSources
import dev.ipf.whitenoise.android.ui.conversation.media.safeDocumentDisplayName
import dev.ipf.whitenoise.android.ui.conversation.media.safeGetType
import dev.ipf.whitenoise.android.ui.conversation.media.uploadSourcesDirectory
import dev.ipf.whitenoise.android.ui.conversation.media.usableSpaceFor
import dev.ipf.whitenoise.android.ui.conversation.share.SharedContact
import dev.ipf.whitenoise.android.ui.conversation.share.VCARD_MIME_TYPE
import dev.ipf.whitenoise.android.ui.conversation.share.attachedVCardContact
import dev.ipf.whitenoise.android.ui.conversation.share.buildVCard
import dev.ipf.whitenoise.android.ui.conversation.share.contactVCardFileName
import dev.ipf.whitenoise.android.ui.conversation.share.formatContactShareText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Keep picker limits aligned with the retained-upload LRU. A larger value
// could evict an attachment during its own insert and make retries impossible.
private const val MEDIA_ATTACHMENT_MAX_BYTES = ConversationController.MEDIA_RETAINED_MAX_BYTES
private const val MEDIA_ALBUM_MAX_TOTAL_BYTES = ConversationController.MEDIA_RETAINED_MAX_BYTES
private const val VIDEO_FALLBACK_NAME = "video.mp4"

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
    STORAGE,
}

private val DocumentReadFailure.messageResource: Int
    get() =
        when (this) {
            DocumentReadFailure.TOO_LARGE -> R.string.media_file_too_large
            DocumentReadFailure.UNREADABLE -> R.string.media_file_unreadable
            DocumentReadFailure.EMPTY -> R.string.media_file_empty
            DocumentReadFailure.UNPROCESSABLE_IMAGE -> R.string.toast_couldnt_decode_image
            DocumentReadFailure.STORAGE -> R.string.share_import_storage
        }

internal data class VisualReadOutcome(
    val attachments: List<PendingAttachment>,
    val albumOverflowed: Boolean,
    val storageUnavailable: Boolean = false,
)

/**
 * Reads picks for a send. [fileBackedLimits] and [usableSpace] are the native file-upload bounds and the
 * free space of a staging directory, both replaceable so tests can script them.
 */
@Suppress("TooManyFunctions") // MIME-specific readers share one byte-budget and sanitization policy.
internal class ConversationAttachmentReader(
    private val appState: WhiteNoiseAppState,
    private val context: Context,
    private val fileBackedLimits: () -> FileBackedSendLimits? = ::nativeFileBackedSendLimits,
    private val usableSpace: (java.io.File) -> Long = ::usableSpaceFor,
) {
    /**
     * The file-backed budget of one outgoing message, staging under the private upload directory and
     * reserving disk through its send's [staging], or null when native limits are unavailable and every
     * pick must fit in memory.
     */
    fun fileBackedBudget(staging: FileBackedSendStaging = FileBackedSendStaging()): FileBackedPickBudget? =
        fileBackedLimits()?.let { limits ->
            val directory = uploadSourcesDirectory(context.cacheDir)
            FileBackedPickBudget(directory, limits, usableBytes = { usableSpace(directory) }, staging = staging)
        }

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

    /** Re-encodes a picked image as a bounded JPEG at the chosen media quality, within [remainingBytes]. */
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
        val checkCancellation: () -> Unit = {},
        val staging: FileBackedSendStaging = FileBackedSendStaging(),
    ) {
        /** Freezes this pass into the result callers blend with the image decode. */
        fun outcome(): DocumentReadOutcome = DocumentReadOutcome(attachments, failures, albumOverflowed, totalBytes)
    }

    /**
     * Reads document picks against the in-memory [bytesBudget]. With [allowFileBacked], a non-image
     * document that does not fit in memory is staged to a private file instead; each document is sent
     * as its own message, so each gets its own file-backed budget. [DocumentReadOutcome.totalBytes]
     * counts in-memory bytes only, and the send's [staging] hears about every snapshot as soon as it is
     * created and reserves the disk its upload still needs.
     */
    suspend fun readPickedDocuments(
        uris: List<android.net.Uri>,
        bytesBudget: Long = MEDIA_ALBUM_MAX_TOTAL_BYTES,
        allowFileBacked: Boolean = false,
        staging: FileBackedSendStaging = FileBackedSendStaging(),
    ): DocumentReadOutcome =
        withContext(Dispatchers.IO) {
            val state = DocumentReadAccumulator(checkCancellation = { ensureActive() }, staging = staging)
            for (uri in uris) {
                if (!allowFileBacked && state.totalBytes >= bytesBudget) {
                    state.albumOverflowed = true
                    break
                }
                readPickedDocument(uri, bytesBudget, state, allowFileBacked)
            }
            state.outcome()
        }

    /** Routes one document pick to the image sanitizer or the raw-file reader. */
    private fun readPickedDocument(
        uri: android.net.Uri,
        bytesBudget: Long,
        state: DocumentReadAccumulator,
        allowFileBacked: Boolean,
    ) {
        val resolvedMime = normalizeDocumentMime(safeGetType(context.contentResolver, uri))
        val remainingBytes = (bytesBudget - state.totalBytes).coerceAtLeast(0L)
        val sniffedImageMime = MediaPipeline.sniffImageMediaType(context.contentResolver, uri)
        if (isImageDocumentPick(resolvedMime, sniffedImageMime)) {
            readSanitizedImageDocument(uri, remainingBytes, state)
        } else {
            readRawDocument(uri, resolvedMime, remainingBytes, state, allowFileBacked)
        }
    }

    /** Reads an image picked as a document through the in-memory image sanitizer; images are never staged to files. */
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

    /**
     * Reads a raw document in memory when it fits the remaining budget. With [allowFileBacked], one
     * that is declared or found larger is staged to a private file rather than refused.
     */
    private fun readRawDocument(
        uri: android.net.Uri,
        mediaType: String,
        remainingBytes: Long,
        state: DocumentReadAccumulator,
        allowFileBacked: Boolean,
    ) {
        val declaredSize = queryContentSize(context.contentResolver, uri)
        val inMemoryCap = minOf(MEDIA_ATTACHMENT_MAX_BYTES, remainingBytes)
        if (allowFileBacked && (inMemoryCap <= 0L || declaredSize > inMemoryCap)) {
            readFileBackedDocument(uri, mediaType, declaredSize, state)
        } else if (declaredSize > MEDIA_ATTACHMENT_MAX_BYTES) {
            state.failures += DocumentReadFailure.TOO_LARGE
        } else {
            val perFileCap =
                inMemoryCap
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            when (val read = readBoundedDocument(perFileCap) { context.contentResolver.openInputStream(uri) }) {
                BoundedDocumentRead.TooLarge -> {
                    if (allowFileBacked) {
                        readFileBackedDocument(uri, mediaType, declaredSize = -1L, state)
                    } else if (remainingBytes < MEDIA_ATTACHMENT_MAX_BYTES) {
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

    /** Stages one document that does not fit in memory into a private file under its own message budget. */
    private fun readFileBackedDocument(
        uri: android.net.Uri,
        mediaType: String,
        declaredSize: Long,
        state: DocumentReadAccumulator,
    ) {
        val budget = fileBackedBudget(state.staging)
        val pick = budget?.stage(declaredSize, state.checkCancellation) { context.contentResolver.openInputStream(uri) }
        val source = pick?.source
        if (source == null) {
            state.failures += pick?.failure.toDocumentReadFailure()
            return
        }
        state.attachments +=
            PendingAttachment(
                plaintextBytes = ByteArray(0),
                mediaType = mediaType,
                fileName = safeDocumentDisplayName(queryDisplayName(context.contentResolver, uri)),
                dim = null,
                sourceFile = source,
            )
    }

    /**
     * Reads visual picks in order. Without [fileBacked], every pick shares the in-memory album budget
     * exactly as before. With it, a video that does not fit the remaining [inMemoryBytesBudget] is
     * staged to a private file, and every accepted item is charged against the message's file budget.
     */
    suspend fun readPickedImages(
        uris: List<android.net.Uri>,
        fileBacked: FileBackedPickBudget? = null,
        inMemoryBytesBudget: Long = MEDIA_ALBUM_MAX_TOTAL_BYTES,
    ): VisualReadOutcome =
        // A file-backed pick copies up to 512 MiB, which belongs on the IO pool rather than a CPU worker.
        withContext(if (fileBacked == null) Dispatchers.Default else Dispatchers.IO) {
            val checkCancellation = { ensureActive() }
            val attachments = mutableListOf<PendingAttachment>()
            var consumedBytes = 0L
            var overflowed = false
            var storageUnavailable = false
            for (uri in uris) {
                val remainingBytes = (inMemoryBytesBudget - consumedBytes).coerceAtLeast(0L)
                if (remainingBytes == 0L && fileBacked == null) {
                    overflowed = true
                    break
                }
                val outcome = readVisualAttachment(uri, remainingBytes, fileBacked, checkCancellation)
                overflowed = overflowed || outcome.overflowed
                storageUnavailable = storageUnavailable || outcome.storageUnavailable
                outcome.attachment?.let { attachment ->
                    // Null when there is no file budget to answer to, or when the item fits it.
                    val refusal = fileBacked?.admit(attachment.byteCount, attachment.sourceFile == null)
                    if (refusal == null) {
                        consumedBytes += attachment.plaintextBytes.size
                        attachments += attachment
                    } else {
                        // The message's ciphertext bound is spent, or the item's disk copies no longer fit the send.
                        attachment.sourceFile?.closeQuietly()
                        overflowed = overflowed || refusal == FileBackedPickFailure.TOO_LARGE
                        storageUnavailable = storageUnavailable || refusal == FileBackedPickFailure.STORAGE
                    }
                }
            }
            VisualReadOutcome(attachments, overflowed, storageUnavailable)
        }

    /** Reads one visual pick through the same transform and in-memory limits used at send time. */
    suspend fun readVisualDraft(uri: android.net.Uri): PendingAttachment? =
        withContext(Dispatchers.Default) {
            readVisualAttachment(uri, MEDIA_ALBUM_MAX_TOTAL_BYTES, fileBacked = null).attachment
        }

    /** Reads one document pick through the same MIME and byte limits used at send time. */
    suspend fun readDocumentDraft(uri: android.net.Uri): PendingAttachment? {
        val outcome = readPickedDocuments(listOf(uri))
        return outcome.attachments.singleOrNull()
    }

    /** Reads one image or video pick; only a video may be staged to a file, and only with [fileBacked]. */
    private fun readVisualAttachment(
        uri: android.net.Uri,
        remainingBytes: Long,
        fileBacked: FileBackedPickBudget?,
        checkCancellation: () -> Unit = {},
    ): ImageAttachmentReadOutcome {
        val mime = safeGetType(context.contentResolver, uri)
        val isVideo = mime.startsWith("video/", ignoreCase = true)
        val declaredSize = if (isVideo) queryContentSize(context.contentResolver, uri) else -1L
        val inMemoryCap = minOf(MediaPipeline.VIDEO_MAX_BYTES, remainingBytes)
        return when {
            // Images stay in memory; with no budget left there is nothing to decode for.
            !isVideo && remainingBytes <= 0L -> ImageAttachmentReadOutcome(null, overflowed = true)
            !isVideo -> readImageAttachment(uri, remainingBytes)
            fileBacked != null && (inMemoryCap <= 0L || declaredSize > inMemoryCap) ->
                readFileBackedVideo(uri, mime, declaredSize, fileBacked, checkCancellation)
            else -> readInMemoryVideo(uri, mime, remainingBytes, fileBacked, checkCancellation)
        }
    }

    /** Reads a video into memory; one found too large is staged to a file instead when [fileBacked] allows. */
    private fun readInMemoryVideo(
        uri: android.net.Uri,
        mediaType: String,
        remainingBytes: Long,
        fileBacked: FileBackedPickBudget?,
        checkCancellation: () -> Unit,
    ): ImageAttachmentReadOutcome =
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
            MediaPipeline.VideoReadResult.TooLarge ->
                if (fileBacked != null) {
                    readFileBackedVideo(uri, mediaType, declaredSize = -1L, fileBacked, checkCancellation)
                } else {
                    ImageAttachmentReadOutcome(null, overflowed = true)
                }
            MediaPipeline.VideoReadResult.Failed -> ImageAttachmentReadOutcome(null)
        }

    /**
     * Stages a large video into a private file and reads its poster metadata from that snapshot. A snapshot
     * whose metadata cannot be read is refused inside staging, before the budget reserves any disk for it.
     */
    private fun readFileBackedVideo(
        uri: android.net.Uri,
        mediaType: String,
        declaredSize: Long,
        fileBacked: FileBackedPickBudget,
        checkCancellation: () -> Unit,
    ): ImageAttachmentReadOutcome {
        var metadata: MediaPipeline.VideoFileMetadata? = null
        val pick =
            fileBacked.stage(
                declaredSize,
                checkCancellation,
                accept = { source -> MediaPipeline.readVideoFileMetadata(source.file).also { metadata = it } != null },
            ) { context.contentResolver.openInputStream(uri) }
        val source = pick.source
        val poster = metadata
        return when {
            source == null || poster == null ->
                when (pick.failure) {
                    FileBackedPickFailure.TOO_LARGE -> ImageAttachmentReadOutcome(null, overflowed = true)
                    FileBackedPickFailure.STORAGE -> ImageAttachmentReadOutcome(null, storageUnavailable = true)
                    else -> ImageAttachmentReadOutcome(null)
                }
            else ->
                ImageAttachmentReadOutcome(
                    PendingAttachment(
                        plaintextBytes = ByteArray(0),
                        mediaType = mediaType,
                        fileName =
                            MediaPipeline.safeDisplayName(
                                queryDisplayName(context.contentResolver, uri).orEmpty(),
                                VIDEO_FALLBACK_NAME,
                            ),
                        dim = "${poster.width}x${poster.height}",
                        thumbhash = poster.thumbhash,
                        sourceFile = source,
                    ),
                )
        }
    }
}

/** Whether a document pick is an image, by its reported type or its sniffed content. */
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
 * `fileBackedLimits` and `usableSpace` pass straight to its [ConversationAttachmentReader].
 */
internal class ConversationMediaSender(
    private val appState: WhiteNoiseAppState,
    private val controller: ConversationController,
    private val context: Context,
    fileBackedLimits: () -> FileBackedSendLimits? = ::nativeFileBackedSendLimits,
    usableSpace: (java.io.File) -> Long = ::usableSpaceFor,
    private val onRevealSent: () -> Unit,
) {
    private val attachmentReader = ConversationAttachmentReader(appState, context, fileBackedLimits, usableSpace)

    /** Captures the reply before contact serialization can suspend. */
    fun sendSharedContact(contact: SharedContact) {
        val replyTarget = controller.replyingTo?.messageIdHex
        val replyVersion = controller.replySelectionVersion
        val outboundVisibleStartedAtElapsedMs = SystemClock.elapsedRealtime()
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
            val seeded =
                controller.queueAttachments(
                    attachments = listOf(attachment),
                    caption = caption,
                    canQueue = { controller.replySelectionVersion == replyVersion },
                    outboundVisibleStartedAtElapsedMs = outboundVisibleStartedAtElapsedMs,
                    replyTarget = replyTarget,
                    replyVersion = replyVersion,
                ) ?: return@launchMutation
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
        val replyTarget = controller.replyingTo?.messageIdHex
        val replyVersion = controller.replySelectionVersion
        val outboundVisibleStartedAtElapsedMs = SystemClock.elapsedRealtime()
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
                        canQueue = {
                            canSend() && controller.canSendMessages && controller.replySelectionVersion == replyVersion
                        },
                        outboundVisibleStartedAtElapsedMs = outboundVisibleStartedAtElapsedMs,
                        replyTarget = replyTarget,
                        replyVersion = replyVersion,
                    ) ?: return@launchMutation
                if (replyTarget == null) {
                    accepted = true
                    onQueued(true)
                }
                onRevealSent()
                controller.uploadQueued(seeded) {
                    if (replyTarget != null) {
                        accepted = true
                        onQueued(true)
                    }
                }
            } finally {
                if (!accepted) onQueued(false)
            }
        }
    }

    private data class PreparedStagedAttachments(
        val images: List<PendingAttachment>,
        val documents: DocumentReadOutcome,
        val imageOverflowed: Boolean,
        val visualFailureToast: Int,
        val imageStorageUnavailable: Boolean = false,
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
        onSettled: () -> Unit = {},
        onAfterSend: () -> Unit = {},
    ) {
        val completion = StagedMediaSendCompletion(onAccepted, onRejected, onSettled)
        if (imageSlots.isEmpty() && documentUris.isEmpty()) {
            completion.reject()
            return
        }
        val replyTarget = controller.replyingTo?.messageIdHex
        val replyVersion = controller.replySelectionVersion
        val pendingDraftClear =
            appState.captureDraftForSend(controller.boundAccountRef, controller.group.groupIdHex)
        val trimmedCaption = caption.trim().takeIf { it.isNotBlank() }
        val sourceAccount = appState.accounts.firstOrNull { it.label == controller.boundAccountRef }?.accountIdHex
        val outboundVisibleStartedAtElapsedMs = SystemClock.elapsedRealtime()
        appState.launchMutation {
            var accepted = false
            var sourceLease: PrivateShareSendLease? = null
            val stagedSources = java.util.concurrent.ConcurrentLinkedQueue<StagedUploadSource>()
            var adoptedSources = emptySet<StagedUploadSource>()
            try {
                sourceLease =
                    acquireStagedSources(context, imageSlots.map { it.uri } + documentUris, sourceAccount).getOrElse {
                        appState.present(R.string.share_import_storage)
                        return@launchMutation
                    }
                val prepared =
                    prepareStagedAttachments(
                        imageSlots,
                        documentUris,
                        preparedImageAttachments,
                        preparedDocumentAttachments,
                        allowFileBacked = replyTarget == null,
                        staging = FileBackedSendStaging { stagedSources += it },
                    )
                val ready =
                    preparedForReplySend(prepared, imageSlots.size, documentUris.size, replyTarget != null)
                        ?: return@launchMutation
                val seeded =
                    seedPreparedAttachments(
                        ready,
                        trimmedCaption,
                        outboundVisibleStartedAtElapsedMs,
                        replyTarget,
                        replyVersion,
                    )
                adoptedSources = adoptedStagedSources(controller, seeded)
                if (seeded.isEmpty()) return@launchMutation
                val sourceReleases = retainStagedSources(appState, controller, sourceLease, seeded)
                accepted = true
                if (replyTarget == null) completion.accept()
                onAfterSend()
                settleStagedUpload(seeded, sourceReleases, pendingDraftClear, replyTarget != null, completion)
            } finally {
                releaseUnadoptedStagedSources(stagedSources.toList(), adoptedSources)
                if (!accepted) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { sourceLease?.release() }
                    completion.reject()
                }
            }
        }
    }

    /** Replies cannot silently drop failed picks; all prepared items share one typed native draft. */
    private suspend fun preparedForReplySend(
        prepared: PreparedStagedAttachments,
        imageCount: Int,
        documentCount: Int,
        isReply: Boolean,
    ): PreparedStagedAttachments? {
        val incomplete = prepared.images.size != imageCount || prepared.documents.attachments.size != documentCount
        if (isReply && incomplete) {
            // A document that could not be read says why (for example too large) rather than "draft changed".
            val documentFailure = prepared.documents.failures.firstOrNull()
            appState.present(documentFailure?.messageResource ?: R.string.media_reply_draft_conflict)
            return null
        }
        return if (acceptPreparedAttachments(prepared, imageCount)) {
            val documents = prepared.documents.copy(attachments = addMissingThumbhashes(prepared.documents.attachments))
            prepared.copy(documents = documents)
        } else {
            null
        }
    }

    /** A rejected attempt releases its UI claim while retry retains separate exact-shelf settlement. */
    private suspend fun settleStagedUpload(
        seeded: List<ConversationController.QueuedAttachmentSend>,
        releases: List<() -> Unit>?,
        pendingClear: dev.ipf.whitenoise.android.state.DraftSendClearToken?,
        isReply: Boolean,
        completion: StagedMediaSendCompletion,
    ) {
        var replyAccepted = false
        uploadStagedAttachments(seeded, releases) {
            if (isReply) {
                replyAccepted = true
                completion.accept()
            }
            pendingClear?.let(appState::clearDraftAfterSuccessfulSend)
        }
        if (isReply && !replyAccepted) completion.reject()
    }

    /** Uploads seeded sends in order; durable acceptance releases each source owner and clears the draft only once. */
    private suspend fun uploadStagedAttachments(
        seeded: List<ConversationController.QueuedAttachmentSend>,
        releases: List<() -> Unit>?,
        clearDraft: (() -> Unit)?,
    ) {
        seeded.forEachIndexed { index, queued ->
            controller.uploadQueued(
                seeded = queued,
                onDurablyAccepted = {
                    if (index == 0) clearDraft?.invoke()
                    releases?.get(index)?.invoke()
                },
            )
        }
    }

    /**
     * Reads the shelf's visual album and documents for one send. [allowFileBacked] lets picks that do
     * not fit the in-memory budget be staged to private files; the album shares one file-backed budget.
     * Replies pass false: native reply drafts carry attachment bytes, so every reply pick stays in memory.
     * [staging] hears about every snapshot as soon as it exists, so a preparation that fails part-way can
     * still be cleaned up by its caller, and reserves disk for the whole send across the album and documents.
     */
    private suspend fun prepareStagedAttachments(
        imageSlots: List<PendingMediaSlot>,
        documentUris: List<android.net.Uri>,
        preparedImageAttachments: Map<String, PendingAttachment>,
        preparedDocumentAttachments: Map<android.net.Uri, PendingAttachment>,
        allowFileBacked: Boolean,
        staging: FileBackedSendStaging,
    ): PreparedStagedAttachments {
        // Reading the native limit is a binding call, so it stays off Main.
        val albumFileBudget =
            if (allowFileBacked) {
                withContext(Dispatchers.IO) { attachmentReader.fileBackedBudget(staging) }
            } else {
                null
            }
        val rawImages = readStagedImages(imageSlots, preparedImageAttachments, albumFileBudget)
        val images = limitAttachmentsToBudget(rawImages.attachments, MEDIA_ALBUM_MAX_TOTAL_BYTES)
        val documentBudget = (MEDIA_ALBUM_MAX_TOTAL_BYTES - images.totalBytes).coerceAtLeast(0L)
        val documents =
            if (documentUris.isEmpty()) {
                DocumentReadOutcome(emptyList(), emptySet(), albumOverflowed = false, totalBytes = 0L)
            } else {
                readStagedDocuments(
                    documentUris,
                    preparedDocumentAttachments,
                    documentBudget,
                    allowFileBacked,
                    staging,
                )
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
            imageStorageUnavailable = rawImages.storageUnavailable,
        )
    }

    /**
     * Reuses native-draft bytes and reads only picks that have not finished staging. With
     * [allowFileBacked], a draft document that no longer fits the in-memory budget is copied to a
     * private file, and an unstaged pick may be read straight into one. [staging] hears about each copy.
     */
    private suspend fun readStagedDocuments(
        documentUris: List<android.net.Uri>,
        preparedDocumentAttachments: Map<android.net.Uri, PendingAttachment>,
        bytesBudget: Long,
        allowFileBacked: Boolean,
        staging: FileBackedSendStaging,
    ): DocumentReadOutcome {
        val attachments = mutableListOf<PendingAttachment>()
        var totalBytes = 0L
        val failures = mutableSetOf<DocumentReadFailure>()
        var overflowed = false
        documentUris.forEach { uri ->
            val remaining = (bytesBudget - totalBytes).coerceAtLeast(0L)
            if (remaining == 0L && !allowFileBacked) {
                overflowed = true
                return@forEach
            }
            val staged = preparedDocumentAttachments[uri]
            if (staged != null && staged.plaintextBytes.size.toLong() <= remaining) {
                attachments += staged
                totalBytes += staged.plaintextBytes.size
            } else if (staged != null && !allowFileBacked) {
                overflowed = true
            } else if (staged != null) {
                val copied =
                    withContext(Dispatchers.IO) {
                        val budget = attachmentReader.fileBackedBudget(staging)
                        stageDraftDocumentToFile(staged, budget)
                    }
                attachments += copied.attachments
                failures += copied.failures
            } else {
                val read = attachmentReader.readPickedDocuments(listOf(uri), remaining, allowFileBacked, staging)
                attachments += read.attachments
                totalBytes += read.totalBytes
                failures += read.failures
                overflowed = overflowed || read.albumOverflowed
            }
        }
        return DocumentReadOutcome(attachments, failures, overflowed, totalBytes)
    }

    /**
     * Reads the visual album in slot order. Without [fileBacked] each slot is read against the whole
     * in-memory budget and trimmed afterwards, as before; with it, each read sees what is left so a
     * video that no longer fits in memory is staged to a file instead of dropped.
     */
    private suspend fun readStagedImages(
        imageSlots: List<PendingMediaSlot>,
        preparedImageAttachments: Map<String, PendingAttachment>,
        fileBacked: FileBackedPickBudget?,
    ): VisualReadOutcome {
        val attachments = mutableListOf<PendingAttachment>()
        var overflowed = false
        var storageUnavailable = false
        var inMemoryBytes = 0L
        imageSlots.forEach { slot ->
            val prepared = preparedImageAttachments[slot.id]
            // A draft item after large videos must fit the same native bound and the send's disk, or the album fails.
            // Draft visuals are always held in memory, only a pick staged through the budget lives in a file.
            // The disk check asks the filesystem for free space, so it runs on the IO pool rather than Main.
            val refusal =
                if (prepared != null && fileBacked != null) {
                    withContext(Dispatchers.IO) { fileBacked.admit(prepared.byteCount, prepared.sourceFile == null) }
                } else {
                    null
                }
            if (refusal != null) {
                overflowed = overflowed || refusal == FileBackedPickFailure.TOO_LARGE
                storageUnavailable = storageUnavailable || refusal == FileBackedPickFailure.STORAGE
            } else if (prepared != null) {
                attachments += prepared
                inMemoryBytes += prepared.plaintextBytes.size
            } else {
                val read =
                    if (fileBacked == null) {
                        attachmentReader.readPickedImages(listOf(slot.uri))
                    } else {
                        val remaining = (MEDIA_ALBUM_MAX_TOTAL_BYTES - inMemoryBytes).coerceAtLeast(0L)
                        attachmentReader.readPickedImages(listOf(slot.uri), fileBacked, remaining)
                    }
                attachments += read.attachments
                inMemoryBytes += read.attachments.sumOf { it.plaintextBytes.size.toLong() }
                overflowed = overflowed || read.albumOverflowed
                storageUnavailable = storageUnavailable || read.storageUnavailable
            }
        }
        return VisualReadOutcome(attachments, overflowed, storageUnavailable)
    }

    /** Presents one notice for what preparation dropped or refused, and reports whether anything is left to send. */
    private fun acceptPreparedAttachments(
        prepared: PreparedStagedAttachments,
        imagePickCount: Int,
    ): Boolean {
        if (prepared.isEmpty && imagePickCount > 0) {
            val toast =
                when {
                    prepared.imageOverflowed -> R.string.media_album_too_large
                    prepared.imageStorageUnavailable -> R.string.share_import_storage
                    else -> prepared.visualFailureToast
                }
            appState.present(toast, copyable = toast == prepared.visualFailureToast)
            return false
        }
        if (prepared.images.size < imagePickCount && !prepared.imageOverflowed && !prepared.imageStorageUnavailable) {
            appState.present(prepared.visualFailureToast, copyable = true)
        }
        if (prepared.imageOverflowed || prepared.documents.albumOverflowed) {
            appState.present(R.string.media_album_too_large)
        } else if (prepared.imageStorageUnavailable) {
            appState.present(R.string.share_import_storage)
        } else {
            val failure = prepared.documents.failures.firstOrNull()
            if (failure != null) appState.present(failure.messageResource)
        }
        return !prepared.isEmpty
    }

    /** One reply owns one native draft/album; ordinary sends retain the existing document grouping. */
    private suspend fun seedPreparedAttachments(
        prepared: PreparedStagedAttachments,
        caption: String?,
        outboundVisibleStartedAtElapsedMs: Long,
        replyTarget: String?,
        replyVersion: Long,
    ): List<ConversationController.QueuedAttachmentSend> {
        if (replyTarget != null) {
            return listOfNotNull(
                controller.queueAttachments(
                    attachments = prepared.images + prepared.documents.attachments,
                    caption = caption,
                    canQueue = { controller.replySelectionVersion == replyVersion },
                    outboundVisibleStartedAtElapsedMs = outboundVisibleStartedAtElapsedMs,
                    replyTarget = replyTarget,
                    replyVersion = replyVersion,
                ),
            )
        }
        val seeded = mutableListOf<ConversationController.QueuedAttachmentSend>()
        if (prepared.images.isNotEmpty()) {
            controller
                .queueAttachments(
                    attachments = prepared.images,
                    caption = caption,
                    replyTarget = null,
                    outboundVisibleStartedAtElapsedMs = outboundVisibleStartedAtElapsedMs,
                )?.let(seeded::add)
        }
        val captionConsumedByImages = prepared.images.isNotEmpty()
        prepared.documents.attachments.forEachIndexed { index, attachment ->
            val userCaption = if (!captionConsumedByImages && index == 0) caption else null
            // A raw .vcf goes out like the contact picker's share: text/vcard
            // plus a name/phone caption, so every bubble draws the same card.
            // A caption the user typed wins over the generated one.
            val contact = withContext(Dispatchers.Default) { attachedVCardContact(attachment) }
            val itemAttachment = if (contact == null) attachment else attachment.copy(mediaType = VCARD_MIME_TYPE)
            val itemCaption = userCaption ?: contact?.let(::formatContactShareText)
            controller
                .queueAttachments(
                    attachments = listOf(itemAttachment),
                    caption = itemCaption,
                    replyTarget = null,
                    outboundVisibleStartedAtElapsedMs = outboundVisibleStartedAtElapsedMs,
                )?.let(seeded::add)
        }
        return seeded
    }
}

/** Snapshots now owned by queued sends; each is released when its retained upload ends. */
private fun adoptedStagedSources(
    controller: ConversationController,
    seeded: List<ConversationController.QueuedAttachmentSend>,
): Set<StagedUploadSource> =
    seeded.flatMapTo(mutableSetOf()) { queued ->
        controller.pendingAttachmentsList(queued.tempId).mapNotNull(PendingAttachment::sourceFile)
    }

/** Maps a refused file-backed pick to the document toast; no budget at all reads as too large. */
private fun FileBackedPickFailure?.toDocumentReadFailure(): DocumentReadFailure =
    when (this) {
        FileBackedPickFailure.EMPTY -> DocumentReadFailure.EMPTY
        FileBackedPickFailure.UNREADABLE -> DocumentReadFailure.UNREADABLE
        FileBackedPickFailure.STORAGE -> DocumentReadFailure.STORAGE
        FileBackedPickFailure.TOO_LARGE, null -> DocumentReadFailure.TOO_LARGE
    }

/**
 * Copies a native-draft document that no longer fits the in-memory budget into a private snapshot,
 * so the send keeps it without growing the retained heap or reopening a picker grant. Call off Main.
 */
private fun stageDraftDocumentToFile(
    attachment: PendingAttachment,
    budget: FileBackedPickBudget?,
): DocumentReadOutcome {
    val pick = budget?.stage(attachment.byteCount) { attachment.plaintextBytes.inputStream() }
    val source = pick?.source
    val staged = source?.let { attachment.copy(plaintextBytes = ByteArray(0), sourceFile = it) }
    return DocumentReadOutcome(
        attachments = listOfNotNull(staged),
        failures = if (staged == null) setOf(pick?.failure.toDocumentReadFailure()) else emptySet(),
        albumOverflowed = false,
        totalBytes = 0L,
    )
}

/** Attachment budget result independent of a conversation or its lifecycle. */
private data class BudgetedAttachments(
    val attachments: List<PendingAttachment>,
    val totalBytes: Long,
    val overflowed: Boolean,
)

/** Preserves pick order while rejecting attachments that exceed the remaining native album budget. */
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

/** Completes missing image metadata off the UI thread without replacing existing thumbhashes. */
private suspend fun addMissingThumbhashes(attachments: List<PendingAttachment>): List<PendingAttachment> =
    if (attachments.isEmpty()) {
        emptyList()
    } else {
        withContext(Dispatchers.Default) {
            attachments.map { attachment ->
                val needsThumbhash =
                    attachment.sourceFile == null &&
                        attachment.mediaType.startsWith("image/", ignoreCase = true) &&
                        attachment.thumbhash == null
                if (!needsThumbhash) {
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

package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import dev.ipf.whitenoise.android.media.ImageAnimationStatus
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.VideoScrubResult
import dev.ipf.whitenoise.android.media.dropKeptOversizedCacheEntries
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.media.BoundedDocumentRead
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedPickBudget
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedPickFailure
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendLimits
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendStaging
import dev.ipf.whitenoise.android.ui.conversation.media.closeQuietly
import dev.ipf.whitenoise.android.ui.conversation.media.nativeFileBackedSendLimits
import dev.ipf.whitenoise.android.ui.conversation.media.normalizeDocumentMime
import dev.ipf.whitenoise.android.ui.conversation.media.queryContentSize
import dev.ipf.whitenoise.android.ui.conversation.media.queryDisplayName
import dev.ipf.whitenoise.android.ui.conversation.media.readBoundedDocument
import dev.ipf.whitenoise.android.ui.conversation.media.removeDocumentMetadata
import dev.ipf.whitenoise.android.ui.conversation.media.removeDocumentMetadataInPlace
import dev.ipf.whitenoise.android.ui.conversation.media.safeDocumentDisplayName
import dev.ipf.whitenoise.android.ui.conversation.media.safeGetType
import dev.ipf.whitenoise.android.ui.conversation.media.scrubVideoMetadata
import dev.ipf.whitenoise.android.ui.conversation.media.uploadSourcesDirectory
import dev.ipf.whitenoise.android.ui.conversation.media.usableSpaceFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Keep picker limits aligned with the retained-upload LRU. A larger value
// could evict an attachment during its own insert and make retries impossible.
private const val MEDIA_ATTACHMENT_MAX_BYTES = ConversationController.MEDIA_RETAINED_MAX_BYTES
private const val VIDEO_FALLBACK_NAME = "video.mp4"

internal data class DocumentReadOutcome(
    val attachments: List<PendingAttachment>,
    val failures: Set<DocumentReadFailure>,
    val albumOverflowed: Boolean,
    val totalBytes: Long,
)

/** The visual album read for one send. [videoMetadataRefused] reports a video refused for its private metadata. */
internal data class VisualReadOutcome(
    val attachments: List<PendingAttachment>,
    val albumOverflowed: Boolean,
    val storageUnavailable: Boolean = false,
    val videoMetadataRefused: Boolean = false,
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
    /** The worker domain of one pick, a slot id or a document URI, read at each image read, single-use when unset. */
    private val domain: (String?) -> dev.ipf.whitenoise.android.media.MediaTrustDomain = {
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique
    },
) {
    /**
     * The file-backed budget of one outgoing message, staging under the private upload directory and
     * reserving disk through its send's [staging], or null when native limits are unavailable and every
     * pick must fit in memory.
     */
    fun fileBackedBudget(staging: FileBackedSendStaging = FileBackedSendStaging()): FileBackedPickBudget? =
        fileBackedLimits()?.let { limits ->
            val directory = uploadSourcesDirectory(context.cacheDir)
            FileBackedPickBudget(
                directory,
                limits,
                usableBytes = { usableSpace(directory) },
                staging = staging,
                reclaimCache = { dropKeptOversizedCacheEntries(context.cacheDir) },
            )
        }

    /** Reads one image pick at the chosen quality, inspecting and decoding it in [pick]'s draft's outgoing worker. */
    fun readImageAttachment(
        uri: android.net.Uri,
        remainingBytes: Long,
        pick: String?,
    ): ImageAttachmentReadOutcome =
        dev.ipf.whitenoise.android.media.sandbox.MediaTrustScope
            .within(domain(pick)) { readImageAttachmentInScope(uri, remainingBytes) }

    private fun readImageAttachmentInScope(
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
    @Suppress("LongParameterList") // Owned MIME is supplied only by the collection journal.
    suspend fun readPickedDocuments(
        uris: List<android.net.Uri>,
        bytesBudget: Long = MEDIA_ALBUM_MAX_TOTAL_BYTES,
        allowFileBacked: Boolean = false,
        staging: FileBackedSendStaging = FileBackedSendStaging(),
        pickKey: (String) -> String = { it },
        ownedMime: Map<android.net.Uri, String> = emptyMap(),
    ): DocumentReadOutcome =
        withContext(Dispatchers.IO) {
            val state = DocumentReadAccumulator(checkCancellation = { ensureActive() }, staging = staging)
            for (uri in uris) {
                if (!allowFileBacked && state.totalBytes >= bytesBudget) {
                    state.albumOverflowed = true
                    break
                }
                readPickedDocument(uri, bytesBudget, state, allowFileBacked, pickKey(uri.toString()), ownedMime[uri])
            }
            state.outcome()
        }

    /** Routes one document pick, [pick], to the image sanitizer or the raw-file reader. */
    @Suppress("LongParameterList") // Combines one pick, its budget and the result of the current read pass.
    private fun readPickedDocument(
        uri: android.net.Uri,
        bytesBudget: Long,
        state: DocumentReadAccumulator,
        allowFileBacked: Boolean,
        pick: String,
        ownedMime: String?,
    ) {
        val resolvedMime = normalizeDocumentMime(ownedMime ?: safeGetType(context.contentResolver, uri))
        val remainingBytes = (bytesBudget - state.totalBytes).coerceAtLeast(0L)
        val sniffedImageMime = MediaPipeline.sniffImageMediaType(context.contentResolver, uri)
        if (isImageDocumentPick(resolvedMime, sniffedImageMime)) {
            readSanitizedImageDocument(uri, remainingBytes, state, pick)
        } else {
            readRawDocument(uri, resolvedMime, remainingBytes, state, allowFileBacked)
        }
    }

    /**
     * Reads an image picked as a document through the in-memory image sanitizer, in [pick]'s draft's worker. Images
     * are never staged to files.
     */
    private fun readSanitizedImageDocument(
        uri: android.net.Uri,
        remainingBytes: Long,
        state: DocumentReadAccumulator,
        pick: String,
    ) {
        val outcome = readImageAttachment(uri, remainingBytes, pick)
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
                // Content decides: a video container or a PDF loses its private metadata in place first, an
                // uncleanable video reported as video is refused, and every other document stays byte-for-byte.
                is BoundedDocumentRead.Success ->
                    if (!removeDocumentMetadataInPlace(mediaType, read.bytes)) {
                        state.failures += DocumentReadFailure.VIDEO_METADATA
                    } else {
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

    /**
     * Stages one document that does not fit in memory into a private file under its own message budget. A snapshot
     * whose content is a video container loses its private metadata inside staging, before the budget reserves disk
     * for it, by the same content rule as an in-memory document.
     */
    private fun readFileBackedDocument(
        uri: android.net.Uri,
        mediaType: String,
        declaredSize: Long,
        state: DocumentReadAccumulator,
    ) {
        val budget = fileBackedBudget(state.staging)
        var metadataRemoved = true
        val pick =
            budget?.stage(
                declaredSize,
                state.checkCancellation,
                accept = { source -> source.removeDocumentMetadata(mediaType).also { metadataRemoved = it } },
            ) { context.contentResolver.openInputStream(uri) }
        val source = pick?.source
        if (source == null) {
            state.failures +=
                if (metadataRemoved) pick?.failure.toDocumentReadFailure() else DocumentReadFailure.VIDEO_METADATA
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
     * A named [pick] is one file and decodes in its draft's worker. [ownedMime] comes from a journaled copy.
     */
    suspend fun readPickedImages(
        uris: List<android.net.Uri>,
        fileBacked: FileBackedPickBudget? = null,
        inMemoryBytesBudget: Long = MEDIA_ALBUM_MAX_TOTAL_BYTES,
        pick: String? = null,
        ownedMime: String? = null,
    ): VisualReadOutcome {
        require(pick == null || uris.size <= 1) { "One pick names one picked file" }
        // A file-backed pick copies up to 512 MiB, which belongs on the IO pool rather than a CPU worker.
        return withContext(if (fileBacked == null) Dispatchers.Default else Dispatchers.IO) {
            val checkCancellation = { ensureActive() }
            val attachments = mutableListOf<PendingAttachment>()
            var consumedBytes = 0L
            var overflowed = false
            var storageUnavailable = false
            var videoMetadataRefused = false
            for (uri in uris) {
                val remainingBytes = (inMemoryBytesBudget - consumedBytes).coerceAtLeast(0L)
                if (remainingBytes == 0L && fileBacked == null) {
                    overflowed = true
                    break
                }
                val outcome = readVisualAttachment(uri, remainingBytes, fileBacked, checkCancellation, pick, ownedMime)
                overflowed = overflowed || outcome.overflowed
                storageUnavailable = storageUnavailable || outcome.storageUnavailable
                videoMetadataRefused = videoMetadataRefused || outcome.videoMetadataRefused
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
            VisualReadOutcome(attachments, overflowed, storageUnavailable, videoMetadataRefused)
        }
    }

    /**
     * Reads one visual [pick] through the same transform and in-memory limits used at send time, decoding in that
     * pick's draft's outgoing worker, while a read that names no pick decodes single-use. A send names the domain of
     * each attachment it sends itself. With [toFile], a video too large for memory is copied to a private file with
     * its metadata removed instead of refused; the copy stops once the read is cancelled, and the caller owns the file
     * only once it receives the attachment.
     */
    suspend fun readVisualDraft(
        uri: android.net.Uri,
        pick: String? = null,
        toFile: Boolean = false,
    ): PendingAttachment? =
        ownedDraftRead { staging ->
            // Copying a large video to a file is I/O-bound, so it runs on the IO pool, an in-memory read on Default.
            withContext(if (toFile) Dispatchers.IO else Dispatchers.Default) {
                val fileBacked = if (toFile) fileBackedBudget(staging) else null
                readVisualAttachment(uri, MEDIA_ALBUM_MAX_TOTAL_BYTES, fileBacked, { ensureActive() }, pick).attachment
            }
        }

    /** Reads one document pick through the same MIME and byte limits used at send time, [toFile] as above. */
    suspend fun readDocumentDraft(
        uri: android.net.Uri,
        toFile: Boolean = false,
    ): PendingAttachment? =
        ownedDraftRead { staging ->
            readPickedDocuments(listOf(uri), allowFileBacked = toFile, staging = staging).attachments.singleOrNull()
        }

    /** Reads one image or video pick; only a video may be staged to a file, and only with [fileBacked]. */
    @Suppress("LongParameterList") // Combines one pick's metadata and read budget with its decoder domain.
    private fun readVisualAttachment(
        uri: android.net.Uri,
        remainingBytes: Long,
        fileBacked: FileBackedPickBudget?,
        checkCancellation: () -> Unit = {},
        pick: String? = null,
        ownedMime: String? = null,
    ): ImageAttachmentReadOutcome {
        val mime = ownedMime ?: safeGetType(context.contentResolver, uri)
        val isVideo = mime.startsWith("video/", ignoreCase = true)
        val declaredSize = if (isVideo) queryContentSize(context.contentResolver, uri) else -1L
        val inMemoryCap = minOf(MediaPipeline.VIDEO_MAX_BYTES, remainingBytes)
        return when {
            // Images stay in memory; with no budget left there is nothing to decode for.
            !isVideo && remainingBytes <= 0L -> ImageAttachmentReadOutcome(null, overflowed = true)
            !isVideo -> readImageAttachment(uri, remainingBytes, pick)
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
        when (val result = MediaPipeline.readVideoForUpload(context, uri, remainingBytes, mediaType)) {
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
            MediaPipeline.VideoReadResult.MetadataNotRemovable ->
                ImageAttachmentReadOutcome(null, videoMetadataRefused = true)
        }

    /**
     * Stages a large video into a private file, removes its private metadata in place and reads its poster
     * metadata from that snapshot. A snapshot whose private metadata cannot be removed, or whose poster metadata
     * cannot be read, is refused inside staging, before the budget reserves any disk for it.
     */
    private fun readFileBackedVideo(
        uri: android.net.Uri,
        mediaType: String,
        declaredSize: Long,
        fileBacked: FileBackedPickBudget,
        checkCancellation: () -> Unit,
    ): ImageAttachmentReadOutcome {
        var metadata: MediaPipeline.VideoFileMetadata? = null
        var metadataRemoved = true
        val pick =
            fileBacked.stage(
                declaredSize,
                checkCancellation,
                accept = { source ->
                    metadataRemoved = source.scrubVideoMetadata() is VideoScrubResult.Scrubbed
                    metadataRemoved &&
                        MediaPipeline.readVideoFileMetadata(source.file).also { metadata = it } != null
                },
            ) { context.contentResolver.openInputStream(uri) }
        val source = pick.source
        val poster = metadata
        return when {
            source == null || poster == null ->
                when {
                    !metadataRemoved -> ImageAttachmentReadOutcome(null, videoMetadataRefused = true)
                    pick.failure == FileBackedPickFailure.TOO_LARGE ->
                        ImageAttachmentReadOutcome(null, overflowed = true)
                    pick.failure == FileBackedPickFailure.STORAGE ->
                        ImageAttachmentReadOutcome(null, storageUnavailable = true)
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

/** Maps a refused file-backed pick to the document toast; no budget at all reads as too large. */
internal fun FileBackedPickFailure?.toDocumentReadFailure(): DocumentReadFailure =
    when (this) {
        FileBackedPickFailure.EMPTY -> DocumentReadFailure.EMPTY
        FileBackedPickFailure.UNREADABLE -> DocumentReadFailure.UNREADABLE
        FileBackedPickFailure.STORAGE -> DocumentReadFailure.STORAGE
        FileBackedPickFailure.TOO_LARGE, null -> DocumentReadFailure.TOO_LARGE
    }

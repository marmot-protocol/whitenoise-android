package dev.ipf.whitenoise.android.share

import dev.ipf.whitenoise.android.ui.conversation.media.FILE_BACKED_TAG_BYTES
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendLimits
import dev.ipf.whitenoise.android.ui.conversation.media.nativeFileBackedSendLimits

private const val BASE_SHARE_IMPORT_TIMEOUT_MS = 60_000L

/** Android intake bounds; file transport uses the sender contract while image decoding remains separately bounded. */
internal data class ShareImportLimits(
    val imageBytes: Long,
    val documentBytes: Long,
    val batchCiphertextBytes: Long,
) {
    init {
        require(imageBytes >= 0 && documentBytes >= 0 && batchCiphertextBytes >= 0)
    }

    /** Every retained item reserves the same tag overhead that the file-backed sender charges. */
    fun remainingPlaintext(usedCiphertextBytes: Long): Long {
        val remaining = batchCiphertextBytes - usedCiphertextBytes - FILE_BACKED_TAG_BYTES
        return remaining.coerceAtLeast(0L)
    }

    /** Scales the finite batch deadline with trusted byte allowance, preserving the legacy throughput budget. */
    fun importTimeoutMs(): Long {
        val windows = 1L + (batchCiphertextBytes - 1L).coerceAtLeast(0L) / PRIVATE_SHARE_BATCH_MAX_BYTES
        return BASE_SHARE_IMPORT_TIMEOUT_MS * windows
    }

    /** Private originals are bounded independently of available filesystem space. */
    val storageBytes: Long get() = batchCiphertextBytes

    /** Restored originals obey the same MIME-specific boundary as new imports. */
    fun fileBytes(mime: String): Long = if (mime.startsWith("image/")) imageBytes else documentBytes
}

/** Native-unavailable intake stays compatible with the existing memory-backed sender. */
internal val LEGACY_SHARE_IMPORT_LIMITS =
    ShareImportLimits(PRIVATE_SHARE_MAX_BYTES, PRIVATE_SHARE_DOCUMENT_MAX_BYTES, PRIVATE_SHARE_BATCH_MAX_BYTES)

/** Converts only the existing platform sender contract; this does not define native upload or decoder policy. */
internal fun shareImportLimits(limits: FileBackedSendLimits?): ShareImportLimits {
    if (limits == null) return LEGACY_SHARE_IMPORT_LIMITS
    val document = minOf(limits.perFileBytes, (limits.batchCiphertextBytes - FILE_BACKED_TAG_BYTES).coerceAtLeast(0L))
    return ShareImportLimits(
        imageBytes = minOf(PRIVATE_SHARE_MAX_BYTES, document),
        documentBytes = document,
        batchCiphertextBytes = limits.batchCiphertextBytes,
    )
}

/** Called lazily on intake/provider I/O, never while creating a composition's ownership checker. */
internal fun nativeShareImportLimits(): ShareImportLimits = shareImportLimits(nativeFileBackedSendLimits())

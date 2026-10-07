package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource

/**
 * One named attachment queued for upload as part of an album. The bytes are pre-processed
 * plaintext; callers must apply any MIME-specific transforms before constructing this value.
 */
data class PendingAttachment(
    val plaintextBytes: ByteArray,
    val mediaType: String,
    val fileName: String,
    val dim: String? = null,
    val thumbhash: String? = null,
    val sourceFile: StagedUploadSource? = null,
) {
    init {
        require(sourceFile == null || plaintextBytes.isEmpty()) { "attachment must have one input representation" }
    }

    val byteCount: Long get() = sourceFile?.byteCount ?: plaintextBytes.size.toLong()
    val hasContent: Boolean get() = byteCount > 0

    /** Compares attachment byte content rather than the backing array's identity. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingAttachment) return false
        if (mediaType != other.mediaType) return false
        if (fileName != other.fileName) return false
        if (sourceFile != other.sourceFile) return false
        return plaintextBytes.contentEquals(other.plaintextBytes)
    }

    /** Produces a content-based hash consistent with [equals]. */
    override fun hashCode(): Int {
        var result = plaintextBytes.contentHashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + (sourceFile?.hashCode() ?: 0)
        return result
    }
}

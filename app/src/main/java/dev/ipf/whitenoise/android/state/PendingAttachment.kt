package dev.ipf.whitenoise.android.state

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
) {
    /** Compares attachment byte content rather than the backing array's identity. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingAttachment) return false
        if (mediaType != other.mediaType) return false
        if (fileName != other.fileName) return false
        return plaintextBytes.contentEquals(other.plaintextBytes)
    }

    /** Produces a content-based hash consistent with [equals]. */
    override fun hashCode(): Int {
        var result = plaintextBytes.contentHashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + fileName.hashCode()
        return result
    }
}

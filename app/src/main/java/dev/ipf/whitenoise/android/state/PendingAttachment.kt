package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.nostr.sha256
import dev.ipf.whitenoise.android.core.nostr.toHex
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
    /** A file the composer's native draft already stores for this attachment, uploaded from there by id. */
    val draftFile: DraftStoredFile? = null,
    /**
     * The outgoing domain of the draft this attachment was sent from, where its pending preview and just-sent
     * thumbnail decode, or single-use when it came from no pick.
     */
    val outgoingDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
    val nativeDraftAttachmentId: String? = null,
    /**
     * The body the native draft holds under [nativeDraftAttachmentId], kept once send preparation replaced these
     * bytes: cleaned of metadata, or moved into a staged file. Null while the bytes are still the draft's own.
     */
    val nativeDraftBody: NativeDraftBody? = null,
    /** The stored row to settle after send preparation copied or cleaned its body into [sourceFile]. */
    val nativeDraftFileBody: DraftStoredFile? = null,
) {
    init {
        val representations = listOf(plaintextBytes.isNotEmpty(), sourceFile != null, draftFile != null)
        require(representations.count { it } <= 1) { "attachment must have one input representation" }
    }

    val byteCount: Long get() = sourceFile?.byteCount ?: draftFile?.byteCount ?: plaintextBytes.size.toLong()

    /** Whether the content lives in a file, a private snapshot or a stored draft file, rather than in memory. */
    val isFileBacked: Boolean get() = sourceFile != null || draftFile != null
    val hasContent: Boolean get() = byteCount > 0

    /**
     * Content held in memory, or null when it lives in a private staged file (or is empty).
     * Readers of a retained attachment fall back to native retention or download on null and
     * never treat a file-backed attachment's empty array as its content.
     */
    val inMemoryBytes: ByteArray? get() = plaintextBytes.takeIf { !isFileBacked && it.isNotEmpty() }

    /** Diagnostics must not include plaintext, document names or private snapshot paths. */
    override fun toString(): String = "PendingAttachment(byteCount=$byteCount, fileBacked=${sourceFile != null}, stored=${draftFile != null})"

    /** Compares attachment byte content rather than the backing array's identity. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingAttachment) return false
        if (mediaType != other.mediaType) return false
        if (fileName != other.fileName) return false
        if (sourceFile != other.sourceFile) return false
        if (draftFile != other.draftFile) return false
        return plaintextBytes.contentEquals(other.plaintextBytes)
    }

    /** Produces a content-based hash consistent with [equals]. */
    override fun hashCode(): Int {
        var result = plaintextBytes.contentHashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + (sourceFile?.hashCode() ?: 0)
        result = 31 * result + (draftFile?.hashCode() ?: 0)
        return result
    }
}

/** The size and SHA-256 of an inline body as the native draft holds it. */
data class NativeDraftBody(
    val byteCount: Long,
    val sha256: String,
)

/**
 * The inline body the native draft holds for this attachment: the one remembered before a rewrite, else these bytes.
 * Null for an attachment the draft does not hold inline, and for one whose bytes left memory unremembered. Hashes the
 * bytes, so call off Main.
 */
internal fun PendingAttachment.nativeInlineBody(): NativeDraftBody? =
    when {
        nativeDraftAttachmentId == null || draftFile != null -> null
        nativeDraftBody != null -> nativeDraftBody
        plaintextBytes.isEmpty() -> null
        else -> NativeDraftBody(plaintextBytes.size.toLong(), sha256(plaintextBytes).toHex())
    }

/**
 * This attachment before send preparation replaces its bytes, remembering the body its native draft holds so the
 * draft's original can still be recognized and removed once the send is accepted. Call off Main.
 */
internal fun PendingAttachment.rememberingNativeDraftBody(): PendingAttachment =
    if (nativeDraftBody != null) this else copy(nativeDraftBody = nativeInlineBody())

/**
 * A large attachment the composer's native draft holds as a stored file: [attachmentId] names it in the draft and
 * [byteCount] is its plaintext size. A send that carries one uploads it from the draft's copy, never from the pick.
 */
data class DraftStoredFile(
    val attachmentId: String,
    val byteCount: Long,
)

package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.ui.conversation.media.FILE_BACKED_TAG_BYTES

/** The stage of a single-file native upload, as MDK's transfer counter reports it. */
internal enum class FileUploadPhase {
    /** MDK is copying the staged file into its own private snapshot. */
    PREPARING,

    /** MDK is encrypting its snapshot. */
    ENCRYPTING,

    /** The ciphertext is being sent to the media server. */
    UPLOADING,

    /** Every byte is sent, and the message itself is being admitted and published. */
    SENDING,
}

/**
 * Progress of one file-backed send. [phaseBytes] counts what the current phase has handled out of
 * [totalBytes], the file's own size, and [fraction] is the whole send's share of work done, so a ring
 * driven by it never moves backwards when the phase changes.
 */
internal data class FileUploadProgress(
    val phase: FileUploadPhase,
    val phaseBytes: Long,
    val totalBytes: Long,
    val fraction: Float,
)

/**
 * Reads MDK's transfer counter for a send of exactly one file of [plaintextBytes] bytes. MDK advances
 * one counter, keeping its maximum, across three passes: its private copy (0 to N), encryption (N to 2N),
 * then the upload of the N + 16 byte ciphertext (twice the ciphertext length plus the bytes sent). Once
 * every ciphertext byte is sent, the rest of the native call admits and publishes the message.
 *
 * Each item writes its own pass values into that one maximum, so the counter only describes a send of a
 * single file — an album's items would hide behind its largest one. The maximum also hides a fallback to
 * another media server after one accepted the whole body and then refused it, so that send reads as
 * Sending while the next server receives the file again. Returns null when there is nothing to show.
 */
internal fun fileUploadProgress(
    processed: Long,
    plaintextBytes: Long,
): FileUploadProgress? {
    if (plaintextBytes <= 0L || processed < 0L) return null
    val ciphertextBytes = plaintextBytes + FILE_BACKED_TAG_BYTES
    val uploadStart = 2 * ciphertextBytes
    val uploadEnd = uploadStart + ciphertextBytes
    val fraction = (processed.toDouble() / uploadEnd.toDouble()).coerceIn(0.0, 1.0).toFloat()
    return when {
        // A finished pass already belongs to the next step: after encryption MDK connects and signs before
        // the first byte leaves, which is Uploading with nothing sent rather than Encrypting at full.
        processed < plaintextBytes ->
            FileUploadProgress(FileUploadPhase.PREPARING, processed, plaintextBytes, fraction)
        processed < 2 * plaintextBytes ->
            FileUploadProgress(FileUploadPhase.ENCRYPTING, processed - plaintextBytes, plaintextBytes, fraction)
        processed < uploadEnd -> {
            // The file's own size is what a person recognises, so the trailing tag is not shown as bytes.
            val sent = (processed - uploadStart).coerceIn(0L, plaintextBytes)
            FileUploadProgress(FileUploadPhase.UPLOADING, sent, plaintextBytes, fraction)
        }
        else -> FileUploadProgress(FileUploadPhase.SENDING, plaintextBytes, plaintextBytes, 1f)
    }
}

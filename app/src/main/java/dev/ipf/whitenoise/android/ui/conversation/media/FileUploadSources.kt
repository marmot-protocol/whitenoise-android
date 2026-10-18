package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.state.PendingAttachment
import java.io.ByteArrayInputStream
import java.io.File

private const val MAX_FILE_UPLOAD_ITEMS = 64
private const val FILE_AUTH_TAG_BYTES = 16L

/** Host-local metadata; only the native runtime creates protocol references and ciphertext. */
internal data class FileUploadSource(
    val sourcePath: String,
    val byteCount: Long,
    val fileName: String,
    val mediaType: String,
    val dim: String?,
    val thumbhash: String?,
) {
    override fun toString(): String = "FileUploadSource(...)"
}

/** Keeps input paths valid through snapshot preparation; it does not own existing retry sources. */
internal class FileUploadSources(
    val inputs: List<FileUploadSource>,
    private val resources: List<AutoCloseable>,
) : AutoCloseable {
    /** Closes every staged source, newest first, rethrowing the first failure with the later ones suppressed. */
    override fun close() {
        var failure: Exception? = null
        resources.asReversed().forEach { resource ->
            try {
                resource.close()
            } catch (expectedCloseFailure: Exception) {
                if (failure == null) failure = expectedCloseFailure else failure.addSuppressed(expectedCloseFailure)
            }
        }
        failure?.let { throw it }
    }
}

/**
 * Whether [attachments] can go to MDK as one native file batch: at most the batch's item count, each item and the
 * batch within [maxCiphertextBytes] with one tag per item, and [usableBytes] of free disk for the copies a staged
 * upload holds at once plus the app's reserve. A caller that cannot use the file path keeps the in-memory upload.
 */
internal fun fileUploadFits(
    attachments: List<PendingAttachment>,
    maxCiphertextBytes: Long,
    usableBytes: Long,
): Boolean {
    val countFits = attachments.isNotEmpty() && attachments.size <= MAX_FILE_UPLOAD_ITEMS
    val ciphertextBytes = attachments.sumOf { it.byteCount + FILE_AUTH_TAG_BYTES }
    val diskBytes = fileUploadCopyBytes(attachments) + FILE_BACKED_DISK_RESERVE_BYTES
    return countFits &&
        attachments.all { it.byteCount > 0 } &&
        ciphertextBytes <= maxCiphertextBytes &&
        usableBytes >= diskBytes
}

/** The disk the copies of one staged upload of [attachments] hold at once, before the app's reserve. */
internal fun fileUploadCopyBytes(attachments: List<PendingAttachment>): Long {
    val plaintextBytes = attachments.sumOf { it.byteCount }
    return plaintextBytes * FILE_BACKED_DISK_COPIES
}

/**
 * The disk that concurrent staged uploads have claimed for their copies, so two destinations of one forward never
 * both count on the same free space (#2556). Each claim is released once its upload ends.
 */
internal class StagedDiskClaims {
    private var claimedBytes = 0L

    /** Claims [bytes] if [fits] accepts what [usableBytes] leaves after earlier claims, and says whether it did. */
    @Synchronized
    fun claimIf(
        bytes: Long,
        usableBytes: Long,
        fits: (availableBytes: Long) -> Boolean,
    ): Boolean {
        val accepted = fits(usableBytes - claimedBytes)
        if (accepted) claimedBytes += bytes
        return accepted
    }

    /** Releases a claim of [bytes] that [claimIf] accepted. */
    @Synchronized
    fun release(bytes: Long) {
        claimedBytes -= bytes
    }
}

/**
 * Converts a mixed album to file inputs without growing the byte-array cache.
 * Supply the matching native ciphertext ceiling, including one 16-byte tag per item.
 * Call off the UI thread. Close this batch after the native call has returned.
 */
internal fun stageFileUploadSources(
    attachments: List<PendingAttachment>,
    directory: File,
    maxCiphertextBytes: Long,
    checkCancellation: () -> Unit = {},
): FileUploadSources {
    require(attachments.isNotEmpty() && attachments.size <= MAX_FILE_UPLOAD_ITEMS)
    var remaining = maxCiphertextBytes
    attachments.forEach { item ->
        val withinBound =
            item.byteCount > 0 && remaining >= FILE_AUTH_TAG_BYTES && item.byteCount <= remaining - FILE_AUTH_TAG_BYTES
        require(withinBound) {
            "file attachments exceed native transfer bound"
        }
        remaining -= item.byteCount + FILE_AUTH_TAG_BYTES
    }
    val resources = mutableListOf<AutoCloseable>()
    try {
        val inputs =
            attachments.map { item ->
                checkCancellation()
                val source =
                    item.sourceFile ?: run {
                        val read =
                            readStagedDocument(directory, item.byteCount, checkCancellation) {
                                ByteArrayInputStream(item.plaintextBytes)
                            }
                        check(read is StagedDocumentRead.Success) { "upload source unavailable" }
                        read.source.also(resources::add)
                    }
                resources += source.acquire()
                fileUploadInput(source, item)
            }
        return FileUploadSources(inputs, resources)
    } catch (expectedStagingFailure: Exception) {
        try {
            FileUploadSources(emptyList(), resources).close()
        } catch (expectedCleanupFailure: Exception) {
            expectedStagingFailure.addSuppressed(expectedCleanupFailure)
        }
        throw expectedStagingFailure
    }
}

private fun fileUploadInput(
    source: StagedUploadSource,
    item: PendingAttachment,
): FileUploadSource =
    FileUploadSource(
        source.file.absolutePath,
        source.byteCount,
        item.fileName,
        item.mediaType,
        item.dim,
        item.thumbhash,
    )

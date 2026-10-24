package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.core.DiagnosticErrorMetadata
import dev.ipf.whitenoise.android.state.PendingAttachment
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

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

/**
 * This app could not write an upload's snapshot because the disk is full, so the send reports storage rather than a
 * generic failure (#2284). The send keeps its own bytes, so Retry works once space is freed.
 */
internal class UploadStagingStorageFullException :
    IOException("upload staging ran out of storage"),
    DiagnosticErrorMetadata {
    override val diagnosticErrorCode = "STORAGE_FULL"
    override val diagnosticTechnicalDetail: String? = null
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

/**
 * The disk one staged upload of [attachments] adds while it runs, before the app's reserve: for an item held in
 * memory, this app's snapshot and MDK's staged copy; for an item already in a private file, only MDK's staged copy.
 */
internal fun fileUploadCopyBytes(attachments: List<PendingAttachment>): Long =
    attachments.sumOf { item ->
        val copies = if (item.sourceFile == null) FILE_BACKED_DISK_COPIES else FILE_BACKED_DISK_COPIES - 1
        item.byteCount * copies
    }

/** The part of [fileUploadCopyBytes] this app writes before MDK starts: snapshots of the items held in memory. */
internal fun fileUploadSnapshotBytes(attachments: List<PendingAttachment>): Long {
    val inMemory = attachments.filter { it.sourceFile == null }
    return inMemory.sumOf { it.byteCount }
}

/**
 * The disk that concurrent staged uploads have claimed for copies not yet written, so two destinations of one
 * forward never both count on the same free space (#2556). A claim shrinks as its copies land on disk, where free
 * space already counts them, and is released once its upload ends.
 */
internal class StagedDiskClaims {
    private var claimedBytes = 0L

    /** One upload's claim on disk for copies it has not written yet. */
    inner class Claim internal constructor(
        private var heldBytes: Long,
    ) {
        /** Gives back [bytes] of this claim that are now written to disk, at most what it still holds. */
        fun shrinkBy(bytes: Long) {
            synchronized(this@StagedDiskClaims) {
                val given = bytes.coerceIn(0L, heldBytes)
                heldBytes -= given
                claimedBytes -= given
            }
        }

        /** Releases what is left of this claim once its upload ends. */
        fun release() = shrinkBy(Long.MAX_VALUE)
    }

    /** Claims [bytes] if [fits] accepts what [usableBytes] leaves after earlier claims, returning the claim or null. */
    @Synchronized
    fun claimIf(
        bytes: Long,
        usableBytes: Long,
        fits: (availableBytes: Long) -> Boolean,
    ): Claim? {
        if (!fits(usableBytes - claimedBytes)) return null
        claimedBytes += bytes
        return Claim(bytes)
    }
}

/**
 * Converts a mixed album to file inputs without growing the byte-array cache.
 * Supply the matching native ciphertext ceiling, including one 16-byte tag per item.
 * A snapshot that cannot be written while [usableBytes] leaves less than the item plus the app's reserve throws
 * [UploadStagingStorageFullException]; any other unreadable snapshot is a generic failure.
 * Call off the UI thread. Close this batch after the native call has returned.
 */
internal fun stageFileUploadSources(
    attachments: List<PendingAttachment>,
    directory: File,
    maxCiphertextBytes: Long,
    checkCancellation: () -> Unit = {},
    usableBytes: (File) -> Long = ::usableSpaceFor,
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
                        if (read is StagedDocumentRead.Unreadable && !snapshotFits(item, directory, usableBytes)) {
                            throw UploadStagingStorageFullException()
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

/**
 * Whether [item]'s snapshot fits in [directory] with the app's reserve left over. A failed write has already deleted
 * its partial copy, so this measures the room it had before it started.
 */
private fun snapshotFits(
    item: PendingAttachment,
    directory: File,
    usableBytes: (File) -> Long,
): Boolean = usableBytes(directory) >= item.byteCount + FILE_BACKED_DISK_RESERVE_BYTES

/** The native file input for one staged [source], carrying [item]'s presentation metadata. */
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

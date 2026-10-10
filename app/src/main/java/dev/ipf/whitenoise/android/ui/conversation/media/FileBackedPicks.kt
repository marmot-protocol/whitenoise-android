package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.media.MediaCacheDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** The AEAD tag MDK appends to every encrypted attachment. */
internal const val FILE_BACKED_TAG_BYTES = 16L

/**
 * Plaintext ceiling for one pick sent from a private staged file: 512 MiB minus the 16-byte AEAD tag.
 * MDK's in-memory download path refuses ciphertext above 512 MiB, so a larger file would upload but
 * stay unreadable for members whose app predates file-backed downloads.
 */
internal const val FILE_BACKED_ATTACHMENT_MAX_BYTES: Long = 512L * 1024L * 1024L - FILE_BACKED_TAG_BYTES

// A large send holds three copies on disk at once: this snapshot, MDK's private snapshot and its ciphertext.
private const val FILE_BACKED_DISK_COPIES = 3L

// Headroom left for the account database and the rest of the app after the copies above.
private const val FILE_BACKED_DISK_RESERVE_BYTES = 64L * 1024L * 1024L

/** Native upload bounds for picks sent from a private staged file. */
internal data class FileBackedSendLimits(
    val perFileBytes: Long,
    val batchCiphertextBytes: Long,
)

/**
 * MarmotKit's file batch ceiling, or null (keep every pick in memory) when the native library cannot be
 * linked, as in JVM tests. Other failures are not expected from this constant getter and propagate.
 */
internal fun nativeFileBackedSendLimits(): FileBackedSendLimits? =
    try {
        FileBackedSendLimits(
            perFileBytes = FILE_BACKED_ATTACHMENT_MAX_BYTES,
            batchCiphertextBytes =
                dev.ipf.marmotkit
                    .maxFileMediaCiphertextBytes()
                    .toLong(),
        )
    } catch (_: LinkageError) {
        null
    }

// Names this process's staging directory. Only in-memory retry state owns a snapshot, so every
// other process's directory under upload_sources is an orphan once this process exists.
private val processUploadSourcesName = "process-" + UUID.randomUUID()

/** This process's private staging directory for file-backed sends, under [cacheDir]'s upload_sources. */
internal fun uploadSourcesDirectory(cacheDir: File): File {
    val root = File(cacheDir, MediaCacheDirs.UPLOAD_SOURCES)
    return File(root, processUploadSourcesName)
}

/**
 * Deletes every staged snapshot left by an earlier process, whatever its age, and never this process's
 * own directory. Safe to repeat: an Activity can be recreated many times in one process.
 */
internal fun sweepOrphanedUploadSources(cacheDir: File) {
    File(cacheDir, MediaCacheDirs.UPLOAD_SOURCES)
        .listFiles()
        ?.filterNot { it.name == processUploadSourcesName }
        ?.forEach { orphan -> runCatching { orphan.deleteRecursively() } }
}

/** Deletes a snapshot without letting a failed unlink escape into cleanup or a coroutine; the sweep retries it. */
internal fun StagedUploadSource.closeQuietly() {
    runCatching { close() }
}

/** Free space on the volume holding [directory], measured at its nearest existing ancestor. */
internal fun usableSpaceFor(directory: File): Long {
    val existing = generateSequence(directory) { it.parentFile }.firstOrNull(File::exists)
    return existing?.usableSpace ?: 0L
}

/** Why a large pick could not be staged for a file-backed send. */
internal enum class FileBackedPickFailure {
    TOO_LARGE,
    EMPTY,
    UNREADABLE,
    STORAGE,
}

/** A staged pick, or the reason it was refused. Exactly one of the two is set. */
internal data class FileBackedPick(
    val source: StagedUploadSource?,
    val failure: FileBackedPickFailure?,
)

/**
 * The file-backed staging of one send operation, shared by its album and every document it sends.
 * [onStaged] hears about every snapshot as soon as it exists, so a caller can release it if a later
 * step of the same preparation fails. The messages of a send upload one at a time, and MDK deletes each
 * message's snapshots and ciphertext when its upload ends, so the send needs room for its largest
 * message's upload at once, beside the snapshots of every message still waiting, never the sum of all.
 * MDK may also keep a plaintext copy of an uploaded message for the sender, but only when it finds room
 * for four times that message, so that optional copy is not reserved here.
 */
internal class FileBackedSendStaging(
    private val onStaged: (StagedUploadSource) -> Unit = {},
) {
    private val largestUploadWrite = AtomicLong()

    /** The most disk any one message of this send still has to write while it uploads. */
    val largestUploadWriteBytes: Long get() = largestUploadWrite.get()

    /** Records that one message's upload, as admitted so far, will write [uploadWriteBytes] bytes. */
    fun reserve(uploadWriteBytes: Long) {
        largestUploadWrite.accumulateAndGet(uploadWriteBytes) { current, next -> maxOf(current, next) }
    }

    /** Reports a snapshot the send now owns. */
    fun staged(source: StagedUploadSource) = onStaged(source)
}

/**
 * The file-backed budget of one outgoing message. Each staged item, and each in-memory item that the
 * upload converts to a file, costs its bytes plus one AEAD tag against the native per-send ciphertext bound.
 * Disk is checked against the send's shared [staging], so a document staged after a large album must fit
 * its own snapshot beside the album's upload, and the album's upload still has room once it lands.
 */
internal class FileBackedPickBudget(
    val directory: File,
    private val limits: FileBackedSendLimits,
    private val usableBytes: () -> Long = { usableSpaceFor(directory) },
    private val staging: FileBackedSendStaging = FileBackedSendStaging(),
) {
    private var remainingCiphertextBytes = limits.batchCiphertextBytes

    // What this message's upload writes: MDK's snapshot and ciphertext of each staged item, and all three
    // copies of each in-memory item it converts to a file. It costs disk only once the message uploads from
    // files, which the first staged snapshot decides. Until then an all-in-memory message uploads from bytes.
    private var uploadWriteBytes = 0L
    private var uploadsFromFiles = false

    /** The largest pick the next item may be, given the per-file ceiling and what this message has used. */
    fun nextPickMaxBytes(): Long {
        val batchRoom = remainingCiphertextBytes - FILE_BACKED_TAG_BYTES
        return minOf(limits.perFileBytes, batchRoom).coerceAtLeast(0L)
    }

    /**
     * Admits one album item of [byteCount] bytes and returns null, or returns why it cannot join: it no
     * longer fits this message's ciphertext bound, or it is [inMemory] in a message that uploads from
     * files and the three disk copies its upload will write do not fit beside the rest of the send.
     */
    fun admit(
        byteCount: Long,
        inMemory: Boolean,
    ): FileBackedPickFailure? {
        val writeAfter = uploadWriteBytes + if (inMemory) byteCount * FILE_BACKED_DISK_COPIES else 0L
        return when {
            byteCount > nextPickMaxBytes() -> FileBackedPickFailure.TOO_LARGE
            uploadsFromFiles && inMemory && !hasStorageFor(0L, writeAfter) -> FileBackedPickFailure.STORAGE
            else -> {
                remainingCiphertextBytes -= byteCount + FILE_BACKED_TAG_BYTES
                uploadWriteBytes = writeAfter
                if (uploadsFromFiles) staging.reserve(uploadWriteBytes)
                null
            }
        }
    }

    /**
     * Copies one provider stream into a private snapshot. A declared size is only a hint: it can refuse
     * early, but the copy itself enforces the ceiling and the free-space check is repeated afterwards.
     * [accept] inspects the finished snapshot before anything is reserved. A refused one is deleted and
     * reported as unreadable, so it leaves no reservation behind.
     */
    fun stage(
        declaredSize: Long,
        checkCancellation: () -> Unit = {},
        accept: (StagedUploadSource) -> Boolean = { true },
        open: () -> InputStream?,
    ): FileBackedPick {
        val maxBytes = nextPickMaxBytes()
        return when {
            maxBytes <= 0L || declaredSize > maxBytes -> refused(FileBackedPickFailure.TOO_LARGE)
            declaredSize > 0L && !hasStorageFor(declaredSize, uploadWriteBytes + nativeCopies(declaredSize)) ->
                refused(FileBackedPickFailure.STORAGE)
            else -> stageWithin(maxBytes, checkCancellation, accept, open)
        }
    }

    /**
     * Runs the bounded copy, lets [accept] inspect it, then confirms this message's upload still fits beside
     * the finished snapshot. The first snapshot makes the whole message upload from files, so the copies its
     * in-memory items will write count from then on.
     */
    private fun stageWithin(
        maxBytes: Long,
        checkCancellation: () -> Unit,
        accept: (StagedUploadSource) -> Boolean,
        open: () -> InputStream?,
    ): FileBackedPick =
        when (val read = readStagedDocument(directory, maxBytes, checkCancellation, open)) {
            is StagedDocumentRead.Success -> {
                // The snapshot is on disk now, MDK's snapshot and ciphertext of it are still to come.
                val writeAfter = uploadWriteBytes + nativeCopies(read.source.byteCount)
                if (!accept(read.source)) {
                    read.source.closeQuietly()
                    refused(FileBackedPickFailure.UNREADABLE)
                } else if (hasStorageFor(0L, writeAfter)) {
                    uploadWriteBytes = writeAfter
                    uploadsFromFiles = true
                    staging.reserve(uploadWriteBytes)
                    staging.staged(read.source)
                    FileBackedPick(read.source, null)
                } else {
                    read.source.closeQuietly()
                    refused(FileBackedPickFailure.STORAGE)
                }
            }
            StagedDocumentRead.TooLarge -> refused(FileBackedPickFailure.TOO_LARGE)
            StagedDocumentRead.Empty -> refused(FileBackedPickFailure.EMPTY)
            // The partial copy is already deleted, so only a disk still inside the app's reserve reads as storage.
            StagedDocumentRead.Unreadable ->
                refused(
                    if (usableBytes() >= FILE_BACKED_DISK_RESERVE_BYTES) {
                        FileBackedPickFailure.UNREADABLE
                    } else {
                        FileBackedPickFailure.STORAGE
                    },
                )
        }

    /** MDK's private snapshot and ciphertext of a staged item of [byteCount] bytes. */
    private fun nativeCopies(byteCount: Long): Long = byteCount * (FILE_BACKED_DISK_COPIES - 1)

    /**
     * Whether [snapshotBytes] still to be copied fit on disk together with the largest upload of the send,
     * counting this message's upload as [uploadWriteAfter], while leaving the app its reserve.
     */
    private fun hasStorageFor(
        snapshotBytes: Long,
        uploadWriteAfter: Long,
    ): Boolean {
        val largestUpload = maxOf(staging.largestUploadWriteBytes, uploadWriteAfter)
        return usableBytes() >= snapshotBytes + largestUpload + FILE_BACKED_DISK_RESERVE_BYTES
    }

    /** A pick that was not staged, carrying only the reason. */
    private fun refused(failure: FileBackedPickFailure) = FileBackedPick(null, failure)
}

/**
 * Deletes staged snapshots that no queued send adopted, off the main thread and despite cancellation.
 * A queued send releases its own snapshots when its retained upload ends.
 */
internal suspend fun releaseUnadoptedStagedSources(
    sources: Collection<StagedUploadSource>,
    adopted: Set<StagedUploadSource>,
) {
    val unadopted = sources.filterNot { it in adopted }
    if (unadopted.isEmpty()) return
    withContext(NonCancellable + Dispatchers.IO) { unadopted.forEach(StagedUploadSource::closeQuietly) }
}

package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.media.MediaCacheDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

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
private fun usableSpaceFor(directory: File): Long {
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
 * The file-backed budget of one outgoing message. Each staged item, and each in-memory item that the
 * upload converts to a file, costs its bytes plus one AEAD tag against the native per-send ciphertext bound.
 * [onStaged] hears about every snapshot as soon as it exists, so a caller can release it if a later
 * step of the same preparation fails.
 */
internal class FileBackedPickBudget(
    val directory: File,
    private val limits: FileBackedSendLimits,
    private val usableBytes: () -> Long = { usableSpaceFor(directory) },
    private val onStaged: (StagedUploadSource) -> Unit = {},
) {
    private var remainingCiphertextBytes = limits.batchCiphertextBytes

    /** The largest pick the next item may be, given the per-file ceiling and what this message has used. */
    fun nextPickMaxBytes(): Long {
        val batchRoom = remainingCiphertextBytes - FILE_BACKED_TAG_BYTES
        return minOf(limits.perFileBytes, batchRoom).coerceAtLeast(0L)
    }

    /** Charges an accepted attachment of [byteCount] bytes against this message's ciphertext bound. */
    fun charge(byteCount: Long) {
        remainingCiphertextBytes -= byteCount + FILE_BACKED_TAG_BYTES
    }

    /**
     * Copies one provider stream into a private snapshot. A declared size is only a hint: it can refuse
     * early, but the copy itself enforces the ceiling and the free-space check is repeated afterwards.
     */
    fun stage(
        declaredSize: Long,
        checkCancellation: () -> Unit = {},
        open: () -> InputStream?,
    ): FileBackedPick {
        val maxBytes = nextPickMaxBytes()
        return when {
            maxBytes <= 0L || declaredSize > maxBytes -> refused(FileBackedPickFailure.TOO_LARGE)
            declaredSize > 0L && !hasStorageFor(declaredSize, FILE_BACKED_DISK_COPIES) ->
                refused(FileBackedPickFailure.STORAGE)
            else -> stageWithin(maxBytes, checkCancellation, open)
        }
    }

    /** Runs the bounded copy, then confirms the native copies still fit beside the finished snapshot. */
    private fun stageWithin(
        maxBytes: Long,
        checkCancellation: () -> Unit,
        open: () -> InputStream?,
    ): FileBackedPick =
        when (val read = readStagedDocument(directory, maxBytes, checkCancellation, open)) {
            is StagedDocumentRead.Success ->
                if (hasStorageFor(read.source.byteCount, FILE_BACKED_DISK_COPIES - 1)) {
                    onStaged(read.source)
                    FileBackedPick(read.source, null)
                } else {
                    read.source.closeQuietly()
                    refused(FileBackedPickFailure.STORAGE)
                }
            StagedDocumentRead.TooLarge -> refused(FileBackedPickFailure.TOO_LARGE)
            StagedDocumentRead.Empty -> refused(FileBackedPickFailure.EMPTY)
            StagedDocumentRead.Unreadable ->
                refused(
                    if (hasStorageFor(0L, 0L)) FileBackedPickFailure.UNREADABLE else FileBackedPickFailure.STORAGE,
                )
        }

    /** Whether [copies] more copies of [byteCount] bytes fit while leaving the app its reserve. */
    private fun hasStorageFor(
        byteCount: Long,
        copies: Long,
    ): Boolean {
        val needed = byteCount * copies + FILE_BACKED_DISK_RESERVE_BYTES
        return usableBytes() >= needed
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

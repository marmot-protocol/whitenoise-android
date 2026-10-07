package dev.ipf.whitenoise.android.ui.conversation.media

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions

/** A temporary platform input for native file upload, never an Android protocol cache. */
class StagedUploadSource internal constructor(
    val file: File,
    val byteCount: Long,
) : AutoCloseable {
    private var closed = false
    private var readers = 0

    override fun toString(): String = "StagedUploadSource(byteCount=$byteCount)"

    /** Pins the path until native snapshot preparation has finished. */
    @Synchronized
    internal fun acquire(): AutoCloseable {
        check(!closed) { "upload source already released" }
        readers++
        var released = false
        return AutoCloseable {
            synchronized(this) {
                if (!released) {
                    released = true
                    readers--
                    if (closed && readers == 0) Files.deleteIfExists(file.toPath())
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        if (readers == 0) Files.deleteIfExists(file.toPath())
    }
}

internal sealed interface StagedDocumentRead {
    data class Success(
        val source: StagedUploadSource,
    ) : StagedDocumentRead

    data object TooLarge : StagedDocumentRead

    data object Empty : StagedDocumentRead

    data object Unreadable : StagedDocumentRead
}

/**
 * Snapshot a ContentResolver stream with bounded buffers. Provider size metadata
 * is advisory; the native runtime hashes, encrypts and validates this snapshot.
 */
internal fun readStagedDocument(
    directory: File,
    maxBytes: Long,
    checkCancellation: () -> Unit = {},
    open: () -> InputStream?,
): StagedDocumentRead {
    require(maxBytes > 0)
    var partial: File? = null
    val result =
        try {
            checkCancellation()
            if (!prepareStagingDirectory(directory)) {
                StagedDocumentRead.Unreadable
            } else {
                open()
                    ?.use { input ->
                        val output =
                            Files.createTempFile(
                                directory.toPath(),
                                "upload-source-",
                                ".part",
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                            )
                                .toFile()
                        partial = output
                        val total =
                            output.outputStream().use { copyStagedDocument(input, it, maxBytes, checkCancellation) }
                        checkCancellation()
                        when (total) {
                            null -> StagedDocumentRead.TooLarge
                            0L -> StagedDocumentRead.Empty
                            else -> {
                                Files.setPosixFilePermissions(
                                    output.toPath(),
                                    PosixFilePermissions.fromString("r--------"),
                                )
                                StagedDocumentRead.Success(StagedUploadSource(output, total))
                            }
                        }
                    }?.also { if (it is StagedDocumentRead.Success) partial = null } ?: StagedDocumentRead.Unreadable
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            StagedDocumentRead.Unreadable
        } finally {
            partial?.let { Files.deleteIfExists(it.toPath()) }
        }
    return result
}

private fun prepareStagingDirectory(directory: File): Boolean {
    if (Files.isSymbolicLink(directory.toPath())) return false
    Files.createDirectories(directory.toPath())
    val isDirectory = Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
    if (isDirectory) Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------"))
    return isDirectory
}

/** Null means overflow; read at most one extra byte and handle providers that return zero. */
private fun copyStagedDocument(
    input: InputStream,
    sink: OutputStream,
    maxBytes: Long,
    checkCancellation: () -> Unit,
): Long? {
    val buffer = ByteArray(STAGED_DOCUMENT_BUFFER_BYTES)
    var total = 0L
    while (true) {
        checkCancellation()
        val available = (maxBytes - total).coerceAtLeast(0)
        val take = if (available >= buffer.size) buffer.size else available.toInt() + 1
        val read = input.read(buffer, 0, take)
        val count =
            if (read == 0) {
                val next = input.read()
                if (next >= 0) buffer[0] = next.toByte()
                if (next < 0) -1 else 1
            } else {
                read
            }
        if (count == -1) break
        if (count.toLong() > available) return null
        sink.write(buffer, 0, count)
        total += count
    }
    return total
}

private const val STAGED_DOCUMENT_BUFFER_BYTES = 64 * 1024

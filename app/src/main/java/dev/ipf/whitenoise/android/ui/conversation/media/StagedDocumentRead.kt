package dev.ipf.whitenoise.android.ui.conversation.media

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream
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
    try {
        checkCancellation()
        if (Files.isSymbolicLink(directory.toPath())) return StagedDocumentRead.Unreadable
        Files.createDirectories(directory.toPath())
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return StagedDocumentRead.Unreadable
        }
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------"))
        val stream = open() ?: return StagedDocumentRead.Unreadable
        val completed =
            stream.use { input ->
                val output =
                    Files
                        .createTempFile(
                            directory.toPath(),
                            "upload-source-",
                            ".part",
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                        ).toFile()
                partial = output
                var total = 0L
                output.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancellation()
                        val available = (maxBytes - total).coerceAtLeast(0)
                        val take = if (available >= buffer.size) buffer.size else available.toInt() + 1
                        val count = input.read(buffer, 0, take)
                        if (count == -1) break
                        if (count == 0) {
                            val next = input.read()
                            if (next == -1) break
                            if (available == 0L) return StagedDocumentRead.TooLarge
                            sink.write(next)
                            total++
                        } else {
                            if (count.toLong() > available) return StagedDocumentRead.TooLarge
                            sink.write(buffer, 0, count)
                            total += count
                        }
                    }
                }
                checkCancellation()
                if (total == 0L) return StagedDocumentRead.Empty
                Files.setPosixFilePermissions(output.toPath(), PosixFilePermissions.fromString("r--------"))
                StagedDocumentRead.Success(StagedUploadSource(output, total))
            }
        partial = null
        return completed
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        return StagedDocumentRead.Unreadable
    } finally {
        partial?.let { Files.deleteIfExists(it.toPath()) }
    }
}

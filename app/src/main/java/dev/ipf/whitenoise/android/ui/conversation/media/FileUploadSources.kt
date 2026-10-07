package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.state.PendingAttachment
import java.io.ByteArrayInputStream
import java.io.File

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
    override fun close() {
        var failure: Exception? = null
        resources.asReversed().forEach { resource ->
            try {
                resource.close()
            } catch (error: Exception) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        failure?.let { throw it }
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
    require(attachments.isNotEmpty() && attachments.size <= 64)
    var remaining = maxCiphertextBytes
    attachments.forEach { item ->
        require(item.byteCount > 0 && remaining >= 16 && item.byteCount <= remaining - 16) {
            "file attachments exceed native transfer bound"
        }
        remaining -= item.byteCount + 16
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
                FileUploadSource(source.file.absolutePath, source.byteCount, item.fileName, item.mediaType, item.dim, item.thumbhash)
            }
        return FileUploadSources(inputs, resources)
    } catch (failure: Exception) {
        try {
            FileUploadSources(emptyList(), resources).close()
        } catch (cleanup: Exception) {
            failure.addSuppressed(cleanup)
        }
        throw failure
    }
}

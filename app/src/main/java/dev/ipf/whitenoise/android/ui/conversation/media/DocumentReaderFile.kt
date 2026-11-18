package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.sandbox.PdfPagePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

// Names this process's reader directory. Readers live only in this process, so every other
// process's directory is an orphan left by a kill before the reader's cleanup ran.
private val processReaderName = "process-" + UUID.randomUUID()

/** This process's private directory for PDFs open in the reader, under [cacheDir]. */
internal fun documentReaderDirectory(cacheDir: File): File = File(readerRoot(cacheDir), processReaderName)

private fun readerRoot(cacheDir: File) = File(cacheDir, MediaCacheDirs.DOCUMENT_READER)

/** Deletes reader copies left by earlier processes at any age; safe while this process has a reader open. */
internal fun sweepOrphanedDocumentReaderFiles(cacheDir: File) {
    readerRoot(cacheDir)
        .listFiles()
        ?.filterNot { it.name == processReaderName }
        ?.forEach { orphan -> runCatching { orphan.deleteRecursively() } }
}

/**
 * Copies the source from [openSource] into a private file that no FileProvider path exposes, closes the source, lends
 * the copy to [use] and deletes it afterwards, so an open reader holds one plaintext copy rather than two. The declared
 * size is checked before any file exists and the streamed size while copying, so a source that misreports its size
 * cannot write past the renderer's input limit. The coroutine owns even partial writes, including cancellation at the
 * dispatcher return boundary.
 */
internal suspend fun withPrivatePdfFile(
    cacheDir: File,
    openSource: suspend () -> AttachmentPlaintext,
    use: suspend (File) -> Unit,
) = withContext(Dispatchers.IO) {
    val file =
        openSource().use { source ->
            require(source.size in 1..PdfPagePolicy.MAX_INPUT)
            val directory = documentReaderDirectory(cacheDir)
            check(directory.isDirectory || directory.mkdirs())
            val copy = File.createTempFile("reader-", ".pdf", directory)
            var written = false
            try {
                ensureActive()
                BoundedPdfOutput(copy.outputStream(), this).use { source.copyTo(it) }
                written = true
                copy
            } finally {
                if (!written) copy.delete()
            }
        }
    try {
        ensureActive()
        use(file)
    } finally {
        file.delete()
    }
}

private class BoundedPdfOutput(
    output: OutputStream,
    private val scope: CoroutineScope,
) : FilterOutputStream(output) {
    private var written = 0L

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        scope.ensureActive()
        written += len
        if (written > PdfPagePolicy.MAX_INPUT) throw IOException("PDF exceeds the reader input limit")
        out.write(b, off, len)
    }
}

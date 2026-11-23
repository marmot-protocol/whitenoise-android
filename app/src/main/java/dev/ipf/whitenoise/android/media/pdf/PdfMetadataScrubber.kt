package dev.ipf.whitenoise.android.media.pdf

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Removes identifying metadata from PDFs in place without changing their size or layout (#3377).
 *
 * Every edit overwrites bytes with the same number of bytes, so cross-reference offsets, incremental revisions,
 * linearization, page content, text, forms and their values, tags, bookmarks, links and annotations all stay as
 * they were. What is removed: document-information entries (author, title, subject, keywords, creating and
 * producing apps, dates and custom entries) in every revision, every XMP packet, and camera, location and editing
 * segments of embedded JPEG photos. What stays: visible content, comments and their author labels, attached
 * files, file identifiers and image formats other than baseline JPEG.
 *
 * Signed and encrypted PDFs are never changed. A PDF whose metadata cannot be rewritten at the same length is not
 * changed either. Each change is checked by planning again, which must find nothing left; otherwise every write is
 * undone, so a refused PDF always keeps its original bytes.
 */
internal object PdfMetadataScrubber {
    /** What removing metadata from [bytes] would do, without changing them. */
    fun inspect(bytes: ByteArray): PdfMetadataResult = inspect(ArrayPdfBytes(bytes))

    /** What removing metadata from [file] would do, without changing it. */
    fun inspect(file: File): PdfMetadataResult = withMapped(file, writable = false, ::inspect)

    /** Removes metadata from [bytes] in place. A refused PDF keeps its bytes. */
    fun scrub(bytes: ByteArray): PdfMetadataResult = scrub(ArrayPdfBytes(bytes))

    /** Removes metadata from [file] in place. A refused PDF keeps its bytes. */
    fun scrub(file: File): PdfMetadataResult = withMapped(file, writable = true, ::scrub)

    /**
     * Bytes to send for [bytes] that may be shared with others, so they are never changed: [bytes] themselves when
     * there is nothing to remove, a cleaned copy, or null when the PDF keeps its metadata.
     */
    fun cleanedCopyOrNull(bytes: ByteArray): ByteArray? =
        when (inspect(bytes)) {
            PdfMetadataResult.NotPdf, PdfMetadataResult.Clean -> bytes
            is PdfMetadataResult.Refused -> null
            else -> bytes.copyOf().takeIf { scrub(it) is PdfMetadataResult.Scrubbed }
        }

    internal fun inspect(bytes: PdfBytes): PdfMetadataResult =
        guarded(bytes) {
            val plan = planPdfMetadataRemoval(bytes)
            when {
                plan.refusal != null -> PdfMetadataResult.Refused(requireNotNull(plan.refusal))
                plan.edits.isEmpty() -> PdfMetadataResult.Clean
                else -> PdfMetadataResult.Removable(plan.fields.toSet())
            }
        }

    internal fun scrub(bytes: PdfBytes): PdfMetadataResult =
        guarded(bytes) {
            val plan = planPdfMetadataRemoval(bytes)
            when {
                plan.refusal != null -> PdfMetadataResult.Refused(requireNotNull(plan.refusal))
                plan.edits.isEmpty() -> PdfMetadataResult.Clean
                else -> applyVerified(bytes, plan)
            }
        }

    /** Applies [plan], then plans again: anything left, or any failure, undoes every write. */
    private fun applyVerified(
        bytes: PdfBytes,
        plan: PdfMetadataPlan,
    ): PdfMetadataResult {
        val undo = plan.edits.map { PdfEdit(it.position, bytes.copy(it.position, it.position + it.bytes.size)) }
        var verified = false
        try {
            plan.edits.forEach { bytes.write(it.position, it.bytes) }
            val again = planPdfMetadataRemoval(bytes)
            verified = again.refusal == null && again.edits.isEmpty()
        } catch (_: PdfSyntaxException) {
            verified = false
        } finally {
            if (!verified) undo.asReversed().forEach { bytes.write(it.position, it.bytes) }
        }
        return if (verified) {
            PdfMetadataResult.Scrubbed(plan.fields.toSet())
        } else {
            PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported)
        }
    }

    /** Runs [block] on a PDF, reading anything malformed or unreadable as unsupported and anything else as not PDF. */
    private inline fun guarded(
        bytes: PdfBytes,
        block: () -> PdfMetadataResult,
    ): PdfMetadataResult {
        if (!looksLikePdf(bytes)) return PdfMetadataResult.NotPdf
        return try {
            block()
        } catch (_: PdfSyntaxException) {
            PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported)
        } catch (_: IndexOutOfBoundsException) {
            PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported)
        } catch (_: IllegalArgumentException) {
            PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported)
        } catch (_: OutOfMemoryError) {
            PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported)
        }
    }

    /** Maps [file] for [block]; a file too large to map, or one that cannot be read, is unsupported. */
    private fun withMapped(
        file: File,
        writable: Boolean,
        block: (PdfBytes) -> PdfMetadataResult,
    ): PdfMetadataResult =
        try {
            RandomAccessFile(file, if (writable) "rw" else "r").use { access ->
                val size = access.length()
                when {
                    size == 0L -> PdfMetadataResult.NotPdf
                    size > Int.MAX_VALUE -> PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported)
                    else -> {
                        val mode = if (writable) FileChannel.MapMode.READ_WRITE else FileChannel.MapMode.READ_ONLY
                        val buffer = access.channel.map(mode, 0L, size)
                        block(BufferPdfBytes(buffer)).also { if (writable) buffer.force() }
                    }
                }
            }
        } catch (_: IOException) {
            PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported)
        }
}

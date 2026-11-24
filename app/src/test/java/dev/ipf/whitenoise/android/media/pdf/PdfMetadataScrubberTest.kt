package dev.ipf.whitenoise.android.media.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Removing PDF metadata in place (#3377): what goes, what stays, and which PDFs are left alone. */
class PdfMetadataScrubberTest {
    @Test
    fun everyCleanableFixtureLosesItsPrivateValuesAtTheSameLength() {
        CLEANABLE_PDF_FIXTURES.forEach { name ->
            val original = pdfFixture(name)
            assertTrue("$name carries private values", leakedPdfValues(original).isNotEmpty())
            val bytes = original.copyOf()

            val result = PdfMetadataScrubber.scrub(bytes)

            assertTrue("$name: $result", result is PdfMetadataResult.Scrubbed)
            assertEquals(name, original.size, bytes.size)
            assertEquals(name, emptyList<String>(), leakedPdfValues(bytes))
            assertEquals(name, PdfMetadataResult.Clean, PdfMetadataScrubber.inspect(bytes))
        }
    }

    @Test
    fun inspectionReportsWhatWouldGoWithoutChangingAnything() {
        val original = pdfFixture("ghostscript")
        val bytes = original.copyOf()

        val result = PdfMetadataScrubber.inspect(bytes)

        assertArrayEquals(original, bytes)
        val fields = (result as PdfMetadataResult.Removable).fields
        assertTrue(
            fields.containsAll(
                listOf(
                    PdfMetadataField.Author,
                    PdfMetadataField.Description,
                    PdfMetadataField.Software,
                    PdfMetadataField.Dates,
                    PdfMetadataField.Xmp,
                ),
            ),
        )
    }

    @Test
    fun contentBookmarksLinksAndFormValuesStay() {
        val document = pdfFixture("ghostscript").also { PdfMetadataScrubber.scrub(it) }
        val form = pdfFixture("form-photo").also { PdfMetadataScrubber.scrub(it) }

        val text = pdfSearchableText(document)
        assertTrue("Chapter One" in text && "Chapter Two" in text)
        assertTrue("https://example.org/plan" in text)
        assertTrue("Searchable page one text" in text)
        assertTrue("Filled answer" in pdfSearchableText(form))
        assertTrue("/MarkInfo" in pdfSearchableText(form))
    }

    @Test
    fun onlyMetadataBytesChange() {
        val original = pdfFixture("ghostscript")
        val bytes = original.copyOf()
        PdfMetadataScrubber.scrub(bytes)

        // Every structural keyword stays exactly where it was, so offsets and the cross-reference table hold.
        listOf("obj", "endobj", "stream", "endstream", "xref", "trailer", "startxref").forEach { keyword ->
            assertEquals(keyword, offsets(original, keyword), offsets(bytes, keyword))
        }
    }

    @Test
    fun embeddedPhotoKeepsItsImageButLosesCameraData() {
        val original = pdfFixture("form-photo")
        val bytes = original.copyOf()

        val result = PdfMetadataScrubber.scrub(bytes) as PdfMetadataResult.Scrubbed

        assertTrue(PdfMetadataField.PhotoData in result.fields)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue("JFIF" in text)
        assertTrue("Exif" !in text && "WNPrivateCamera" !in text && "WNPrivateComment" !in text)
    }

    @Test
    fun pdfaIdentificationSurvives() {
        val bytes = pdfFixture("pdfa").also { PdfMetadataScrubber.scrub(it) }

        val text = pdfSearchableText(bytes)
        assertTrue(text, "pdfaid:part=\"2\"" in text && "pdfaid:conformance=\"B\"" in text)
        assertEquals(emptyList<String>(), leakedPdfValues(bytes))
    }

    @Test
    fun signedEncryptedAndUnrewritablePdfsAreRefusedUnchanged() {
        mapOf(
            "signed" to PdfMetadataRefusal.Signed,
            "encrypted" to PdfMetadataRefusal.Encrypted,
            "hex-xmp" to PdfMetadataRefusal.Unsupported,
        ).forEach { (name, reason) ->
            val original = pdfFixture(name)
            val bytes = original.copyOf()

            assertEquals(name, PdfMetadataResult.Refused(reason), PdfMetadataScrubber.inspect(bytes))
            assertEquals(name, PdfMetadataResult.Refused(reason), PdfMetadataScrubber.scrub(bytes))
            assertArrayEquals(name, original, bytes)
            assertEquals(name, null, PdfMetadataScrubber.cleanedCopyOrNull(bytes))
        }
    }

    @Test
    fun aDocumentThatIsNotAPdfIsNotTouched() {
        val text = "%PDX not a pdf, WNPrivateAuthor".toByteArray()
        val bytes = text.copyOf()

        assertEquals(PdfMetadataResult.NotPdf, PdfMetadataScrubber.scrub(bytes))
        assertArrayEquals(text, bytes)
        assertEquals(PdfMetadataResult.NotPdf, PdfMetadataScrubber.inspect(ByteArray(0)))
    }

    @Test
    fun truncatedPdfsAreRefusedOrCleanedButNeverHalfWritten() {
        val original = pdfFixture("object-streams")
        listOf(original.size / 4, original.size / 2, original.size - 40).forEach { length ->
            val truncated = original.copyOf(length)
            val bytes = truncated.copyOf()

            when (val result = PdfMetadataScrubber.scrub(bytes)) {
                is PdfMetadataResult.Scrubbed, PdfMetadataResult.Clean ->
                    assertEquals("$length", PdfMetadataResult.Clean, PdfMetadataScrubber.inspect(bytes))
                else -> assertArrayEquals("$length: $result", truncated, bytes)
            }
        }
    }

    @Test
    fun aStagedFileIsCleanedInPlaceExactlyLikeMemory() {
        val original = pdfFixture("incremental")
        val file = Files.createTempFile("pdf-metadata", ".pdf").toFile()
        try {
            file.writeBytes(original)
            val memory = original.copyOf().also { PdfMetadataScrubber.scrub(it) }

            assertTrue(PdfMetadataScrubber.scrub(file) is PdfMetadataResult.Scrubbed)

            assertArrayEquals(memory, file.readBytes())
            assertEquals(PdfMetadataResult.Clean, PdfMetadataScrubber.inspect(file))
        } finally {
            file.delete()
        }
        val missing = PdfMetadataScrubber.inspect(File("/missing"))
        assertEquals(PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported), missing)
    }

    @Test
    fun aCleanedCopyLeavesSharedBytesAlone() {
        val original = pdfFixture("flate-xmp")
        val shared = original.copyOf()

        val copy = checkNotNull(PdfMetadataScrubber.cleanedCopyOrNull(shared))

        assertArrayEquals(original, shared)
        assertNotEquals(original.toList(), copy.toList())
        assertEquals(emptyList<String>(), leakedPdfValues(copy))
        assertTrue(PdfMetadataScrubber.cleanedCopyOrNull(copy) === copy)
    }

    private fun offsets(
        bytes: ByteArray,
        keyword: String,
    ): List<Int> = Regex("\\b$keyword\\b").findAll(String(bytes, Charsets.ISO_8859_1)).map { it.range.first }.toList()
}

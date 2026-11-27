package dev.ipf.whitenoise.android.media.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hand-built PDFs for structures the generated fixtures do not reach (#3377). */
class PdfMetadataEdgeCaseTest {
    /** A string the information dictionary shares with a form field is refused, so the field keeps its value. */
    @Test
    fun aStringSharedWithVisibleContentIsRefused() {
        val source =
            pdf(
                "1 0 obj << /Type /Catalog /AcroForm << /Fields [4 0 R] >> >> endobj",
                "3 0 obj << /Author 5 0 R >> endobj",
                "4 0 obj << /FT /Tx /T (name) /V 5 0 R >> endobj",
                "5 0 obj (Alice Example) endobj",
                trailer = "<< /Root 1 0 R /Info 3 0 R >>",
            )
        val bytes = source.copyOf()

        assertEquals(PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported), PdfMetadataScrubber.scrub(bytes))
        assertArrayEquals(source, bytes)
    }

    /** Only the object generation the information dictionary names is emptied; a reused number keeps its value. */
    @Test
    fun aReusedObjectNumberKeepsItsOtherGeneration() {
        val bytes =
            pdf(
                "1 0 obj << /Type /Catalog >> endobj",
                "3 0 obj << /Author 4 1 R >> endobj",
                "4 0 obj (Visible caption) endobj",
                "4 1 obj (Alice Example) endobj",
                trailer = "<< /Root 1 0 R /Info 3 0 R >>",
            )

        assertTrue(PdfMetadataScrubber.scrub(bytes) is PdfMetadataResult.Scrubbed)
        assertFalse(bytes.text().contains("Alice Example"))
        assertTrue(bytes.text().contains("Visible caption"))
    }

    /** A `trailer` keyword inside a comment is not read as the document's trailer. */
    @Test
    fun aCommentedTrailerIsIgnored() {
        val bytes =
            pdf(
                "% trailer << /Encrypt 9 0 R >>",
                "1 0 obj << /Type /Catalog >> endobj",
                "3 0 obj << /Author (Alice Example) >> endobj",
                trailer = "<< /Root 1 0 R /Info 3 0 R >>",
            )

        assertTrue(PdfMetadataScrubber.scrub(bytes) is PdfMetadataResult.Scrubbed)
        assertFalse(bytes.text().contains("Alice Example"))
    }

    @Test
    fun commentsInsideObjectHeaderDoNotHideInformation() {
        val bytes =
            pdf(
                "1 0 obj << /Type /Catalog >> endobj",
                "3 % comment before generation\n0 % comment before keyword\nobj << /Author (Alice Example) >> endobj",
                trailer = "<< /Root 1 0 R /Info 3 0 R >>",
            )

        assertTrue(PdfMetadataScrubber.scrub(bytes) is PdfMetadataResult.Scrubbed)
        assertFalse(bytes.text().contains("Alice Example"))
    }

    @Test
    fun referenceTextInsideAParsedStringDoesNotClaimTheReferencedValue() {
        val bytes =
            pdf(
                "1 0 obj << /Type /Catalog >> endobj",
                "3 0 obj << /Author 5 0 R >> endobj",
                "4 0 obj (The literal words 5 0 R stay here.) endobj",
                "5 0 obj (Alice Example) endobj",
                trailer = "<< /Root 1 0 R /Info 3 0 R >>",
            )

        assertTrue(PdfMetadataScrubber.scrub(bytes) is PdfMetadataResult.Scrubbed)
        assertTrue(bytes.text().contains("literal words 5 0 R"))
        assertFalse(bytes.text().contains("Alice Example"))
    }

    @Test
    fun applicationPrivateMetadataRequiresAnExplicitOriginalChoice() {
        val bytes =
            pdf(
                "1 0 obj << /Type /Catalog /PieceInfo << /Editor << /Private (Private value) >> >> >> endobj",
                trailer = "<< /Root 1 0 R >>",
            )
        val original = bytes.copyOf()

        assertEquals(PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported), PdfMetadataScrubber.scrub(bytes))
        assertArrayEquals(original, bytes)
    }

    @Test
    fun archivalRevisionSurvivesButAccessibilityClaimLeavesWithItsTitle() {
        val source =
            """
            <rdf:Description>
                <pdfaid:part>4</pdfaid:part>
                <pdfaid:rev>2020</pdfaid:rev>
                <pdfuaid:part>2</pdfuaid:part>
                <dc:title>Original title</dc:title>
            </rdf:Description>
            """.trimIndent()
        val replacement = PdfXmpPacket.replacing(source.latin1()).head.text()

        assertTrue(replacement.contains("""pdfaid:part="4""""))
        assertTrue(replacement.contains("""pdfaid:rev="2020""""))
        assertFalse(replacement.contains("pdfaid:conformance"))
        assertFalse(replacement.contains("pdfuaid"))
        assertFalse(replacement.contains("Original title"))
    }

    /** Information dictionaries nobody points to any more, and pdfTeX's copies of included files' ones, are emptied. */
    @Test
    fun leftoverInformationCopiesAreEmptied() {
        val bytes =
            pdf(
                "1 0 obj << /Type /Catalog >> endobj",
                "3 0 obj << /Producer (Clean) >> endobj",
                "6 0 obj << /Author (Old Author) /CreationDate (D:20200101) >> endobj",
                "7 0 obj << /Type /XObject /Subtype /Form /PTEX.FileName (/home/alice/figure.pdf) " +
                    "/PTEX.InfoDict 8 0 R /Length 0 >> stream\n\nendstream endobj",
                "8 0 obj << /Author (Figure Author) >> endobj",
                trailer = "<< /Root 1 0 R /Info 3 0 R >>",
            )

        assertTrue(PdfMetadataScrubber.scrub(bytes) is PdfMetadataResult.Scrubbed)
        listOf("Old Author", "/home/alice", "Figure Author").forEach { value ->
            assertFalse(value, bytes.text().contains(value))
        }
        assertEquals(PdfMetadataResult.Clean, PdfMetadataScrubber.inspect(bytes))
    }

    /** A JPEG segment placed after the image data still loses its camera data. */
    @Test
    fun aPhotoSegmentAfterTheScanIsZeroed() {
        val exif = "Exif\u0000\u0000GPS 52.5N".toByteArray(Charsets.ISO_8859_1)
        val jpeg =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
                segment(0xDA, byteArrayOf(1, 1, 0, 0, 0x3F, 0)) +
                byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0x34) +
                segment(0xE1, exif) +
                byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val head =
            "1 0 obj << /Type /Catalog >> endobj\n" +
                "5 0 obj << /Type /XObject /Subtype /Image /Filter /DCTDecode /Length ${jpeg.size} >> stream\n"
        val bytes =
            "%PDF-1.7\n$head".latin1() + jpeg + "\nendstream endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n".latin1()

        assertTrue(PdfMetadataScrubber.scrub(bytes) is PdfMetadataResult.Scrubbed)
        assertFalse(bytes.text().contains("GPS 52.5N"))
        assertTrue("the image data stays", bytes.text().contains("\u0012ÿ\u00004"))
    }

    @Test
    fun appendedJpegLosesItsMetadataAndOpaqueTailRequiresConfirmation() {
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val image = soi + segment(0xE1, "Exif Sample".latin1()) + eoi

        fun document(jpeg: ByteArray): ByteArray =
            "%PDF-1.7\n5 0 obj << /Type /XObject /Subtype /Image /Filter /DCTDecode /Length ${jpeg.size} >> stream\n"
                .latin1() + jpeg + "\nendstream endobj\ntrailer << >>\n%%EOF\n".latin1()

        val both = document(image + image)
        assertTrue(PdfMetadataScrubber.scrub(both) is PdfMetadataResult.Scrubbed)
        assertFalse(both.text().contains("Exif Sample"))

        val opaque = document(image + "opaque data".latin1())
        val original = opaque.copyOf()
        assertEquals(PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported), PdfMetadataScrubber.scrub(opaque))
        assertArrayEquals(original, opaque)
    }

    /** Thousands of streams without an end finish quickly instead of searching the rest of the file each time. */
    @Test(timeout = 10_000)
    fun hostileStructureFinishesWithinItsBudget() {
        val body = "1 0 obj << /Length 999999999 >> stream\n".repeat(50_000)
        val bytes = pdf(body, trailer = "<< /Info 2 0 R >>")

        val result = PdfMetadataScrubber.scrub(bytes)

        assertTrue("$result", result is PdfMetadataResult.Refused)
    }

    /** A stream whose end had to be guessed, and whose declared length disagrees, may hide syntax: refused. */
    @Test
    fun aStreamEndThatCannotBeConfirmedIsRefused() {
        val payload = "endstream endobj\ntrailer << /Info 3 0 R >>\n"
        val source =
            pdf(
                "1 0 obj << /Type /Catalog >> endobj",
                "3 0 obj << /Title (Visible title) >> endobj",
                "5 0 obj << /Length 6 0 R >> stream\n${payload}more data\nendstream endobj",
                "6 0 obj ${payload.length + "more data".length} endobj",
                trailer = "<< /Root 1 0 R >>",
            )
        val bytes = source.copyOf()

        assertEquals(PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported), PdfMetadataScrubber.scrub(bytes))
        assertArrayEquals(source, bytes)
    }

    /** An indirect length that confirms the guessed end lets the plan go ahead. */
    @Test
    fun aConfirmedIndirectLengthIsTrusted() {
        val bytes =
            pdf(
                "1 0 obj << /Type /Catalog >> endobj",
                "3 0 obj << /Author (Alice Example) >> endobj",
                "5 0 obj << /Length 6 0 R >> stream\nBT ET\nendstream endobj",
                "6 0 obj 5 endobj",
                trailer = "<< /Root 1 0 R /Info 3 0 R >>",
            )

        assertTrue(PdfMetadataScrubber.scrub(bytes) is PdfMetadataResult.Scrubbed)
        assertFalse(bytes.text().contains("Alice Example"))
    }

    /** A length stored in two revisions is ambiguous, so a guessed stream end is not trusted. */
    @Test
    fun aLengthStoredTwiceConfirmsNothing() {
        val source =
            pdf(
                "1 0 obj << /Type /Catalog >> endobj",
                "3 0 obj << /Author (Alice Example) >> endobj",
                "5 0 obj << /Length 6 0 R >> stream\nBT ET\nendstream endobj",
                "6 0 obj 5 endobj",
                "6 0 obj 9 endobj",
                trailer = "<< /Root 1 0 R /Info 3 0 R >>",
            )

        assertEquals(PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported), PdfMetadataScrubber.inspect(source))
    }

    /** pdfTeX's file name, stored as a string a form field also uses, is left alone by refusing. */
    @Test
    fun anIndirectFileNameSharedWithAFieldIsRefused() {
        val source =
            pdf(
                "1 0 obj << /Type /Catalog >> endobj",
                "4 0 obj << /FT /Tx /V 9 0 R >> endobj",
                "7 0 obj << /Type /XObject /Subtype /Form /PTEX.FileName 9 0 R /Length 0 >> stream\n\nendstream endobj",
                "9 0 obj (figure.pdf) endobj",
                trailer = "<< /Root 1 0 R >>",
            )

        assertEquals(PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported), PdfMetadataScrubber.inspect(source))
    }

    /** pdfTeX's file name stored on its own is emptied. */
    @Test
    fun anIndirectFileNameOnItsOwnIsEmptied() {
        val bytes =
            pdf(
                "1 0 obj << /Type /Catalog >> endobj",
                "7 0 obj << /Type /XObject /Subtype /Form /PTEX.FileName 9 0 R /Length 0 >> stream\n\nendstream endobj",
                "9 0 obj (/home/alice/figure.pdf) endobj",
                trailer = "<< /Root 1 0 R >>",
            )

        assertTrue(PdfMetadataScrubber.scrub(bytes) is PdfMetadataResult.Scrubbed)
        assertFalse(bytes.text().contains("alice"))
    }

    /** A signature key spelled with name escapes still marks the PDF signed. */
    @Test
    fun anEscapedSignatureKeyIsFound() {
        val source =
            pdf(
                "1 0 obj << /Type /Catalog >> endobj",
                "3 0 obj << /Author (Alice Example) >> endobj",
                "4 0 obj << /Type /Sig /#42yte#52ange [0 1 2 3] >> endobj",
                trailer = "<< /Root 1 0 R /Info 3 0 R >>",
            )

        assertEquals(PdfMetadataResult.Refused(PdfMetadataRefusal.Signed), PdfMetadataScrubber.inspect(source))
    }

    /** A reference written with a `+` sign still counts as a use of the shared string. */
    @Test
    fun aSignedReferenceStillSharesTheString() {
        val source =
            pdf(
                "1 0 obj << /Type /Catalog >> endobj",
                "3 0 obj << /Author 5 0 R >> endobj",
                "4 0 obj << /FT /Tx /V +5 0 R >> endobj",
                "5 0 obj (Alice Example) endobj",
                trailer = "<< /Root 1 0 R /Info 3 0 R >>",
            )

        assertEquals(PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported), PdfMetadataScrubber.inspect(source))
    }

    /** An object number the named document information shares with a form field is ambiguous: refused. */
    @Test
    fun aNamedObjectReusedForAFormFieldIsRefused() {
        val source =
            pdf(
                "1 0 obj << /Type /Catalog >> endobj",
                "3 0 obj << /Author (Alice Example) >> endobj",
                "3 0 obj << /FT /Tx /T (name) /V (Typed value) >> endobj",
                trailer = "<< /Root 1 0 R /Info 3 0 R >>",
            )

        assertEquals(PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported), PdfMetadataScrubber.inspect(source))
    }

    /** An earlier information dictionary whose author is indirect loses that string too. */
    @Test
    fun aLeftoverDictionaryLosesItsIndirectStrings() {
        val bytes =
            pdf(
                "1 0 obj << /Type /Catalog >> endobj",
                "3 0 obj << /Producer (Clean) >> endobj",
                "6 0 obj << /Author 7 0 R /ModDate (D:20200101) >> endobj",
                "7 0 obj (Old Author) endobj",
                trailer = "<< /Root 1 0 R /Info 3 0 R >>",
            )

        assertTrue(PdfMetadataScrubber.scrub(bytes) is PdfMetadataResult.Scrubbed)
        assertFalse(bytes.text().contains("Old Author"))
        assertEquals(PdfMetadataResult.Clean, PdfMetadataScrubber.inspect(bytes))
    }

    /** An XMP packet printed inside page content could be visible text: refused instead of rewritten. */
    @Test
    fun aPacketInsidePageContentIsRefused() {
        val content =
            "BT (<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>" +
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"></x:xmpmeta><?xpacket end=\"w\"?>) Tj ET"
        val source =
            pdf(
                "1 0 obj << /Type /Catalog >> endobj",
                "5 0 obj << /Length ${content.length} >> stream\n$content\nendstream endobj",
                trailer = "<< /Root 1 0 R >>",
            )

        assertEquals(PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported), PdfMetadataScrubber.inspect(source))
    }

    /** A photo that ends before its EOI is refused rather than trusted. */
    @Test
    fun aPhotoWithoutItsEndIsRefused() {
        val jpeg =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
                segment(0xE1, "Exif\u0000\u0000GPS".toByteArray(Charsets.ISO_8859_1)) +
                segment(0xDA, byteArrayOf(1, 1, 0, 0, 0x3F, 0)) +
                byteArrayOf(0x12, 0x34)
        val head = "5 0 obj << /Type /XObject /Subtype /Image /Filter /DCTDecode /Length ${jpeg.size} >> stream\n"
        val source = "%PDF-1.7\n$head".latin1() + jpeg + "\nendstream endobj\ntrailer << >>\n%%EOF\n".latin1()

        assertEquals(PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported), PdfMetadataScrubber.inspect(source))
    }

    private fun pdf(
        vararg objects: String,
        trailer: String,
    ): ByteArray = ("%PDF-1.7\n" + objects.joinToString("\n") + "\ntrailer $trailer\n%%EOF\n").latin1()

    private fun segment(
        marker: Int,
        payload: ByteArray,
    ): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun String.latin1() = toByteArray(Charsets.ISO_8859_1)

    private fun ByteArray.text() = String(this, Charsets.ISO_8859_1)
}

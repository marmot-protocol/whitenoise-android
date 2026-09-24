package dev.ipf.whitenoise.android.ui.conversation.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class DocumentAttachmentPolicyTest {
    @Test
    fun concreteTypesSurviveWithoutAnExtensionAllowlist() {
        val types =
            listOf(
                "text/plain",
                "application/pdf",
                "application/zip",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "audio/ogg",
                "video/mp4",
                "application/octet-stream",
            )
        types.forEach { type -> assertEquals(type, normalizeDocumentMime(type)) }
        assertEquals("application/pdf", normalizeDocumentMime(" Application/PDF; charset=binary "))
    }

    @Test
    fun absentWildcardAndMalformedTypesUseOpaqueBinaryMetadata() {
        listOf(null, "", "*/*", "image/", "application/pdf/extra", "text/plain\napplication/x-msdownload")
            .forEach { type -> assertEquals("application/octet-stream", normalizeDocumentMime(type)) }
    }

    @Test
    fun safeNameIsIndependentOfMimeAndNeverInfersExecutableType() {
        assertEquals("invoice.exe", safeDocumentDisplayName("../invoice.exe"))
        assertEquals("file", safeDocumentDisplayName("../.."))
        assertEquals("file", safeDocumentDisplayName(null))
        assertEquals("report.pdf", safeDocumentDisplayName("C:\\tmp\\report.pdf"))
        assertEquals("application/octet-stream", normalizeDocumentMime(null))
    }

    @Test
    fun boundedReadPreservesAnyNonemptyFileBytes() {
        val bytes = byteArrayOf(0, 1, -1, 0, 42)
        val result = readBoundedDocument(bytes.size) { ByteArrayInputStream(bytes) }
        assertArrayEquals(bytes, (result as BoundedDocumentRead.Success).bytes)
    }

    @Test
    fun boundedReadDistinguishesEmptyUnreadableAndOversized() {
        assertEquals(BoundedDocumentRead.Empty, readBoundedDocument(8) { ByteArrayInputStream(byteArrayOf()) })
        assertEquals(BoundedDocumentRead.Unreadable, readBoundedDocument(8) { null })
        assertEquals(
            BoundedDocumentRead.Unreadable,
            readBoundedDocument(8) { throw IOException("provider failed") },
        )
        assertEquals(BoundedDocumentRead.TooLarge, readBoundedDocument(8) { ByteArrayInputStream(ByteArray(9)) })
    }
}

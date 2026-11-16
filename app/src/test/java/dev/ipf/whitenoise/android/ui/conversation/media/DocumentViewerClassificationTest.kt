package dev.ipf.whitenoise.android.ui.conversation.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentViewerClassificationTest {
    @Test fun explicitTypesPrecedeExtensions() {
        assertEquals(DocumentAttachmentKind.Pdf, documentAttachmentKind("Application/PDF; x=y", "report.html"))
        assertEquals(DocumentAttachmentKind.Html, documentAttachmentKind("application/xhtml+xml", "report.pdf"))
    }

    @Test fun extensionsOverrideGenericAndMisleadingFamilies() {
        for (mime in listOf("", "application/octet-stream", "image/jpeg", "audio/mp3", "video/mp4")) {
            assertEquals(DocumentAttachmentKind.Pdf, documentAttachmentKind(mime, "Report.PDF"))
            for (extension in listOf(
                "htm",
                "html",
                "xhtml",
            )) {
                assertEquals(
                    DocumentAttachmentKind.Html,
                    documentAttachmentKind(
                        mime,
                        "report.$extension",
                    ),
                )
            }
        }
    }

    @Test fun excludedFormatsAndPackagesRetainTheirOwners() {
        for (ext in listOf(
            "apk",
            "svg",
            "xml",
            "js",
            "mhtml",
            "shtml",
            "xht",
        )) {
            assertNull(
                documentAttachmentKind(
                    "application/pdf",
                    "file.$ext",
                ),
            )
        }
        for (mime in listOf(
            "image/svg+xml",
            "application/xml",
            "application/rss+xml",
            "application/vnd.android.package-archive",
            "message/rfc822",
        )) {
            assertNull(documentAttachmentKind(mime, "file.html"))
        }
        assertNull(documentAttachmentKind("text/plain", "report.txt"))
    }
}

package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.state.AttachmentTransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPresentationTest {
    @Test
    fun commonMimeTypesUseHumanFriendlyLabelsAndSemanticCategories() {
        val cases =
            listOf(
                Triple(
                    "application/vnd.android.package-archive",
                    "release.bin",
                    "APK" to AttachmentIconCategory.AndroidPackage,
                ),
                Triple("application/pdf", "paper.bin", "PDF" to AttachmentIconCategory.Pdf),
                Triple("application/zip", "bundle.bin", "ZIP" to AttachmentIconCategory.Archive),
                Triple(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "notes.bin",
                    "DOCX" to AttachmentIconCategory.Document,
                ),
                Triple(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "budget.bin",
                    "XLSX" to AttachmentIconCategory.Spreadsheet,
                ),
                Triple(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "deck.bin",
                    "PPTX" to AttachmentIconCategory.Presentation,
                ),
                Triple("text/markdown", "readme.bin", "Markdown" to AttachmentIconCategory.Text),
                Triple("application/json", "payload.bin", "JSON" to AttachmentIconCategory.Code),
            )

        cases.forEach { (mime, name, expected) ->
            val actual = resolveAttachmentPresentation(mime, name)
            assertEquals(expected.first, actual.formatLabel)
            assertEquals(expected.second, actual.iconCategory)
        }
    }

    @Test
    fun mimeNormalizationHandlesCaseWhitespaceAndParameters() {
        val presentation = resolveAttachmentPresentation("  Application/PDF ; charset=binary ", "file.dat")

        assertEquals("PDF", presentation.formatLabel)
        assertEquals(AttachmentIconCategory.Pdf, presentation.iconCategory)
    }

    @Test
    fun genericMimeUsesRecognizedSafeExtensionIncludingCompoundArchives() {
        assertEquals(
            AttachmentPresentation("APK", AttachmentIconCategory.AndroidPackage),
            resolveAttachmentPresentation("application/octet-stream", "release.APK"),
        )
        assertEquals(
            AttachmentPresentation("TAR.GZ", AttachmentIconCategory.Archive),
            resolveAttachmentPresentation("", "backup.tar.gz"),
        )
    }

    @Test
    fun knownMimeWinsAConflictingFilenameExtension() {
        val presentation = resolveAttachmentPresentation("application/pdf", "definitely-not-an-app.apk")

        assertEquals("PDF", presentation.formatLabel)
        assertEquals(AttachmentIconCategory.Pdf, presentation.iconCategory)
    }

    @Test
    fun familyMimeOnlyUsesAnExtensionFromTheSameFamily() {
        val presentation = resolveAttachmentPresentation("text/plain", "misleading.apk")

        assertNull(presentation.formatLabel)
        assertEquals(AttachmentIconCategory.Text, presentation.iconCategory)
    }

    @Test
    fun unknownVendorMimeNeverLeaksRawMimeJargon() {
        val withExtension = resolveAttachmentPresentation("application/vnd.acme.machine-part", "board.pcb")
        val withoutExtension = resolveAttachmentPresentation("application/vnd.acme.machine-part", "README")

        assertEquals("PCB", withExtension.formatLabel)
        assertEquals(AttachmentIconCategory.Generic, withExtension.iconCategory)
        assertNull(withoutExtension.formatLabel)
        assertFalse(withExtension.formatLabel!!.contains("VND", ignoreCase = true))
    }

    @Test
    fun unsafeOrUnboundedExtensionsFallBackToGenericFile() {
        val bidi = resolveAttachmentPresentation("application/octet-stream", "report.\u202Eapk")
        val tooLong = resolveAttachmentPresentation("", "report.thisextensionistoolong")

        assertNull(bidi.formatLabel)
        assertNull(tooLong.formatLabel)
        assertTrue(bidi.iconCategory == AttachmentIconCategory.Generic)
        assertTrue(tooLong.iconCategory == AttachmentIconCategory.Generic)
    }

    @Test
    fun codeAndMediaExtensionsRetainRecognizableShortFormats() {
        assertEquals(
            AttachmentPresentation("KT", AttachmentIconCategory.Code),
            resolveAttachmentPresentation("application/octet-stream", "Main.kt"),
        )
        assertEquals(
            AttachmentPresentation("JPG", AttachmentIconCategory.Image),
            resolveAttachmentPresentation("image/jpeg", "photo.jpeg"),
        )
    }

    @Test
    fun presentationNeverChangesTheMimeUsedForExternalOpen() {
        val originalMime = "application/vnd.android.package-archive"

        assertEquals("APK", resolveAttachmentPresentation(originalMime, "release.apk").formatLabel)
        assertEquals(originalMime, attachmentOpenMime(originalMime))
        assertEquals("application/octet-stream", attachmentOpenMime(""))
    }

    @Test
    fun unresolvedCacheStateNeverStartsAnAutomaticDownload() {
        assertFalse(shouldStartAttachmentDownload(AttachmentTransferState.Resolving, true, 12uL, mine = false))
        assertFalse(shouldStartAttachmentDownload(AttachmentTransferState.Available, true, 12uL, mine = false))
        assertTrue(shouldStartAttachmentDownload(AttachmentTransferState.Remote, true, 12uL, mine = false))
        assertFalse(shouldStartAttachmentDownload(AttachmentTransferState.Remote, true, 0uL, mine = false))
        assertFalse(shouldStartAttachmentDownload(AttachmentTransferState.Failed, true, 12uL, mine = false))
        assertFalse(shouldStartAttachmentDownload(AttachmentTransferState.NotRetained, true, 12uL, mine = false))
    }
}

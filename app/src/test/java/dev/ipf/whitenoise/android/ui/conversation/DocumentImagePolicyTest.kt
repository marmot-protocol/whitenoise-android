package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentImagePolicyTest {
    @Test
    fun reportedImageMimeUsesMetadataSafeImagePath() {
        assertTrue(isImageDocumentPick("image/jpeg", null))
    }

    @Test
    fun sniffedImageUsesMetadataSafeImagePathWhenProviderReportsGenericMime() {
        assertTrue(isImageDocumentPick("application/octet-stream", "image/png"))
    }

    @Test
    fun nonImageDocumentKeepsRawFilePath() {
        assertFalse(isImageDocumentPick("application/pdf", null))
    }
}

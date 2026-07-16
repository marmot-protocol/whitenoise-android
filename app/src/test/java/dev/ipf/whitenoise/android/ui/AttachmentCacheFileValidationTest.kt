package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.media.validatedAttachmentCacheFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class AttachmentCacheFileValidationTest {
    @Test
    fun retainedFileIsRejectedAfterEvictionOrTruncation() {
        val file = Files.createTempFile("retained-attachment", ".media").toFile()
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(file, validatedAttachmentCacheFile(file))

            file.writeBytes(byteArrayOf())
            assertNull(validatedAttachmentCacheFile(file))

            file.delete()
            assertNull(validatedAttachmentCacheFile(file))
        } finally {
            file.delete()
        }
    }
}

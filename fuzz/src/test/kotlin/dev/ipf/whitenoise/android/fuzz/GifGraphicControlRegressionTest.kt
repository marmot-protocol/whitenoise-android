package dev.ipf.whitenoise.android.fuzz

import dev.ipf.whitenoise.android.media.AnimationSourceAdmission
import dev.ipf.whitenoise.android.media.admitAnimationSource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** JVM-only regression for the fixed-size GIF graphic-control extension. */
@Tag("jazzer")
class GifGraphicControlRegressionTest {
    @Test
    fun rejectsAdditionalGraphicControlDataSubBlock() {
        // A valid fixed-size GCE is still admitted.
        assertTrue(admitAnimationSource(gifWithExtension("21f9040000000000")) is AnimationSourceAdmission.Admitted)
        // Four GCE payload bytes followed by an illegal extra one-byte data block.
        val extension = "21f90400000000010100"
        val bytes = gifWithExtension(extension)
        assertTrue(admitAnimationSource(bytes) is AnimationSourceAdmission.Refused)
        // A missing terminator cannot borrow the first byte of the image descriptor.
        assertTrue(admitAnimationSource(gifWithExtension("21f90400000000")) is AnimationSourceAdmission.Refused)
        // Comment extensions legitimately permit multiple data sub-blocks.
        assertTrue(admitAnimationSource(gifWithExtension("21fe0161016200")) is AnimationSourceAdmission.Admitted)
    }

    /** Hand-built container only; the LZW payload is not a native decoder fixture. */
    private fun gifWithExtension(extension: String): ByteArray {
        val screen = "47494638396104000400800000000000ffffff"
        val frameAndTrailer = "2c00000000010001000002024c01003b"
        return (screen + extension + frameAndTrailer).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

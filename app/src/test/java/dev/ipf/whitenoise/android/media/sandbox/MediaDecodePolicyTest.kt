package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDecodePolicyTest {
    @Test
    fun pixelCapacityRejectsOverflowAndWorkerChosenDimensions() {
        assertEquals(1048576, MediaDecodePolicy.outputCapacity(512))
        assertNull(MediaDecodePolicy.outputCapacity(Int.MAX_VALUE))
        assertNull(MediaDecodePolicy.outputCapacity(0))
        assertNull(MediaDecodePolicy.pixelBytes(Int.MAX_VALUE, 1, 512, Int.MAX_VALUE))
        assertNull(MediaDecodePolicy.pixelBytes(513, 512, 512, 1048576))
        assertNull(MediaDecodePolicy.pixelBytes(512, 512, 512, 1048575))
        assertEquals(1048576, MediaDecodePolicy.pixelBytes(512, 512, 512, 1048576))
    }

    @Test
    fun sourceCeilingsDoNotDependOnEncodedFileLength() {
        assertTrue(MediaDecodePolicy.sourceAllowed(4096, 4096))
        assertTrue(MediaDecodePolicy.sourceAllowed(12000, 9000))
        assertTrue(MediaDecodePolicy.sourceAllowed(16320, 12240))
        assertTrue(MediaDecodePolicy.sourceAllowed(20000, 10000))
        assertFalse(MediaDecodePolicy.sourceAllowed(20001, 10000))
        assertNull(MediaDecodePolicy.outputCapacity(4097))
        assertFalse(MediaDecodePolicy.sourceAllowed(32769, 1))
        assertFalse(MediaDecodePolicy.sourceAllowed(32768, 32768))
        assertFalse(MediaDecodePolicy.sourceAllowed(-1, 1))
    }

    @Test
    fun workerNamesStayValidAcrossGenerationWrapAndDistinctLanes() {
        val names =
            listOf(
                workerInstanceName("images", 1),
                workerInstanceName("media", 1),
                workerInstanceName("images", Int.MIN_VALUE),
            )
        assertEquals(3, names.toSet().size)
        names.forEach { assertTrue(it.matches(Regex("[A-Za-z0-9_.]+"))) }
    }

    @Test
    fun descriptorRangesRejectOverflowAndEscapeFromTheSelectedFile() {
        assertTrue(validMediaRange(100, 10, 90))
        assertFalse(validMediaRange(100, 10, 91))
        assertFalse(validMediaRange(100, -1, 1))
        assertFalse(validMediaRange(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(validMediaRange(-1, 0, 1))
    }
}

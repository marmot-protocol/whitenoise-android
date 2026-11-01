package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerImageInspectionTest {
    @Test
    fun compromisedWorkerDimensionsMimeAndOrientationFailClosed() {
        assertNull(validatedImageInspection(Int.MAX_VALUE, 1, "image/png", 1))
        assertNull(validatedImageInspection(32768, 32768, "image/png", 1))
        assertNull(validatedImageInspection(1, 1, "image/svg+xml", 1))
        assertNull(validatedImageInspection(1, 1, " Image/SVG+xml ; charset=UTF-8", 1))
        assertNull(validatedImageInspection(1, 1, "image/png", 0))
        assertNull(validatedImageInspection(1, 1, "image/png", 9))
        assertNull(validatedImageInspection(1, 1, "x".repeat(65), 1))
        assertNull(validatedImageInspection(8193, 1, "image/svg+xml", 1, svg = true))
        assertNull(validatedImageInspection(1, 1, "image/png", 1, svg = true))
        assertNull(validatedImageInspection(1, 1, "image/svg+xml", 2, svg = true))
        assertEquals(
            ImageInspection(8192, 8192, "image/svg+xml", 1),
            validatedImageInspection(8192, 8192, "image/svg+xml", 1, svg = true),
        )
    }
}

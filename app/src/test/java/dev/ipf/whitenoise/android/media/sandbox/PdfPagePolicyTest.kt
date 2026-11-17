package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfPagePolicyTest {
    @Test fun invalidAndOverflowingDimensionsFailClosed() {
        for (value in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE, 32769.0)) {
            assertNull(PdfPagePolicy.size(value, 100.0, 2048))
            assertNull(PdfPagePolicy.size(100.0, value, 2048))
        }
        assertNull(PdfPagePolicy.size(Double.MIN_VALUE, Double.MIN_VALUE, 2048))
        assertNull(PdfPagePolicy.bytes(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(PdfPagePolicy.size(100.0, 100.0, Int.MAX_VALUE))
        assertNull(PdfPagePolicy.size(100.0, 100.0, 0))
    }

    @Test fun thinPagesStayPositiveAndWithinPixelBudget() {
        assertEquals(1 to 2048, PdfPagePolicy.size(1.0, 32768.0, 2048))
        assertEquals(2048 to 1, PdfPagePolicy.size(32768.0, 1.0, 2048))
        assertEquals(PdfPagePolicy.MAX_BYTES, PdfPagePolicy.bytes(2048, 2048))
        assertEquals(2048 to 2048, PdfPagePolicy.size(100.0, 100.0, 2048))
    }
}

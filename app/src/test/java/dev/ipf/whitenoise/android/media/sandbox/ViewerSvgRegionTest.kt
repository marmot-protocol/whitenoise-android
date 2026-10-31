package dev.ipf.whitenoise.android.media.sandbox

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ViewerSvgRegionTest {
    @Test
    fun fractionalBoundsStayFinitePositiveAndInsideSource() {
        assertTrue(ViewerSvgRegion(12.0, 12.0, 12.023, 12.023).validFor(24, 24))
        listOf(
            ViewerSvgRegion(Double.NaN, 0.0, 1.0, 1.0),
            ViewerSvgRegion(0.0, 0.0, Double.POSITIVE_INFINITY, 1.0),
            ViewerSvgRegion(-0.01, 0.0, 1.0, 1.0),
            ViewerSvgRegion(0.0, 0.0, 24.001, 1.0),
            ViewerSvgRegion(1.0, 0.0, 1.0, 1.0),
            ViewerSvgRegion(0.0, 0.0, Double.MIN_VALUE, 1.0),
        ).forEach { assertFalse(it.validFor(24, 24)) }
    }

    @Test
    fun requestRequiresEveryExactDoublePrimitive() {
        val request =
            Bundle().apply {
                putDouble("svgLeft", 12.0)
                putDouble("svgTop", 12.0)
                putDouble("svgRight", 12.023)
                putDouble("svgBottom", 12.023)
            }
        assertEquals(ViewerSvgRegion(12.0, 12.0, 12.023, 12.023), readViewerSvgRegion(request))
        for (name in listOf("svgLeft", "svgTop", "svgRight", "svgBottom")) {
            assertThrows(IllegalArgumentException::class.java) {
                readViewerSvgRegion(Bundle(request).apply { remove(name) })
            }
            assertThrows(IllegalArgumentException::class.java) {
                readViewerSvgRegion(Bundle(request).apply { putInt(name, 12) })
            }
            assertThrows(IllegalArgumentException::class.java) {
                readViewerSvgRegion(Bundle(request).apply { putDouble(name, Double.NaN) })
            }
        }
    }
}

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
    /** Checks finite positive SVG crop coordinates against original source bounds without integer expansion. */
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

    /** Omits or changes fractional request primitives to ensure the receiver accepts only the exact vector shape. */
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

    /** Rectangular outputs use at most 16 MiB even when their admitted long edge exceeds 2048 pixels. */
    @Test
    fun rectangularTargetCoversPhonePixelsAndCapsEveryAspectByArea() {
        val phone = requireNotNull(ViewerSvgRegion(0.0, 0.0, 1439.25, 2802.75).targetSize(2803))
        assertTrue(phone.width >= 1439.25 && phone.height >= 2802.75)
        assertTrue(phone.capacity <= 16 * 1024 * 1024)
        val square = requireNotNull(ViewerSvgRegion(0.0, 0.0, 24.0, 24.0).targetSize(4096))
        assertEquals(ViewerSvgTarget(2048, 2048), square)
        for ((width, height) in listOf(24.0 to 24.0, 24.0 to 1.0, 1.0 to 24.0, 0.000001 to 24.0)) {
            for (edge in listOf(1, 256, 2048, 2803, 4096)) {
                val target = requireNotNull(ViewerSvgRegion(0.0, 0.0, width, height).targetSize(edge))
                assertTrue(target.width in 1..edge && target.height in 1..edge)
                assertTrue(target.width.toLong() * target.height <= 4_194_304)
                assertTrue(target.capacity in 4..16 * 1024 * 1024)
            }
        }
        assertEquals(null, ViewerSvgRegion(0.0, 0.0, 1.0, 1.0).targetSize(0))
        assertEquals(null, ViewerSvgRegion(0.0, 0.0, 1.0, 1.0).targetSize(4097))
        assertEquals(null, ViewerSvgRegion(Double.NaN, 0.0, 1.0, 1.0).targetSize(256))
    }
}

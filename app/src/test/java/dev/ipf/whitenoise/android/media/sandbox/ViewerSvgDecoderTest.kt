package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Color
import dev.ipf.whitenoise.android.media.GroupSvgPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerSvgDecoderTest {
    private fun svg(content: String): ByteArray =
        (
            """<svg xmlns="http://www.w3.org/2000/svg" width="4096" height="4096""" +
                """">$content</svg>"""
        ).toByteArray()

    @Test
    fun originalVectorRegionRerendersSubpixelLineAtViewportResolution() {
        val bytes = svg("""<rect x="100" y="100" width="0.1" height="1" fill="red"/>""")
        val bitmap = requireNotNull(ViewerSvgDecoder.region(bytes, ViewerSvgRegion(100.0, 100.0, 101.0, 101.0), 512))
        try {
            assertEquals(512, bitmap.width)
            assertEquals(512, bitmap.height)
            assertTrue(Color.red(bitmap.getPixel(10, 200)) > 240)
            assertEquals(0, Color.alpha(bitmap.getPixel(200, 200)))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun tinyDocumentFractionalViewportDoesNotRoundOutToOneVectorUnit() {
        val bytes =
            (
                """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><r""" +
                    """ect x="12" y="12" width="0.001" height="0.02" fill="red"/></svg>"""
            ).toByteArray()
        val bitmap = requireNotNull(ViewerSvgDecoder.region(bytes, ViewerSvgRegion(12.0, 12.0, 12.023, 12.023), 512))
        try {
            assertEquals(512, bitmap.width)
            assertTrue(Color.red(bitmap.getPixel(5, 100)) > 240)
            assertEquals(0, Color.alpha(bitmap.getPixel(100, 100)))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun boundedTextIsViewerOnlyAndDocumentDimensionsComeFromOriginal() {
        val bytes = svg("""<text x="100" y="100" font-size="12">Fine detail</text>""")
        assertEquals(ImageInspection(4096, 4096, "image/svg+xml", 1), ViewerSvgDecoder.inspect(bytes))
        assertTrue(runCatching { GroupSvgPolicy.validate(bytes) }.isFailure)
        val bitmap = requireNotNull(ViewerSvgDecoder.region(bytes, ViewerSvgRegion(90.0, 80.0, 128.0, 110.0), 512))
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.any { Color.alpha(it) > 0 })
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun unsafeTextMetricsAndInheritedCssFailBeforeRenderer() {
        listOf(
            """<text x="0" y="0" textLength="1e100">a</text>""",
            """<text x="0" y="0" letter-spacing="Infinity">a</text>""",
            """<g word-spacing="NaN"><text x="0" y="0">a</text></g>""",
            """<g style="letter-spacing:100000000px"><text x="0" y="0">a</text></g>""",
            """<text x="0" y="0" font-size="1e100">a</text>""",
            """<text x="NaN" y="0">a</text>""",
            """<text x="0" y="0" dx="Infinity">a</text>""",
            """<text x="0" y="0" font-size="12em">a</text>""",
            """<text x="0" y="0" font-family="url(https://example.invalid/font)">a</text>""",
        ).forEach { assertNull(ViewerSvgDecoder.inspect(svg(it))) }
    }

    @Test
    fun externalContentEntitiesScriptsAndDocumentBombsFailClosed() {
        listOf(
            svg("""<image href="https://example.invalid/picture"/>"""),
            svg("""<script>alert(1)</script>"""),
            svg("""<use href="#x"/>"""),
            (
                "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///private'>]>" +
                    svg("&x;").toString(Charsets.UTF_8)
            ).toByteArray(),
            svg("<g>".repeat(20) + "</g>".repeat(20)),
            svg("<rect/>".repeat(257)),
            svg("<text>" + "a".repeat(8193) + "</text>"),
            svg("<text x=" + 34.toChar() + "0 ".repeat(8193) + 34.toChar() + ">a</text>"),
        ).forEach { assertNull(ViewerSvgDecoder.inspect(it)) }
    }

    @Test
    fun invalidRegionsAndOutputBudgetsDoNotAllocatePixels() {
        val bytes = svg("""<rect width="10" height="10"/>""")
        assertNull(ViewerSvgDecoder.region(bytes, ViewerSvgRegion(-1.0, 0.0, 100.0, 100.0), 256))
        assertNull(ViewerSvgDecoder.region(bytes, ViewerSvgRegion(0.0, 0.0, 4097.0, 100.0), 256))
        assertNull(ViewerSvgDecoder.region(bytes, ViewerSvgRegion(0.0, 0.0, 100.0, 100.0), 2049))
        assertNull(ViewerSvgDecoder.region(bytes, ViewerSvgRegion(0.0, 0.0, 100.0, 100.0), 0))
    }
}

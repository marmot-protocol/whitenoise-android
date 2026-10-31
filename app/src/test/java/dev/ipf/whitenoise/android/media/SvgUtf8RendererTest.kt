package dev.ipf.whitenoise.android.media

import android.graphics.BitmapFactory
import android.graphics.Color
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.ViewerSvgDecoder
import dev.ipf.whitenoise.android.media.sandbox.ViewerSvgRegion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Both entry points consume canonical header-free UTF-8; identity output retains its established 1024px contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SvgUtf8RendererTest {
    @Test
    fun utf8NonAsciiTitleAndStandardHeaderPreserveIdentityAndViewerPixels() =
        runBlocking {
            IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
            val source =
                (
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                        """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24">""" +
                        """<title>Åä é 水</title><rect width="24" height="24" fill="red"/></svg>"""
                ).toByteArray(Charsets.UTF_8)
            val png = GroupSvgRasterizer.rasterize(source)
            val identity = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
            val viewer = requireNotNull(ViewerSvgDecoder.region(source, ViewerSvgRegion(0.0, 0.0, 24.0, 24.0), 256))
            try {
                assertEquals(1024, identity.width)
                assertEquals(1024, identity.height)
                assertEquals(Color.RED, identity.getPixel(512, 512))
                assertEquals(Color.RED, viewer.getPixel(128, 128))
                assertTrue(GroupSvgPolicy.validate(source).contains("Åä é 水"))
                assertTrue(!GroupSvgPolicy.validate(source).startsWith("<?xml"))
            } finally {
                identity.recycle()
                viewer.recycle()
            }
        }
}

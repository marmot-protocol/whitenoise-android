package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Color
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Positive SVG pixels through normal production installation, not the optional lifetime-qualification lane. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
@MediaContainmentAcceptance
class DefaultViewerSvgDeviceTest {
    @Test
    fun normalReusableInstallerRendersFractionalSvgAndRecoversFromRefusedXml() {
        IsolatedMediaDecoder.install(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val field = IsolatedMediaDecoder::class.java.getDeclaredField("transport").apply { isAccessible = true }
            assertTrue(field.get(IsolatedMediaDecoder) is ReusableMediaTransport)
            val source =
                """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><rect x="12" y="12" width="0.001" height="0.02" fill="red"/></svg>"""
                    .toByteArray()
            assertEquals(ImageInspection(24, 24, "image/svg+xml", 1), IsolatedMediaDecoder.svgInspect(source))
            val pixels =
                requireNotNull(
                    IsolatedMediaDecoder.svgRegion(source, ViewerSvgRegion(12.0, 12.0, 12.023, 12.023), 512),
                )
            val worker = requireNotNull(IsolatedMediaDecoder.lastIsolation)
            try {
                assertEquals(512, pixels.width)
                assertTrue(Color.red(pixels.getPixel(5, 100)) > 240)
                assertEquals(0, Color.alpha(pixels.getPixel(100, 100)))
                assertTrue(worker.uid != Process.myUid() && worker.isolated)
                assertFalse(worker.internetGranted)
                assertFalse(worker.runtimeInitialized)
            } finally {
                pixels.recycle()
            }
            val hostile = """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><g font="1e30px serif"><text>a</text></g></svg>""".toByteArray()
            assertNull(IsolatedMediaDecoder.svgInspect(hostile))
            assertEquals(ImageInspection(24, 24, "image/svg+xml", 1), IsolatedMediaDecoder.svgInspect(source))
            for (declaration in listOf(
                """<?xml version="1.0?" encoding="UTF-16LE"?>""",
                """<?xml version="encoding'UTF-16LE'"?>""",
                """<?xml version="1.0" encoding="UTF-8" encoding="UTF-16"?>""",
            )) {
                val invalid = (declaration + source.toString(Charsets.UTF_8)).toByteArray()
                assertNull(IsolatedMediaDecoder.svgInspect(invalid))
                assertNull(IsolatedMediaDecoder.svg(invalid))
            }
            val standard = ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + source.toString(Charsets.UTF_8)).toByteArray()
            assertEquals(ImageInspection(24, 24, "image/svg+xml", 1), IsolatedMediaDecoder.svgInspect(standard))
            // Normal healthy successes retain the existing reusable worker; this is not per-input death qualification.
            assertEquals(worker.uid, requireNotNull(IsolatedMediaDecoder.lastIsolation).uid)
        } finally {
            IsolatedMediaDecoder.clear()
        }
    }
}

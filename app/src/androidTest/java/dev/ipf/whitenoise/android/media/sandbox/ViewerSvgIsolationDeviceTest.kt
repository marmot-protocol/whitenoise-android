package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
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
import java.io.ByteArrayOutputStream
import java.io.File

/** Real vector IPC and exact worker-death witnesses; no network, account or user-media fixture. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
@MediaContainmentAcceptance
class ViewerSvgIsolationDeviceTest {
    @Test
    fun fractionalVectorDetailAndHostileXmlStayInsideTheirPermissionlessWorkers() {
        val context = MediaWorkerBaselineBinding(InstrumentationRegistry.getInstrumentation().targetContext)
        val canary = File(context.filesDir, "media-containment-canary")
        check(!canary.exists())
        canary.writeText("synthetic-private-canary")
        val transport = IsolatedMediaTransport(context)
        try {
            val source =
                (
                    """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><r""" +
                        """ect x="12" y="12" width="0.001" height="0.02" fill="red"/></svg>"""
                ).toByteArray()
            assertEquals(ImageInspection(24, 24, "image/svg+xml", 1), transport.svgInspect(source))
            assertFalse(context.isEndpointAlive(1))
            val pixels = requireNotNull(transport.svgRegion(source, ViewerSvgRegion(12.0, 12.0, 12.023, 12.023), 512))
            try {
                assertEquals(512, pixels.width)
                assertEquals(512, pixels.height)
                assertTrue(Color.red(pixels.getPixel(5, 100)) > 240)
                assertEquals(0, Color.alpha(pixels.getPixel(100, 100)))
                assertTrue(pixels.allocationByteCount <= 512 * 512 * 4)
                val worker = requireNotNull(IsolatedMediaDecoder.lastIsolation)
                assertTrue(worker.uid != Process.myUid() && worker.isolated)
                assertFalse(worker.internetGranted)
                assertFalse(worker.runtimeInitialized)
                assertTrue(worker.privateCanaryDenied)
                assertEquals(android.system.OsConstants.EBADF, worker.inputWriteErrno)
                assertFalse(context.isEndpointAlive(2))
            } finally {
                pixels.recycle()
            }
            val hostile =
                (
                    """<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///private'>]><svg xmlns="""" +
                        """http://www.w3.org/2000/svg">&x;</svg>"""
                ).toByteArray()
            assertNull(transport.svgInspect(hostile))
            assertFalse(context.isEndpointAlive(3))
            requireNotNull(transport.decode(healthyPng(), 2, false, MediaDecodePolicy.DECODE)).recycle()
            assertFalse(context.isEndpointAlive(4))
            assertTrue(canary.isFile)
        } finally {
            transport.close()
            context.closeObservers()
            check(canary.delete())
        }
    }

    private fun healthyPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.BLUE)
            ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}

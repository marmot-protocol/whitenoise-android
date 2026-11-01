package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The actual production request serializer carries exact doubles, not rounded legacy region integers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReusableSvgPayloadTest {
    @Test
    fun defaultTransportSerializesOnlyTheDedicatedFractionalShape() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transport = ReusableMediaTransport(context)
        val file = File.createTempFile("svg-request-", ".xml", context.cacheDir).apply { writeText("<svg/>") }
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                SharedMemory.create("svg-payload-test", 4).use { output ->
                    val method =
                        ReusableMediaTransport::class.java
                            .getDeclaredMethod(
                                "requestPayload",
                                ParcelFileDescriptor::class.java,
                                SharedMemory::class.java,
                                MediaDecodeRequest::class.java,
                            ).apply {
                                isAccessible =
                                    true
                            }
                    val region = ViewerSvgRegion(12.0, 12.0, 12.023, 12.023)
                    val payload =
                        method.invoke(
                            transport,
                            input,
                            output,
                            MediaDecodeRequest(1, false, MediaDecodePolicy.VIEWER_SVG_REGION, svgRegion = region),
                        ) as Bundle
                    assertEquals(MediaDecodePolicy.VIEWER_SVG_REGION, payload.getInt("operation"))
                    assertEquals(region, readViewerSvgRegion(payload))
                    assertFalse(payload.containsKey("left"))
                    val raster =
                        method.invoke(
                            transport,
                            input,
                            output,
                            MediaDecodeRequest(
                                1,
                                false,
                                MediaDecodePolicy.REGION,
                                region = MediaImageRegion(0, 0, 1, 1),
                            ),
                        ) as Bundle
                    assertTrue(raster.containsKey("left"))
                    assertFalse(raster.containsKey("svgLeft"))
                }
            }
        } finally {
            transport.close()
            check(file.delete())
        }
    }
}

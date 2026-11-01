package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.ui.conversation.media.DecodedAttachmentPresentation
import dev.ipf.whitenoise.android.ui.conversation.media.decodeMessageAttachmentImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Measures the actual isolated decoder output on Android; no account, relay or installed-data mutation is needed. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
class MediaThumbnailDecodeDeviceTest {
    /** Compares the same source at a measured tile bound, the former ceiling and the independent viewer ceiling. */
    @Test
    fun renderedTileUsesFewerDecodedBytesWhileTheViewerRetainsOriginalDetail() {
        IsolatedMediaDecoder.install(InstrumentationRegistry.getInstrumentation().targetContext)
        val bytes = landscapePng()
        val tileEdge = thumbnailDecodeMaxEdge(160, 120, 4f / 3f)
        val tile = decode(bytes, tileEdge)
        val previous = decode(bytes, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
        val viewer = decode(bytes, MediaPipeline.VIEWER_MAX_EDGE_PX)
        try {
            assertEquals(256, tileEdge)
            assertTrue(tile.width >= 160 && tile.height >= 120)
            assertTrue(maxOf(tile.width, tile.height) <= tileEdge)
            assertTrue(tile.allocationByteCount < previous.allocationByteCount)
            assertEquals(1600, viewer.width)
            assertEquals(1200, viewer.height)
            assertTrue(viewer.allocationByteCount > tile.allocationByteCount)
            report("tile", tileEdge, tile)
            report("previous", MediaPipeline.THUMBNAIL_MAX_EDGE_PX, previous)
            report("viewer", MediaPipeline.VIEWER_MAX_EDGE_PX, viewer)
        } finally {
            tile.recycle()
            previous.recycle()
            viewer.recycle()
        }
    }

    /** Uses the same admitted, isolated decode path as the production image bubble and viewer. */
    private fun decode(
        bytes: ByteArray,
        edge: Int,
    ): Bitmap =
        runBlocking {
            val decoded = decodeMessageAttachmentImage(bytes, "image/png", edge)
            check(decoded is DecodedAttachmentPresentation.Static)
            decoded.bitmap
        }

    /** Prints only synthetic dimensions and measured allocation, retained in the hosted instrumentation report. */
    private fun report(
        role: String,
        edge: Int,
        bitmap: Bitmap,
    ) {
        println(
            "THUMBNAIL_DECODE role=$role requested=$edge width=${bitmap.width} height=${bitmap.height} " +
                "allocationBytes=${bitmap.allocationByteCount}",
        )
    }

    /** Produces a controlled source larger than chat tiles and smaller than the viewer's independent limit. */
    private fun landscapePng(): ByteArray {
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(android.graphics.Color.BLUE)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}

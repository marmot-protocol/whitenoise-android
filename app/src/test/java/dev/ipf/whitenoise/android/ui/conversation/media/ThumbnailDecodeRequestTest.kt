package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** A mounted image keeps a bounded high-water mark across parent and window layout changes. */
class ThumbnailDecodeRequestTest {
    /** Repeated small/large layouts cannot alternate decoder targets or create new cache variants. */
    @Test
    fun shrinkingAndReturningDoNotRestartAtSmallerBuckets() {
        val request = ThumbnailDecodeRequest(1f)
        assertEquals(0, request.maxEdgePx)
        request.onSizeChanged(IntSize(96, 96))
        assertEquals(128, request.maxEdgePx)
        request.onSizeChanged(IntSize(300, 300))
        assertEquals(384, request.maxEdgePx)
        repeat(10) {
            request.onSizeChanged(IntSize(80, 80))
            request.onSizeChanged(IntSize(300, 300))
        }
        assertEquals(384, request.maxEdgePx)
    }

    /** Actual decoded aspect corrects misleading metadata so a heavily cropped tile does not remain undersampled. */
    @Test
    fun decodedGeometryCanRaiseButNeverLowerTheBound() {
        val request = ThumbnailDecodeRequest(1f)
        request.onSizeChanged(IntSize(200, 200))
        assertEquals(256, request.maxEdgePx)
        request.onDecodedSize(256, 64)
        assertEquals(1024, request.maxEdgePx)
        request.onDecodedSize(100, 100)
        request.onSizeChanged(IntSize(0, 0))
        assertEquals(1024, request.maxEdgePx)
    }
}

package dev.ipf.whitenoise.android.ui.conversation.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaBubbleAspectRatioTest {
    @Test
    fun metadataDimensionsOwnTheInitialGeometry() {
        assertEquals(
            1.5f,
            initialMediaBubbleAspectRatio(
                dim = "1200x800",
            ) ?: error("expected metadata ratio"),
            0f,
        )
    }

    @Test
    fun missingGeometryUsesTheSameFixedFallbackRegardlessOfPixelCacheState() {
        assertNull(initialMediaBubbleAspectRatio(dim = null))
        assertNull(initialMediaBubbleAspectRatio(dim = "invalid"))
    }
}

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.graphics.RectangleShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test
    fun captionedMediaDefersItsVisibleCornersToTheSharedFrame() {
        assertSame(RectangleShape, visualMediaBubbleShape(attachedToCaption = true))
        assertNotSame(RectangleShape, visualMediaBubbleShape(attachedToCaption = false))
    }
}

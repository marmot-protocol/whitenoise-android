package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationRichContentShape
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

    /** Media inside a bubble keeps the prototype's own 10 dp corners whether or not a caption follows. */
    @Test
    fun mediaInsideABubbleKeepsItsOwnRoundedCorners() {
        assertEquals(RoundedCornerShape(10.dp), ConversationRichContentShape)
    }

    /** A landscape photo fills the 256 dp frame width and derives its height; a portrait keeps the frame height. */
    @Test
    fun singleMediaFrameFollowsThePrototypeExtents() {
        assertEquals(256f to 256f, singleMediaSizeDp(ratio = 1.5f))
        assertEquals(192f to 256f, singleMediaSizeDp(ratio = 0.75f))
        assertEquals(256f to 256f, singleMediaSizeDp(ratio = null))
    }

    /** Sources smaller than the frame render at 192 dp instead of being blown up to it. */
    @Test
    fun smallSourcesDisplayAtTheReducedExtent() {
        assertEquals(192f to 192f, singleMediaSizeDp(ratio = 1f, sourceShortSidePx = 120))
        assertEquals(256f to 256f, singleMediaSizeDp(ratio = 1f, sourceShortSidePx = 1200))
        assertEquals(minOf(320, 180), sourceShortSideFromDim("320x180"))
        assertNull(sourceShortSideFromDim("0x180"))
    }
}

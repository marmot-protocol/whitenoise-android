package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import dev.ipf.whitenoise.android.media.thumbnailDecodeMaxEdge

/**
 * Transient geometry for one mounted attachment. The largest observed bucket wins until that attachment is replaced,
 * so resize oscillations cannot repeatedly cancel and restart decoding or accumulate cached variants.
 */
internal class ThumbnailDecodeRequest(
    private var sourceAspectRatio: Float?,
) {
    private var size = IntSize.Zero

    var maxEdgePx by mutableIntStateOf(0)
        private set

    /** Receives physical pixels after layout, including density, parent constraints and window resizing. */
    fun onSizeChanged(measuredSize: IntSize) {
        size = measuredSize
        updateBound()
    }

    /** Verified geometry corrects inaccurate attachment metadata without lowering an admitted bound. */
    fun onDecodedSize(
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        sourceAspectRatio = width.toFloat() / height
        updateBound()
    }

    /** Raises the request through a fixed number of buckets; shrinking can reuse the already adequate pixels. */
    private fun updateBound() {
        maxEdgePx = maxOf(maxEdgePx, thumbnailDecodeMaxEdge(size.width, size.height, sourceAspectRatio))
    }
}

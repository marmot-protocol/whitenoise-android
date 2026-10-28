package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

internal fun mediaViewerMotionBounds(
    source: Rect,
    viewport: Size,
    progress: Float,
    aspectRatio: Float = source.width / source.height,
    targetViewport: Rect = Rect(Offset.Zero, viewport),
): Rect {
    val width = minOf(targetViewport.width, targetViewport.height * aspectRatio)
    val height = width / aspectRatio
    val destination =
        Rect(
            targetViewport.center.x - width / 2f,
            targetViewport.center.y - height / 2f,
            targetViewport.center.x + width / 2f,
            targetViewport.center.y + height / 2f,
        )
    return androidx.compose.ui.geometry
        .lerp(source, destination, progress.coerceIn(0f, 1f))
}

/** Cover the morphing rounded clip with the already-recorded thumbnail, centered by its draw owner. */
internal fun mediaViewerThumbnailScale(
    thumbnail: Size,
    bounds: Rect,
): Float = maxOf(bounds.width / thumbnail.width, bounds.height / thumbnail.height)

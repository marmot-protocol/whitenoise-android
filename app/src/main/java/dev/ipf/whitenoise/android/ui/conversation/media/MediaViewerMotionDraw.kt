package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.toSize

private const val MEDIA_CONTENT_BLEND_START = 0.85f
private const val MEDIA_CONTENT_BLEND_INTERVAL = 0.15f

/** Keeps the still-image handoff within the same parent draw pass as the pager. */
internal class MediaViewerMotionDraw(
    private val hasVideoSurface: Boolean,
) {
    private val outline = Path()
    private val blendPaint = if (hasVideoSurface) Paint() else null
    private var root: LayoutCoordinates? = null
    private var body: LayoutCoordinates? = null
    private var traveling = false

    fun body(
        modifier: Modifier,
        videoAlpha: () -> Float,
    ): Modifier {
        val measured = modifier.onGloballyPositioned { body = it }
        return if (hasVideoSurface) {
            // The platform video surface needs a graphics-layer alpha.
            measured.graphicsLayer { alpha = videoAlpha() }
        } else {
            measured.drawWithContent { if (!traveling) drawContent() }
        }
    }

    fun travel(
        modifier: Modifier,
        knownAspect: Float?,
        thumbnail: () -> MediaViewerThumbnailRegistry.ResolvedThumbnail?,
        progress: () -> Float,
    ): Modifier =
        modifier
            .onGloballyPositioned { root = it }
            .drawWithContent {
                val frame = geometry(progress(), knownAspect, thumbnail)
                // Independently fading the still-image crops can show both or neither.
                traveling = !hasVideoSurface && frame != null
                drawContent()
                if (frame != null) drawMediaMotionThumbnail(frame, outline, blendPaint)
            }

    /** Reads actual viewer coordinates only while its traveling copy can still paint. */
    private fun geometry(
        fraction: Float,
        knownAspect: Float?,
        thumbnail: () -> MediaViewerThumbnailRegistry.ResolvedThumbnail?,
    ): MediaMotionDrawGeometry? {
        if (fraction >= 1f || knownAspect == null) return null
        val rootCoordinates = root?.takeIf { it.isAttached }
        val bodyCoordinates = body?.takeIf { it.isAttached }
        return if (rootCoordinates != null && bodyCoordinates != null) {
            thumbnail()?.let { source ->
                val origin = rootCoordinates.positionOnScreen()
                MediaMotionDrawGeometry(
                    origin,
                    Rect(bodyCoordinates.positionOnScreen() - origin, bodyCoordinates.size.toSize()),
                    fraction,
                    knownAspect,
                    source,
                )
            }
        } else {
            null
        }
    }
}

/** Draws the measured crop. Only the video SurfaceView needs a composited opacity handoff. */
private fun DrawScope.drawMediaMotionThumbnail(
    frame: MediaMotionDrawGeometry,
    outline: Path,
    blendPaint: Paint?,
) {
    val current = frame.thumbnail
    val layer = current.thumbnail.layer
    val source = current.bounds.translate(-frame.origin)
    val bounds = mediaViewerMotionBounds(source, size, frame.progress, frame.aspectRatio, frame.viewport)
    outline.reset()
    val corners = current.corners
    val remaining = 1f - frame.progress
    outline.addRoundRect(
        RoundRect(
            bounds,
            CornerRadius(corners.topLeft * remaining),
            CornerRadius(corners.topRight * remaining),
            CornerRadius(corners.bottomRight * remaining),
            CornerRadius(corners.bottomLeft * remaining),
        ),
    )
    clipPath(outline) {
        val alpha = (remaining / MEDIA_CONTENT_BLEND_INTERVAL).coerceIn(0f, 1f)
        val blend = blendPaint?.takeIf { alpha < 1f }
        if (blend != null) drawContext.canvas.saveLayer(bounds, blend.apply { this.alpha = alpha })
        try {
            val factor = mediaViewerThumbnailScale(layer.size.toSize(), bounds)
            translate(
                bounds.center.x - layer.size.width * factor / 2f,
                bounds.center.y - layer.size.height * factor / 2f,
            ) {
                scale(factor, factor, pivot = Offset.Zero) { drawLayer(layer) }
            }
        } finally {
            if (blend != null) drawContext.canvas.restore()
        }
    }
}

private data class MediaMotionDrawGeometry(
    val origin: Offset,
    val viewport: Rect,
    val progress: Float,
    val aspectRatio: Float,
    val thumbnail: MediaViewerThumbnailRegistry.ResolvedThumbnail,
)

/** A settled or disabled canonical viewer never measures its source; active travel still checks live eligibility. */
internal fun mediaViewerContentAlpha(
    enabled: Boolean,
    progress: Float,
    sourceAvailable: () -> Boolean,
): Float =
    if (!enabled || progress >= 1f || !sourceAvailable()) {
        1f
    } else {
        ((progress - MEDIA_CONTENT_BLEND_START) / MEDIA_CONTENT_BLEND_INTERVAL).coerceIn(0f, 1f)
    }

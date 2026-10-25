@file:Suppress("FunctionNaming") // Compose surface functions follow UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationMessageMetrics
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationMotionEnabled

private const val MAX_REGISTERED_MEDIA_THUMBNAILS = 128
private const val MEDIA_OPEN_MILLIS = 220
private const val MEDIA_CLOSE_MILLIS = 180
private const val MEDIA_CONTENT_BLEND_START = 0.85f
private const val MEDIA_CONTENT_BLEND_INTERVAL = 0.15f

/** Visible, already drawn pixels only. No media reads, decodes or saved state. */
internal class MediaViewerThumbnailRegistry {
    internal class Thumbnail(
        val layer: GraphicsLayer,
        val sourceEpoch: ULong,
    ) {
        val token = Any()
        var bounds by mutableStateOf(Rect.Zero)
        var ready by mutableStateOf(false)
        var corners by mutableStateOf(MediaThumbnailCorners())
    }

    private val thumbnails =
        androidx.compose.runtime.mutableStateMapOf<ConversationMediaViewerAttachmentId, List<Thumbnail>>()

    private var overflowed by mutableStateOf(false)

    internal fun register(
        id: ConversationMediaViewerAttachmentId,
        thumbnail: Thumbnail,
    ) {
        if (overflowed) return
        if (thumbnails.values.sumOf { it.size } >= MAX_REGISTERED_MEDIA_THUMBNAILS) {
            // Prefer the direct presentation for this owner over retaining unbounded draw layers or
            // mistaking an untracked duplicate for a unique source. A new owner creates a fresh registry.
            overflowed = true
            thumbnails.clear()
            return
        }
        thumbnails[id] = thumbnails[id].orEmpty() + thumbnail
    }

    internal fun remove(
        id: ConversationMediaViewerAttachmentId,
        thumbnail: Thumbnail,
    ) {
        val remaining = thumbnails[id].orEmpty().filterNot { it === thumbnail }
        if (remaining.isEmpty()) thumbnails.remove(id) else thumbnails[id] = remaining
    }

    internal fun find(id: ConversationMediaViewerAttachmentId): Thumbnail? =
        // A quoted/duplicated visible rendering must never become an arbitrary last-registered origin.
        thumbnails[id]?.filter { it.ready && it.bounds.width > 0f && it.bounds.height > 0f }?.singleOrNull()
}

private val LocalMediaThumbnailCanvas = staticCompositionLocalOf<(() -> LayoutCoordinates?)?> { null }

@Composable
internal fun MediaThumbnailCanvas(content: @Composable () -> Unit) {
    var canvas by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentCanvas = canvas
    CompositionLocalProvider(LocalMediaThumbnailCanvas provides { currentCanvas }) {
        Box(Modifier.onGloballyPositioned { canvas = it }) { content() }
    }
}

private val LocalMediaViewerThumbnails = staticCompositionLocalOf<MediaViewerThumbnailRegistry?> { null }

@Composable
internal fun MediaViewerThumbnailHost(
    registry: MediaViewerThumbnailRegistry,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val visibleRegistry = registry.takeIf { enabled && rememberConversationMotionEnabled() }
    CompositionLocalProvider(LocalMediaViewerThumbnails provides visibleRegistry, content = content)
}

/** Records the image's existing draw commands, not a bitmap or another media pipeline. */
@Composable
internal fun Modifier.mediaViewerThumbnail(
    messageIdHex: String,
    attachmentIndex: Int,
    sourceEpoch: ULong,
    gridTile: Boolean = false,
): Modifier {
    val registry = LocalMediaViewerThumbnails.current ?: return this
    val id =
        remember(messageIdHex, attachmentIndex) {
            ConversationMediaViewerAttachmentId(messageIdHex, attachmentIndex)
        }
    val radius = with(LocalDensity.current) { ConversationMessageMetrics.RichComponentCornerRadius.toPx() }
    val canvas = LocalMediaThumbnailCanvas.current
    val layer = rememberGraphicsLayer()
    val thumbnail =
        remember(registry, id, sourceEpoch, layer) {
            MediaViewerThumbnailRegistry.Thumbnail(layer, sourceEpoch)
        }
    DisposableEffect(registry, id, thumbnail) {
        registry.register(id, thumbnail)
        onDispose { registry.remove(id, thumbnail) }
    }
    return this
        .onGloballyPositioned {
            val full = it.boundsInWindow(clipBounds = false)
            val corners = mediaThumbnailCorners(it, gridTile, radius, canvas?.invoke())
            thumbnail.bounds =
                if (it.boundsInWindow() == full && corners != null) {
                    Rect(it.positionOnScreen(), full.size)
                } else {
                    Rect.Zero
                }
            thumbnail.corners = corners ?: MediaThumbnailCorners()
        }.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            if (!thumbnail.ready) thumbnail.ready = true
            drawLayer(layer)
        }
}

internal fun mediaViewerMotionBounds(
    source: Rect,
    viewport: Size,
    progress: Float,
): Rect {
    val factor = minOf(viewport.width / source.width, viewport.height / source.height)
    val width = source.width * factor
    val height = source.height * factor
    val destination =
        Rect(
            (viewport.width - width) / 2f,
            (viewport.height - height) / 2f,
            (viewport.width + width) / 2f,
            (viewport.height + height) / 2f,
        )
    return androidx.compose.ui.geometry
        .lerp(source, destination, progress.coerceIn(0f, 1f))
}

/** Keeps the real viewer, playback and controls mounted throughout; only a non-interactive visual travels. */
@Composable
@Suppress("LongParameterList", "LongMethod") // Existing viewer/session inputs and one bounded draw-layer transition.
internal fun MediaViewerMotion(
    registry: MediaViewerThumbnailRegistry?,
    opening: ConversationMediaViewerAttachmentId,
    selected: ConversationMediaViewerAttachmentId,
    closing: Boolean,
    allowMotion: Boolean = true,
    onClosed: () -> Unit,
    selectedSourceEpoch: ULong? = null,
    content: @Composable () -> Unit,
) {
    val enabled = rememberConversationMotionEnabled() && allowMotion
    val openingToken = remember(registry, opening) { registry?.find(opening)?.token }
    val initialAvailable = openingToken != null
    val progress = remember { Animatable(if (enabled && initialAvailable) 0f else 1f) }
    var entrySettled by remember { mutableStateOf(!enabled || !initialAvailable) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val thumbnail =
        if (closing) {
            registry?.find(selected)?.takeIf { selectedSourceEpoch == null || it.sourceEpoch == selectedSourceEpoch }
        } else {
            registry?.find(opening)?.takeIf { it.token === openingToken }
        }
    val latestClosed by androidx.compose.runtime.rememberUpdatedState(onClosed)
    LaunchedEffect(closing, enabled, thumbnail) {
        if (!enabled || thumbnail == null) {
            entrySettled = true
            progress.snapTo(1f)
            if (closing) latestClosed()
        } else if (closing) {
            progress.animateTo(0f, tween(MEDIA_CLOSE_MILLIS, easing = FastOutSlowInEasing))
            latestClosed()
        } else if (!entrySettled) {
            progress.animateTo(1f, tween(MEDIA_OPEN_MILLIS, easing = FastOutSlowInEasing))
            entrySettled = true
        }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionOnScreen() }) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha =
                    if (!enabled ||
                        thumbnail == null
                    ) {
                        1f
                    } else {
                        ((progress.value - MEDIA_CONTENT_BLEND_START) / MEDIA_CONTENT_BLEND_INTERVAL).coerceIn(0f, 1f)
                    }
            },
        ) { content() }
        if (thumbnail != null && enabled && progress.value < 1f) {
            Canvas(Modifier.fillMaxSize()) {
                // Resolve the current registration at draw time: disposed/offscreen rows never leave retained pixels.
                val current = registry?.find(if (closing) selected else opening)
                if (current === thumbnail) {
                    val source = thumbnail.bounds.translate(-origin)
                    val bounds = mediaViewerMotionBounds(source, size, progress.value)
                    val outline =
                        Path().apply {
                            val corners = thumbnail.corners
                            val remaining = 1f - progress.value
                            addRoundRect(
                                RoundRect(
                                    bounds,
                                    CornerRadius(corners.topLeft * remaining),
                                    CornerRadius(corners.topRight * remaining),
                                    CornerRadius(corners.bottomRight * remaining),
                                    CornerRadius(corners.bottomLeft * remaining),
                                ),
                            )
                        }
                    clipPath(outline) {
                        drawContext.canvas.saveLayer(
                            bounds,
                            Paint().apply {
                                alpha = ((1f - progress.value) / MEDIA_CONTENT_BLEND_INTERVAL).coerceIn(0f, 1f)
                            },
                        )
                        translate(bounds.left, bounds.top) {
                            scale(
                                bounds.width / thumbnail.layer.size.width,
                                bounds.height / thumbnail.layer.size.height,
                                pivot = Offset.Zero,
                            ) {
                                drawLayer(thumbnail.layer)
                            }
                        }
                        drawContext.canvas.restore()
                    }
                }
            }
        }
    }
}

/** Physical corners of the actual source tile, before it leaves the album's rounded surface. */
internal data class MediaThumbnailCorners(
    val topLeft: Float = 0f,
    val topRight: Float = 0f,
    val bottomRight: Float = 0f,
    val bottomLeft: Float = 0f,
)

private fun mediaThumbnailCorners(
    coordinates: LayoutCoordinates,
    grid: Boolean,
    radius: Float,
    canvas: LayoutCoordinates?,
): MediaThumbnailCorners? {
    if (!grid) return MediaThumbnailCorners(radius, radius, radius, radius)
    // The explicit masonry host owns the outer clip; nested rows/columns are not clip bounds.
    if (canvas?.isAttached != true) return null
    val tile = coordinates.boundsInWindow(clipBounds = false)
    val outer = canvas.boundsInWindow(clipBounds = false)
    val left = tile.left <= outer.left + CORNER_EDGE_TOLERANCE_PX
    val right = tile.right >= outer.right - CORNER_EDGE_TOLERANCE_PX
    val top = tile.top <= outer.top + CORNER_EDGE_TOLERANCE_PX
    val bottom = tile.bottom >= outer.bottom - CORNER_EDGE_TOLERANCE_PX
    return MediaThumbnailCorners(
        if (left && top) radius else 0f,
        if (right && top) radius else 0f,
        if (right && bottom) radius else 0f,
        if (left && bottom) radius else 0f,
    )
}

private const val CORNER_EDGE_TOLERANCE_PX = 0.5f

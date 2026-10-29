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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
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
import androidx.compose.ui.unit.toSize
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationMessageMetrics
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationMotionEnabled
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
        private val geometry: (() -> Pair<Rect, MediaThumbnailCorners>?)? = null,
    ) {
        val token = Any()
        private var measuredBounds = Rect.Zero
        private var measuredCorners = MediaThumbnailCorners()
        var bounds: Rect
            get() = if (geometry == null) measuredBounds else geometry.invoke()?.first ?: Rect.Zero
            set(value) {
                measuredBounds = value
            }
        var ready = false

        /** One fresh measurement shared by eligibility, bounds and corners for this lookup only. */
        fun readGeometry(): Pair<Rect, MediaThumbnailCorners>? {
            if (!ready) return null
            val current = if (geometry == null) measuredBounds to measuredCorners else geometry.invoke()
            return current?.takeIf { (bounds, _) -> bounds.width > 0f && bounds.height > 0f }
        }

        var corners: MediaThumbnailCorners
            get() = if (geometry == null) measuredCorners else geometry.invoke()?.second ?: MediaThumbnailCorners()
            set(value) {
                measuredCorners = value
            }
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

    /** Resolves current visibility once; no geometry is cached across frames or source mutations. */
    internal fun resolve(id: ConversationMediaViewerAttachmentId): ResolvedThumbnail? {
        var found: ResolvedThumbnail? = null
        for (thumbnail in thumbnails[id].orEmpty()) {
            val (bounds, corners) = thumbnail.readGeometry() ?: continue
            // A quoted/duplicated visible rendering must never become an arbitrary origin.
            if (found != null) return null
            found = ResolvedThumbnail(thumbnail, bounds, corners)
        }
        return found
    }

    /** Supplies the current unique registration to callers that do not consume a draw snapshot. */
    internal fun find(id: ConversationMediaViewerAttachmentId): Thumbnail? = resolve(id)?.thumbnail

    /** Ephemeral draw input; retaining this value must never replace a subsequent visibility lookup. */
    internal data class ResolvedThumbnail(
        val thumbnail: Thumbnail,
        val bounds: Rect,
        val corners: MediaThumbnailCorners,
    )
}

private val LocalMediaThumbnailCanvas = staticCompositionLocalOf<(() -> LayoutCoordinates?)?> { null }

@Composable
internal fun MediaThumbnailCanvas(content: @Composable () -> Unit) {
    val canvas = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val readCanvas = remember(canvas) { { canvas[0] } }
    CompositionLocalProvider(LocalMediaThumbnailCanvas provides readCanvas) {
        Box(Modifier.onGloballyPositioned { canvas[0] = it }) { content() }
    }
}

private val LocalMediaReadingViewport = staticCompositionLocalOf<(() -> Rect?)?> { null }

/** The current motion-eligible thumbnail host also scopes its controls' press feedback. */
internal val LocalMediaViewerThumbnails = staticCompositionLocalOf<MediaViewerThumbnailRegistry?> { null }

@Composable
internal fun MediaViewerThumbnailHost(
    registry: MediaViewerThumbnailRegistry,
    enabled: Boolean = true,
    readingViewport: (() -> Rect?)? = null,
    content: @Composable () -> Unit,
) {
    val visibleRegistry = registry.takeIf { enabled && rememberConversationMotionEnabled() }
    CompositionLocalProvider(
        LocalMediaViewerThumbnails provides visibleRegistry,
        LocalMediaReadingViewport provides readingViewport,
        content = content,
    )
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
    val readingViewport = LocalMediaReadingViewport.current
    val currentReadingViewport by androidx.compose.runtime.rememberUpdatedState(readingViewport)
    val coordinates = remember(registry, id, sourceEpoch) { arrayOfNulls<LayoutCoordinates>(1) }
    val layer = rememberGraphicsLayer()
    val thumbnail =
        remember(registry, id, sourceEpoch, layer, gridTile, radius, canvas) {
            MediaViewerThumbnailRegistry.Thumbnail(layer, sourceEpoch) {
                coordinates[0]?.takeIf { it.isAttached }?.let { measured ->
                    val full = measured.boundsInWindow(clipBounds = false)
                    val readViewport = currentReadingViewport
                    val viewport = readViewport?.invoke()
                    val visible =
                        measured.boundsInWindow() == full &&
                            (
                                readViewport == null ||
                                    viewport != null &&
                                    full.left >= viewport.left &&
                                    full.top >= viewport.top &&
                                    full.right <= viewport.right &&
                                    full.bottom <= viewport.bottom
                            )
                    val corners = mediaThumbnailCorners(full, gridTile, radius, canvas?.invoke())
                    if (visible && corners != null) Rect(measured.positionOnScreen(), full.size) to corners else null
                }
            }
        }
    DisposableEffect(registry, id, thumbnail) {
        registry.register(id, thumbnail)
        onDispose { registry.remove(id, thumbnail) }
    }
    return this
        .onGloballyPositioned { coordinates[0] = it }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            if (!thumbnail.ready) thumbnail.ready = true
            drawLayer(layer)
        }
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
    attachmentAspectRatio: Float? = null,
    content: @Composable (Modifier) -> Unit,
) {
    val knownAspect = attachmentAspectRatio?.takeIf { it.isFinite() && it > 0f }
    val enabled = rememberConversationMotionEnabled() && allowMotion && knownAspect != null
    val openingToken =
        remember(registry, opening) {
            // The animation monitor and draw/layer phases observe live geometry. Initial admission
            // must not subscribe the whole viewer composition to the timeline's layout publication.
            Snapshot.withoutReadObservation { registry?.resolve(opening)?.thumbnail?.token }
        }
    val initialAvailable = openingToken != null
    val awaitEntryFrame = rememberMediaViewerEntryFrame(enabled, initialAvailable)
    val progress = remember { Animatable(if (enabled && initialAvailable) 0f else 1f) }
    var entrySettled by remember { mutableStateOf(!enabled || !initialAvailable) }
    val geometry = remember { arrayOfNulls<LayoutCoordinates>(2) }
    val readThumbnail =
        remember(registry, opening, selected, closing, selectedSourceEpoch, openingToken) {
            { registry?.motionThumbnail(opening, selected, closing, selectedSourceEpoch, openingToken) }
        }
    val latestClosed by androidx.compose.runtime.rememberUpdatedState(onClosed)
    LaunchedEffect(closing, enabled, readThumbnail, awaitEntryFrame) {
        if (!enabled || readThumbnail() == null) {
            entrySettled = true
            progress.snapTo(1f)
            if (closing) latestClosed()
        } else if (closing || !entrySettled) {
            animateMediaViewerMotion(
                progress,
                closing,
                sourceAvailable = { readThumbnail() != null },
                awaitEntryFrame = awaitEntryFrame,
                onSettled = { entrySettled = true },
            )
            if (closing) latestClosed()
        }
    }
    val bodyModifier =
        remember(geometry, enabled, readThumbnail) {
            Modifier.onGloballyPositioned { geometry[1] = it }.graphicsLayer {
                alpha = mediaViewerContentAlpha(enabled, progress.value) { readThumbnail() != null }
            }
        }
    Box(Modifier.fillMaxSize().onGloballyPositioned { geometry[0] = it }) {
        CompositionLocalProvider(LocalMediaViewerPressFeedback provides enabled) {
            content(bodyModifier)
        }
        MediaMotionVisual({ if (enabled) readThumbnail() else null }) {
            mediaMotionDrawGeometry(geometry, { progress.value }, knownAspect)
        }
    }
}

/** Reads actual viewer coordinates only while its traveling copy can still paint. */
private fun mediaMotionDrawGeometry(
    geometry: Array<LayoutCoordinates?>,
    progress: () -> Float,
    knownAspect: Float?,
): MediaMotionDrawGeometry? {
    val fraction = progress()
    if (fraction >= 1f) return null
    val root = geometry[0]?.takeIf { it.isAttached }
    val body = geometry[1]?.takeIf { it.isAttached }
    return if (root == null || body == null || knownAspect == null) {
        null
    } else {
        val origin = root.positionOnScreen()
        MediaMotionDrawGeometry(
            origin,
            Rect(body.positionOnScreen() - origin, body.size.toSize()),
            fraction,
            knownAspect,
        )
    }
}

/** Monitors source eligibility during window admission and travel, cancelling either before direct fallback. */
internal suspend fun animateMediaViewerMotion(
    progress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    closing: Boolean,
    sourceAvailable: () -> Boolean,
    awaitEntryFrame: suspend () -> Unit,
    onSettled: () -> Unit,
) {
    var sourceLost = false
    coroutineScope {
        val animation =
            launch {
                if (!closing) awaitEntryFrame()
                if (sourceAvailable()) {
                    progress.animateTo(
                        if (closing) 0f else 1f,
                        tween(if (closing) MEDIA_CLOSE_MILLIS else MEDIA_OPEN_MILLIS, easing = FastOutSlowInEasing),
                    )
                } else {
                    sourceLost = true
                }
            }
        while (animation.isActive) {
            withFrameNanos { }
            if (!sourceAvailable()) {
                sourceLost = true
                animation.cancel()
            }
        }
        animation.join()
    }
    if (sourceLost) progress.snapTo(1f)
    onSettled()
}

/** Draws a fresh eligible source with reusable clipping objects and the existing pixel handoff. */
@Composable
private fun MediaMotionVisual(
    thumbnail: () -> MediaViewerThumbnailRegistry.ResolvedThumbnail?,
    geometry: () -> MediaMotionDrawGeometry?,
) {
    val outline = remember { Path() }
    val blendPaint = remember { Paint() }
    Canvas(Modifier.fillMaxSize()) {
        val frame = geometry() ?: return@Canvas
        val progress = frame.progress
        if (progress >= 1f) return@Canvas
        // Resolve once at draw time: disposed/offscreen rows never leave retained pixels.
        val current = thumbnail() ?: return@Canvas
        val layer = current.thumbnail.layer
        val source = current.bounds.translate(-frame.origin)
        val bounds = mediaViewerMotionBounds(source, size, progress, frame.aspectRatio, frame.viewport)
        outline.reset()
        val corners = current.corners
        val remaining = 1f - progress
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
            val alpha = ((1f - progress) / MEDIA_CONTENT_BLEND_INTERVAL).coerceIn(0f, 1f)
            if (alpha < 1f) {
                drawContext.canvas.saveLayer(bounds, blendPaint.apply { this.alpha = alpha })
            }
            try {
                val factor = mediaViewerThumbnailScale(layer.size.toSize(), bounds)
                translate(
                    bounds.center.x - layer.size.width * factor / 2f,
                    bounds.center.y - layer.size.height * factor / 2f,
                ) {
                    scale(factor, factor, pivot = Offset.Zero) { drawLayer(layer) }
                }
            } finally {
                if (alpha < 1f) drawContext.canvas.restore()
            }
        }
    }
}

private data class MediaMotionDrawGeometry(
    val origin: Offset,
    val viewport: Rect,
    val progress: Float,
    val aspectRatio: Float,
)

/** Fences each fresh lookup by the opening registration or the selected return page's source epoch. */
private fun MediaViewerThumbnailRegistry.motionThumbnail(
    opening: ConversationMediaViewerAttachmentId,
    selected: ConversationMediaViewerAttachmentId,
    closing: Boolean,
    selectedSourceEpoch: ULong?,
    openingToken: Any?,
): MediaViewerThumbnailRegistry.ResolvedThumbnail? =
    if (closing) {
        resolve(selected)?.takeIf { selectedSourceEpoch == null || it.thumbnail.sourceEpoch == selectedSourceEpoch }
    } else {
        resolve(opening)?.takeIf {
            it.thumbnail.token === openingToken &&
                (selectedSourceEpoch == null || it.thumbnail.sourceEpoch == selectedSourceEpoch)
        }
    }

/** SurfaceView fractional opacity is unavailable before Android14; keep that playback presentation direct. */
internal fun mediaViewerPlatformMotionAllowed(
    isVideo: Boolean,
    sdk: Int,
): Boolean = !isVideo || sdk >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE

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

/** Physical corners of the actual source tile, before it leaves the album's rounded surface. */
internal data class MediaThumbnailCorners(
    val topLeft: Float = 0f,
    val topRight: Float = 0f,
    val bottomRight: Float = 0f,
    val bottomLeft: Float = 0f,
)

/** Derives physical outer album corners from the bounds already measured for this source. */
private fun mediaThumbnailCorners(
    tile: Rect,
    grid: Boolean,
    radius: Float,
    canvas: LayoutCoordinates?,
): MediaThumbnailCorners? {
    if (!grid) return MediaThumbnailCorners(radius, radius, radius, radius)
    // The explicit masonry host owns the outer clip; nested rows/columns are not clip bounds.
    return canvas?.takeIf { it.isAttached }?.let { outerCanvas ->
        val outer = outerCanvas.boundsInWindow(clipBounds = false)
        val left = tile.left <= outer.left + CORNER_EDGE_TOLERANCE_PX
        val right = tile.right >= outer.right - CORNER_EDGE_TOLERANCE_PX
        val top = tile.top <= outer.top + CORNER_EDGE_TOLERANCE_PX
        val bottom = tile.bottom >= outer.bottom - CORNER_EDGE_TOLERANCE_PX
        MediaThumbnailCorners(
            if (left && top) radius else 0f,
            if (right && top) radius else 0f,
            if (right && bottom) radius else 0f,
            if (left && bottom) radius else 0f,
        )
    }
}

private const val CORNER_EDGE_TOLERANCE_PX = 0.5f

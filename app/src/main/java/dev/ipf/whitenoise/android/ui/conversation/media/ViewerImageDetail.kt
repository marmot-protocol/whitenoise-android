package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.ipf.whitenoise.android.media.decodeOwnedMedia
import dev.ipf.whitenoise.android.media.sandbox.ImageInspection
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaImageRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

private const val VIEWER_DETAIL_SETTLE_MS = 80L
private const val VIEWER_FALLBACK_BYTES = 16 * 1024 * 1024

/** A transient source owned by the current visible page only; never a protocol or persistent media cache. */
internal data class ViewerOriginalImage(
    val bytes: ByteArray,
    val inspection: ImageInspection,
) {
    val vector: Boolean get() = inspection.mime == "image/svg+xml"
    val width: Int get() = inspection.width
    val height: Int get() = inspection.height
}

internal data class ViewerImageDetail(
    val bitmap: Bitmap,
    val sourceRegion: ViewerDetailRegion,
) {
    constructor(bitmap: Bitmap, region: MediaImageRegion) : this(bitmap, ViewerDetailRegion.Raster(region))
}

// Shared across page transitions: cancellation of one blocking codec must finish before another detail starts.
private val viewerImageWork = Mutex()

/** Acquire before reading original bytes, so queued/cancelled pages cannot each retain another source. */
internal suspend fun <T> withViewerImageWork(operation: suspend () -> T): T = viewerImageWork.withLock { operation() }

internal data class ViewerSourceInspection(
    val image: ImageInspection?,
    val staticSource: Boolean,
)

/** The real source inspection boundary is interruptible, serialized by its page's source-work owner. */
internal suspend fun inspectViewerSource(
    bytes: ByteArray,
    inspect: (ByteArray) -> ImageInspection? = {
        IsolatedMediaDecoder.inspect(it) ?: IsolatedMediaDecoder.svgInspect(it)
    },
): ViewerSourceInspection? =
    decodeOwnedMedia(
        decode = {
            ViewerSourceInspection(
                inspect(bytes),
                dev.ipf.whitenoise.android.media
                    .admitAnimationSource(bytes) is
                    dev.ipf.whitenoise.android.media.AnimationSourceAdmission.NotAnimation,
            )
        },
        discard = {},
    )

/** Exact mapping of the latest decoded source rectangle, not a thumbnail stretched over the whole image. */
@Composable
@OptIn(FlowPreview::class)
// Compose naming; the source lifetime owns the collector, fallback child and pixel disposal together.
@Suppress("FunctionNaming", "LongMethod")
internal fun ViewerImageDetailOverlay(
    source: ViewerOriginalImage?,
    geometry: ViewerViewportGeometry,
    active: Boolean,
    fallback: suspend (ViewerOriginalImage) -> ViewerImageDetail? = ::decodeViewerFallback,
    decode: suspend (ViewerOriginalImage, ViewerDetailRegion) -> ViewerImageDetail? = ::decodeViewerDetail,
) {
    val state = remember(source) { mutableStateOf<ViewerImageDetail?>(null) }
    var detail by state
    var fallbackRetained by remember(source) { mutableStateOf(false) }
    val latestGeometry by rememberUpdatedState(geometry)
    val latestDecode by rememberUpdatedState(decode)
    val latestFallback by rememberUpdatedState(fallback)
    // Covers a result assigned immediately before removal, before its own DisposableEffect can enter composition.
    DisposableEffect(source, state) { onDispose { state.value?.bitmap?.recycle() } }
    DisposableEffect(detail) {
        val owned = detail
        onDispose { owned?.bitmap?.recycle() }
    }
    LaunchedEffect(source, active) {
        if (source == null || !active) {
            detail = null
            fallbackRetained = false
            return@LaunchedEffect
        }
        var regionSupported = true
        val sourceScope = this
        snapshotFlow { latestGeometry.detailRequest(source.vector) }
            .debounce(VIEWER_DETAIL_SETTLE_MS)
            .distinctUntilChanged()
            .collectLatest { region ->
                if (region == null) {
                    // A completed whole-source fallback is still the best source on the next zoom.
                    if (!fallbackRetained) detail = null
                } else if (regionSupported) {
                    // collectLatest cancels and joins obsolete regions; a confirmed refusal belongs to the source.
                    val next = withViewerImageWork { latestDecode(source, region) }
                    try {
                        currentCoroutineContext().ensureActive()
                        if (next != null) {
                            detail = next
                        } else {
                            regionSupported = false
                            // This one operation belongs to the outer source/lifecycle scope. Panning or fitting
                            // cannot cancel it and spend another fallback attempt on the next viewport.
                            sourceScope.launch {
                                val produced = withViewerImageWork { latestFallback(source) }
                                try {
                                    currentCoroutineContext().ensureActive()
                                    if (produced != null) {
                                        fallbackRetained = true
                                        detail = produced
                                    }
                                } catch (cancelled: CancellationException) {
                                    produced?.bitmap?.recycle()
                                    throw cancelled
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        next?.bitmap?.recycle()
                        throw cancelled
                    }
                }
            }
    }

    detail?.takeIf { active && source != null && geometry.scale > 1f }?.let { current ->
        Canvas(Modifier.fillMaxSize().testTag("conversation.media.viewer.detail")) {
            val destination = geometry.sourceRectOnScreen(current.sourceRegion.rectangle)
            if (destination.isEmpty) return@Canvas
            drawImage(
                image = current.bitmap.asImageBitmap(),
                dstOffset = IntOffset(destination.left.roundToInt(), destination.top.roundToInt()),
                dstSize =
                    IntSize(
                        destination.width.roundToInt().coerceAtLeast(1),
                        destination.height.roundToInt().coerceAtLeast(1),
                    ),
            )
        }
    }
}

/** Only static admitted raster sources reach region decoding; animation admission is resolved by the page. */
internal suspend fun decodeViewerDetail(
    source: ViewerOriginalImage,
    region: ViewerDetailRegion,
): ViewerImageDetail? =
    decodeOwnedMedia(
        decode = {
            val decoded =
                when (region) {
                    is ViewerDetailRegion.Vector ->
                        IsolatedMediaDecoder.svgRegion(
                            source.bytes,
                            region.region,
                            VIEWER_DETAIL_EDGE,
                        )
                    is ViewerDetailRegion.Raster ->
                        IsolatedMediaDecoder.region(
                            source.bytes,
                            region.region,
                            VIEWER_DETAIL_EDGE,
                        )
                }
            decoded?.let { ViewerImageDetail(it, region) }
        },
        discard = { it.bitmap.recycle() },
    )

/** A failed region never bypasses animation admission: only the page's static source can reach this path. */
private suspend fun decodeViewerFallback(source: ViewerOriginalImage): ViewerImageDetail? {
    val edge = if (source.vector) null else viewerFallbackEdge(source.width, source.height)
    if (edge == null) return null
    return decodeOwnedMedia(
        decode = {
            IsolatedMediaDecoder.decode(source.bytes, edge)?.let { bitmap ->
                if (bitmap.allocationByteCount > VIEWER_FALLBACK_BYTES) {
                    bitmap.recycle()
                    null
                } else {
                    ViewerImageDetail(bitmap, MediaImageRegion(0, 0, source.width, source.height))
                }
            }
        },
        discard = { it.bitmap.recycle() },
    )
}

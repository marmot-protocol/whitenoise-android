package dev.ipf.whitenoise.android.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

/**
 * Geometry only: no text, bitmap, input, focus or route state enters a handoff. Live registrations
 * admit each endpoint. The source is captured before the same composition replaces its child.
 */
internal class MeasuredControlHandoff(
    initialDestination: Any?,
    val morphSurface: Boolean = false,
    // Composer insets can move during entry; other controls retain the existing settle-on-movement policy.
    private val followMovingTarget: Boolean = false,
) {
    private val live = mutableStateMapOf<Any, Pair<Any, Rect>>()
    private var previous = initialDestination
    private var start: Rect? by mutableStateOf(null)
    private var end: Rect? by mutableStateOf(null)
    private val progress = Animatable(1f)
    private val surfaces = mutableStateMapOf<Any, ControlMorphSurface>()
    private var originSurface: ControlMorphSurface? = null
    private var originRadius = 0f
    private var originDecorationAlpha = 0f
    private var boundsStartFraction by mutableStateOf(0f)
    private var request: Any? = null
    private var enabled by mutableStateOf(true)
    private var awaitingFrame by mutableStateOf(false)
    private val fraction: Float get() = if (awaitingFrame) 0f else progress.value

    val destination: Any? get() = previous
    val targetBounds: Rect? get() = previous?.let { live[it]?.second }
    val active: Boolean get() = morphSurface && enabled && start != null
    val renderedSurface: ControlMorphSurface?
        get() = surfaces[previous] ?: originSurface
    val renderedRadius: Float
        get() {
            val from = originRadius
            val to = surfaces[previous]?.radius ?: from
            return from + (to - from) * fraction
        }

    /** Optional bridge artwork follows the same clock and captures its opacity when reversing. */
    val renderedDecorationAlpha: Float
        get() {
            val to = surfaces[previous]?.decorationAlpha ?: originDecorationAlpha
            return originDecorationAlpha + (to - originDecorationAlpha) * fraction
        }

    /** Retains geometry/artwork only, never an editor, draft or recording session. */
    fun reportSurface(
        key: Any,
        surface: ControlMorphSurface,
    ) {
        surfaces[key] = surface
    }

    /** An input gesture immediately exposes the settled, authoritative endpoint. */
    fun finish() {
        request = null
        start = null
        end = null
    }

    /** Called for a committed owner change, before either endpoint can draw its first frame. */
    fun prepare(
        destination: Any?,
        allowed: Boolean,
    ): Any {
        val token = Any()
        val from = captureOrigin()
        val source = previous
        originRadius = if (active) renderedRadius else surfaces[source]?.radius ?: 0f
        originDecorationAlpha =
            if (active) renderedDecorationAlpha else surfaces[source]?.decorationAlpha ?: 0f
        boundsStartFraction = 0f
        originSurface = surfaces[source]
        previous = destination
        enabled = allowed
        request = token
        awaitingFrame = true
        val hasEndpoints = source != null && destination != null
        val canMove = allowed && source != destination && from != null
        if (!hasEndpoints || !canMove) {
            start = null
            end = null
        } else {
            start = from
            end = from
        }
        return token
    }

    /** New placement supplies the destination; a missing endpoint never waits or revives later. */
    suspend fun animatePrepared(token: Any) {
        if (request !== token || start == null) return
        withFrameNanos { }
        val target = targetBounds.takeIf { request === token }
        if (target == null) {
            if (request === token) finish()
            return
        }
        end = target
        progress.snapTo(0f)
        awaitingFrame = false
        progress.animateTo(1f, tween(CONTROL_MORPH_MILLIS))
        if (request === token) finish()
    }

    /** Current draw geometry is the reversal origin, never a fresh copy of the old endpoint. */
    val renderedBounds: Rect?
        get() =
            start?.let { from ->
                (if (morphSurface) targetBounds ?: end else end)?.let { to ->
                    val remaining = 1f - boundsStartFraction
                    val boundsFraction =
                        if (remaining > 0f) ((fraction - boundsStartFraction) / remaining).coerceIn(0f, 1f) else 1f
                    lerp(from, to, if (morphSurface) boundsFraction else progress.value)
                }
            }

    /** Captures only the currently mounted source or an active interruption, never stale history. */
    fun captureOrigin(): Rect? = renderedBounds ?: live[previous]?.second

    /** A subtle outline leaves the authoritative live control readable and immediately usable. */
    val opacity: Float get() = (1f - progress.value) * 0.55f

    /** Reports measured window bounds; zero-sized or clipped endpoints cannot admit a handoff. */
    fun report(
        key: Any,
        token: Any,
        bounds: Rect,
    ) {
        val positiveSize = bounds.width > 0f && bounds.height > 0f
        val finiteOrigin = bounds.left.isFinite() && bounds.top.isFinite()
        if (positiveSize && finiteOrigin) {
            val movingTarget = active && !awaitingFrame && key == previous
            val moved = live[key]?.second?.let { it != bounds } == true
            if (movingTarget && moved) {
                if (followMovingTarget && fraction < 1f) {
                    // Follow composer insets without restarting the clock or jumping to its new placement.
                    start = renderedBounds
                    boundsStartFraction = fraction
                } else {
                    finish()
                }
            }
            live[key] = token to bounds
        } else {
            remove(key, token)
        }
    }

    /** An outgoing instance cannot remove a replacement with the same endpoint key. */
    fun remove(
        key: Any,
        token: Any,
    ) {
        if (live[key]?.first === token) {
            live.remove(key)
            surfaces.remove(key)
            if (active && key == previous) finish()
        }
    }

    /**
     * One committed layout frame supplies the new endpoint. Absent endpoints use direct
     * presentation; late unrelated mounts do not revive an old navigation animation.
     */
    suspend fun moveTo(
        destination: Any?,
        enabled: Boolean,
        origin: Rect?,
    ) {
        val source = previous
        previous = destination
        val hasEndpoints = source != null && destination != null
        if (!enabled || !hasEndpoints || source == destination) {
            start = null
            end = null
            progress.snapTo(1f)
            return
        }
        withFrameNanos { }
        val target = live[destination]?.second
        if (origin == null || target == null) {
            start = null
            end = null
            return
        }
        start = origin
        end = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(CONTROL_HANDOFF_MILLIS))
        start = null
        end = null
    }
}

/** Clears all retained geometry when the caller's account/runtime owner changes. */
@Composable
internal fun rememberMeasuredControlHandoff(
    owner: Any?,
    destination: Any?,
    morphSurface: Boolean = false,
    followMovingTarget: Boolean = false,
): MeasuredControlHandoff =
    remember(owner, morphSurface, followMovingTarget) {
        MeasuredControlHandoff(destination, morphSurface, followMovingTarget)
    }

/** Measures the actual endpoint without changing its size, focus, hit target or semantics. */
@Composable
internal fun Modifier.controlHandoffEndpoint(
    state: MeasuredControlHandoff?,
    key: Any,
): Modifier {
    val token = remember(state, key) { Any() }
    DisposableEffect(state, key, token) { onDispose { state?.remove(key, token) } }
    return onPlaced {
        val raw = Rect(it.positionInWindow(), it.size.toSize())
        val visible = it.boundsInWindow()
        val unclippedStart =
            kotlin.math.abs(raw.left - visible.left) < 0.5f &&
                kotlin.math.abs(raw.top - visible.top) < 0.5f
        val unclippedEnd =
            kotlin.math.abs(raw.right - visible.right) < 0.5f &&
                kotlin.math.abs(raw.bottom - visible.bottom) < 0.5f
        val whole = unclippedStart && unclippedEnd
        state?.report(key, token, if (whole) raw else Rect.Zero)
    }
}

/** Draws only an empty measured outline above live content; it adds no interactive child. */
@Composable
@Suppress("FunctionNaming")
internal fun MeasuredControlHandoffHost(
    state: MeasuredControlHandoff,
    destination: Any?,
    modifier: Modifier = Modifier,
    allowed: Boolean = true,
    content: @Composable () -> Unit,
) {
    val enabled = rememberSystemAnimationsEnabled() && allowed
    val color = MaterialTheme.colorScheme.outline
    var origin by remember(state) { mutableStateOf(Offset.Zero) }
    val transitionOrigin = remember(state, destination, enabled) { state.captureOrigin() }
    val prepared =
        remember(state, destination, enabled) {
            if (state.morphSurface) state.prepare(destination, enabled) else null
        }
    LaunchedEffect(state, destination, enabled) {
        if (prepared != null) state.animatePrepared(prepared) else state.moveTo(destination, enabled, transitionOrigin)
    }
    Box(
        modifier
            .onPlaced { origin = it.boundsInWindow().topLeft }
            .drawWithContent {
                drawContent()
                if (enabled && state.active) {
                    drawMorphBridge(state, origin)
                } else if (enabled && !state.morphSurface) {
                    state.renderedBounds?.let { windowBounds ->
                        val bounds = windowBounds.translate(-origin)
                        drawRoundRect(
                            color = color.copy(alpha = state.opacity),
                            topLeft = bounds.topLeft,
                            size = bounds.size,
                            cornerRadius = CornerRadius(minOf(bounds.height / 2f, 24.dp.toPx())),
                            style = Stroke(1.dp.toPx()),
                        )
                    }
                }
            },
    ) { content() }
}

private const val CONTROL_HANDOFF_MILLIS = 180

/** Stable artwork shared by two container shapes; it contains no content or behavior. */
internal data class ControlMorphSurface(
    val fill: Color,
    val border: Color?,
    val radius: Float,
    val stroke: Float,
    val decorationAlpha: Float = 0f,
)

/** Paints one real endpoint's container; content remains unscaled and keeps its existing input owner. */
@Composable
internal fun Modifier.controlMorphSurface(
    state: MeasuredControlHandoff?,
    key: Any,
    artwork: ControlMorphSurface,
): Modifier {
    val fill = artwork.fill
    var bounds by remember(state, key) { mutableStateOf(Rect.Zero) }
    return controlHandoffEndpoint(state, key)
        .onPlaced {
            bounds = it.boundsInWindow()
            state?.reportSurface(key, artwork)
        }.onPreviewKeyEvent {
            state?.finish()
            false
        }.pointerInput(state, key) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed }) state?.finish()
                }
            }
        }.drawWithContent {
            if (state?.active != true) {
                // Original Material chrome owns every settled pixel, including its inset border.
                drawContent()
                return@drawWithContent
            }
            // Source content is never photographed or carried into another route.
            if (state.destination != key) return@drawWithContent
            val shape = state.renderedBounds?.translate(-bounds.topLeft) ?: Rect(Offset.Zero, size)
            val corner = state.renderedRadius
            val path = Path().apply { addRoundRect(RoundRect(shape, CornerRadius(corner))) }
            clipPath(path) {
                drawRoundRect(fill, shape.topLeft, shape.size, CornerRadius(corner))
                this@drawWithContent.drawContent()
            }
        }
}

private const val CONTROL_MORPH_MILLIS = 200

/** Outgoing route pixels have no live controls or editor semantics. */
internal val LocalControlHandoffEndpointCurrent = staticCompositionLocalOf { true }

/** User input settles decoration without delaying the underlying command. */
internal val LocalFinishControlMorph = staticCompositionLocalOf<() -> Unit> { {} }

/** Paints the container bridge above route pixels; the shell places dictation controls afterward. */
private fun DrawScope.drawMorphBridge(
    state: MeasuredControlHandoff,
    origin: Offset,
) {
    state.renderedBounds?.let { windowBounds ->
        state.renderedSurface?.let { surface ->
            val bounds = windowBounds.translate(-origin)
            // The live destination paints inside its own bounds; only the bridge paints outside it.
            val destinationBounds = state.targetBounds?.translate(-origin)
            if (destinationBounds != null) {
                clipRect(
                    destinationBounds.left,
                    destinationBounds.top,
                    destinationBounds.right,
                    destinationBounds.bottom,
                    clipOp = androidx.compose.ui.graphics.ClipOp.Difference,
                ) {
                    drawRoundRect(surface.fill, bounds.topLeft, bounds.size, CornerRadius(state.renderedRadius))
                }
            } else {
                drawRoundRect(surface.fill, bounds.topLeft, bounds.size, CornerRadius(state.renderedRadius))
            }
            surface.border?.let { border ->
                drawRoundRect(
                    border,
                    bounds.topLeft,
                    bounds.size,
                    CornerRadius(state.renderedRadius),
                    style = Stroke(surface.stroke),
                )
            }
        }
    }
}

/** Pointer, keyboard and accessibility activation all finish the same decorative transition first. */
@Composable
internal fun settleControlMorph(action: () -> Unit): () -> Unit {
    val finish = LocalFinishControlMorph.current
    return {
        finish()
        action()
    }
}

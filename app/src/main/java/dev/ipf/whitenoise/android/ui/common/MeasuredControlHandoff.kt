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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp

/**
 * Geometry only: no text, bitmap, input, focus or route state enters a handoff. Live registrations
 * admit each endpoint. The source is captured before the same composition replaces its child.
 */
internal class MeasuredControlHandoff(initialDestination: Any?) {
    private val live = mutableStateMapOf<Any, Pair<Any, Rect>>()
    private var previous = initialDestination
    private var start: Rect? by mutableStateOf(null)
    private var end: Rect? by mutableStateOf(null)
    private val progress = Animatable(1f)

    /** Current draw geometry is the reversal origin, never a fresh copy of the old endpoint. */
    val renderedBounds: Rect?
        get() = start?.let { from -> end?.let { to -> lerp(from, to, progress.value) } }

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
        if (bounds.width > 0f && bounds.height > 0f) {
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
        if (live[key]?.first === token) live.remove(key)
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
): MeasuredControlHandoff = remember(owner) { MeasuredControlHandoff(destination) }

/** Measures the actual endpoint without changing its size, focus, hit target or semantics. */
@Composable
internal fun Modifier.controlHandoffEndpoint(
    state: MeasuredControlHandoff?,
    key: Any,
): Modifier {
    val token = remember(state, key) { Any() }
    DisposableEffect(state, key, token) { onDispose { state?.remove(key, token) } }
    return onPlaced { state?.report(key, token, it.boundsInWindow()) }
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
    LaunchedEffect(state, destination, enabled) { state.moveTo(destination, enabled, transitionOrigin) }
    Box(
        modifier
            .onPlaced { origin = it.boundsInWindow().topLeft }
            .drawWithContent {
                drawContent()
                if (enabled) {
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

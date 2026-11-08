package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val PEN_FLIGHT_BUBBLE_MILLIS = 300
private const val PEN_FLIGHT_STAGGER_MILLIS = 25
private const val PEN_FLIGHT_BUBBLE_COUNT = 4
private const val PEN_FLIGHT_TOTAL_MILLIS =
    PEN_FLIGHT_BUBBLE_MILLIS + PEN_FLIGHT_STAGGER_MILLIS * (PEN_FLIGHT_BUBBLE_COUNT - 1)

/**
 * Carries the pen's tap into New Message: each quick action bubble leaves from where the pen sat
 * and travels to its own row. One linear clock drives every bubble, so no layout or recomposition
 * happens per frame; only bubble layers move.
 */
internal class NewMessagePenFlight {
    /** The pen's latest placed center in window pixels; read only when it is tapped. */
    var penCenter: Offset? = null

    /** The pen center at the tap that opened New Message, or null when nothing is in flight. */
    var origin by mutableStateOf<Offset?>(null)
        private set

    /** Rests at 0 so the first New Message frame already draws every bubble at the pen. */
    private val clock = Animatable(0f)

    /** Reduced motion never starts a flight. */
    fun launchFromPen(motionAllowed: Boolean) {
        if (motionAllowed) origin = penCenter
    }

    /** Runs the pending flight, if any, to its end. */
    suspend fun fly() {
        if (origin == null) return
        try {
            clock.animateTo(1f, tween(PEN_FLIGHT_TOTAL_MILLIS, easing = LinearEasing))
        } finally {
            origin = null
            // Also after cancellation, so the next tap's first frame draws every bubble at the pen.
            withContext(NonCancellable) { clock.snapTo(0f) }
        }
    }

    /** Eased 0..1 progress of the bubble at [index]; 1 when no flight is running. */
    fun progress(index: Int): Float {
        if (origin == null) return 1f
        val elapsed = clock.value * PEN_FLIGHT_TOTAL_MILLIS - index * PEN_FLIGHT_STAGGER_MILLIS
        return FastOutSlowInEasing.transform((elapsed / PEN_FLIGHT_BUBBLE_MILLIS).coerceIn(0f, 1f))
    }
}

internal val LocalNewMessagePenFlight = staticCompositionLocalOf<NewMessagePenFlight?> { null }

/** Remembers where the pen sits so a tap can start the flight from there. */
@Composable
internal fun Modifier.newMessagePenOrigin(): Modifier {
    val flight = LocalNewMessagePenFlight.current ?: return this
    return onPlaced { flight.penCenter = it.boundsInWindow().center }
}

/** Moves a quick action bubble from the tapped pen to its own place. */
@Composable
internal fun Modifier.penFlightBubble(index: Int): Modifier {
    val flight = LocalNewMessagePenFlight.current ?: return this
    // State, so the first placement re-runs the layer block with a known home.
    val home = remember { mutableStateOf<Offset?>(null) }
    return onPlaced { home.value = it.boundsInWindow().center }
        .graphicsLayer {
            val origin = flight.origin
            val center = home.value
            if (origin != null && center != null) {
                val remaining = 1f - flight.progress(index)
                translationX = (origin.x - center.x) * remaining
                translationY = (origin.y - center.y) * remaining
            }
        }
}

/** Fades a quick action label in behind its arriving bubble. */
@Composable
internal fun Modifier.penFlightLabel(index: Int): Modifier {
    val flight = LocalNewMessagePenFlight.current ?: return this
    return graphicsLayer { alpha = flight.progress(index) }
}

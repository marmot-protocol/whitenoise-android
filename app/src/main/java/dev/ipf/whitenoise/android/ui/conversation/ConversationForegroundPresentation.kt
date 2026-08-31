package dev.ipf.whitenoise.android.ui.conversation

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

private typealias ForegroundSettlePredicate = (ConversationForegroundSettleState) -> Boolean

/** Layout and IME animation state required before the foreground frame can be exposed. */
internal data class ConversationForegroundSettleState(
    val geometry: ConversationForegroundGeometry,
    val imeTargetBottomPx: Int,
    val bottomChromeMeasured: Boolean,
) {
    /** Whether every measured surface agrees on one coherent viewport geometry. */
    fun isGeometrySettled(): Boolean =
        geometry.viewportHeightPx > 0 &&
            bottomChromeMeasured &&
            geometry.imeBottomPx == imeTargetBottomPx

    /** Whether coherent geometry also matches the IME visibility requested at resume. */
    fun isSettled(expectedImeVisible: Boolean): Boolean =
        isGeometrySettled() &&
            (geometry.imeBottomPx > 0) == expectedImeVisible
}

/**
 * Waits for the requested IME state for one bounded liveness window. When that
 * deadline expires, [onSettleDeadlineExpired] must open the draw gate and
 * schedule a frame immediately. Coherent geometry can then complete at once;
 * otherwise the captured snapshot remains armed until later settled geometry
 * arrives to apply the one deferred correction without blocking presentation.
 */
internal suspend fun awaitConversationForegroundPresentation(
    preDrawSignals: ReceiveChannel<Unit>,
    currentState: () -> ConversationForegroundSettleState,
    expectedImeVisible: Boolean,
    expectedVisibilityTimeoutMillis: Long,
    onSettleDeadlineExpired: () -> Unit = {},
): ConversationForegroundSettleState {
    /** Returns the first pre-draw state accepted by [predicate]. */
    suspend fun awaitState(predicate: ForegroundSettlePredicate): ConversationForegroundSettleState {
        while (true) {
            preDrawSignals.receive()
            val state = currentState()
            if (predicate(state)) return state
        }
    }

    val requestedPresentation =
        withTimeoutOrNull(expectedVisibilityTimeoutMillis) {
            awaitState { it.isSettled(expectedImeVisible) }
        }
    if (requestedPresentation != null) return requestedPresentation

    onSettleDeadlineExpired()
    return currentState().takeIf { it.isGeometrySettled() }
        ?: awaitState { it.isGeometrySettled() }
}

/**
 * Keeps Android on its task snapshot while the foreground transaction owns
 * presentation. Every pre-draw pumps [onPreDraw] — the settle wait re-checks on
 * each signal, including after the deadline opens the gate, when blocked
 * pre-draws no longer occur but a deferred correction may still be armed.
 */
internal class ConversationForegroundDrawGate(
    private val isBlocked: () -> Boolean,
    private val onPreDrawSignal: () -> Unit = {},
) : ViewTreeObserver.OnPreDrawListener {
    /** Signals each frame attempt and exposes it only after the live gate opens. */
    override fun onPreDraw(): Boolean {
        onPreDrawSignal()
        return !isBlocked()
    }
}

/** Installs one root pre-draw gate for the lifetime of the mounted conversation. */
@Suppress("FunctionNaming")
@Composable
internal fun ConversationForegroundDrawGateEffect(
    isBlocked: () -> Boolean,
    onPreDraw: () -> Unit,
) {
    val view = LocalView.current
    val currentIsBlocked by rememberUpdatedState(isBlocked)
    val currentOnPreDraw by rememberUpdatedState(onPreDraw)

    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val gate =
            ConversationForegroundDrawGate(
                isBlocked = { currentIsBlocked() },
                onPreDrawSignal = { currentOnPreDraw() },
            )
        observer.addOnPreDrawListener(gate)
        onDispose {
            if (observer.isAlive) observer.removeOnPreDrawListener(gate)
        }
    }
}

/** Schedules the first root draw after the foreground gate opens. */
internal fun requestConversationForegroundFrame(view: android.view.View) {
    view.postInvalidateOnAnimation()
}

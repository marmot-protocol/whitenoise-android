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
    fun isGeometrySettled(): Boolean =
        geometry.viewportHeightPx > 0 &&
            bottomChromeMeasured &&
            geometry.imeBottomPx == imeTargetBottomPx

    fun isSettled(expectedImeVisible: Boolean): Boolean =
        isGeometrySettled() &&
            (geometry.imeBottomPx > 0) == expectedImeVisible
}

/**
 * Waits for the requested IME state, then relaxes only that request after the
 * liveness timeout. Transient inset or unmeasured chrome geometry never passes.
 * The relaxed geometry wait is bounded by the same timeout, so the caller is
 * never blocked longer than twice the liveness window — a null return means
 * geometry never settled and the foreground transaction must be released with
 * the correction deferred until valid geometry arrives.
 */
internal suspend fun awaitConversationForegroundPresentation(
    preDrawSignals: ReceiveChannel<Unit>,
    currentState: () -> ConversationForegroundSettleState,
    expectedImeVisible: Boolean,
    expectedVisibilityTimeoutMillis: Long,
): ConversationForegroundSettleState? {
    suspend fun awaitState(predicate: ForegroundSettlePredicate): ConversationForegroundSettleState {
        while (true) {
            preDrawSignals.receive()
            val state = currentState()
            if (predicate(state)) return state
        }
    }

    return withTimeoutOrNull(expectedVisibilityTimeoutMillis) {
        awaitState { it.isSettled(expectedImeVisible) }
    } ?: currentState().takeIf { it.isGeometrySettled() }
        ?: withTimeoutOrNull(expectedVisibilityTimeoutMillis) {
            awaitState { it.isGeometrySettled() }
        }
}

/** Keeps Android on its task snapshot while the foreground transaction owns presentation. */
internal class ConversationForegroundDrawGate(
    private val isBlocked: () -> Boolean,
    private val onBlockedPreDraw: () -> Unit = {},
) : ViewTreeObserver.OnPreDrawListener {
    override fun onPreDraw(): Boolean {
        val blocked = isBlocked()
        if (blocked) onBlockedPreDraw()
        return !blocked
    }
}

/** Installs one root pre-draw gate for the lifetime of the mounted conversation. */
@Suppress("FunctionNaming")
@Composable
internal fun ConversationForegroundDrawGateEffect(
    isBlocked: () -> Boolean,
    onBlockedPreDraw: () -> Unit,
) {
    val view = LocalView.current
    val currentIsBlocked by rememberUpdatedState(isBlocked)
    val currentOnBlockedPreDraw by rememberUpdatedState(onBlockedPreDraw)

    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val gate =
            ConversationForegroundDrawGate(
                isBlocked = { currentIsBlocked() },
                onBlockedPreDraw = { currentOnBlockedPreDraw() },
            )
        observer.addOnPreDrawListener(gate)
        onDispose {
            if (observer.isAlive) observer.removeOnPreDrawListener(gate)
        }
    }
}

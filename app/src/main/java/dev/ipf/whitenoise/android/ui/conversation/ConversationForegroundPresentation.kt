package dev.ipf.whitenoise.android.ui.conversation

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView

/** Layout and IME animation state required before the foreground frame can be exposed. */
internal data class ConversationForegroundSettleState(
    val geometry: ConversationForegroundGeometry,
    val imeTargetBottomPx: Int,
) {
    fun isSettled(expectedImeVisible: Boolean): Boolean =
        geometry.viewportHeightPx > 0 &&
            geometry.bottomChromeHeightPx > 0 &&
            geometry.imeBottomPx == imeTargetBottomPx &&
            (geometry.imeBottomPx > 0) == expectedImeVisible
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

package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import dev.ipf.whitenoise.android.ui.common.LocalControlHandoffEndpointCurrent
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoff

/** Current launcher geometry only; the shell's capsule/obstacle lifetime remains session-scoped. */
internal class DictationLauncherOrigin {
    private var current: Pair<Any, Rect>? by mutableStateOf(null)
    val boundsInWindow: Rect? get() = current?.second
    private var preparedSessionId: Long? = null

    /** Consumes a session's presentation entry once, even when policy or missing geometry blocks it. */
    fun takeEntrance(
        sessionId: Long?,
        eligible: Boolean,
    ): Rect? {
        if (sessionId == null || preparedSessionId == sessionId) return null
        preparedSessionId = sessionId
        return boundsInWindow.takeIf { eligible }
    }

    /** Retains only finite, positive geometry from a currently placed launcher. */
    fun report(
        token: Any,
        bounds: Rect,
    ) {
        if (bounds.width > 0f &&
            bounds.height > 0f &&
            bounds.left.isFinite() &&
            bounds.top.isFinite() &&
            bounds.right.isFinite() &&
            bounds.bottom.isFinite()
        ) {
            current = token to bounds
        } else {
            remove(token)
        }
    }

    /** Outgoing launcher disposal cannot remove a replacement slot's registration. */
    fun remove(token: Any) {
        if (current?.first === token) current = null
    }
}

internal val LocalDictationLauncherOrigin = staticCompositionLocalOf<DictationLauncherOrigin?> { null }

/** Read-only shell presentation geometry/clock; no input, draft, audio or session ownership. */
internal val LocalDictationControlHandoff = staticCompositionLocalOf<MeasuredControlHandoff?> { null }

/** Measures the actual launcher target without cloning its glyph, action, focus or session. */
@Composable
internal fun Modifier.dictationLauncherOrigin(): Modifier {
    val origin = LocalDictationLauncherOrigin.current
    val current = LocalControlHandoffEndpointCurrent.current
    val token = remember(origin) { Any() }
    DisposableEffect(origin, token, current) {
        if (!current) origin?.remove(token)
        onDispose { origin?.remove(token) }
    }
    return if (current) onPlaced { origin?.report(token, it.boundsInWindow()) } else this
}

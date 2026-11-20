package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

/** One synchronous row activation; preparation carries its consumed bounds on the existing request. */
internal class ConversationRouteOrigin {
    private var pending: Pair<String, Rect>? = null

    fun activate(
        groupId: String,
        bounds: Rect?,
        action: () -> Unit,
    ) {
        pending = bounds?.takeUnless { it.isEmpty }?.let { groupId to it }
        try {
            action()
        } finally {
            pending = null
        }
    }

    fun take(groupId: String): Rect? {
        val selected = pending
        pending = null
        return selected?.second?.takeIf { selected.first == groupId }
    }
}

internal val LocalConversationRouteOrigin = staticCompositionLocalOf<ConversationRouteOrigin?> { null }

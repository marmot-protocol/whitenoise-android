package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.NativePageHeaderMotion
import dev.ipf.whitenoise.android.ui.common.motionBoundsOnScreen
import dev.ipf.whitenoise.android.ui.common.nativePageBodyPresence

/** The conversation header settles upward by this distance while it fades in. */
internal val CONVERSATION_ROUTE_HEADER_TRAVEL = 12.dp

/**
 * Header opacity on the same intrinsic clock as the body (list = 0, conversation = 1).
 * The outgoing header clears before the incoming one appears, so the two never overlap.
 * Only the [owner] chat draws a conversation header; a direct switch shows only the current header.
 */
internal fun conversationRouteHeaderPresence(
    visibility: Float,
    conversationRoute: Boolean,
    current: Boolean,
    owner: Boolean,
    direct: Boolean,
): Float =
    when {
        direct -> if (current) 1f else 0f
        conversationRoute && !owner -> 0f
        else -> nativePageBodyPresence(visibility, conversationRoute)
    }

/** Fraction of [CONVERSATION_ROUTE_HEADER_TRAVEL] still below rest; the list header stays in place. */
internal fun conversationRouteHeaderTravel(
    presence: Float,
    conversationRoute: Boolean,
): Float = if (conversationRoute) 1f - presence.coerceIn(0f, 1f) else 0f

/**
 * The header band runs from the reported top bar down to the body, so rows between them
 * (folder pills, playback strip, notices) fade with the bar instead of cutting.
 */
@Suppress("LongParameterList")
internal fun conversationRouteHeaderMotion(
    header: Rect?,
    body: Rect?,
    visibility: Float,
    conversationRoute: Boolean,
    current: Boolean,
    owner: Boolean,
    direct: Boolean,
    travelPx: Float,
): NativePageHeaderMotion? {
    if (header == null || header.isEmpty) return null
    val bottom = body?.top?.takeIf { it >= header.bottom } ?: header.bottom
    val presence = conversationRouteHeaderPresence(visibility, conversationRoute, current, owner, direct)
    return NativePageHeaderMotion(
        bounds = Rect(header.left, header.top, header.right, bottom),
        presence = presence,
        offsetY = travelPx * conversationRouteHeaderTravel(presence, conversationRoute),
    )
}

/** Reports the route's top bar so the retained route can fade it with its body instead of cutting it. */
@Composable
internal fun Modifier.conversationRouteHeaderMotion(): Modifier {
    val motion = LocalConversationBodyMotion.current ?: return this
    val view = LocalView.current
    DisposableEffect(motion) { onDispose { motion.nativeBody.header = null } }
    return onGloballyPositioned { motion.nativeBody.header = it.motionBoundsOnScreen(view) }
}

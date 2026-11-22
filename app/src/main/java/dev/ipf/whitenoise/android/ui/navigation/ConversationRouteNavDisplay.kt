package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import dev.ipf.whitenoise.android.ui.common.LocalControlHandoffEndpointCurrent

/** One route of the conversation pane as Navigation 3 keys it: the main route or one conversation. */
private data class ConversationPaneRoute<T>(
    val route: T,
)

/**
 * The conversation pane rendered with Navigation 3's NavDisplay. Its stack is a projection of [transition]'s target:
 * the main route, then the target conversation when one is open. Like [ConversationRouteAnimatedContent] it moves
 * only draw layers from one shared visibility value and asks for the same outgoing-slot retention, but the slots,
 * their retention and their disposal are NavDisplay's. [mainRoute] is the value that stands for no conversation.
 * Navigation 3 handles no Back here (the scene reports no previous entries) and keeps no saved state.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun <T> ConversationRouteNavDisplay(
    transition: Transition<T>,
    mainRoute: T,
    routeForwardDirection: Int,
    suppressMotion: Boolean,
    contentKey: (T) -> Any?,
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val conversationVisibility =
        transition.animateFloat(
            transitionSpec = { if (suppressMotion) snap() else conversationRouteAnimationSpec },
            label = "conversation route visibility",
        ) { state -> if (contentKey(state) != contentKey(mainRoute)) 1f else 0f }
    val main = ConversationPaneRoute(mainRoute)
    val target = transition.targetState
    val open = contentKey(target) != contentKey(mainRoute)
    val stack = if (open) listOf(main, ConversationPaneRoute(target)) else listOf(main)
    val motion = ConversationPaneMotion(conversationVisibility, routeForwardDirection, suppressMotion)
    // Entries are built once per stack change, so they always call the latest layer, never a captured one.
    val currentLayer by rememberUpdatedState<@Composable (T) -> Unit> { route ->
        ConversationPaneLayer(
            motion = motion,
            conversationRoute = contentKey(route) != contentKey(mainRoute),
            current = contentKey(route) == contentKey(transition.targetState),
        ) { LocalNavAnimatedContentScope.current.content(route) }
    }
    val currentSuppressMotion by rememberUpdatedState(suppressMotion)
    NavDisplay(
        backStack = stack,
        modifier = Modifier.fillMaxSize(),
        onBack = {},
        entryDecorators = emptyList(),
        sceneStrategies = remember { listOf(ShellTopEntrySceneStrategy()) },
        transitionSpec = { routeContentTransform(currentSuppressMotion) },
        popTransitionSpec = { routeContentTransform(currentSuppressMotion) },
        predictivePopTransitionSpec = { routeContentTransform(currentSuppressMotion) },
        entryProvider = { pane ->
            NavEntry(pane, contentKey = contentKey(pane.route) ?: MAIN_SHELL_ROUTE_KEY) { currentLayer(it.route) }
        },
    )
}

/** The shared draw-layer motion of the conversation pane. */
private class ConversationPaneMotion(
    val visibility: State<Float>,
    val routeForwardDirection: Int,
    val suppressMotion: Boolean,
)

/**
 * One route's full-screen layer: its alpha and travel come from the shared visibility in the draw phase, as in
 * [ConversationRouteAnimatedContent]. [current] tells the route's controls whether this layer is the target one.
 */
@Composable
@Suppress("FunctionNaming")
private fun ConversationPaneLayer(
    motion: ConversationPaneMotion,
    conversationRoute: Boolean,
    current: Boolean,
    content: @Composable () -> Unit,
) {
    val separatedChrome = LocalComposerFabHandoff.current?.morphSurface == true
    val travelPx = with(LocalDensity.current) { CONVERSATION_ROUTE_LAYER_TRAVEL.toPx() }
    val suppressMotion = motion.suppressMotion
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val progress = motion.visibility.value
            alpha =
                if (separatedChrome) 1f else conversationRouteLayerAlpha(progress, conversationRoute, suppressMotion)
            translationX =
                (if (separatedChrome) 0f else travelPx) * motion.routeForwardDirection *
                conversationRouteLayerTranslation(progress, conversationRoute, suppressMotion)
        },
    ) {
        CompositionLocalProvider(
            LocalControlHandoffEndpointCurrent provides current,
            LocalConversationBodyMotion provides
                if (separatedChrome) {
                    ConversationBodyMotion(
                        visibility = motion.visibility,
                        conversation = conversationRoute,
                        direction = motion.routeForwardDirection,
                        suppressed = suppressMotion,
                    )
                } else {
                    null
                },
            content = content,
        )
    }
}

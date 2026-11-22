package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.NavDisplay

/** The shell's decorated route entries, in stack order. */
private typealias ShellEntries = List<NavEntry<ShellRoute>>

/** No enter or exit motion: a route change replaces the rendered entry outright, as the shell's own hosts do. */
private val NoRouteMotion: ContentTransform = EnterTransition.None togetherWith ExitTransition.None

/**
 * The identity Navigation 3 keys an entry's composition by. It names the destination only, never a route's live
 * fields (a conversation's row or open context), so an update to those keeps the same entry and its state.
 */
internal fun shellEntryContentKey(route: ShellRoute): String =
    when (route) {
        ShellRoute.Chats -> "chats"
        is ShellRoute.Settings -> "settings:" + (route.detail?.name ?: "home")
        ShellRoute.Diagnostics -> "diagnostics"
        is ShellRoute.Conversation -> "conversation:" + route.accountRef + ":" + route.groupIdHex
        is ShellRoute.ChatDetailsScreen -> "chat-details:" + route.destination.name
    }

/** The one key every shell scene shares, see [ShellTopEntryScene]. */
private const val SHELL_SCENE_KEY = "shell-top-entry"

/**
 * Renders the top of the stack. It reports no previous entries, so Navigation 3 never enables its own Back or
 * predictive-back handling: system Back keeps reaching the shell's plain Back handlers, which send the navigator its
 * events, exactly as before.
 *
 * NavDisplay moves its scene transition to a new scene from an effect, so the scene it shows trails the stack by a
 * few frames, and with a scene per entry the entry the route had left, its Back handler and its editable state stayed
 * composed for those frames. So every shell scene has the same key, which keeps NavDisplay's AnimatedContent at one
 * slot, and that slot draws [latestTop], the stack's top in the frame it changed, rather than the entry this scene was
 * calculated for. Keying on the top's content key gives each route its own composition, so two routes drawn through
 * the same call never share state.
 */
private class ShellTopEntryScene(
    private val entry: NavEntry<ShellRoute>,
    private val latestTop: State<ShellRoute>,
    private val render: State<@Composable (ShellRoute) -> Unit>,
) : Scene<ShellRoute> {
    override val key: Any = SHELL_SCENE_KEY
    override val entries: List<NavEntry<ShellRoute>> = listOf(entry)
    override val previousEntries: List<NavEntry<ShellRoute>> = emptyList()
    override val content: @Composable () -> Unit = {
        val route = latestTop.value
        key(shellEntryContentKey(route)) { render.value(route) }
    }

    /** Two scenes are the same when they were calculated for the same entry instance. */
    override fun equals(other: Any?): Boolean = other is ShellTopEntryScene && other.entry == entry

    /** Hashes the entry, matching [equals]. */
    override fun hashCode(): Int = entry.hashCode()
}

/** Places the top of the stack in a [ShellTopEntryScene] that draws [latestTop] with [render]. */
private class ShellTopEntrySceneStrategy(
    private val latestTop: State<ShellRoute>,
    private val render: State<@Composable (ShellRoute) -> Unit>,
) : SceneStrategy<ShellRoute> {
    /** The scene for the last entry of [entries]. */
    override fun SceneStrategyScope<ShellRoute>.calculateScene(entries: ShellEntries): Scene<ShellRoute> {
        val top = entries.last()
        return ShellTopEntryScene(top, latestTop, render)
    }
}

/**
 * Renders a projection of the navigator's [stack] with Navigation 3's NavDisplay. The navigator stays the only route
 * owner: this display never changes the stack, keeps no saved state of its own (no entry decorators, so the saved
 * format is unchanged), runs no transition and handles no Back. [content] draws one route, and the latest [content]
 * is always the one called, so an entry never renders with stale shell state.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ShellNavDisplay(
    stack: List<ShellRoute>,
    modifier: Modifier = Modifier,
    content: @Composable (ShellRoute) -> Unit,
) {
    val render = rememberUpdatedState(content)
    val latestTop = rememberUpdatedState(stack.last())
    val strategy = remember(render, latestTop) { ShellTopEntrySceneStrategy(latestTop, render) }
    NavDisplay(
        backStack = stack,
        modifier = modifier,
        onBack = {},
        entryDecorators = emptyList(),
        sceneStrategies = listOf(strategy),
        transitionSpec = { NoRouteMotion },
        popTransitionSpec = { NoRouteMotion },
        predictivePopTransitionSpec = { NoRouteMotion },
        entryProvider = { route -> NavEntry(route, contentKey = shellEntryContentKey(route)) { render.value(it) } },
    )
}

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
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.settings.SettingsRouteMotion

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
 * The composition a route is drawn in. Every Settings screen shares one, as master kept one Settings composition
 * across its screens and moved between them with its own motion, and every other route has its own.
 */
internal fun shellCompositionKey(route: ShellRoute): String =
    when (route) {
        is ShellRoute.Settings -> "settings"
        else -> shellEntryContentKey(route)
    }

/** Draws the stack's latest top: [frame] outside the route's own composition, [render] inside it. */
private class ShellTopRenderer(
    private val latestTop: State<ShellRoute>,
    private val frame: State<@Composable (ShellRoute, @Composable () -> Unit) -> Unit>,
    private val render: State<@Composable (ShellRoute) -> Unit>,
) {
    /** Draws the latest top inside the frame, keyed by its composition. */
    val draw: @Composable () -> Unit = {
        val route = latestTop.value
        frame.value(route) { key(shellCompositionKey(route)) { render.value(route) } }
    }
}

/**
 * Renders the top of the stack. It reports no previous entries, so Navigation 3 never enables its own Back or
 * predictive-back handling: system Back keeps reaching the shell's plain Back handlers, which send the navigator its
 * events, exactly as before.
 *
 * NavDisplay moves its scene transition to a new scene from an effect, so the scene it shows trails the stack by a
 * few frames, and with a scene per entry the entry the route had left, its Back handler and its editable state stayed
 * composed for those frames. So every shell scene has the same key, which keeps NavDisplay's AnimatedContent at one
 * slot, and that slot draws the stack's top in the frame it changed, rather than the entry this scene was calculated
 * for. The shell's own route motion runs in that slot, as it ran on master: it disposes the route it leaves at once.
 */
private class ShellTopEntryScene(
    private val entry: NavEntry<ShellRoute>,
    private val renderer: ShellTopRenderer,
) : Scene<ShellRoute> {
    override val key: Any = SHELL_SCENE_KEY
    override val entries: List<NavEntry<ShellRoute>> = listOf(entry)
    override val previousEntries: List<NavEntry<ShellRoute>> = emptyList()
    override val content: @Composable () -> Unit = renderer.draw

    /** Two scenes are the same when they were calculated for the same entry instance. */
    override fun equals(other: Any?): Boolean = other is ShellTopEntryScene && other.entry == entry

    /** Hashes the entry, matching [equals]. */
    override fun hashCode(): Int = entry.hashCode()
}

/** Places the top of the stack in a [ShellTopEntryScene] drawn by [renderer]. */
private class ShellTopEntrySceneStrategy(
    private val renderer: ShellTopRenderer,
) : SceneStrategy<ShellRoute> {
    /** The scene for the last entry of [entries]. */
    override fun SceneStrategyScope<ShellRoute>.calculateScene(entries: ShellEntries): Scene<ShellRoute> {
        val top = entries.last()
        return ShellTopEntryScene(top, renderer)
    }
}

/**
 * Renders a projection of the navigator's [stack] with Navigation 3's NavDisplay. The navigator stays the only route
 * owner: this display never changes the stack, keeps no saved state of its own (no entry decorators, so the saved
 * format is unchanged), runs no transition of its own and handles no Back. [frame] wraps every route outside its own
 * composition, which is where the shell's route motion lives, and [content] draws one route. The latest of both is
 * always the one called, so a route never renders with stale shell state.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ShellNavDisplay(
    stack: List<ShellRoute>,
    modifier: Modifier = Modifier,
    frame: @Composable (ShellRoute, @Composable () -> Unit) -> Unit = { _, entry -> entry() },
    content: @Composable (ShellRoute) -> Unit,
) {
    val render = rememberUpdatedState(content)
    val currentFrame = rememberUpdatedState(frame)
    val latestTop = rememberUpdatedState(stack.last())
    val strategy =
        remember(render, currentFrame, latestTop) {
            ShellTopEntrySceneStrategy(ShellTopRenderer(latestTop, currentFrame, render))
        }
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

/**
 * Master's list-pane motion around [content]: the top-level bubble between the chat list and Settings and the slide
 * to Diagnostics, keyed by the section [route] belongs to and owned by [ownerKey], the account and runtime.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ShellSectionMotion(
    route: ShellRoute,
    ownerKey: Any?,
    suppressMotion: Boolean,
    animationsEnabled: Boolean = rememberSystemAnimationsEnabled(),
    content: @Composable () -> Unit,
) {
    val section =
        when (route) {
            is ShellRoute.Settings -> MainSection.Settings
            ShellRoute.Diagnostics -> MainSection.Diagnostics
            else -> MainSection.Chats
        }
    SettingsRouteMotion(
        target = section,
        depth =
            when (section) {
                MainSection.Chats -> 0
                MainSection.Settings -> 1
                MainSection.Diagnostics -> 2
            },
        ownerKey = ownerKey,
        suppressMotion = suppressMotion,
        animationsEnabled = animationsEnabled,
        topLevelBubble = true,
    ) { content() }
}

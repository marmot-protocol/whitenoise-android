package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.NavDisplay

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

/**
 * Renders only the top entry. It reports no previous entries, so Navigation 3 never enables its own Back or
 * predictive-back handling: system Back keeps reaching the shell's plain Back handlers, which send the navigator its
 * events, exactly as before.
 */
private class ShellTopEntryScene<T : Any>(
    private val entry: NavEntry<T>,
) : Scene<T> {
    override val key: Any = entry.contentKey
    override val entries: List<NavEntry<T>> = listOf(entry)
    override val previousEntries: List<NavEntry<T>> = emptyList()
    override val content: @Composable () -> Unit = { entry.Content() }

    /** Two scenes are the same when they render the same entry instance. */
    override fun equals(other: Any?): Boolean = other is ShellTopEntryScene<*> && other.entry == entry

    /** Hashes the rendered entry, matching [equals]. */
    override fun hashCode(): Int = entry.hashCode()
}

/** Places the top of the stack in a [ShellTopEntryScene], the only scene the shell's displays use. */
internal class ShellTopEntrySceneStrategy<T : Any> : SceneStrategy<T> {
    /** The scene for the last entry of [entries]. */
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T> {
        val top = entries.last()
        return ShellTopEntryScene(top)
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
    val currentContent by rememberUpdatedState(content)
    NavDisplay(
        backStack = stack,
        modifier = modifier,
        onBack = {},
        entryDecorators = emptyList(),
        sceneStrategies = remember { listOf(ShellTopEntrySceneStrategy()) },
        transitionSpec = { NoRouteMotion },
        popTransitionSpec = { NoRouteMotion },
        predictivePopTransitionSpec = { NoRouteMotion },
        entryProvider = { route -> NavEntry(route, contentKey = shellEntryContentKey(route)) { currentContent(it) } },
    )
}

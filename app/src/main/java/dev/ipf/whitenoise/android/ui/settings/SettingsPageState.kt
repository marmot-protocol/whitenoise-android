package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import dev.ipf.whitenoise.android.ui.navigation.ShellRoute

/**
 * Saved state for the open Settings pages ([places], home excluded): a page below the top keeps its saved state, such
 * as its scroll position or a search query, for the Back that returns to it, and loses it once it leaves the stack.
 */
@Composable
internal fun rememberSettingsPageState(places: List<ShellRoute.Settings>): SaveableStateHolder {
    val pageState = rememberSaveableStateHolder()
    val pageKeys = places.map(::settingsPageStateKey).toSet()
    val retainedPageKeys = remember { mutableSetOf<String>() }
    SideEffect {
        (retainedPageKeys - pageKeys).forEach(pageState::removeState)
        retainedPageKeys.retainAll(pageKeys)
        retainedPageKeys += pageKeys
    }
    return pageState
}

/** The saved-state key of one open Settings page: its destination and, for a one-item page, its item. */
internal fun settingsPageStateKey(place: ShellRoute.Settings): String {
    val item = place.arg?.let { ":$it" }.orEmpty()
    return "settings-page:" + place.detail?.name + item
}

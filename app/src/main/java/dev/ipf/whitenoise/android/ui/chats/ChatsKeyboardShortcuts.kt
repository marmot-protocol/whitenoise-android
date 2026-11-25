package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import dev.ipf.whitenoise.android.ui.keyboard.HardwareShortcutCommand
import dev.ipf.whitenoise.android.ui.keyboard.KeyboardShortcut

/**
 * Remembers the Chats search field's focus requester and lets Ctrl+F open that search through the toolbar
 * button's transition, or focus the field when search is already open (#4005). Unavailable during selection,
 * where the selection bar replaces the search field.
 */
@Composable
internal fun rememberGlobalSearchShortcut(
    searchOpen: Boolean,
    selectionMode: Boolean,
    onGlobalSearchStateChange: ((GlobalSearchState) -> GlobalSearchState) -> Unit,
): FocusRequester {
    val focusRequester = remember { FocusRequester() }
    KeyboardShortcut(HardwareShortcutCommand.GlobalSearch, available = !selectionMode) {
        if (searchOpen) {
            focusRequester.requestFocus()
        } else {
            onGlobalSearchStateChange(GlobalSearchTransitions::openSearch)
        }
    }
    return focusRequester
}

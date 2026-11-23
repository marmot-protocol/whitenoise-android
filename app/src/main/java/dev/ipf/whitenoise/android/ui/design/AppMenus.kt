package dev.ipf.whitenoise.android.ui.design

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.scrollEdgeFade
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

// Roomier than Material's default menu-item padding so conversation overflow
// rows read as full lines of text rather than compact cells.
internal val conversationMenuItemPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)

// Anchored dropdown that does NOT collapse the soft keyboard while it is open.
//
// A default DropdownMenu opens a *focusable* popup window; Android dismisses the
// IME the moment the window holding the focused composer loses focus. That
// collapse strips the composer's imePadding and reflows the transcript down by
// the keyboard height — so a menu/picker launched from the composer toolbar
// (the attach clip, the conversation overflow) animates in over a shifted
// layout, and dismissing it leaves the composer unfocused with the keyboard
// down (#323). Same "modal UI launched from the composer toolbar steals IME
// state instead of overlaying it" family as the voice-record bar (#207) and the
// long-press popover (#284).
//
// focusable = false keeps the keyboard up but opens two gaps versus a focusable
// menu, restored here exactly as ConversationLongPressMenu does for #284:
//   1. Back dismissal — Popup's dismissOnBackPress is a no-op while the popup is
//      non-focusable, so Back would fall through to the IME/activity instead of
//      closing the menu. A host-window BackHandler closes it without touching
//      IME focus.
//   2. Outside-tap click-through — taps outside a non-focusable popup are
//      delivered to the windows beneath it, so a dismiss tap would also activate
//      the underlying content. A full-window, non-focusable scrim Popup placed
//      below the menu consumes those taps: tapping it dismisses the menu and the
//      press is consumed so it never reaches the content. The scrim is itself
//      non-focusable, so it too preserves the open keyboard.
//
// Shared popup chrome retains the original parent anchor and two nonfocusable windows.
// The scrim and host Back handler remain mounted until the final exit frame.
@Composable
internal fun KeyboardPreservingDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    shape: Shape = MenuDefaults.shape,
    content: @Composable ColumnScope.() -> Unit,
) {
    val menuScrollState = rememberScrollState()
    AnimatedDropdownMenu(
        scrollState = menuScrollState,
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.scrollEdgeFade(menuScrollState, stableRenderTarget = true),
        offset = offset,
        shape = shape,
        border = amoledSurfaceBorderStroke(),
        keyboardPreserving = true,
    ) { content() }
}

package dev.ipf.whitenoise.android.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
// Positioning, anchoring and the menu chrome stay DropdownMenu's — only its
// focus behavior and dismissal plumbing change.
@Composable
internal fun KeyboardPreservingDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    shape: Shape = MenuDefaults.shape,
    content: @Composable ColumnScope.() -> Unit,
) {
    // BackHandler + scrim only exist while the menu is open. They render in
    // their own popup windows, so they never disturb the anchor's layout.
    if (expanded) {
        BackHandler(enabled = true) { onDismissRequest() }
        // Scrim: composed before the menu so the menu renders on top of it.
        // Fills the window and swallows any tap as a pure dismissal.
        Popup(
            properties =
                PopupProperties(
                    focusable = false,
                    // The scrim owns outside-tap dismissal; the menu's own
                    // outside-tap detection is disabled below so a single tap is
                    // handled exactly once, here, and consumed.
                    dismissOnClickOutside = false,
                ),
            onDismissRequest = onDismissRequest,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { onDismissRequest() }
                        },
            )
        }
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = shape,
        border = amoledSurfaceBorderStroke(),
        properties =
            PopupProperties(
                focusable = false,
                // Outside taps are handled by the scrim above (which also blocks
                // click-through); disabling the menu's own outside-dismiss keeps
                // a single tap from being processed twice.
                dismissOnClickOutside = false,
            ),
        content = content,
    )
}

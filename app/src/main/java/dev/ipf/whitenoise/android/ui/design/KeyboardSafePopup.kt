package dev.ipf.whitenoise.android.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

// focusable = false keeps the soft keyboard up while this overlay is open.
// A focusable popup window steals window focus from the conversation's host
// window, and Android dismisses the IME when the window holding the focused
// composer loses focus. That collapse then removes the composer's imePadding,
// reflowing the transcript down by the keyboard height mid gesture — so a
// long-press popover lands at a shifted position rather than where the user
// pressed (#284). Same "modal UI fights the IME" family as the voice-record bar
// in #207, and Material3 ModalBottomSheet on simple conversation sheets (#1396).
//
// A non-focusable popup has two gaps versus focusable modal UI that we restore
// explicitly here, without re-focusing (which would collapse the IME again):
//   1. Back dismissal — Popup's dismissOnBackPress is a no-op while the popup is
//      non-focusable, so a Back press would fall through to the IME/activity
//      instead of closing the overlay. A host-window BackHandler (same pattern
//      as QuickActionFabMenu) closes it on Back. It runs in the conversation
//      window and does not touch IME focus.
//   2. Outside-tap click-through — events outside a non-focusable popup are
//      delivered to the windows beneath it, so a dismiss tap would also activate
//      the underlying chat content (open a profile, a link, the media viewer,
//      etc.). A full-window, non-focusable scrim Popup placed below the
//      content popup consumes those taps: tapping it dismisses the overlay and
//      the press is consumed so it never reaches the transcript. The scrim is
//      itself non-focusable, so it preserves the open keyboard.
//
// Everything below is wrapped in a single zero-size Box so this composable
// always contributes exactly ONE (zero-height) child to the caller's layout,
// whether or not the overlay is open. Emitting the scrim + content popups as
// bare siblings only while expanded would otherwise add extra Arrangement gaps
// in spacedBy parents, visibly growing bubble height on long-press (#284 review).
// The popups themselves render in their own windows, so the Box stays zero-size
// either way.
private val keyboardSafePopupProperties =
    PopupProperties(
        focusable = false,
        // Callers own dismissal via the scrim tap handler and host BackHandler;
        // keep Popup's own outside-tap detection off so a single outside tap is
        // handled exactly once and consumed.
        dismissOnClickOutside = false,
        // Both popup windows can touch system-gesture edges. Let Android retain
        // those edges so gesture/predictive Back reaches the host BackHandler.
        excludeFromSystemGesture = false,
    )

/**
 * Bottom-anchors popup content to the host window. Use for simple sheets that
 * should overlay the conversation without stealing IME focus from the composer.
 *
 * [PopupPositionProvider] receives [windowSize] from the host view's visible
 * display frame (Compose default [PopupProperties.clippingEnabled]); with
 * `adjustResize` that frame already sits above the IME, so callers must not
 * subtract [WindowInsets.ime] again. When content is taller than the frame,
 * allow a negative y so the bottom edge stays pinned while the top is clipped.
 */
internal object BottomAnchoredPopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (windowSize.width - popupContentSize.width) / 2
        val y = windowSize.height - popupContentSize.height
        return IntOffset(x, y)
    }
}

@Composable
internal fun KeyboardSafePopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    popupPositionProvider: PopupPositionProvider,
    content: @Composable () -> Unit,
) {
    Box {
        if (!expanded) return@Box
        BackHandler(enabled = true) { onDismissRequest() }
        // Scrim popup: composed before the content popup so content renders on top.
        // Fills the window and swallows any tap as a pure dismissal.
        Popup(
            properties = keyboardSafePopupProperties,
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
        Popup(
            popupPositionProvider = popupPositionProvider,
            onDismissRequest = onDismissRequest,
            properties = keyboardSafePopupProperties,
            content = content,
        )
    }
}

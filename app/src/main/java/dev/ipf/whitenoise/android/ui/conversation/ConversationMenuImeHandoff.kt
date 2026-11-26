package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageMenuKeyboardOwner
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageMenuKeyboardPolicy
import kotlinx.coroutines.launch

/**
 * Keyboard handoff state for one controller's message action menus. The screen remembers it per
 * controller, so neither account/runtime replacement nor route exit can reuse a menu's pending
 * keyboard restoration.
 */
internal class ConversationMenuImeHandoff {
    /** Records which input regains the keyboard when the open menu closes. */
    val policy = MessageMenuKeyboardPolicy()

    /** IME inset held below the transcript while a menu hides the keyboard, or null when the live IME owns it. */
    var backdropBottom by mutableStateOf<Int?>(null)

    /** Whether the held inset is moving back to the live keyboard height. */
    var backdropReturning by mutableStateOf(false)

    /** Whether the composer was editing when the menu hid the keyboard. */
    var composerWasEditing by mutableStateOf(false)

    /** Whether the composer keeps its editing height while the held inset stands in for the hidden keyboard. */
    val preservesComposerEditingLayout: Boolean
        get() = backdropBottom != null && composerWasEditing

    /** Hands transcript geometry back to the live IME inset. */
    fun releaseBackdrop() {
        backdropBottom = null
        backdropReturning = false
    }

    /**
     * Settles a closed menu's keyboard intent. Selection keeps the keyboard hidden. Otherwise the input
     * the menu hid regains focus, and a failed focus starts the held inset's return at once.
     */
    fun restoreAfterClose(
        controller: ConversationController,
        suppressRestore: Boolean,
        navigationState: ConversationNavigationState,
        composerFocus: FocusRequester,
        keyboardController: SoftwareKeyboardController?,
    ) {
        val restoreOwner =
            policy.close(
                replyTarget = controller.replyingTo?.messageIdHex,
                editTarget = controller.editingMessageId,
                selectionMode = suppressRestore,
            )
        if (suppressRestore) {
            keyboardController?.hide()
            releaseBackdrop()
        } else if (restoreOwner != null) {
            val focusTarget =
                when (restoreOwner) {
                    MessageMenuKeyboardOwner.Search ->
                        navigationState.searchFocusRequester.takeIf { navigationState.searchOpen }
                    MessageMenuKeyboardOwner.Composer -> composerFocus.takeUnless { navigationState.searchOpen }
                }
            if (
                focusTarget != null &&
                runCatching { focusTarget.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
            ) {
                keyboardController?.show()
            } else {
                backdropReturning = true
            }
            // Failed focus starts the continuous return immediately.
        }
    }
}

/**
 * Runs the message-menu keyboard handoff for the conversation and returns a reader for the IME inset held
 * below the transcript, or null when the live IME owns it. Read it in layout so the hand-back animation
 * does not recompose the conversation. Pausing drops a pending restore and the open menu. A
 * closed menu restores the input it hid unless the route is leaving or selection took over, and the
 * held inset then follows the returning keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun conversationMenuImeBackdrop(
    handoff: ConversationMenuImeHandoff,
    openActionMenuIdState: MutableState<String?>,
    controller: ConversationController,
    navigationState: ConversationNavigationState,
    composerFocus: FocusRequester,
    routeTransitionInProgress: Boolean,
    transcriptRoutedAway: Boolean,
    suppressRestore: Boolean,
): (() -> Int)? {
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val menuIme = handoff.policy
    var openActionMenuId by openActionMenuIdState
    val displayedBottom =
        animatedMenuImeBackdrop(
            frozenBottom = handoff.backdropBottom,
            returning = handoff.backdropReturning,
            liveBottom = { imeInsets.getBottom(density) },
            onReleased = { handoff.releaseBackdrop() },
        )
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (openActionMenuId != null || menuIme.pendingRestore || handoff.backdropBottom != null) {
            menuIme.pause()
            openActionMenuId = null
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            handoff.releaseBackdrop()
        }
    }
    LaunchedEffect(openActionMenuId, routeTransitionInProgress, transcriptRoutedAway, suppressRestore) {
        if (routeTransitionInProgress || transcriptRoutedAway) {
            menuIme.cancel()
            handoff.releaseBackdrop()
        } else if (openActionMenuId == null) {
            handoff.restoreAfterClose(controller, suppressRestore, navigationState, composerFocus, keyboardController)
        }
    }
    val menuImeTargetInsets = WindowInsets.imeAnimationTarget
    LaunchedEffect(openActionMenuId, handoff.backdropBottom != null) {
        if (openActionMenuId == null && handoff.backdropBottom != null && !handoff.backdropReturning) {
            // The return may use a shorter keyboard. Hand geometry back at its actual
            // settled target, rather than waiting for the previous keyboard height.
            awaitMenuImeRestore(
                readInset = { imeInsets.getBottom(density) },
                readTargetInset = { menuImeTargetInsets.getBottom(density) },
                awaitFrame = { withFrameNanos { } },
            )
            // Settlement and timeout both enter a continuous handoff. Neither may
            // swap a partial snapshot straight to a different live keyboard height.
            handoff.backdropReturning = true
        }
    }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (menuIme.resumeRestoreCancelled) {
            scope.launch {
                // Let every resume observer consume the cancellation before a later
                // user focus can establish the next ordinary foreground session.
                withFrameNanos { }
                menuIme.resumeConsumed()
            }
        }
    }
    return displayedBottom
}

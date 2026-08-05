package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.ipf.whitenoise.android.ui.conversation.composer.shouldClearFocusOnResume
import dev.ipf.whitenoise.android.ui.conversation.composer.shouldRestoreComposerFocusOnResume

/**
 * Keeps the conversation's long-lived lifecycle observer wired to the latest
 * Compose focus ownership instead of the values captured when it was installed.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ConversationComposerLifecycleEffect(
    observerKey: Any?,
    lifecycleOwner: LifecycleOwner?,
    composerFocused: Boolean,
    searchOpen: Boolean,
    hasActiveEditOrReplySession: Boolean,
    onPause: () -> Unit,
    onResume: (restoreComposerFocus: Boolean, clearFocus: Boolean) -> Unit,
    onObserverDisposed: () -> Unit = {},
) {
    val currentComposerFocused by rememberUpdatedState(composerFocused)
    val currentSearchOpen by rememberUpdatedState(searchOpen)
    val currentHasActiveEditOrReplySession by rememberUpdatedState(hasActiveEditOrReplySession)
    val currentOnPause by rememberUpdatedState(onPause)
    val currentOnResume by rememberUpdatedState(onResume)

    // Disposal belongs to the observer key that installed it. Do not redirect it
    // to a replacement key's callback through rememberUpdatedState.
    DisposableEffect(observerKey, lifecycleOwner) {
        if (lifecycleOwner == null) {
            onDispose { }
        } else {
            var wasComposerFocusedOnPause = false
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> {
                            wasComposerFocusedOnPause = currentComposerFocused
                            currentOnPause()
                        }
                        Lifecycle.Event.ON_RESUME -> {
                            val restoreFocus =
                                shouldRestoreComposerFocusOnResume(
                                    wasComposerFocusedOnPause = wasComposerFocusedOnPause,
                                    hasActiveEditOrReplySession = currentHasActiveEditOrReplySession,
                                )
                            currentOnResume(
                                restoreFocus,
                                shouldClearFocusOnResume(
                                    restoringComposerFocus = restoreFocus,
                                    searchOpen = currentSearchOpen,
                                ),
                            )
                        }
                        else -> Unit
                    }
                }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                onObserverDisposed()
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }
}

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerAutoReadToggle

/** Keeps the per-chat speaker in the header, independently of typing and dictation controls. */
@Composable
@Suppress("FunctionNaming")
internal fun ConversationAutoReadAction(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
) {
    val preferences by appState.ttsAutoReadPreferences.state.collectAsState()
    val accountRef = controller.boundAccountRef ?: return
    val groupId = controller.group.groupIdHex
    val checked =
        remember(preferences, accountRef, groupId) {
            appState.conversationAutoReadFor(accountRef, groupId)
        }
    val visible =
        remember(preferences, accountRef, groupId) {
            appState.ttsAutoReadPreferences.isComposerShortcutVisibleFor(accountRef, groupId)
        }
    val visibility = remember(accountRef, groupId) { MutableTransitionState(visible) }
    visibility.targetState = visible
    AnimatedVisibility(
        visibleState = visibility,
        enter = EnterTransition.None,
        exit = fadeOut(tween(durationMillis = 250, delayMillis = 350)),
    ) {
        ComposerAutoReadToggle(
            checked = checked,
            enabled = visible && appState.activeAccountRef == accountRef,
            onClick = {
                if (appState.activeAccountRef == accountRef) {
                    appState.setConversationAutoReadOverride(
                        groupId,
                        if (checked) TtsAutoReadOverride.OFF else TtsAutoReadOverride.ON,
                    )
                }
            },
        )
    }
}

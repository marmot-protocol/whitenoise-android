package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.ShellTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoffHost
import dev.ipf.whitenoise.android.ui.common.rememberMeasuredControlHandoff
import dev.ipf.whitenoise.android.ui.conversation.LocalShellPlaybackHost
import dev.ipf.whitenoise.android.ui.conversation.ShellPlaybackHost
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationNotificationNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationPersistentControl

/** The shell's single notice layout, using control ownership from this same navigation composition. */
@Composable
@Suppress("FunctionNaming", "LongParameterList", "LongMethod") // One shell layout owns notice and control slots.
internal fun MainShellNoticeLayout(
    notice: TransientNotice?,
    dictationController: ConversationDictationController,
    dictationComposerRoute: ConversationDictationComposerRoute,
    appLockScreenVisible: Boolean,
    persistentTopContent: @Composable () -> Unit = {},
    persistentTopContentConsumesStatusBars: Boolean = false,
    playbackHost: ShellPlaybackHost? = null,
    handoffOwner: Any? = null,
    handoffDestination: ComposerFabEndpoint? = null,
    content: @Composable (ConversationDictationControlOwner) -> Unit,
) {
    val dictationControlOwner =
        conversationDictationControlOwner(
            state = dictationController.state,
            route = dictationComposerRoute,
            appLockScreenVisible = appLockScreenVisible,
            recoveryHandedToComposer = dictationController.recoveryHandedToComposer,
        )
    val anchor = remember { FloatingDictationAnchor() }
    val handoff = rememberMeasuredControlHandoff(handoffOwner to appLockScreenVisible, handoffDestination)
    CompositionLocalProvider(
        LocalShellPlaybackHost provides playbackHost,
        LocalFloatingDictationAnchor provides anchor,
        LocalComposerFabHandoff provides handoff,
    ) {
        MeasuredControlHandoffHost(
            state = handoff,
            destination = handoffDestination,
            allowed = !appLockScreenVisible && handoffDestination != null,
        ) {
            FloatingDictationLayout(
                anchor = anchor,
                content = {
                    ShellTransientNoticeLayout(
                        notice = notice,
                        persistentTopContent = persistentTopContent,
                        persistentTopContentConsumesStatusBars = persistentTopContentConsumesStatusBars,
                        persistentBottomContent = {
                            if (!appLockScreenVisible) {
                                if (dictationController.hasDurableSession &&
                                    dictationController.completionControlsRequired
                                ) {
                                    Box(Modifier.floatingDictationAnchor()) {
                                        ConversationDictationNotificationNotice()
                                    }
                                }
                            }
                        },
                        content = { content(dictationControlOwner) },
                    )
                },
                control = {
                    if (dictationControlOwner == ConversationDictationControlOwner.Persistent) {
                        ConversationDictationPersistentControl(
                            state = dictationController.state,
                            controller = dictationController,
                        )
                    }
                },
            )
        }
    }
}

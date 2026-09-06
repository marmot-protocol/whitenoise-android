package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.ShellTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationNotificationNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationPersistentControl

/** The shell's single notice layout, using control ownership from this same navigation composition. */
@Composable
@Suppress("FunctionNaming", "LongParameterList") // One shell layout owns notice and control slots.
internal fun MainShellNoticeLayout(
    notice: TransientNotice?,
    dictationController: ConversationDictationController,
    dictationComposerRoute: ConversationDictationComposerRoute,
    appLockScreenVisible: Boolean,
    persistentTopContent: @Composable () -> Unit = {},
    persistentTopContentConsumesStatusBars: Boolean = false,
    content: @Composable (ConversationDictationControlOwner) -> Unit,
) {
    val dictationControlOwner =
        conversationDictationControlOwner(
            state = dictationController.state,
            route = dictationComposerRoute,
            appLockScreenVisible = appLockScreenVisible,
        )
    ShellTransientNoticeLayout(
        notice = notice,
        persistentTopContent = persistentTopContent,
        persistentTopContentConsumesStatusBars = persistentTopContentConsumesStatusBars,
        persistentBottomContent = {
            if (!appLockScreenVisible) {
                if (dictationController.hasDurableSession) ConversationDictationNotificationNotice()
                if (dictationControlOwner == ConversationDictationControlOwner.Persistent) {
                    ConversationDictationPersistentControl(
                        state = dictationController.state,
                        controller = dictationController,
                        modifier = Modifier.navigationBarsPadding(),
                    )
                }
            }
        },
        content = { content(dictationControlOwner) },
    )
}

@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.LocalTopNoticeScope

/**
 * What an open conversation gives everything drawn inside it, including the group details and modals it opens: the
 * slot its own banners render in, and MDK's history-import state for [groupIdHex] of [accountRef].
 */
@Composable
internal fun ConversationScope(
    appState: WhiteNoiseAppState,
    accountRef: String,
    groupIdHex: String,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTopNoticeScope provides ConversationNoticeDestination(accountRef, groupIdHex),
        LocalChatHistoryImport provides rememberChatHistoryImport(appState, accountRef, groupIdHex),
        content = content,
    )
}

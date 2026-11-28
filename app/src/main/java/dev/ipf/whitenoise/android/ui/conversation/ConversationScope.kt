@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidedValue
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.LocalTopNoticeScope

/**
 * What an open conversation gives everything drawn inside it, including the group details and modals it opens: the
 * slot its own banners render in, and MDK's history-import state for [groupIdHex] of [accountRef]. [extra] carries
 * whatever else the caller provides to the same subtree.
 */
@Composable
internal fun ConversationScope(
    appState: WhiteNoiseAppState,
    accountRef: String,
    groupIdHex: String,
    vararg extra: ProvidedValue<*>,
    content: @Composable () -> Unit,
) {
    // Someone who opens a conversation expects replies, so this is a moment to ask for notifications (#4278).
    LaunchedEffect(accountRef, groupIdHex) { appState.noteNotificationMoment() }
    CompositionLocalProvider(
        LocalTopNoticeScope provides ConversationNoticeDestination(accountRef, groupIdHex),
        LocalChatHistoryImport provides rememberChatHistoryImport(appState, accountRef, groupIdHex),
        *extra,
        content = content,
    )
}

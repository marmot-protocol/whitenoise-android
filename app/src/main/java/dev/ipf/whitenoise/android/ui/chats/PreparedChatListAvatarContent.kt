package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatListAvatarDomain
import dev.ipf.whitenoise.android.ui.common.PreparedVisibleGroupAvatarContent
import dev.ipf.whitenoise.android.ui.common.RetainedGroupAvatarPreparation
import dev.ipf.whitenoise.android.ui.common.chatRowCompatibilityAvatarSelection

/** Connects chat-row avatar selection and compatibility sources to the visible list. */
@Composable
@Suppress("FunctionNaming")
internal fun PreparedChatListAvatarContent(
    appState: WhiteNoiseAppState,
    rows: List<ChatListItem>,
    listState: LazyListState,
    rowKey: (ChatListItem) -> String,
    accountRef: String?,
    surfaceIdentity: Any,
    retainedPreparation: RetainedGroupAvatarPreparation?,
    content: @Composable () -> Unit,
) {
    PreparedVisibleGroupAvatarContent(
        appState = appState,
        rows = rows,
        listState = listState,
        rowKey = rowKey,
        assetForRow = { it.selectedAvatarAsset },
        accountRef = accountRef,
        surfaceIdentity = surfaceIdentity,
        retainedPreparation = retainedPreparation,
        compatibilityForRow = { chatRowCompatibilityAvatarSelection(it, accountRef, appState::avatarUrl) },
        domainForRow = { chatListAvatarDomain(it, accountRef) },
        content = content,
    )
}

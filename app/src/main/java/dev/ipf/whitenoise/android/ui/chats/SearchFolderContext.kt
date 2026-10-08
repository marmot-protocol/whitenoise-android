package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatFolderSource

internal data class SearchFolderContext(
    val folders: List<ChatFolder>,
    val resolveChatIds: (String) -> Set<String>,
)

/** All message-search entries use the existing folder membership and smart-rule projection. */
@Composable
internal fun rememberSearchFolderContext(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    titleCopy: GroupTitleCopy,
): SearchFolderContext {
    val storeState by appState.chatFolderPreferences.state.collectAsState()
    val account = controller.boundAccountRef
    val folders = remember(storeState, account) { account?.let(appState.chatFolderPreferences::foldersFor).orEmpty() }
    val resolve: (String) -> Set<String> =
        remember(storeState, account, controller.items, controller.archivedItems, titleCopy, appState.profileRevisionForCompose) {
            { folderId ->
                account
                    ?.let { owner ->
                        val rule = appState.chatFolderPreferences.folderRule(owner, folderId)
                        val items = chatFolderSource(rule, controller.items, controller.archivedItems)
                        val mutedIds = items.filter { it.engineMuted() }.mapTo(mutableSetOf()) { it.group.groupIdHex }
                        chatFolderChatIds(
                            items = items,
                            manualChatIds = appState.chatFolderPreferences.membershipFor(owner, folderId),
                            excludedChatIds = appState.chatFolderPreferences.excludedChats(owner, folderId),
                            rule = rule,
                            activeAccountIdHex = controller.boundAccountIdHex(),
                            isMuted = { it in mutedIds },
                            displayTitle = { chatListItemDisplayTitle(it, appState, titleCopy) },
                        )
                    }.orEmpty()
            }
        }
    return SearchFolderContext(folders, resolve)
}

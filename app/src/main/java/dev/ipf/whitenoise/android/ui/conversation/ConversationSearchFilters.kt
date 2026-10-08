package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.canonicalChatListGroupId
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchAccountScope
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchChatFilter
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchFilterOptions
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchFilterScope
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchFolderOption
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchState
import dev.ipf.whitenoise.android.ui.chats.SearchFolderContext
import dev.ipf.whitenoise.android.ui.chats.applyGlobalSearchChatScope
import dev.ipf.whitenoise.android.ui.chats.chatFolderDisplayName
import dev.ipf.whitenoise.android.ui.chats.globalSearchScopedChats
import dev.ipf.whitenoise.android.ui.chats.rememberGlobalSearchFilterOptions
import dev.ipf.whitenoise.android.ui.chats.rememberSearchFolderContext
import dev.ipf.whitenoise.android.ui.chats.restrictToChatIds

internal fun conversationSearchPreset(
    accountRef: String?,
    runtimeGeneration: Int,
    chatId: String,
    title: String,
): GlobalSearchState =
    GlobalSearchState(
        isOpen = true,
        accountScopeToken = GlobalSearchAccountScope.from(accountRef, runtimeGeneration).encodeToken(),
        chatFilters = setOf(GlobalSearchChatFilter(canonicalChatListGroupId(chatId), title)),
    )

/** A single explicitly selected chat keeps its exhaustive navigator; broader scopes use the existing list. */
internal fun keepsConversationSearchNavigator(
    state: GlobalSearchState,
    chatId: String,
): Boolean = state.chatFilters.map { canonicalChatListGroupId(it.stableId) }.toSet() == setOf(canonicalChatListGroupId(chatId))

internal data class ConversationSearchControls(
    val options: GlobalSearchFilterOptions,
    val folderNames: Map<String, String>,
    val includesConversation: Boolean,
)

/** Reuses home pickers and membership resolution, bound to the conversation's actual account. */
@Composable
internal fun rememberConversationSearchControls(
    appState: WhiteNoiseAppState,
    chatsController: ChatsController?,
    chat: ChatListItem,
    accountRef: String?,
    selfId: String?,
    titleCopy: GroupTitleCopy,
    state: GlobalSearchState,
): ConversationSearchControls {
    val ownedController = chatsController?.takeIf { it.boundAccountRef == accountRef && accountRef != null }
    val folders =
        if (ownedController != null) {
            rememberSearchFolderContext(appState, ownedController, titleCopy)
        } else {
            SearchFolderContext(emptyList()) { emptySet() }
        }
    // A notification can open a valid conversation before the list projects it.
    val source = (ownedController?.let { it.items + it.archivedItems }.orEmpty() + chat).distinctBy { it.id }
    val folderIds = state.folderFilters.takeIf { it.isNotEmpty() }?.flatMapTo(mutableSetOf()) { folders.resolveChatIds(it) }
    val choices = restrictToChatIds(applyGlobalSearchChatScope(source, state.chatTypeFilters, emptySet()), folderIds)
    val scoped = globalSearchScopedChats(source, state, folderIds)
    LaunchedEffect(ownedController, state.isOpen, scoped) {
        if (state.isOpen) ownedController?.requestMemberSnapshots(scoped.map { it.group.groupIdHex })
    }
    val folderOptions = folders.folders.map { GlobalSearchFolderOption(it.id, chatFolderDisplayName(it)) }
    val options =
        rememberGlobalSearchFilterOptions(
            appState,
            GlobalSearchFilterScope(
                folders = folderOptions,
                chatChoices = choices,
                senderChats = scoped,
                titleCopy = titleCopy,
                selfId = selfId,
                selfLabel = stringResource(R.string.you),
                accountRef = accountRef,
                accountScope = state.accountScopeToken,
            ),
            enabled = state.isOpen,
        )
    return ConversationSearchControls(
        options,
        folderOptions.associate { it.id to it.name },
        scoped.any { canonicalChatListGroupId(it.id) == canonicalChatListGroupId(chat.id) },
    )
}

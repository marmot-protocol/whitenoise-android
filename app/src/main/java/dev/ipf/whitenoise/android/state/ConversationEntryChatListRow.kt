package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListRowFfi

/**
 * The chat-list row a newly opened conversation starts from. The open item can be a copy taken
 * while the list was debounced or hidden, before newer messages arrived; the same account's
 * backing row is current, so the unread line freezes at the boundary the user actually reached.
 */
internal fun conversationEntryChatListRow(
    openChat: ChatListItem,
    chatsController: ChatsController,
    accountRef: String,
): ChatListRowFfi? =
    chatsController
        .takeIf { it.accountRef == accountRef }
        ?.let { it.chatRowsByGroup[it.chatRowKey(openChat.group.groupIdHex)] }
        ?: openChat.projection

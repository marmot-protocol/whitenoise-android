package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.ui.chats.ChatListReturnHead

/**
 * One-shot chat-list return-head provenance for issue #1313. The visible filtered
 * active-list head is armed only when leaving ChatsScreen via a list-origin open
 * (direct row or profile sheet presented from the list). Every other navigation
 * path must clear or replace any prior armed head so a stale snapshot cannot
 * leak across unrelated opens.
 */
internal sealed class ChatListReturnHeadSnapState {
    data object Unarmed : ChatListReturnHeadSnapState()

    data class Profile(
        val head: ChatListReturnHead,
    ) : ChatListReturnHeadSnapState()

    data class Conversation(
        val head: ChatListReturnHead,
    ) : ChatListReturnHeadSnapState()

    data class Published(
        val head: ChatListReturnHead,
    ) : ChatListReturnHeadSnapState()
}

/** Profile sheet presented from the chat list; binds the filtered visible head. */
internal fun presentProfileFromChatList(
    state: ChatListReturnHeadSnapState,
    visibleActiveListHead: ChatListReturnHead?,
): ChatListReturnHeadSnapState =
    if (visibleActiveListHead != null) {
        ChatListReturnHeadSnapState.Profile(visibleActiveListHead)
    } else {
        ChatListReturnHeadSnapState.Unarmed
    }

/** Profile sheet dismissed without opening a conversation. */
internal fun dismissChatListProfile(state: ChatListReturnHeadSnapState): ChatListReturnHeadSnapState =
    if (state is ChatListReturnHeadSnapState.Profile) {
        ChatListReturnHeadSnapState.Unarmed
    } else {
        state
    }

/** Direct conversation open from ChatsScreen with the filtered visible head. */
internal fun openGroupFromChatList(
    state: ChatListReturnHeadSnapState,
    visibleActiveListHead: ChatListReturnHead?,
): ChatListReturnHeadSnapState =
    if (visibleActiveListHead != null) {
        ChatListReturnHeadSnapState.Conversation(visibleActiveListHead)
    } else {
        ChatListReturnHeadSnapState.Unarmed
    }

/**
 * Conversation open from a profile sheet. Transfers a list-bound profile head,
 * or clears provenance when the sheet was not armed from the chat list.
 */
internal fun openGroupFromProfileSheet(state: ChatListReturnHeadSnapState): ChatListReturnHeadSnapState =
    when (state) {
        is ChatListReturnHeadSnapState.Profile -> ChatListReturnHeadSnapState.Conversation(state.head)
        else -> ChatListReturnHeadSnapState.Unarmed
    }

/**
 * Conversation open after Create Group completes (issue #3529). A create started from
 * the chat list binds [flowOriginHead], the head the list showed when the flow opened —
 * the list is not composed behind the flow, so its keyed scroll anchor still holds that
 * row while the new group lands above it. Without one, a profile-sheet head transfers
 * and any other provenance clears.
 */
internal fun openCreatedGroup(
    state: ChatListReturnHeadSnapState,
    flowOriginHead: ChatListReturnHead?,
): ChatListReturnHeadSnapState =
    if (flowOriginHead != null) {
        openGroupFromChatList(state, flowOriginHead)
    } else {
        openGroupFromProfileSheet(state)
    }

internal fun resetChatListReturnHeadSnap(): ChatListReturnHeadSnapState = ChatListReturnHeadSnapState.Unarmed

/**
 * Chat list became visible again after [ChatsController.setChatListVisible](true).
 * Publishes the armed conversation head once.
 */
internal fun onChatListBecameVisible(state: ChatListReturnHeadSnapState): ChatListReturnHeadSnapState =
    when (state) {
        is ChatListReturnHeadSnapState.Conversation -> ChatListReturnHeadSnapState.Published(state.head)
        else -> state
    }

/** ChatsScreen consumed the one-shot published head. */
internal fun onConversationReturnHeadHandled(state: ChatListReturnHeadSnapState): ChatListReturnHeadSnapState =
    when (state) {
        is ChatListReturnHeadSnapState.Published -> ChatListReturnHeadSnapState.Unarmed
        else -> state
    }

/** The head published once for ChatsScreen's return snap, or null outside the Published state. */
internal fun publishedConversationReturnHead(state: ChatListReturnHeadSnapState): ChatListReturnHead? =
    when (state) {
        is ChatListReturnHeadSnapState.Published -> state.head
        else -> null
    }

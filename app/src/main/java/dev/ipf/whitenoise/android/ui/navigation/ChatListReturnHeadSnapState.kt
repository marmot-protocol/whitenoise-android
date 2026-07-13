package dev.ipf.whitenoise.android.ui.navigation

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
        val head: String,
    ) : ChatListReturnHeadSnapState()

    data class Conversation(
        val head: String,
    ) : ChatListReturnHeadSnapState()

    data class Published(
        val head: String,
    ) : ChatListReturnHeadSnapState()
}

/** Profile sheet presented from the chat list; binds the filtered visible head. */
internal fun presentProfileFromChatList(
    state: ChatListReturnHeadSnapState,
    visibleActiveListHeadId: String?,
): ChatListReturnHeadSnapState =
    if (visibleActiveListHeadId != null) {
        ChatListReturnHeadSnapState.Profile(visibleActiveListHeadId)
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
    visibleActiveListHeadId: String?,
): ChatListReturnHeadSnapState =
    if (visibleActiveListHeadId != null) {
        ChatListReturnHeadSnapState.Conversation(visibleActiveListHeadId)
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

internal fun publishedConversationReturnHead(state: ChatListReturnHeadSnapState): String? = (state as? ChatListReturnHeadSnapState.Published)?.head

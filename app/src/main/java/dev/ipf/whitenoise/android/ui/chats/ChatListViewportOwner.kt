package dev.ipf.whitenoise.android.ui.chats

/**
 * Who a chat-list scroll position belongs to.
 *
 * A viewport only means something for the account that scrolled it, the runtime generation it was
 * scrolled in, and the list — active or archived — it was scrolled within. Rows are keyed by group
 * id, and two local accounts in the same group expose the same key, so row identity can never be
 * what resets a position; this ownership has to.
 */
internal data class ChatListViewportOwner(
    val accountRef: String?,
    val runtimeGeneration: Int,
    val showArchived: Boolean,
)

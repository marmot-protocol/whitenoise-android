package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.state.ChatListActionOwner
import dev.ipf.whitenoise.android.state.ChatListBatchCommand
import dev.ipf.whitenoise.android.state.ChatListSelectAllScope

/**
 * Immutable selection captured for one batch; [followScope] is the viewport a PIN batch may return to the top.
 * [completeScope] marks a complete Select all whose IDs are natively revalidated before the first attempt (#2925).
 */
internal class PendingChatListBatch(
    val ids: List<String>,
    val command: ChatListBatchCommand,
    val owner: ChatListActionOwner,
    val folderId: String? = null,
    val followScope: ChatPinFollowScope? = null,
    val completeScope: ChatListSelectAllScope? = null,
)

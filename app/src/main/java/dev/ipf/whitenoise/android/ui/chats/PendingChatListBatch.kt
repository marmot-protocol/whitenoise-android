package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.whitenoise.android.state.ChatListActionOwner
import dev.ipf.whitenoise.android.state.ChatListBatchCommand

/**
 * Immutable selection captured for one batch; [followScope] is the viewport a PIN batch may return to the top.
 * [completeView] marks a complete Select all whose IDs are natively revalidated before the first attempt (#2925).
 */
internal class PendingChatListBatch(
    val ids: List<String>,
    val command: ChatListBatchCommand,
    val owner: ChatListActionOwner,
    val folderId: String? = null,
    val followScope: ChatPinFollowScope? = null,
    val completeView: ChatListViewFfi? = null,
)

package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.state.ChatListActionOwner
import dev.ipf.whitenoise.android.state.ChatListBatchCommand

/** Immutable selection captured for one batch; [followScope] is the viewport a PIN batch may return to the top. */
internal class PendingChatListBatch(
    val ids: List<String>,
    val command: ChatListBatchCommand,
    val owner: ChatListActionOwner,
    val folderId: String? = null,
    val followScope: ChatPinFollowScope? = null,
)

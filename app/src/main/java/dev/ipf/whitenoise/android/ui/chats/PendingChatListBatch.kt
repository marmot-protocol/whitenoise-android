package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.state.ChatListActionOwner
import dev.ipf.whitenoise.android.state.ChatListBatchCommand

internal class PendingChatListBatch(
    val ids: List<String>,
    val command: ChatListBatchCommand,
    val owner: ChatListActionOwner,
    val folderId: String? = null,
)

package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatsSubscription
import dev.ipf.marmotkit.PresentedChatListSubscription
import dev.ipf.marmotkit.PresentedChatListUpdateFfi

/** Lifecycle seam for the authoritative chat-list projection stream. */
internal interface ChatListSubscriptionHandle {
    /** Returns the complete first frame captured when this handle opened. */
    fun snapshot(): PresentedChatListUpdateFfi?

    /** Waits for the next authoritative presented-list frame. */
    suspend fun nextUpdate(): PresentedChatListUpdateFfi?

    /** Releases this handle and unblocks any pending update read. */
    fun close()
}

/** Lifecycle seam for the matching group-record stream. */
internal interface ChatsSubscriptionHandle {
    /** Returns the matching initial group-record snapshot. */
    fun snapshot(): List<AppGroupRecordFfi>

    /** Waits for the next group-record update. */
    suspend fun next(): AppGroupRecordFfi?

    /** Releases this handle and unblocks any pending update read. */
    fun close()
}

/** Production adapter around MarmotKit's chat-list subscription. */
private class FfiChatListSubscriptionHandle(
    private val subscription: PresentedChatListSubscription,
) : ChatListSubscriptionHandle {
    /** Delegates the initial frame without changing native cursor identity. */
    override fun snapshot(): PresentedChatListUpdateFfi? = subscription.snapshot()

    /** Delegates the next-frame wait to MarmotKit. */
    override suspend fun nextUpdate(): PresentedChatListUpdateFfi? = subscription.next()

    /** Closes the underlying MarmotKit subscription. */
    override fun close() = subscription.close()
}

/** Production adapter around MarmotKit's group subscription. */
private class FfiChatsSubscriptionHandle(
    private val subscription: ChatsSubscription,
) : ChatsSubscriptionHandle {
    /** Delegates the initial group-record frame. */
    override fun snapshot(): List<AppGroupRecordFfi> = subscription.snapshot()

    /** Delegates the next group-record wait. */
    override suspend fun next(): AppGroupRecordFfi? = subscription.next()

    /** Closes the underlying MarmotKit subscription. */
    override fun close() = subscription.close()
}

/** Opens the paired streams consumed by [ChatsController]. */
internal class ChatListLiveSubscriptions(
    val openChatList: suspend (account: String, includeArchived: Boolean) -> ChatListSubscriptionHandle,
    val openChats: suspend (account: String, includeArchived: Boolean) -> ChatsSubscriptionHandle,
) {
    companion object {
        /** Binds the seam to the production MarmotKit runtime. */
        fun bind(appState: WhiteNoiseAppState): ChatListLiveSubscriptions =
            ChatListLiveSubscriptions(
                openChatList = { account, includeArchived ->
                    appState.marmotIo {
                        FfiChatListSubscriptionHandle(openPresentedChatList(account, includeArchived))
                    }
                },
                openChats = { account, includeArchived ->
                    appState.marmotIo {
                        FfiChatsSubscriptionHandle(subscribeChats(account, includeArchived))
                    }
                },
            )
    }
}

/** Test-only replacement points for both controller subscription families. */
internal data class LiveSubscriptionOverrides(
    var chatList: ChatListLiveSubscriptions? = null,
    var conversation: ConversationLiveSubscriptions? = null,
)

/** Resolves the test seam or production MarmotKit-backed subscriptions. */
internal fun WhiteNoiseAppState.chatListLiveSubscriptions(): ChatListLiveSubscriptions =
    liveSubscriptionOverrides.chatList ?: ChatListLiveSubscriptions.bind(this)

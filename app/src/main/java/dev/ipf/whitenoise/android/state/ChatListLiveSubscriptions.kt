package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscription
import dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi
import dev.ipf.marmotkit.ChatsSubscription

/** Lifecycle seam for the authoritative chat-list projection stream. */
internal interface ChatListSubscriptionHandle {
    fun snapshot(): List<ChatListRowFfi>

    suspend fun nextUpdate(): ChatListSubscriptionUpdateFfi?

    fun close()
}

/** Lifecycle seam for the matching group-record stream. */
internal interface ChatsSubscriptionHandle {
    fun snapshot(): List<AppGroupRecordFfi>

    suspend fun next(): AppGroupRecordFfi?

    fun close()
}

/** Production adapter around MarmotKit's chat-list subscription. */
private class FfiChatListSubscriptionHandle(
    private val subscription: ChatListSubscription,
) : ChatListSubscriptionHandle {
    override fun snapshot(): List<ChatListRowFfi> = subscription.snapshot()

    override suspend fun nextUpdate(): ChatListSubscriptionUpdateFfi? = subscription.nextUpdate()

    override fun close() = subscription.close()
}

/** Production adapter around MarmotKit's group subscription. */
private class FfiChatsSubscriptionHandle(
    private val subscription: ChatsSubscription,
) : ChatsSubscriptionHandle {
    override fun snapshot(): List<AppGroupRecordFfi> = subscription.snapshot()

    override suspend fun next(): AppGroupRecordFfi? = subscription.next()

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
                        FfiChatListSubscriptionHandle(subscribeChatList(account, includeArchived))
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

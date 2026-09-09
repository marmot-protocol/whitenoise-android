package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatsSubscription
import dev.ipf.marmotkit.PresentedChatListSubscription
import dev.ipf.marmotkit.PresentedChatListUpdateFfi

/** Lifecycle seam for the authoritative chat-list projection stream. */
internal interface ChatListSubscriptionHandle {
    fun snapshot(): PresentedChatListUpdateFfi?

    suspend fun nextUpdate(): PresentedChatListUpdateFfi?

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
    private val subscription: PresentedChatListSubscription,
) : ChatListSubscriptionHandle {
    override fun snapshot(): PresentedChatListUpdateFfi? = subscription.snapshot()

    override suspend fun nextUpdate(): PresentedChatListUpdateFfi? = subscription.next()

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

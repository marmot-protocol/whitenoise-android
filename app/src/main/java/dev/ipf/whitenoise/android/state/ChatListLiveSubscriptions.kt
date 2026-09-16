package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.ChatListWindowSubscription
import dev.ipf.marmotkit.ChatsSubscription

/** Rows requested when a chat-list window opens; MDK defaults to the same value and caps requests at 100. */
internal const val CHAT_LIST_WINDOW_INITIAL_ROWS: UInt = 50u

/** Rows requested per forward page; the window retains at most 200. */
internal const val CHAT_LIST_WINDOW_PAGE_ROWS: UInt = 50u

/** Lifecycle seam for one bounded live chat-list window bound to a single MDK view. */
internal interface ChatListWindowHandle {
    /** Returns the complete first replacement captured when this window opened; consumed once. */
    fun snapshot(): ChatListWindowSnapshotFfi?

    /** Waits for the next complete replacement, or null once the window is closed. */
    suspend fun next(): ChatListWindowSnapshotFfi?

    /** Extends the retained window from the installed [sequence] in [direction]. */
    suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ): ChatListWindowSnapshotFfi

    /** Reports the row the user actually sees so later replacements keep it in view. */
    suspend fun setVisibleAnchor(
        sequence: ULong,
        groupIdHex: String,
    ): ChatListWindowSnapshotFfi

    /** Returns the window to the top of the view and resumes following new activity. */
    suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi

    /** Releases this handle and unblocks any pending receive. */
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

/** Production adapter around MarmotKit's chat-list window. */
private class FfiChatListWindowHandle(
    private val subscription: ChatListWindowSubscription,
) : ChatListWindowHandle {
    /** Delegates the initial replacement without changing native cursor identity. */
    override fun snapshot(): ChatListWindowSnapshotFfi? = subscription.snapshot()

    /** Delegates the next-replacement wait to MarmotKit. */
    override suspend fun next(): ChatListWindowSnapshotFfi? = subscription.next()

    /** Delegates a page command; MarmotKit validates the sequence and row count. */
    override suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ): ChatListWindowSnapshotFfi = subscription.page(sequence, direction, count)

    /** Delegates the visible-anchor report. */
    override suspend fun setVisibleAnchor(
        sequence: ULong,
        groupIdHex: String,
    ): ChatListWindowSnapshotFfi = subscription.setVisibleAnchor(sequence, groupIdHex)

    /** Delegates the return-to-top command. */
    override suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi = subscription.returnToTop(sequence)

    /** Closes the underlying MarmotKit window; these handles expose no separate cancel. */
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
    val openChatListWindow: suspend (account: String, view: ChatListViewFfi) -> ChatListWindowHandle,
    val openChats: suspend (account: String, includeArchived: Boolean) -> ChatsSubscriptionHandle,
) {
    companion object {
        /** Binds the seam to the production MarmotKit runtime. */
        fun bind(appState: WhiteNoiseAppState): ChatListLiveSubscriptions =
            ChatListLiveSubscriptions(
                openChatListWindow = { account, view ->
                    appState.marmotIo {
                        FfiChatListWindowHandle(openChatListWindow(account, view, CHAT_LIST_WINDOW_INITIAL_ROWS))
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

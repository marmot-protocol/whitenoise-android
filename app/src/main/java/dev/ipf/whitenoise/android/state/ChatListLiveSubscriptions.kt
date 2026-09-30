package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.ChatListWindowSubscription
import dev.ipf.marmotkit.ChatsSubscription
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.PresentedChatListUpdateFfi
import dev.ipf.marmotkit.PresentedChatRowFfi

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

/**
 * MarmotKit's pre-0.10.0 presented chat list wearing the window seam. Every replacement is exposed as one
 * complete `CHATS` view that already contains archived rows, never has more to page, and ignores anchor
 * and return-to-top commands. It exists so a chat list still renders and stays live when the bounded
 * windows cannot open on an account, which happened on real accounts right after the 0.10.0 upgrade.
 */
internal class PresentedChatListWindowHandle(
    private val snapshotOnce: () -> PresentedChatListUpdateFfi?,
    private val nextUpdate: suspend () -> PresentedChatListUpdateFfi?,
    private val release: () -> Unit,
) : ChatListWindowHandle {
    private var latest: ChatListWindowSnapshotFfi? = null

    /** The initial presented frame as a whole-list `CHATS` window. */
    override fun snapshot(): ChatListWindowSnapshotFfi? = snapshotOnce()?.let(::asWindow)

    /** The next presented frame as a whole-list replacement. */
    override suspend fun next(): ChatListWindowSnapshotFfi? = nextUpdate()?.let(::asWindow)

    /** Nothing more is ever retained beyond the complete list, so paging returns the installed frame. */
    override suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ): ChatListWindowSnapshotFfi = installedFrame()

    /** The complete list needs no anchor; the installed frame is returned unchanged. */
    override suspend fun setVisibleAnchor(
        sequence: ULong,
        groupIdHex: String,
    ): ChatListWindowSnapshotFfi = installedFrame()

    /** The complete list is already at its top. */
    override suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi = installedFrame()

    /** Releases the presented-list subscription. */
    override fun close() = release()

    private fun installedFrame(): ChatListWindowSnapshotFfi = latest ?: throw MarmotKitException.ChatWindowClosed()

    private fun asWindow(update: PresentedChatListUpdateFfi): ChatListWindowSnapshotFfi =
        ChatListWindowSnapshotFfi(
            subscriptionGeneration = update.subscriptionGeneration,
            sequence = update.sequence,
            view = ChatListViewFfi.CHATS,
            rows = update.snapshot.rows,
            hasMoreBefore = false,
            hasMoreAfter = false,
            anchor = ChatListAnchorOutcomeFfi.Top,
        ).also { latest = it }
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
    /** Whole-list fallback used only when the bounded windows fail to open; null disables the fallback. */
    val openPresentedChatList: (suspend (account: String) -> ChatListWindowHandle)? = null,
    /** Keyed MDK read used only when a replacement omits an active row that should still fit in its window. */
    val presentedRowByGroup: (suspend (account: String, groupIdHex: String) -> PresentedChatRowFfi?)? = null,
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
                openPresentedChatList = { account ->
                    appState.marmotIo {
                        val subscription = openPresentedChatList(account, true)
                        PresentedChatListWindowHandle(
                            snapshotOnce = subscription::snapshot,
                            nextUpdate = subscription::next,
                            release = subscription::close,
                        )
                    }
                },
                presentedRowByGroup = { account, groupIdHex ->
                    appState.marmotIo { presentedChatListRow(account, groupIdHex) }
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

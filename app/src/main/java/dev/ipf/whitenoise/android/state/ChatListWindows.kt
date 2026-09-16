package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.PresentedChatRowFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** The window handle opened for each rendered view. */
private typealias OpenedWindows = Map<ChatListViewFfi, ChatListWindowHandle>

/** Each view's first complete replacement, consumed once when the set opens. */
private typealias InitialReplacements = Map<ChatListViewFfi, ChatListWindowSnapshotFfi>

/** The MDK chat-list views whose rows the app renders: active chats, archived chats and departed groups. */
internal val CHAT_LIST_WINDOW_VIEWS = listOf(ChatListViewFfi.CHATS, ChatListViewFfi.ARCHIVED, ChatListViewFfi.LEFT)

/**
 * One account's bounded live chat-list windows, merged into the single row set [ChatsController] renders.
 *
 * Each view owns a native handle, a [ChatListWindowCursor] and its newest installed replacement. A
 * command result and its stream echo are deduplicated by sequence, a foreign generation ends the
 * receive loop so the controller reopens every window, and a stale or outside-anchor command is
 * dropped in favour of the newest installed state instead of being repeated.
 */
internal class ChatListWindowSet private constructor(
    private val handles: Map<ChatListViewFfi, ChatListWindowHandle>,
    initial: Map<ChatListViewFfi, ChatListWindowSnapshotFfi>,
) {
    private val cursors = initial.mapValues { (_, snapshot) -> ChatListWindowCursor(snapshot) }
    private val installed = initial.toMutableMap()
    private val commands = Mutex()

    /** Every retained row across the merged views, in view order. */
    val rows: List<PresentedChatRowFfi>
        get() = CHAT_LIST_WINDOW_VIEWS.flatMap { view -> installed[view]?.rows.orEmpty() }

    /** Whether MDK retains rows beyond this view's window that a forward page can load. */
    fun hasMoreAfter(view: ChatListViewFfi): Boolean = installed[view]?.hasMoreAfter == true

    /** Newest installed replacement for [view], or null when the view is not open. */
    fun installed(view: ChatListViewFfi): ChatListWindowSnapshotFfi? = installed[view]

    /**
     * Runs one receive loop per view until any stream ends or reports a foreign generation, then
     * cancels the siblings and rethrows the first failure. [onReplacement] runs on the caller's
     * dispatcher after each newer replacement has been installed. Mirrors runUntilFirstLiveSubscriptionEnds:
     * any sibling failure is recorded and rethrown once.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun receive(onReplacement: suspend (ChatListViewFfi, ChatListWindowSnapshotFfi) -> Unit) {
        supervisorScope {
            val ended = CompletableDeferred<Unit>()
            val failure = AtomicReference<Throwable?>(null)
            val jobs =
                handles.map { (view, handle) ->
                    launch {
                        try {
                            receiveView(view, handle, onReplacement)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (throwable: Throwable) {
                            failure.compareAndSet(null, throwable)
                        } finally {
                            ended.complete(Unit)
                        }
                    }
                }
            ended.await()
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            failure.get()?.let { throw it }
        }
    }

    /** Loads the next page of [view] when MDK retains more rows; null when nothing newer was installed. */
    suspend fun pageForward(view: ChatListViewFfi): ChatListWindowSnapshotFfi? =
        command(view) { handle, sequence ->
            if (!hasMoreAfter(view)) return@command null
            handle.page(sequence, ChatListPageDirectionFfi.FORWARD, CHAT_LIST_WINDOW_PAGE_ROWS)
        }

    /** Reports the row the user sees so the window keeps it across replacements; null when unchanged. */
    suspend fun setVisibleAnchor(
        view: ChatListViewFfi,
        groupIdHex: String,
    ): ChatListWindowSnapshotFfi? = command(view) { handle, sequence -> handle.setVisibleAnchor(sequence, groupIdHex) }

    /** Returns [view] to the top of the list and resumes following new activity. */
    suspend fun returnToTop(view: ChatListViewFfi): ChatListWindowSnapshotFfi? {
        val result = command(view) { handle, sequence -> handle.returnToTop(sequence) }
        return result
    }

    /** Releases every native handle; these windows expose no separate cancel. */
    fun close() {
        handles.values.forEach { handle -> runCatching { handle.close() } }
    }

    private suspend fun receiveView(
        view: ChatListViewFfi,
        handle: ChatListWindowHandle,
        onReplacement: suspend (ChatListViewFfi, ChatListWindowSnapshotFfi) -> Unit,
    ) {
        val cursor = cursors.getValue(view)
        while (currentCoroutineContextIsActive()) {
            val update = withContext(Dispatchers.IO) { handle.next() } ?: return
            if (cursor.requiresReopen(update)) return
            if (install(view, update)) onReplacement(view, update)
        }
    }

    // A stale sequence or an anchor outside the retained rows carries no detail worth surfacing: the
    // contract is to reassess from the newest installed replacement, which the receive loop delivers.
    @Suppress("SwallowedException")
    private suspend fun command(
        view: ChatListViewFfi,
        block: suspend (ChatListWindowHandle, ULong) -> ChatListWindowSnapshotFfi?,
    ): ChatListWindowSnapshotFfi? =
        commands.withLock {
            val handle = handles[view] ?: return@withLock null
            val sequence = cursors.getValue(view).sequence
            val result =
                try {
                    withContext(Dispatchers.IO) { block(handle, sequence) }
                } catch (stale: MarmotKitException.ChatWindowStale) {
                    null
                } catch (outside: MarmotKitException.ChatWindowAnchorOutside) {
                    null
                }
            result?.takeIf { install(view, it) }
        }

    private fun install(
        view: ChatListViewFfi,
        update: ChatListWindowSnapshotFfi,
    ): Boolean {
        if (!cursors.getValue(view).accept(update)) return false
        installed[view] = update
        return true
    }

    companion object {
        /**
         * Opens every rendered view and consumes each initial replacement; closes all handles if any open
         * fails. When MarmotKit refuses the windows and [openFallback] is given, the set is built from that
         * single whole-list handle instead, so the account still gets a live chat list. The refusal is
         * logged as a release-safe marker because it is exactly the evidence a field report needs.
         */
        @Suppress("TooGenericExceptionCaught") // Any failure while opening must release the handles opened so far.
        suspend fun open(
            account: String,
            openFallback: (suspend (account: String) -> ChatListWindowHandle)? = null,
            openWindow: suspend (account: String, view: ChatListViewFfi) -> ChatListWindowHandle,
        ): ChatListWindowSet {
            val handles = LinkedHashMap<ChatListViewFfi, ChatListWindowHandle>()
            try {
                for (view in CHAT_LIST_WINDOW_VIEWS) handles[view] = openWindow(account, view)
                return ChatListWindowSet(handles, initialReplacements(handles))
            } catch (refused: MarmotKitException) {
                handles.values.forEach { handle -> runCatching { handle.close() } }
                val fallback = openFallback ?: throw refused
                val marker = releaseFailureMarker("CHAT_LIST_WINDOW_OPEN", refused)
                Log.e("DMChats", "$marker fallback=presented_list")
                val whole = linkedMapOf(ChatListViewFfi.CHATS to fallback(account))
                return ChatListWindowSet(whole, initialReplacements(whole))
            } catch (throwable: Throwable) {
                handles.values.forEach { handle -> runCatching { handle.close() } }
                throw throwable
            }
        }

        private suspend fun initialReplacements(handles: OpenedWindows): InitialReplacements =
            handles.mapValues { (_, handle) ->
                withContext(Dispatchers.IO) { handle.snapshot() }.requireChatListWindowSnapshot()
            }
    }
}

private suspend fun currentCoroutineContextIsActive(): Boolean = kotlinx.coroutines.currentCoroutineContext().isActive

/** Loads the next page of active chats when the list reaches its end; a no-op while nothing more is retained. */
suspend fun ChatsController.loadMoreChats(view: ChatListViewFfi = ChatListViewFfi.CHATS) {
    val windows = chatListWindows ?: return
    val account = accountRef ?: return
    if (windows.pageForward(view) != null) applyChatListWindowRows(account, windows.rows)
}

/** Reports the chat the user actually sees so window replacements keep it in place. */
suspend fun ChatsController.reportVisibleChat(
    groupIdHex: String,
    view: ChatListViewFfi = ChatListViewFfi.CHATS,
) {
    val windows = chatListWindows ?: return
    val account = accountRef ?: return
    if (windows.setVisibleAnchor(view, groupIdHex) != null) applyChatListWindowRows(account, windows.rows)
}

/** Returns the active list to its top after a scroll-to-top gesture. */
suspend fun ChatsController.returnChatListToTop(view: ChatListViewFfi = ChatListViewFfi.CHATS) {
    val windows = chatListWindows ?: return
    val account = accountRef ?: return
    if (windows.returnToTop(view) != null) applyChatListWindowRows(account, windows.rows)
}

/** Whether MDK retains more active chats than the window currently shows. */
fun ChatsController.hasMoreChats(view: ChatListViewFfi = ChatListViewFfi.CHATS): Boolean {
    val windows = chatListWindows ?: return false
    return windows.hasMoreAfter(view)
}

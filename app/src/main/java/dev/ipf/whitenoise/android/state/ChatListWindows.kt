package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.PresentedChatRowFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
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

/** Ends the current window set so the controller's bounded reconnect path obtains a new authoritative frame. */
internal class IncompleteChatListReplacement : IllegalStateException("incomplete active chat-list replacement")

internal fun requireCompleteChatListWindowRows(complete: Boolean) {
    if (!complete) throw IncompleteChatListReplacement()
}

/** A merged row snapshot bound to the replacement that produced it. */
internal data class ChatListFrame(
    val rows: List<PresentedChatRowFfi>,
    val revision: Long,
)

/**
 * One account's bounded live chat-list windows, merged into the single row set [ChatsController] renders.
 * The window owns the frame-revision guard as well as native handles and paging.
 *
 * Each view owns a native handle, a [ChatListWindowCursor] and its newest installed replacement. A
 * command result and its stream echo are deduplicated by sequence, a foreign generation ends the
 * receive loop so the controller reopens every window, and a stale or outside-anchor command is
 * dropped in favour of the newest installed state instead of being repeated.
 */
@Suppress("TooManyFunctions") // Native window operations and their atomic frame guard share one lifecycle.
internal class ChatListWindowSet private constructor(
    private val handles: Map<ChatListViewFfi, ChatListWindowHandle>,
    initial: Map<ChatListViewFfi, ChatListWindowSnapshotFfi>,
) {
    private val cursors = initial.mapValues { (_, snapshot) -> ChatListWindowCursor(snapshot) }
    private val installed = initial.toMutableMap()
    private val lifetime = ChatListWindowLifetime(handles)
    private val frameLock = lifetime.lock
    private var revision = 0L
    private val commands = Mutex()
    private val forwardStalled = mutableSetOf<ChatListViewFfi>()

    val closed: Boolean get() = lifetime.closed

    /** Every retained row across the merged views, in view order. */
    val rows: List<PresentedChatRowFfi>
        get() = frame().rows

    fun frame(): ChatListFrame =
        synchronized(frameLock) {
            ChatListFrame(CHAT_LIST_WINDOW_VIEWS.flatMap { view -> installed[view]?.rows.orEmpty() }, revision)
        }

    /** Prevents a callback suspended during validation from publishing over a newer view's frame. */
    fun publishIfCurrent(
        frame: ChatListFrame,
        publish: (List<PresentedChatRowFfi>) -> Unit,
    ): Boolean =
        synchronized(frameLock) {
            if (closed || frame.revision != revision) return@synchronized false
            publish(frame.rows)
            true
        }

    fun isCurrent(frame: ChatListFrame): Boolean = synchronized(frameLock) { !closed && frame.revision == revision }

    /** Whether MDK retains rows beyond this view's window that a forward page can load. */
    fun hasMoreAfter(view: ChatListViewFfi): Boolean = installed(view)?.hasMoreAfter == true

    /** Newest installed replacement for [view], or null when the view is not open. */
    fun installed(view: ChatListViewFfi): ChatListWindowSnapshotFfi? = synchronized(frameLock) { installed[view] }

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
                handles.keys.map { view ->
                    val job =
                        launch(start = CoroutineStart.LAZY) {
                            try {
                                receiveView(view, onReplacement)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (throwable: Throwable) {
                                failure.compareAndSet(null, throwable)
                            }
                        }
                    job.invokeOnCompletion {
                        lifetime.unregisterReceiver(job)
                        ended.complete(Unit)
                    }
                    if (lifetime.registerReceiver(job)) job.start() else job.cancel()
                    job
                }
            ended.await()
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            failure.get()?.let { throw it }
        }
    }

    /**
     * Loads the next page of [view] when MDK retains more rows; null when nothing newer was installed.
     *
     * A capped window can only move forward past the row it is anchored on, so a page issued before the
     * reader's settled row has been reported may come back with the same rows (#2926). Such a no-progress
     * page suspends forward demand for [view] until the next installed replacement, anchor report or
     * backward page supplies fresh state: edge demand is re-evaluated then, never re-issued blindly.
     */
    suspend fun pageForward(view: ChatListViewFfi): ChatListWindowSnapshotFfi? {
        val before = installed(view)?.takeIf { it.hasMoreAfter && !isForwardStalled(view) } ?: return null
        val result =
            command(view) { handle, sequence ->
                if (!hasMoreAfter(view)) return@command null
                handle.page(sequence, ChatListPageDirectionFfi.FORWARD, CHAT_LIST_WINDOW_PAGE_ROWS)
            }
        val after = installed(view)
        if (after != null && after.sequence != before.sequence && after.sameRowsAs(before)) markForwardStalled(view)
        return result
    }

    /** Whether a no-progress forward page has parked [view]'s edge demand until fresh state arrives. */
    fun isForwardStalled(view: ChatListViewFfi): Boolean = synchronized(frameLock) { view in forwardStalled }

    /** Parks [view]'s forward demand; the next installed replacement clears it. */
    private fun markForwardStalled(view: ChatListViewFfi) {
        synchronized(frameLock) { forwardStalled += view }
        chatsDebug { "chat window forward page made no progress view=$view, waiting for fresh state" }
    }

    /** Loads the preceding page of [view] when the retained window is no longer at the true top. */
    suspend fun pageBackward(view: ChatListViewFfi): ChatListWindowSnapshotFfi? =
        command(view) { handle, sequence ->
            if (installed(view)?.hasMoreBefore != true) return@command null
            handle.page(sequence, ChatListPageDirectionFfi.BACKWARD, CHAT_LIST_WINDOW_PAGE_ROWS)
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

    /** Retires immediately and cancels receive workers, including any callback running in one. */
    fun close() = lifetime.close()

    /** Waits for the finite native cleanup without delaying logical retirement. */
    suspend fun awaitReleased() = lifetime.awaitReleased()

    private suspend fun receiveView(
        view: ChatListViewFfi,
        onReplacement: suspend (ChatListViewFfi, ChatListWindowSnapshotFfi) -> Unit,
    ) {
        val cursor = cursors.getValue(view)
        while (!closed && currentCoroutineContextIsActive()) {
            val update = withContext(Dispatchers.IO) { lifetime.withHandle(view) { it.next() } } ?: return
            if (closed || cursor.requiresReopen(update)) return
            if (install(view, update)) onReplacement(view, update)
        }
    }

    // A stale sequence or an anchor outside the retained rows carries no detail worth surfacing: the
    // contract is to reassess from the newest installed replacement, which the receive loop delivers.
    // Native shutdown may precede Kotlin retirement. The receive loop owns reopening a closed
    // window; viewport commands have no result to publish and must not escape into UI effects.
    @Suppress("SwallowedException")
    private suspend fun command(
        view: ChatListViewFfi,
        block: suspend (ChatListWindowHandle, ULong) -> ChatListWindowSnapshotFfi?,
    ): ChatListWindowSnapshotFfi? =
        commands.withLock {
            val result =
                try {
                    withContext(Dispatchers.IO) {
                        lifetime.withHandle(view) { handle ->
                            val sequence = synchronized(frameLock) { cursors.getValue(view).sequence }
                            block(handle, sequence)
                        }
                    }
                } catch (stale: MarmotKitException.ChatWindowStale) {
                    null
                } catch (outside: MarmotKitException.ChatWindowAnchorOutside) {
                    null
                } catch (ended: MarmotKitException.ChatWindowClosed) {
                    null
                }
            result?.takeIf { install(view, it) }
        }

    /** Installs a newer same-generation replacement for [view] and bumps the frame revision; false for a duplicate. */
    private fun install(
        view: ChatListViewFfi,
        update: ChatListWindowSnapshotFfi,
    ): Boolean {
        val accepted =
            synchronized(frameLock) {
                if (closed || !cursors.getValue(view).accept(update)) return@synchronized false
                installed[view] = update
                // Any newer replacement is fresh state: the forward edge may be re-evaluated against it.
                forwardStalled -= view
                revision++
                true
            }
        if (!accepted) return false
        update.logWindowFrame(view, "replace")
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
                return openOrClose(whole) { ChatListWindowSet(whole, initialReplacements(whole)) }
            } catch (throwable: Throwable) {
                handles.values.forEach { handle -> runCatching { handle.close() } }
                throw throwable
            }
        }

        /** Runs [build] and closes every handle in [handles] if it fails, so a half-open set cannot leak. */
        @Suppress("TooGenericExceptionCaught") // Any failure must release the handles opened so far.
        private inline fun openOrClose(
            handles: OpenedWindows,
            build: () -> ChatListWindowSet,
        ): ChatListWindowSet =
            try {
                build()
            } catch (failure: Throwable) {
                handles.values.forEach { handle -> runCatching { handle.close() } }
                throw failure
            }

        private suspend fun initialReplacements(handles: OpenedWindows): InitialReplacements =
            handles.mapValues { (view, handle) ->
                withContext(Dispatchers.IO) { handle.snapshot() }.requireChatListWindowSnapshot().also { snapshot ->
                    snapshot.logWindowFrame(view, "initial")
                }
            }
    }
}

private suspend fun currentCoroutineContextIsActive(): Boolean = kotlinx.coroutines.currentCoroutineContext().isActive

/** Whether two replacements retain the same rows in the same order, ignoring per-row presentation. */
private fun ChatListWindowSnapshotFfi.sameRowsAs(other: ChatListWindowSnapshotFfi): Boolean =
    rows.size == other.rows.size &&
        rows.indices.all { index -> rows[index].row.groupIdHex == other.rows[index].row.groupIdHex }

internal const val CHAT_LIST_LOG_HASH_RADIX = 16

internal fun chatListLogHash(value: String): String = value.hashCode().toUInt().toString(CHAT_LIST_LOG_HASH_RADIX)

/** Debug-only numeric window diagnostics; no group IDs, titles, or message content. */
private fun ChatListWindowSnapshotFfi.logWindowFrame(
    view: ChatListViewFfi,
    phase: String,
) {
    chatsDebug {
        "chat window $phase view=$view generation=${chatListLogHash(subscriptionGeneration)} " +
            "sequence=$sequence rows=${rows.size} before=$hasMoreBefore after=$hasMoreAfter"
    }
}

/** Loads the next page of active chats when the list reaches its end; a no-op while nothing more is retained. */
suspend fun ChatsController.loadMoreChats(view: ChatListViewFfi = ChatListViewFfi.CHATS) {
    val windows = chatListWindows ?: return
    val account = accountRef ?: return
    if (windows.pageForward(view) != null) applyChatListWindowRows(account, windows)
}

/** Loads rows before the retained active window when the reader approaches its shifted front. */
suspend fun ChatsController.loadEarlierChats(view: ChatListViewFfi = ChatListViewFfi.CHATS) {
    val windows = chatListWindows ?: return
    val account = accountRef ?: return
    if (windows.pageBackward(view) != null) applyChatListWindowRows(account, windows)
}

/** Reports the chat the user actually sees so window replacements keep it in place. */
suspend fun ChatsController.reportVisibleChat(
    groupIdHex: String,
    view: ChatListViewFfi = ChatListViewFfi.CHATS,
) {
    val windows = chatListWindows ?: return
    val account = accountRef ?: return
    if (windows.setVisibleAnchor(view, groupIdHex) != null) applyChatListWindowRows(account, windows)
}

/** Returns the active list to its top after a scroll-to-top gesture. */
suspend fun ChatsController.returnChatListToTop(view: ChatListViewFfi = ChatListViewFfi.CHATS) {
    val windows = chatListWindows ?: return
    val account = accountRef ?: return
    if (windows.returnToTop(view) != null) applyChatListWindowRows(account, windows)
}

/** Whether MDK retains more active chats than the window currently shows. */
fun ChatsController.hasMoreChats(view: ChatListViewFfi = ChatListViewFfi.CHATS): Boolean {
    val windows = chatListWindows ?: return false
    return windows.hasMoreAfter(view)
}

/** Whether MDK retains active chats before the front of the currently rendered window. */
fun ChatsController.hasEarlierChats(view: ChatListViewFfi = ChatListViewFfi.CHATS): Boolean {
    val windows = chatListWindows ?: return false
    return windows.installed(view)?.hasMoreBefore == true
}

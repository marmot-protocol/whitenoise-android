package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListViewQueryFfi
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

/** The window handle opened for each rendered window. */
private typealias OpenedWindows = Map<ChatListWindowKey, ChatListWindowHandle>

/** Each window's first complete replacement, consumed once when the set opens. */
private typealias InitialReplacements = Map<ChatListWindowKey, ChatListWindowSnapshotFfi>

/** One window in a set: a plain view, or the selected folder's native query over that view (#3652). */
internal data class ChatListWindowKey(
    val view: ChatListViewFfi,
    val folder: Boolean = false,
)

/** The selected folder's native query and the opener for its windows, one per view the folder covers. */
internal class ChatFolderWindows(
    val query: ChatFolderQuery,
    val open: suspend (account: String, query: ChatListViewQueryFfi) -> ChatListWindowHandle,
)

/** The MDK chat-list views whose rows the app renders: active chats, archived chats and departed groups. */
internal val CHAT_LIST_WINDOW_VIEWS = listOf(ChatListViewFfi.CHATS, ChatListViewFfi.ARCHIVED, ChatListViewFfi.LEFT)

/** Ends the current window set so the controller's bounded reconnect path obtains a new authoritative frame. */
internal class IncompleteChatListReplacement : IllegalStateException("incomplete active chat-list replacement")

internal fun requireCompleteChatListWindowRows(complete: Boolean) {
    if (!complete) throw IncompleteChatListReplacement()
}

/** One window set's frame revision, naming which bounded window a published projection came from (#3555). */
internal data class ChatListFrameMark(
    val windows: ChatListWindowSet,
    val revision: Long,
)

/** A merged row snapshot bound to the replacement that produced it. */
internal data class ChatListFrame(
    val rows: List<PresentedChatRowFfi>,
    val revision: Long,
) {
    /**
     * These rows, then any row of the selected folder's [folder] windows they do not already hold, so a folder chat
     * beyond the plain windows still reaches every screen that reads the merged list. The revision stays this
     * frame's, since the plain set's guard decides what may publish.
     */
    fun withFolder(folder: ChatListWindowSet?): ChatListFrame {
        val extra = folder?.takeUnless { it.closed }?.frame()?.rows ?: return this
        val held = rows.mapTo(HashSet()) { it.row.groupIdHex }
        return copy(rows = rows + extra.filter { held.add(it.row.groupIdHex) })
    }
}

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
    private val handles: OpenedWindows,
    initial: InitialReplacements,
) {
    private val cursors = initial.mapValues { (_, snapshot) -> ChatListWindowCursor(snapshot) }
    private val installed = initial.toMutableMap()
    private val lifetime = ChatListWindowLifetime(handles)
    private val frameLock = lifetime.lock
    private var revision = 0L
    private val commands = Mutex()
    private val forwardStalled = mutableSetOf<ChatListWindowKey>()

    val closed: Boolean get() = lifetime.closed

    /** Every retained row across the merged views, in view order. */
    val rows: List<PresentedChatRowFfi>
        get() = frame().rows

    /** Advances whenever this set installs a newer replacement, so a captured frame can tell it is stale. */
    val currentRevision: Long get() = synchronized(frameLock) { revision }

    /** Every installed window's rows in view order, the plain views' or, for a folder set, the folder's. */
    fun frame(): ChatListFrame =
        synchronized(frameLock) {
            val keys = CHAT_LIST_WINDOW_VIEWS.flatMap { listOf(ChatListWindowKey(it), ChatListWindowKey(it, true)) }
            ChatListFrame(keys.flatMap { installed[it]?.rows.orEmpty() }, revision)
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
    fun hasMoreAfter(
        view: ChatListViewFfi,
        folder: Boolean = false,
    ): Boolean = installed(view, folder)?.hasMoreAfter == true

    /** Newest installed replacement for [view], or the folder's window over it, or null when it is not open. */
    fun installed(
        view: ChatListViewFfi,
        folder: Boolean = false,
    ): ChatListWindowSnapshotFfi? = synchronized(frameLock) { installed[ChatListWindowKey(view, folder)] }

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
                handles.keys.map { key ->
                    val job =
                        launch(start = CoroutineStart.LAZY) {
                            try {
                                receiveView(key, onReplacement)
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
     * page parks forward demand for [view]: the missing input is the anchor, so the next anchor report
     * completes the parked page itself (see [setVisibleAnchor]), while a backward page or a return to top
     * moves the window instead. Replacements that merely refresh the retained rows leave the park in
     * place, because they cannot make a forward page progress either.
     */
    suspend fun pageForward(
        view: ChatListViewFfi,
        folder: Boolean = false,
    ): ChatListWindowSnapshotFfi? {
        val key = ChatListWindowKey(view, folder)
        val before = installed(view, folder)?.takeIf { it.hasMoreAfter && !isForwardStalled(key) } ?: return null
        val result =
            command(key) { handle, sequence ->
                if (!hasMoreAfter(view, folder)) return@command null
                handle.page(sequence, ChatListPageDirectionFfi.FORWARD, CHAT_LIST_WINDOW_PAGE_ROWS)
            }
        val after = installed(view, folder)
        val noProgress = after != null && after.sequence != before.sequence && after.sameRowsAs(before)
        if (result != null && noProgress) markForwardStalled(key)
        return result
    }

    /** Whether a no-progress forward page has parked [view]'s edge demand until an anchor report arrives. */
    fun isForwardStalled(view: ChatListViewFfi): Boolean = isForwardStalled(ChatListWindowKey(view))

    /** Whether a no-progress forward page has parked [key]'s edge demand. */
    private fun isForwardStalled(key: ChatListWindowKey): Boolean = synchronized(frameLock) { key in forwardStalled }

    /** Parks [key]'s forward demand until a viewport command supplies the anchor it lacked. */
    private fun markForwardStalled(key: ChatListWindowKey) {
        synchronized(frameLock) { forwardStalled += key }
        chatsDebug { "chat window forward page made no progress window=$key, waiting for an anchor report" }
    }

    /** Lifts the park after a viewport command moved [key]; true when demand had been parked. */
    private fun unparkForward(key: ChatListWindowKey): Boolean = synchronized(frameLock) { forwardStalled.remove(key) }

    /** Loads the preceding page of [view] when the retained window is no longer at the true top. */
    suspend fun pageBackward(
        view: ChatListViewFfi,
        folder: Boolean = false,
    ): ChatListWindowSnapshotFfi? {
        val key = ChatListWindowKey(view, folder)
        return command(key) { handle, sequence ->
            if (installed(view, folder)?.hasMoreBefore != true) return@command null
            handle.page(sequence, ChatListPageDirectionFfi.BACKWARD, CHAT_LIST_WINDOW_PAGE_ROWS)
        }?.also { unparkForward(key) }
    }

    /**
     * Reports the row the user sees so the window keeps it across replacements; null when unchanged.
     *
     * When a forward page had parked for want of this anchor, the same report completes that page: the
     * reader was at the retained end when the page made no progress, and only an anchored position lets
     * a capped window move forward. One page follows one report, so a second no-progress answer parks
     * demand again instead of looping.
     */
    suspend fun setVisibleAnchor(
        view: ChatListViewFfi,
        groupIdHex: String,
        folder: Boolean = false,
    ): ChatListWindowSnapshotFfi? {
        val key = ChatListWindowKey(view, folder)
        val anchored =
            command(key) { handle, sequence -> handle.setVisibleAnchor(sequence, groupIdHex) } ?: return null
        val completed = if (unparkForward(key)) pageForward(view, folder) else null
        return completed ?: anchored
    }

    /** Returns [view] to the top of the list and resumes following new activity. */
    suspend fun returnToTop(
        view: ChatListViewFfi,
        folder: Boolean = false,
    ): ChatListWindowSnapshotFfi? {
        val key = ChatListWindowKey(view, folder)
        return command(key) { handle, sequence -> handle.returnToTop(sequence) }?.also { unparkForward(key) }
    }

    /** Retires immediately and cancels receive workers, including any callback running in one. */
    fun close() = lifetime.close()

    /** Waits for the finite native cleanup without delaying logical retirement. */
    suspend fun awaitReleased() = lifetime.awaitReleased()

    private suspend fun receiveView(
        key: ChatListWindowKey,
        onReplacement: suspend (ChatListViewFfi, ChatListWindowSnapshotFfi) -> Unit,
    ) {
        val cursor = cursors.getValue(key)
        while (!closed && currentCoroutineContextIsActive()) {
            val update = withContext(Dispatchers.IO) { lifetime.withHandle(key) { it.next() } } ?: return
            if (closed || cursor.requiresReopen(update)) return
            if (install(key, update)) onReplacement(key.view, update)
        }
    }

    // A stale sequence or an anchor outside the retained rows carries no detail worth surfacing: the
    // contract is to reassess from the newest installed replacement, which the receive loop delivers.
    // Native shutdown may precede Kotlin retirement. The receive loop owns reopening a closed
    // window; viewport commands have no result to publish and must not escape into UI effects.
    // Null therefore means the command was refused or issued nothing, never that it was answered.
    @Suppress("SwallowedException")
    private suspend fun command(
        key: ChatListWindowKey,
        block: suspend (ChatListWindowHandle, ULong) -> ChatListWindowSnapshotFfi?,
    ): ChatListWindowSnapshotFfi? =
        commands.withLock {
            val result =
                try {
                    withContext(Dispatchers.IO) {
                        lifetime.withHandle(key) { handle ->
                            val sequence = synchronized(frameLock) { cursors.getValue(key).sequence }
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
            // MDK publishes the answered frame to the stream before it replies, so the receive loop may
            // have installed this very frame already. The command still succeeded: callers re-apply the
            // installed rows, which is idempotent, and a parked forward page must not lose its anchor to it.
            // A result that arrives after retirement, or one older than the installed frame, is still dropped.
            result?.takeIf { install(key, it) || alreadyInstalled(key, it) }
        }

    /** Installs a newer same-generation replacement for [key] and bumps the frame revision; false for a duplicate. */
    private fun install(
        key: ChatListWindowKey,
        update: ChatListWindowSnapshotFfi,
    ): Boolean {
        val accepted =
            synchronized(frameLock) {
                if (closed || !cursors.getValue(key).accept(update)) return@synchronized false
                installed[key] = update
                revision++
                true
            }
        if (!accepted) return false
        update.logWindowFrame(key.view, if (key.folder) "folder replace" else "replace")
        return true
    }

    /** Whether [update] is the very frame the receive loop already installed for a live [key]. */
    private fun alreadyInstalled(
        key: ChatListWindowKey,
        update: ChatListWindowSnapshotFfi,
    ): Boolean =
        synchronized(frameLock) {
            val current = installed[key]?.takeUnless { closed } ?: return@synchronized false
            current.sequence == update.sequence && current.subscriptionGeneration == update.subscriptionGeneration
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
            val handles = LinkedHashMap<ChatListWindowKey, ChatListWindowHandle>()
            try {
                for (view in CHAT_LIST_WINDOW_VIEWS) handles[ChatListWindowKey(view)] = openWindow(account, view)
                return ChatListWindowSet(handles, initialReplacements(handles))
            } catch (refused: MarmotKitException) {
                handles.values.forEach { handle -> runCatching { handle.close() } }
                val fallback = openFallback ?: throw refused
                val marker = releaseFailureMarker("CHAT_LIST_WINDOW_OPEN", refused)
                Log.e("DMChats", "$marker fallback=presented_list")
                val whole = linkedMapOf(ChatListWindowKey(ChatListViewFfi.CHATS) to fallback(account))
                return openOrClose(whole) { ChatListWindowSet(whole, initialReplacements(whole)) }
            } catch (throwable: Throwable) {
                handles.values.forEach { handle -> runCatching { handle.close() } }
                throw throwable
            }
        }

        /**
         * Opens the selected folder's own windows, one per view it covers, with their first replacements. The set
         * lives beside the plain one, so opening, failing or closing it never touches the plain windows. Any failure
         * closes the handles opened so far and propagates, the caller decides whether to retry.
         */
        suspend fun openFolder(
            account: String,
            folder: ChatFolderWindows,
        ): ChatListWindowSet {
            val handles = LinkedHashMap<ChatListWindowKey, ChatListWindowHandle>()
            return openOrClose(handles) {
                for (view in folder.query.views) {
                    handles[ChatListWindowKey(view, folder = true)] = folder.open(account, folder.query.forView(view))
                }
                ChatListWindowSet(handles, initialReplacements(handles))
            }
        }

        /** One complete native stream replaces all bounded handles for an explicitly sorted folder. */
        suspend fun openComplete(
            account: String,
            open: suspend (String) -> ChatListWindowHandle,
        ): ChatListWindowSet {
            val handles = linkedMapOf(ChatListWindowKey(ChatListViewFfi.CHATS) to open(account))
            return openOrClose(handles) { ChatListWindowSet(handles, initialReplacements(handles)) }
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
            handles.mapValues { (key, handle) ->
                withContext(Dispatchers.IO) { handle.snapshot() }.requireChatListWindowSnapshot().also { snapshot ->
                    snapshot.logWindowFrame(key.view, if (key.folder) "folder initial" else "initial")
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

/**
 * Loads the next page of active chats when the list reaches its end; false when no page was issued or
 * nothing newer was installed, so the viewport need not wait for rows that are not coming.
 */
suspend fun ChatsController.loadMoreChats(view: ChatListViewFfi = ChatListViewFfi.CHATS): Boolean {
    val windows = chatListWindows
    val account = accountRef
    if (windows == null || account == null || windows.pageForward(view) == null) return false
    applyChatListWindowRows(account, windows)
    return true
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
    returnChatListToTopFrame(view)
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

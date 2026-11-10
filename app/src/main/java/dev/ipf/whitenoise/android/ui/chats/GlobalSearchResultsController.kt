package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.core.ChatListMessageSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one MDK read global message results make: a typed timeline page for the bound account. */
internal fun interface GlobalSearchTimelineReader {
    /** Reads one page of MDK's account-wide search. */
    suspend fun page(query: TimelineMessageQueryFfi): TimelinePageFfi
}

/** What the controller needs beyond its reader: local hides, the owner check, the clock and the I/O dispatcher. */
internal class GlobalSearchResultsEnvironment(
    val hiddenMessageIds: (groupIdHex: String) -> Set<String> = { emptySet() },
    val isCurrent: () -> Boolean = { true },
    val nowMillis: () -> Long = System::currentTimeMillis,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)

/**
 * Owns one account's global message-search session (#1944). There is exactly one request generation: a new query
 * cancels the batch in flight and restarts the reducer, and a late completion from the old generation, or from a
 * previous account or runtime ([GlobalSearchResultsEnvironment.isCurrent]), never reaches [state]. Each batch reads
 * at most four MDK pages / 200 rows and returns its last continuation. Nothing outlives the session.
 */
internal class GlobalSearchResultsController(
    private val reader: GlobalSearchTimelineReader,
    private val scope: CoroutineScope,
    internal val environment: GlobalSearchResultsEnvironment = GlobalSearchResultsEnvironment(),
) {
    /** What the screen renders for the current generation. */
    var state by mutableStateOf(GlobalSearchResultsState())
        private set

    /** The query the current generation answers, or null before the first search and after [close]. */
    var query: GlobalSearchResultsQuery? = null
        private set

    private var job: Job? = null
    private var closed = false

    /**
     * The loaded rows were last read before this session was detached from the screen, so a message deleted,
     * hidden or expired meanwhile may still be among them. The screen shows none of them until the first recheck
     * after the return lands; the holder sets it on detach (#1945).
     */
    var stale by mutableStateOf(false)

    /** The recheck of a [stale] session failed, so its unverified rows stay hidden behind a Retry. */
    var recheckFailed by mutableStateOf(false)
        private set

    private var refreshPending = false
    private var loadPending = false

    /**
     * Starts a generation for [next] unless it is already the current query. [debounceMillis] delays only the
     * first read, so typing does not read a page per keystroke.
     */
    fun search(
        next: GlobalSearchResultsQuery,
        debounceMillis: Long = 0L,
    ) {
        if (closed || (next == query && state.generation > 0)) return
        job?.cancel()
        refreshPending = false
        loadPending = false
        stale = false
        recheckFailed = false
        query = next
        val restarted = GlobalSearchResultsReducer.restart(state)
        // A scope with no chats cannot match, so it ends at once instead of reading the whole account.
        state = if (next.matchesNothing) restarted.copy(endReached = true) else restarted
        read(debounceMillis)
    }

    /** Reads the next bounded batch unless one is in flight or MDK reported the end. */
    fun loadMore() = read(debounceMillis = 0L)

    /** Rechecks a [stale] session again, or reads the failed batch again from its cursor; loaded rows stay. */
    fun retry() {
        if (stale) {
            recheckFailed = false
            refresh()
        } else {
            loadMore()
        }
    }

    /**
     * Rechecks the loaded rows after a change that can delete, expire, hide or add a match: a projection update,
     * a block change or a disappearing deadline. It rereads the loaded range from the head within a bounded
     * budget and replaces rows in place. A request while anything is in flight, the first page included, runs
     * once that read ends, since the read may already hold a row the change affects.
     */
    fun refresh() {
        val current = query?.takeUnless { closed } ?: return
        val started = GlobalSearchResultsReducer.beginRefresh(state)
        // Anything in flight, the first page included, may already hold a row a later change affects.
        if (state.inFlight != null) {
            refreshPending = true
        } else if (started != null) {
            val budget = refreshBatchBudget(state.results.size)
            state = started.first
            launch(current, started.second, debounceMillis = 0L, refreshBudget = budget)
        }
    }

    /**
     * Marks loaded rows [stale] when the screen leaves (#1945). Any read in flight or queued is dropped, since it
     * read the store before the reader went away and must not be what clears [stale]: only a recheck launched
     * after the return may. A page dropped here is read again from its cursor later.
     */
    fun detach() {
        if (state.batches == 0) return
        job?.cancel()
        refreshPending = false
        loadPending = false
        state.inFlight?.let { state = GlobalSearchResultsReducer.cancel(state, it) }
        stale = true
        recheckFailed = false
    }

    /** Cancels the batch in flight and fences every later completion. */
    fun close() {
        closed = true
        refreshPending = false
        loadPending = false
        job?.cancel()
        query = null
    }

    /** Starts one batch for the current generation, unless one is in flight or MDK reported the end. */
    private fun read(debounceMillis: Long) {
        val current = query?.takeUnless { closed } ?: return
        // A page or Retry asked for during a background refresh runs once the refresh lands, never dropped.
        if (state.inFlight?.refresh == true) loadPending = true
        val (next, request) = GlobalSearchResultsReducer.begin(state) ?: return
        state = next
        launch(current, request, debounceMillis, refreshBudget = 0)
    }

    /** Runs one request and applies it only while it is still current; a failed refresh keeps the rows. */
    @Suppress("TooGenericExceptionCaught") // Any read failure becomes the retryable boundary state.
    private fun launch(
        current: GlobalSearchResultsQuery,
        request: GlobalSearchPageRequest,
        debounceMillis: Long,
        refreshBudget: Int,
    ) {
        job =
            scope.launch {
                try {
                    if (debounceMillis > 0L) delay(debounceMillis)
                    val next = withContext(environment.ioDispatcher) { complete(current, request, refreshBudget) }
                    if (accepts(current)) {
                        state = next(state)
                        // A landed recheck verified every row the screen will show.
                        if (request.refresh) stale = false
                    }
                } catch (cancel: CancellationException) {
                    if (accepts(current)) state = GlobalSearchResultsReducer.cancel(state, request)
                    throw cancel
                } catch (_: Exception) {
                    // The failure kind is the whole outcome; the message never reaches the screen.
                    if (accepts(current)) {
                        state = failedTransition(state, request)
                        if (request.refresh && stale) recheckFailed = true
                    }
                }
                if (accepts(current)) continueAfter()
            }
    }

    /** Reads what [request] asks for and returns the transition that applies it to the then-current state. */
    private suspend fun complete(
        current: GlobalSearchResultsQuery,
        request: GlobalSearchPageRequest,
        refreshBudget: Int,
    ): (GlobalSearchResultsState) -> GlobalSearchResultsState =
        if (request.refresh) {
            val source = batchSource(reader, environment, current)
            val (rows, through) = rereadGlobalSearchRange(source, request.cursor, refreshBudget)
            val apply: (GlobalSearchResultsState) -> GlobalSearchResultsState = {
                GlobalSearchResultsReducer.applyRefresh(it, request, rows, through)
            }
            apply
        } else {
            val source = batchSource(reader, environment, current)
            val batch = fillGlobalSearchBatch(request.cursor, request.seenKeys, source)
            val apply: (GlobalSearchResultsState) -> GlobalSearchResultsState = {
                GlobalSearchResultsReducer.applyBatch(it, request, batch)
            }
            apply
        }

    /** Runs a refresh that arrived during the batch, else a page asked for during a refresh. */
    private fun continueAfter() {
        if (refreshPending) {
            refreshPending = false
            refresh()
        } else if (loadPending) {
            loadPending = false
            read(0L)
        }
    }

    /** Only the live query on an open, current session may change [state]. */
    private fun accepts(owner: GlobalSearchResultsQuery): Boolean = !closed && owner == query && environment.isCurrent()
}

/** A failed page waits for Retry; a failed background refresh just keeps the rows it had. */
private fun failedTransition(
    state: GlobalSearchResultsState,
    request: GlobalSearchPageRequest,
): GlobalSearchResultsState =
    if (request.refresh) {
        GlobalSearchResultsReducer.cancel(state, request)
    } else {
        GlobalSearchResultsReducer.applyFailure(state, request)
    }

/**
 * Keeps one account's global message-search session alive in the shell while the chat list is away (#1945): opening
 * a result disposes the chat list, and Back recomposes it on the same session, so the query, filters, loaded pages,
 * cursor and scroll position return as they were without reading those pages again. The session's own recheck
 * then rereads the loaded range quietly in the background, so a row that was deleted, hidden or expired while the
 * reader was away never comes back. A different account or runtime, leaving message results, or the shell's
 * account scope ending closes the held session; process death drops it, like the rest of the in-memory results.
 */
internal class GlobalSearchResultsHolder {
    private var owner: Any? = null
    private var held: GlobalSearchResultsController? = null
    private var scope: CoroutineScope? = null

    /** The held session for [owner], creating it with [create] on a scope the holder owns when there is none. */
    fun obtain(
        owner: Any,
        create: (CoroutineScope) -> GlobalSearchResultsController,
    ): GlobalSearchResultsController {
        val current = held?.takeIf { owner == this.owner }
        if (current != null) return current
        close()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val created = create(sessionScope)
        this.owner = owner
        held = created
        scope = sessionScope
        return created
    }

    /** Detaches the held session when the chat list leaves composition; see [GlobalSearchResultsController.detach]. */
    fun detach() {
        held?.detach()
    }

    /** Closes the held session and cancels its work. */
    fun close() {
        held?.close()
        scope?.cancel()
        held = null
        scope = null
        owner = null
    }
}

/**
 * Rereads from the head until the old continuation [through] (or MDK's end) is reached, or [budget] batches
 * were read. Returns the rows found and the continuation the reread stopped at.
 */
internal suspend fun <R> rereadGlobalSearchRange(
    source: GlobalSearchBatchSource<R>,
    through: GlobalSearchCursor?,
    budget: Int,
): Pair<List<GlobalSearchResult>, GlobalSearchCursor?> {
    val rows = LinkedHashMap<String, GlobalSearchResult>()
    var cursor: GlobalSearchCursor? = null
    var batches = 0
    do {
        val batch = fillGlobalSearchBatch(cursor, rows.keys.toSet(), source)
        batch.results.forEach { rows.putIfAbsent(it.key, it) }
        cursor = batch.nextCursor
        batches++
        val reached = cursor == null || (through != null && cursor <= through)
    } while (!reached && batches < budget)
    return rows.values.toList() to cursor
}

/** How a batch reads MDK and post-filters with the query's Android-owned checks. */
private fun batchSource(
    reader: GlobalSearchTimelineReader,
    environment: GlobalSearchResultsEnvironment,
    current: GlobalSearchResultsQuery,
): GlobalSearchBatchSource<TimelineMessageRecordFfi> {
    val hidden = HashMap<String, Set<String>>()
    val fanOut = current.fanOutGroups
    return GlobalSearchBatchSource(
        readPage = { cursor, limit ->
            if (fanOut != null) {
                readScopedPage(reader, current, fanOut, cursor, limit)
            } else {
                val page = reader.page(current.toFfi(cursor, limit))
                GlobalSearchRawPage(page.messages, page.hasMoreBefore)
            }
        },
        cursorOf = { it.globalSearchCursor() },
        admit = { record ->
            current.admit(
                record,
                { group -> hidden.getOrPut(group) { environment.hiddenMessageIds(group) } },
                environment.nowMillis(),
            )
        },
    )
}

/**
 * One merged page over a small chat scope: each chat is read directly with an equal share of [limit] (at least one
 * row each, so a read of n chats can exceed a [limit] below n by at most n - 1 rows), and the pages are merged into
 * account-search order. A per-chat MDK read pages by (time, message id) alone, so the frontier compares that pair:
 * only rows at or above the newest "last row" among chats that still have older rows are consumed — including its
 * same-second, same-id twins in other chats, which the next per-chat cursor would otherwise skip — and the rest
 * are read again next time. Every fetched row counts against the batch budget.
 */
private suspend fun readScopedPage(
    reader: GlobalSearchTimelineReader,
    current: GlobalSearchResultsQuery,
    groups: List<String>,
    cursor: GlobalSearchCursor?,
    limit: UInt,
): GlobalSearchRawPage<TimelineMessageRecordFfi> {
    val perChat = (limit.toInt() / groups.size).coerceAtLeast(1).toUInt()
    val pages = groups.map { group -> reader.page(current.toFfi(cursor, perChat, groupIdHex = group)) }
    val frontier =
        pages
            .filter { it.hasMoreBefore && it.messages.isNotEmpty() }
            .maxOfOrNull { page -> page.messages.minOf { it.chatCursor() } }
    val fetched = pages.flatMap { it.messages }
    val consumed = if (frontier == null) fetched else fetched.filter { it.chatCursor() >= frontier }
    return GlobalSearchRawPage(consumed, hasMoreBefore = frontier != null, rowsRead = fetched.size)
}

/** A row's position in a single-chat MDK read, which orders by time and message id without the group. */
private fun TimelineMessageRecordFfi.chatCursor(): GlobalSearchCursor =
    GlobalSearchCursor(timelineAt = timelineAt, messageIdHex = messageIdHex, groupIdHex = "")

/** Triggers that never change whether a loaded row is listed or what its snippet says. */
private val GLOBAL_SEARCH_INERT_TRIGGERS =
    setOf(
        TimelineUpdateTriggerFfi.REACTION_ADDED,
        TimelineUpdateTriggerFfi.REACTION_REMOVED,
        TimelineUpdateTriggerFfi.RECEIPT_CHANGED,
        TimelineUpdateTriggerFfi.REPLY_PREVIEW_CHANGED,
        TimelineUpdateTriggerFfi.AGENT_ACTIVITY,
        TimelineUpdateTriggerFfi.AGENT_OPERATION,
    )

/** Triggers through which a message not yet listed can become a match. */
private val GLOBAL_SEARCH_MATCHING_TRIGGERS =
    setOf(
        TimelineUpdateTriggerFfi.NEW_MESSAGE,
        TimelineUpdateTriggerFfi.MESSAGE_EDITED_OR_REPROJECTED,
        TimelineUpdateTriggerFfi.AGENT_STREAM_FINISHED,
        TimelineUpdateTriggerFfi.SNAPSHOT_REFRESH,
    )

/**
 * Triggers that rewrite or remove a row that already existed, which a read in flight may be holding. MDK also
 * reprojects existing rows as a snapshot refresh, for a moderator deletion or a history import.
 */
private val GLOBAL_SEARCH_EXISTING_ROW_TRIGGERS =
    setOf(
        TimelineUpdateTriggerFfi.MESSAGE_DELETED,
        TimelineUpdateTriggerFfi.MESSAGE_EDITED_OR_REPROJECTED,
        TimelineUpdateTriggerFfi.SNAPSHOT_REFRESH,
    )

/**
 * Whether a timeline projection update can change what this session lists: the removal of a loaded row, a
 * deletion, edit, send-state or snapshot change of one, or a new or edited message the query would now admit.
 * Reactions, receipts and activity elsewhere never cause a reread, so a busy account does not keep rereading.
 * While a page or refresh is in flight it may hold rows that are not listed yet, so a removal, or a deletion, edit,
 * snapshot reprojection or invalidation of an in-scope row of a searchable kind, also counts then. Other kinds
 * (poll responses, reports) never sit in a held read, and a new message counts only when the query admits it,
 * so a refresh never queues another one for unrelated traffic.
 */
internal fun GlobalSearchResultsController.isTouchedBy(update: TimelineProjectionUpdateFfi): Boolean {
    val current = query ?: return false
    val loaded = state.seenKeys
    val heldByRead = state.inFlight != null && current.coversGroup(update.groupIdHex)
    return update.changes.any { change ->
        when (change) {
            is TimelineMessageChangeFfi.Remove ->
                heldByRead || globalSearchKey(update.groupIdHex, change.messageIdHex) in loaded
            is TimelineMessageChangeFfi.Upsert ->
                when {
                    globalSearchKey(update.groupIdHex, change.message.messageIdHex) in loaded ->
                        change.trigger !in GLOBAL_SEARCH_INERT_TRIGGERS
                    heldByRead && change.rewritesExistingRow() -> true
                    else ->
                        change.trigger in GLOBAL_SEARCH_MATCHING_TRIGGERS &&
                            current.admit(
                                change.message,
                                environment.hiddenMessageIds,
                                environment.nowMillis(),
                                needleMatchedByMdk = false,
                            ) != null
                }
        }
    }
}

/**
 * A deletion, edit or snapshot reprojection of an existing row of a searchable kind, or a send-state change that
 * invalidated one — the only rows a read in flight can be holding.
 */
private fun TimelineMessageChangeFfi.Upsert.rewritesExistingRow(): Boolean {
    val invalidated =
        trigger == TimelineUpdateTriggerFfi.DELIVERY_OR_SEND_STATE_CHANGED && message.invalidationStatus != null
    val rewrite = trigger in GLOBAL_SEARCH_EXISTING_ROW_TRIGGERS || invalidated
    return rewrite && ChatListMessageSearch.isSearchableKind(message.kind)
}

/**
 * Batches one refresh may read: enough for [loadedRows] at the batch target plus the empty-batch allowance, capped
 * at [GLOBAL_SEARCH_REFRESH_MAX_BATCHES] so a long scrolled session stays cheap. Rows below where a capped refresh
 * stopped keep their previous state and are rechecked by a later refresh.
 */
internal fun refreshBatchBudget(loadedRows: Int): Int {
    val wanted = loadedRows / GLOBAL_SEARCH_BATCH_TARGET + GLOBAL_SEARCH_AUTO_EMPTY_BATCHES
    return wanted.coerceAtMost(GLOBAL_SEARCH_REFRESH_MAX_BATCHES)
}

/** Whether a generation's first page just landed with nothing, which warrants one recheck. */
internal fun shouldProbeAfterFirstPage(state: GlobalSearchResultsState): Boolean =
    state.batches == 1 && state.results.isEmpty() && !state.failed && state.inFlight == null

/** The most batches one refresh rereads, about a thousand raw rows. */
internal const val GLOBAL_SEARCH_REFRESH_MAX_BATCHES = 5

/**
 * What the screen may show of this session for the [asked] query: nothing until the session adopted the query, nothing
 * while a held session's rows await their first recheck after a return, and a failure boundary with no rows when
 * that recheck failed — never rows that were not verified since the reader came back (#1945).
 */
internal fun GlobalSearchResultsController.shownState(asked: GlobalSearchResultsQuery): GlobalSearchResultsState? =
    when {
        query != asked -> null
        recheckFailed -> GlobalSearchResultsState(generation = state.generation, batches = 1, failed = true)
        stale -> null
        else -> state
    }

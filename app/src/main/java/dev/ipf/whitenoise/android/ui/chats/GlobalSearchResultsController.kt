package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val environment: GlobalSearchResultsEnvironment = GlobalSearchResultsEnvironment(),
) {
    /** What the screen renders for the current generation. */
    var state by mutableStateOf(GlobalSearchResultsState())
        private set

    /** The query the current generation answers, or null before the first search and after [close]. */
    var query: GlobalSearchResultsQuery? = null
        private set

    private var job: Job? = null
    private var closed = false
    private var refreshPending = false

    /**
     * Starts a generation for [next] unless it is already the current query. [debounceMillis] delays only the
     * first read, so typing does not read a page per keystroke; [resumeTarget] rereads that many rows first.
     */
    fun search(
        next: GlobalSearchResultsQuery,
        debounceMillis: Long = 0L,
        resumeTarget: Int = 0,
    ) {
        if (closed || (next == query && state.generation > 0)) return
        job?.cancel()
        query = next
        val restarted = GlobalSearchResultsReducer.restart(state, resumeTarget)
        // A scope with no chats cannot match, so it ends at once instead of reading the whole account.
        state = if (next.matchesNothing) restarted.copy(endReached = true, resumeTarget = 0) else restarted
        read(debounceMillis)
    }

    /** Reads the next bounded batch unless one is in flight or MDK reported the end. */
    fun loadMore() = read(debounceMillis = 0L)

    /** Reads the failed batch again from the cursor it stopped at; loaded rows stay. */
    fun retry() = loadMore()

    /**
     * Rechecks the loaded rows after a change that can delete, expire, hide or add a match: a projection update,
     * a block change or a disappearing deadline. It rereads the loaded range from the head within a bounded
     * budget and replaces rows in place; a request while a later batch is in flight runs once that batch ends.
     * Before the first batch lands there is nothing to recheck, since that batch reads current data anyway.
     */
    fun refresh() {
        val current = query?.takeUnless { closed } ?: return
        val started = GlobalSearchResultsReducer.beginRefresh(state)
        if (state.inFlight != null && state.batches > 0) {
            refreshPending = true
        } else if (started != null) {
            val budget = state.results.size / GLOBAL_SEARCH_BATCH_TARGET + GLOBAL_SEARCH_AUTO_EMPTY_BATCHES
            state = started.first
            launch(current, started.second, debounceMillis = 0L, refreshBudget = budget)
        }
    }

    /** Cancels the batch in flight and fences every later completion. */
    fun close() {
        closed = true
        job?.cancel()
        query = null
    }

    /** Starts one batch for the current generation, unless one is in flight or MDK reported the end. */
    private fun read(debounceMillis: Long) {
        val current = query?.takeUnless { closed } ?: return
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
                    if (accepts(current)) state = next(state)
                } catch (cancel: CancellationException) {
                    if (accepts(current)) state = GlobalSearchResultsReducer.cancel(state, request)
                    throw cancel
                } catch (_: Exception) {
                    // The failure kind is the whole outcome; the message never reaches the screen.
                    if (accepts(current)) state = failed(state, request)
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

    /** A failed page waits for Retry; a failed background refresh just keeps the rows it had. */
    private fun failed(
        state: GlobalSearchResultsState,
        request: GlobalSearchPageRequest,
    ): GlobalSearchResultsState =
        if (request.refresh) {
            GlobalSearchResultsReducer.cancel(state, request)
        } else {
            GlobalSearchResultsReducer.applyFailure(state, request)
        }

    /** Runs a refresh that arrived during the batch, else continues a returning session's bounded reread. */
    private fun continueAfter() {
        if (refreshPending) {
            refreshPending = false
            refresh()
        } else if (GlobalSearchResultsReducer.needsResumeRead(state)) {
            read(0L)
        }
    }

    /** Only the live query on an open, current session may change [state]. */
    private fun accepts(owner: GlobalSearchResultsQuery): Boolean = !closed && owner == query && environment.isCurrent()
}

/**
 * How far the message results had read for one query in this account's search session. A session reopened for
 * the same query, account and runtime rereads this many rows before it shows the list, so Back lands on the row
 * the user opened; any other owner starts at the top. It keeps a count only, never rows or cursors.
 */
internal class GlobalSearchResultsExtent {
    private var owner: Any? = null
    private var query: GlobalSearchResultsQuery? = null
    private var loaded = 0

    /** Records the rows a settled session of [owner] holds for [query]. */
    fun record(
        owner: Any,
        query: GlobalSearchResultsQuery,
        loaded: Int,
    ) {
        this.owner = owner
        this.query = query
        this.loaded = loaded
    }

    /** The rows to reread for [query] under [owner]: the recorded count for the same pair, otherwise none. */
    fun resumeCount(
        owner: Any,
        query: GlobalSearchResultsQuery,
    ): Int = if (owner == this.owner && query == this.query) loaded else 0

    /** Forgets the extent once message results end, so the next search starts at the top. */
    fun clear() {
        owner = null
        query = null
        loaded = 0
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

/** The smallest per-chat page a merged read asks for, so a wide fan-out still makes progress each read. */
private const val GLOBAL_SEARCH_FAN_OUT_MIN_PAGE = 5

/**
 * One merged page over a small chat scope: each chat is read directly (MDK pages one chat by time and message id)
 * with an equal share of [limit], and the pages are merged into account-search order. Only rows down to the
 * newest "last row" among chats that still have older rows are safe to consume, since an unread row from such a
 * chat could be newer than anything below that frontier; the rest are read again next time. Every fetched row
 * counts against the batch budget.
 */
private suspend fun readScopedPage(
    reader: GlobalSearchTimelineReader,
    current: GlobalSearchResultsQuery,
    groups: List<String>,
    cursor: GlobalSearchCursor?,
    limit: UInt,
): GlobalSearchRawPage<TimelineMessageRecordFfi> {
    val perChat = (limit.toInt() / groups.size).coerceAtLeast(GLOBAL_SEARCH_FAN_OUT_MIN_PAGE).toUInt()
    val pages = groups.map { group -> reader.page(current.toFfi(cursor, perChat, groupIdHex = group)) }
    val frontier =
        pages
            .filter { it.hasMoreBefore && it.messages.isNotEmpty() }
            .maxOfOrNull { page -> page.messages.minOf { it.globalSearchCursor() } }
    val fetched = pages.flatMap { it.messages }
    val consumed = if (frontier == null) fetched else fetched.filter { it.globalSearchCursor() >= frontier }
    return GlobalSearchRawPage(consumed, hasMoreBefore = frontier != null, rowsRead = fetched.size)
}

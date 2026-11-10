package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.TimelineMessageQueryFfi
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

    /** Cancels the batch in flight and fences every later completion. */
    fun close() {
        closed = true
        job?.cancel()
        query = null
    }

    /** Launches one batch for the current generation and applies it only if that request is still current. */
    @Suppress("TooGenericExceptionCaught") // Any read failure becomes the retryable boundary state.
    private fun read(debounceMillis: Long) {
        val current = query?.takeUnless { closed } ?: return
        val (next, request) = GlobalSearchResultsReducer.begin(state) ?: return
        state = next
        job =
            scope.launch {
                try {
                    if (debounceMillis > 0L) delay(debounceMillis)
                    val batch = withContext(environment.ioDispatcher) { readBatch(current, request) }
                    if (accepts(current)) state = GlobalSearchResultsReducer.applyBatch(state, request, batch)
                } catch (cancel: CancellationException) {
                    if (accepts(current)) state = GlobalSearchResultsReducer.cancel(state, request)
                    throw cancel
                } catch (_: Exception) {
                    // The failure kind is the whole outcome; the message never reaches the screen.
                    if (accepts(current)) state = GlobalSearchResultsReducer.applyFailure(state, request)
                }
                if (GlobalSearchResultsReducer.needsResumeRead(state) && accepts(current)) read(0L)
            }
    }

    /** Only the live query on an open, current session may change [state]. */
    private fun accepts(owner: GlobalSearchResultsQuery): Boolean = !closed && owner == query && environment.isCurrent()

    /** One bounded batch from MDK, post-filtered by the query's Android-owned checks. */
    private suspend fun readBatch(
        current: GlobalSearchResultsQuery,
        request: GlobalSearchPageRequest,
    ): GlobalSearchBatch {
        val hidden = HashMap<String, Set<String>>()
        val source =
            GlobalSearchBatchSource(
                readPage = { cursor, limit ->
                    val page = reader.page(current.toFfi(cursor, limit))
                    GlobalSearchRawPage(page.messages, page.hasMoreBefore)
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
        return fillGlobalSearchBatch(request.cursor, request.seenKeys, source)
    }
}

/**
 * How far the message results had read for one query in this account's search session. A session reopened for
 * the same query rereads this many rows before it shows the list, so Back lands on the row the user opened.
 * It keeps a count only, never rows or cursors.
 */
internal class GlobalSearchResultsExtent {
    private var query: GlobalSearchResultsQuery? = null
    private var loaded = 0

    /** Records the rows a settled session holds for [query]. */
    fun record(
        query: GlobalSearchResultsQuery,
        loaded: Int,
    ) {
        this.query = query
        this.loaded = loaded
    }

    /** The rows to reread for [query]: the recorded count for the same query, otherwise none. */
    fun resumeCount(query: GlobalSearchResultsQuery): Int = if (query == this.query) loaded else 0

    /** Forgets the extent once message results end, so the next search starts at the top. */
    fun clear() {
        query = null
        loaded = 0
    }
}

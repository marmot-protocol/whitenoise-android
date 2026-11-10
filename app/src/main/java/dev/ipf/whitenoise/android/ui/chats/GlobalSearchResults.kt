package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.core.SnippetHighlight
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/** Raw MDK pages one visible batch may read before it hands back its last continuation (#1944). */
internal const val GLOBAL_SEARCH_BATCH_PAGES = 4

/** Raw MDK rows one visible batch may read, across all of its pages. */
internal const val GLOBAL_SEARCH_BATCH_ROWS = 200

/** Rows per raw MDK page; four of them use the whole row budget. */
internal const val GLOBAL_SEARCH_RAW_PAGE_SIZE = 50

/** Visible results after which a batch stops reading, so a broad query never spends its whole budget. */
internal const val GLOBAL_SEARCH_BATCH_TARGET = 20

/**
 * Consecutive batches that may show nothing before the results stop reading on their own and offer to search
 * older messages instead, so a filter that hides everything never walks the whole account unasked.
 */
internal const val GLOBAL_SEARCH_AUTO_EMPTY_BATCHES = 3

/**
 * MDK's account-wide search cursor: the oldest row a page returned. MDK orders account search by timeline time,
 * then message id, then group, newest first, so the triple is a total order and same-second rows never repeat or
 * vanish at a page edge.
 */
internal data class GlobalSearchCursor(
    val timelineAt: ULong,
    val messageIdHex: String,
    val groupIdHex: String,
) : Comparable<GlobalSearchCursor> {
    /** MDK's own ascending order: time, then message id, then group. */
    override fun compareTo(other: GlobalSearchCursor): Int = CURSOR_ORDER.compare(this, other)

    private companion object {
        /** Time, then message id, then group, ascending. */
        val CURSOR_ORDER: Comparator<GlobalSearchCursor> =
            compareBy<GlobalSearchCursor> { it.timelineAt }.thenBy { it.messageIdHex }.thenBy { it.groupIdHex }
    }
}

/** The kind of attachment a result carries, named the way the attachment surfaces name it. */
internal enum class GlobalSearchResultAttachmentKind {
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
}

/**
 * The first attachment of a result: its kind from the MIME type, the sender's own [fileName] (blank when none
 * was given, never a MIME type), and how many attachments the message holds.
 */
internal data class GlobalSearchResultAttachment(
    val kind: GlobalSearchResultAttachmentKind,
    val fileName: String,
    val count: Int,
)

/**
 * One matching message (#1944). Every match is its own row, so several matches from one chat stay distinct; the
 * [key] combines group and message id because a message id is only unique inside its chat. The [snippet] is
 * built from text that already passed the eligibility, deletion, retention and local-hide checks; [expiresAt] is
 * the unix second a disappearing match stops being searchable, so the screen can recheck it then.
 */
internal data class GlobalSearchResult(
    val groupIdHex: String,
    val messageIdHex: String,
    val senderIdHex: String,
    val timelineAt: ULong,
    val snippet: SnippetHighlight,
    val attachment: GlobalSearchResultAttachment? = null,
    val expiresAt: ULong? = null,
) {
    val key: String = globalSearchKey(groupIdHex, messageIdHex)

    /** This row's position in MDK's account-search order. */
    val cursor: GlobalSearchCursor get() = GlobalSearchCursor(timelineAt, messageIdHex, groupIdHex)
}

private const val GLOBAL_SEARCH_KEY_SEPARATOR = ":"

/** The stable key of one message: group and message id, since a message id is only unique inside its chat. */
internal fun globalSearchKey(
    groupIdHex: String,
    messageIdHex: String,
): String = groupIdHex.lowercase(Locale.ROOT) + GLOBAL_SEARCH_KEY_SEPARATOR + messageIdHex.lowercase(Locale.ROOT)

/**
 * One raw MDK page reduced to the rows it consumed and whether older rows remain. [rowsRead] counts every row
 * the read fetched, which a merged chat-by-chat page can exceed, so the batch budget charges what was read.
 */
internal data class GlobalSearchRawPage<R>(
    val rows: List<R>,
    val hasMoreBefore: Boolean,
    val rowsRead: Int = rows.size,
)

/**
 * One visible batch: the rows it admitted, newest first, and the continuation of the last raw page it consumed.
 * A null [nextCursor] is MDK's authoritative end. [rawPages] and [rawRows] record what the batch spent.
 */
internal data class GlobalSearchBatch(
    val results: List<GlobalSearchResult>,
    val nextCursor: GlobalSearchCursor?,
    val rawPages: Int,
    val rawRows: Int,
)

/** How much one visible batch may read, and after how many visible rows it stops early. */
internal data class GlobalSearchBatchBudget(
    val maxRawPages: Int = GLOBAL_SEARCH_BATCH_PAGES,
    val maxRawRows: Int = GLOBAL_SEARCH_BATCH_ROWS,
    val rawPageSize: Int = GLOBAL_SEARCH_RAW_PAGE_SIZE,
    val visibleTarget: Int = GLOBAL_SEARCH_BATCH_TARGET,
)

/** How one batch reads MDK and turns its rows into results; injected so paging is testable without bindings. */
internal class GlobalSearchBatchSource<R>(
    val readPage: suspend (cursor: GlobalSearchCursor?, limit: UInt) -> GlobalSearchRawPage<R>,
    val cursorOf: (R) -> GlobalSearchCursor,
    val admit: (R) -> GlobalSearchResult?,
)

/**
 * Reads at most [GlobalSearchBatchBudget.maxRawPages] pages / [GlobalSearchBatchBudget.maxRawRows] rows from
 * [cursor] and keeps the rows [GlobalSearchBatchSource.admit] accepts — the Android-owned local hides, eligibility
 * and retention checks MDK cannot apply. Each page is consumed whole, so the returned cursor is always the last
 * consumed page's own continuation and no row is skipped. Rows already in [seenKeys] or earlier in this batch
 * are dropped. A batch whose rows were all filtered out still returns its continuation. Over a small chat scope one
 * raw page is a merged read of up to n per-chat MDK queries, still charged by the rows they fetch.
 */
internal suspend fun <R> fillGlobalSearchBatch(
    cursor: GlobalSearchCursor?,
    seenKeys: Set<String>,
    source: GlobalSearchBatchSource<R>,
    budget: GlobalSearchBatchBudget = GlobalSearchBatchBudget(),
): GlobalSearchBatch {
    val admitted = LinkedHashMap<String, GlobalSearchResult>()
    var next = cursor
    var pages = 0
    var rows = 0
    do {
        currentCoroutineContext().ensureActive()
        val limit = minOf(budget.rawPageSize, budget.maxRawRows - rows).coerceAtLeast(1)
        val page = source.readPage(next, limit.toUInt())
        pages++
        rows += page.rowsRead
        val ordered = page.rows.map { it to source.cursorOf(it) }.sortedByDescending { it.second }
        ordered.forEach { (row, _) ->
            val result = source.admit(row)
            if (result != null && result.key !in seenKeys) admitted.putIfAbsent(result.key, result)
        }
        // A cursor that fails to move older is treated as the end rather than read again.
        val oldest = ordered.lastOrNull()?.second?.takeIf { page.hasMoreBefore && (next == null || it < next) }
        next = oldest
        val withinBudget = pages < budget.maxRawPages && rows < budget.maxRawRows
    } while (next != null && admitted.size < budget.visibleTarget && withinBudget)
    return GlobalSearchBatch(admitted.values.toList(), next, pages, rows)
}

/**
 * The one batch a generation has in flight. A completion is applied only while it is still the state's
 * [GlobalSearchResultsState.inFlight], so a page from an older query, filter set or account is dropped. A
 * [refresh] rereads the loaded range from the head; its [cursor] is then the old continuation it reads through.
 * [launch] is unique per request within a generation, so a completion that outlived its cancellation can never
 * match a newer request that happens to read the same range.
 */
internal data class GlobalSearchPageRequest(
    val generation: Int,
    val sequence: Int,
    val cursor: GlobalSearchCursor?,
    val seenKeys: Set<String>,
    val refresh: Boolean = false,
    val launch: Long = 0L,
)

/**
 * Transient results of one search generation. Only admitted rows are kept, never raw pages. [emptyBatches] counts
 * consecutive batches that showed nothing, [launches] numbers every request started, and [batches] increases with
 * every finished one,
 * so paging effects relaunch even when a batch completes within one frame.
 */
internal data class GlobalSearchResultsState(
    val generation: Int = 0,
    val results: List<GlobalSearchResult> = emptyList(),
    val seenKeys: Set<String> = emptySet(),
    val nextCursor: GlobalSearchCursor? = null,
    val endReached: Boolean = false,
    val inFlight: GlobalSearchPageRequest? = null,
    val failed: Boolean = false,
    val emptyBatches: Int = 0,
    val batches: Int = 0,
    val launches: Long = 0L,
) {
    /** The earliest deadline of a loaded disappearing match, when the rows must be checked again. */
    val nextExpiry: ULong? = results.mapNotNull { it.expiresAt }.minOrNull()

    /** A page is being read; a background refresh keeps the boundary settled and is not loading. */
    val loading: Boolean get() = inFlight != null && !inFlight.refresh

    /** A background refresh of the loaded rows is in flight. */
    val refreshing: Boolean get() = inFlight?.refresh == true

    /** MDK returned a continuation, or the first batch has not finished yet. */
    val hasMore: Boolean get() = !endReached

    /** Nothing has been shown for this generation yet, and no failure has ended the first read. */
    val initialLoading: Boolean get() = batches == 0 && !failed

    /** The next batch may load unasked: more history, no failure, and only a few empty batches in a row. */
    val autoReads: Boolean get() = hasMore && !failed && emptyBatches < GLOBAL_SEARCH_AUTO_EMPTY_BATCHES
}

/** Pure transitions of the global message results; the controller owns I/O and cancellation. */
internal object GlobalSearchResultsReducer {
    /** A new query, filter set or account: drops every row, cursor and request, and opens the next generation. */
    fun restart(state: GlobalSearchResultsState): GlobalSearchResultsState {
        val generation = state.generation + 1
        return GlobalSearchResultsState(generation = generation)
    }

    /** Starts the next batch, or returns null while one is in flight or after MDK's end. */
    fun begin(state: GlobalSearchResultsState): Pair<GlobalSearchResultsState, GlobalSearchPageRequest>? {
        if (state.inFlight != null || state.endReached) return null
        val launch = state.launches + 1
        val request =
            GlobalSearchPageRequest(state.generation, state.batches, state.nextCursor, state.seenKeys, launch = launch)
        return state.copy(inFlight = request, failed = false, launches = launch) to request
    }

    /** Appends a completed batch for the current request, deduplicated by stable key; a stale one is ignored. */
    fun applyBatch(
        state: GlobalSearchResultsState,
        request: GlobalSearchPageRequest,
        batch: GlobalSearchBatch,
    ): GlobalSearchResultsState {
        if (request != state.inFlight) return state
        val fresh = batch.results.filter { it.key !in state.seenKeys }.distinctBy { it.key }
        val results = state.results + fresh
        val ended = batch.nextCursor == null
        val emptyBatches = if (fresh.isEmpty()) state.emptyBatches + 1 else 0
        return state.copy(
            results = results,
            seenKeys = state.seenKeys + fresh.map { it.key },
            nextCursor = batch.nextCursor,
            endReached = ended,
            inFlight = null,
            emptyBatches = emptyBatches,
            batches = state.batches + 1,
        )
    }

    /**
     * Starts rereading the loaded range from the head, after a change that may delete, expire or hide a loaded
     * row or add a newer match. Null while a batch is in flight or before anything loaded.
     */
    fun beginRefresh(state: GlobalSearchResultsState): Pair<GlobalSearchResultsState, GlobalSearchPageRequest>? {
        if (state.inFlight != null || state.batches == 0) return null
        val launch = state.launches + 1
        val request =
            GlobalSearchPageRequest(
                state.generation,
                state.batches,
                state.nextCursor,
                emptySet(),
                refresh = true,
                launch = launch,
            )
        return state.copy(inFlight = request, launches = launch) to request
    }

    /**
     * Replaces the loaded range with a reread. [refreshed] covers the head down to [rereadThrough]; loaded rows
     * older than that keep their place, so a reread capped before the old continuation loses nothing. Rows
     * keep their stable keys, so a list viewport stays where it was while gone rows drop out.
     */
    fun applyRefresh(
        state: GlobalSearchResultsState,
        request: GlobalSearchPageRequest,
        refreshed: List<GlobalSearchResult>,
        rereadThrough: GlobalSearchCursor?,
    ): GlobalSearchResultsState {
        if (request != state.inFlight || !request.refresh) return state
        val through = request.cursor
        // The old continuation is the oldest row the range consumed, so it belongs to the range.
        val head = refreshed.filter { through == null || it.cursor >= through }
        val tail = state.results.filter { rereadThrough != null && it.cursor < rereadThrough }
        val results = (head + tail).distinctBy { it.key }
        return state.copy(
            results = results,
            seenKeys = results.mapTo(HashSet()) { it.key },
            inFlight = null,
            batches = state.batches + 1,
        )
    }

    /** Keeps every loaded row and the cursor, and waits for a retry; a stale failure is ignored. */
    fun applyFailure(
        state: GlobalSearchResultsState,
        request: GlobalSearchPageRequest,
    ): GlobalSearchResultsState {
        val current = request == state.inFlight
        return if (current) state.copy(inFlight = null, failed = true) else state
    }

    /** Forgets a cancelled request without marking a failure, so the same cursor can be read again. */
    fun cancel(
        state: GlobalSearchResultsState,
        request: GlobalSearchPageRequest,
    ): GlobalSearchResultsState = if (request != state.inFlight) state else state.copy(inFlight = null)
}

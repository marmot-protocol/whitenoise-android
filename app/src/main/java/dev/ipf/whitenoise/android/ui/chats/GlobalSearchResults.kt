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
 * match a newer request that happens to read the same range. A [verifying] refresh rechecks rows the reader has not
 * seen confirmed since returning (#1945); it never keeps rows below where it stopped. A refresh with a [from] cursor
 * continues a capped recheck: it rereads only the rows older than [from], which the previous pass already covered.
 */
internal data class GlobalSearchPageRequest(
    val generation: Int,
    val sequence: Int,
    val cursor: GlobalSearchCursor?,
    val seenKeys: Set<String>,
    val refresh: Boolean = false,
    val launch: Long = 0L,
    val verifying: Boolean = false,
    val from: GlobalSearchCursor? = null,
)

/**
 * Transient results of one search generation. Only admitted rows are kept, never raw pages. [emptyBatches] counts
 * consecutive batches that showed nothing, [launches] numbers every request started, and [batches] increases with
 * every finished one,
 * so paging effects relaunch even when a batch completes within one frame. [validateFrom] is where a capped recheck
 * stopped: loaded rows older than it have not been reread since the change that asked for the recheck, so the
 * controller keeps rechecking from there until the whole loaded range is covered. [withdrawn] holds rows MDK reported
 * removed, which no later page or reread of this session, in any generation, may list again unless MDK reports them
 * live again.
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
    val validateFrom: GlobalSearchCursor? = null,
    val withdrawn: Set<String> = emptySet(),
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
    /**
     * A new query or filter set: drops every row, cursor and request, and opens the next generation. Withdrawn keys
     * carry over, so a removal the store has not caught up with stays out however often the query changes.
     */
    fun restart(state: GlobalSearchResultsState): GlobalSearchResultsState =
        GlobalSearchResultsState(generation = state.generation + 1, withdrawn = state.withdrawn)

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
        val fresh =
            batch.results
                .filter { it.key !in state.seenKeys && it.key !in state.withdrawn }
                .distinctBy { it.key }
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
     * row or add a newer match, or from [GlobalSearchResultsState.validateFrom] when [continuing] a capped recheck.
     * A verifying refresh always starts at the head. Null while a batch is in flight or before anything loaded.
     */
    fun beginRefresh(
        state: GlobalSearchResultsState,
        verifying: Boolean = false,
        continuing: Boolean = false,
    ): Pair<GlobalSearchResultsState, GlobalSearchPageRequest>? {
        if (state.inFlight != null || state.batches == 0) return null
        val from = if (continuing && !verifying) state.validateFrom else null
        val launch = state.launches + 1
        val request =
            GlobalSearchPageRequest(
                state.generation,
                state.batches,
                state.nextCursor,
                emptySet(),
                refresh = true,
                launch = launch,
                verifying = verifying,
                from = from,
            )
        return state.copy(inFlight = request, launches = launch) to request
    }

    /**
     * Replaces the loaded range with a reread. [refreshed] covers the request's start (the head, or the rows older
     * than [GlobalSearchPageRequest.from]) down to [rereadThrough]; loaded rows newer than the start were covered by
     * the previous pass and keep their place. Loaded rows older than [rereadThrough] keep their place too, so a
     * reread capped before the old continuation loses nothing, and [GlobalSearchResultsState.validateFrom] records
     * where the next pass must continue. Rows keep their stable keys, so a list viewport stays where it was while
     * gone rows drop out. A verifying reread that stopped short instead drops those older, unconfirmed rows and
     * continues from [rereadThrough], so they return only as fresh pages.
     */
    fun applyRefresh(
        state: GlobalSearchResultsState,
        request: GlobalSearchPageRequest,
        refreshed: List<GlobalSearchResult>,
        rereadThrough: GlobalSearchCursor?,
    ): GlobalSearchResultsState {
        if (request != state.inFlight || !request.refresh) return state
        val through = request.cursor
        val stoppedShort = rereadThrough != null && (through == null || rereadThrough > through)
        val dropTail = request.verifying && stoppedShort
        val results = keptAfterReread(state, request, refreshed, rereadThrough.takeUnless { dropTail })
        return state.copy(
            results = results,
            seenKeys = results.mapTo(HashSet()) { it.key },
            validateFrom = if (stoppedShort && !dropTail) rereadThrough else null,
            nextCursor = if (dropTail) rereadThrough else state.nextCursor,
            endReached = state.endReached && !dropTail,
            emptyBatches = if (dropTail) 0 else state.emptyBatches,
            inFlight = null,
            batches = state.batches + 1,
        )
    }

    /**
     * The rows a reread leaves listed: rows newer than the request's start, which the pass that set it rechecked,
     * then the reread rows, then loaded rows older than [keepOlderThan] (none when null). Withdrawn rows never stay.
     */
    private fun keptAfterReread(
        state: GlobalSearchResultsState,
        request: GlobalSearchPageRequest,
        refreshed: List<GlobalSearchResult>,
        keepOlderThan: GlobalSearchCursor?,
    ): List<GlobalSearchResult> {
        val from = request.from
        val through = request.cursor
        val covered = if (from == null) emptyList() else state.results.filter { it.cursor >= from }
        // The old continuation is the oldest row the range consumed, so it belongs to the range.
        val reread = refreshed.filter { through == null || it.cursor >= through }
        val tail = if (keepOlderThan == null) emptyList() else state.results.filter { it.cursor < keepOlderThan }
        return (covered + reread + tail).filter { it.key !in state.withdrawn }.distinctBy { it.key }
    }

    /**
     * Drops loaded rows whose disappearing deadline has passed at [nowSeconds], the way the conversation hides them.
     * A later page or reread does not list them again because admission rejects a row past its deadline.
     */
    fun dropExpired(
        state: GlobalSearchResultsState,
        nowSeconds: ULong,
    ): GlobalSearchResultsState {
        val expired = state.results.filter { result -> result.expiresAt?.let { it <= nowSeconds } == true }
        if (expired.isEmpty()) return state
        return state.copy(results = state.results - expired.toSet())
    }

    /**
     * Drops loaded rows MDK reported removed, and keeps every reported key, loaded or still held by a read in flight,
     * out of every later page and reread of this session.
     */
    fun withdraw(
        state: GlobalSearchResultsState,
        keys: Set<String>,
    ): GlobalSearchResultsState {
        if (keys.isEmpty()) return state
        return state.copy(results = state.results.filter { it.key !in keys }, withdrawn = state.withdrawn + keys)
    }

    /** Applies a drained queue once; any removal drops the current row, while the last change controls admission. */
    fun applyKeyChanges(
        state: GlobalSearchResultsState,
        changes: Sequence<GlobalSearchKeyChanges>,
    ): GlobalSearchResultsState {
        val iterator = changes.iterator()
        if (!iterator.hasNext()) return state
        val removed = mutableSetOf<String>()
        val withdrawn = state.withdrawn.toMutableSet()
        do {
            val change = iterator.next()
            removed.addAll(change.removed)
            withdrawn.addAll(change.removed)
            withdrawn.removeAll(change.restored)
        } while (iterator.hasNext())
        return state.copy(
            results = if (removed.isEmpty()) state.results else state.results.filter { it.key !in removed },
            withdrawn = withdrawn,
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

/**
 * Whether a stale session's re-read has covered the range it had before the reader left: reached MDK's end, or
 * passed the old continuation [target]. A null target means the old range had ended, so only the end counts.
 */
internal fun verificationReached(
    state: GlobalSearchResultsState,
    target: GlobalSearchCursor?,
): Boolean {
    val cursor = state.nextCursor
    return state.endReached || cursor == null || (target != null && cursor <= target)
}

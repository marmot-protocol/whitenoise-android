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

/** The first attachment of a result, its file name or type, and how many attachments the message holds. */
internal data class GlobalSearchResultAttachment(
    val kind: GlobalSearchResultAttachmentKind,
    val label: String,
    val count: Int,
)

/**
 * One matching message (#1944). Every match is its own row, so several matches from one chat stay distinct; the
 * [key] combines group and message id because a message id is only unique inside its chat. The [snippet] is
 * built from text that already passed the eligibility, deletion, retention and local-hide checks.
 */
internal data class GlobalSearchResult(
    val groupIdHex: String,
    val messageIdHex: String,
    val senderIdHex: String,
    val timelineAt: ULong,
    val snippet: SnippetHighlight,
    val attachment: GlobalSearchResultAttachment? = null,
) {
    val key: String =
        groupIdHex.lowercase(Locale.ROOT) + GLOBAL_SEARCH_KEY_SEPARATOR + messageIdHex.lowercase(Locale.ROOT)
}

private const val GLOBAL_SEARCH_KEY_SEPARATOR = ":"

/** One raw MDK page reduced to its rows and whether older rows remain. */
internal data class GlobalSearchRawPage<R>(
    val rows: List<R>,
    val hasMoreBefore: Boolean,
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
 * are dropped. A batch whose rows were all filtered out still returns its continuation.
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
        rows += page.rows.size
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
 * [GlobalSearchResultsState.inFlight], so a page from an older query, filter set or account is dropped.
 */
internal data class GlobalSearchPageRequest(
    val generation: Int,
    val sequence: Int,
    val cursor: GlobalSearchCursor?,
    val seenKeys: Set<String>,
)

/**
 * Transient results of one search generation. Only admitted rows are kept, never raw pages. [resumeTarget] is
 * the row count a returning session rereads before it shows the list, so Back lands where the user left;
 * [emptyBatches] counts consecutive batches that showed nothing and [batches] increases with every finished one,
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
    val resumeTarget: Int = 0,
) {
    /** A batch is being read. */
    val loading: Boolean get() = inFlight != null

    /** MDK returned a continuation, or the first batch has not finished yet. */
    val hasMore: Boolean get() = !endReached

    /** A returning session is still rereading the rows it had before. */
    val resuming: Boolean get() = resumeTarget > 0 && !failed

    /** Nothing has been shown for this generation yet, and no failure has ended the first read. */
    val initialLoading: Boolean get() = batches == 0 && !failed

    /** The next batch may load unasked: more history, no failure, and only a few empty batches in a row. */
    val autoReads: Boolean get() = hasMore && !failed && emptyBatches < GLOBAL_SEARCH_AUTO_EMPTY_BATCHES
}

/** Pure transitions of the global message results; the controller owns I/O and cancellation. */
internal object GlobalSearchResultsReducer {
    /** A new query, filter set or account: drops every row, cursor and request, and opens the next generation. */
    fun restart(
        state: GlobalSearchResultsState,
        resumeTarget: Int = 0,
    ): GlobalSearchResultsState {
        val generation = state.generation + 1
        return GlobalSearchResultsState(generation = generation, resumeTarget = resumeTarget.coerceAtLeast(0))
    }

    /** Starts the next batch, or returns null while one is in flight or after MDK's end. */
    fun begin(state: GlobalSearchResultsState): Pair<GlobalSearchResultsState, GlobalSearchPageRequest>? {
        if (state.inFlight != null || state.endReached) return null
        val request = GlobalSearchPageRequest(state.generation, state.batches, state.nextCursor, state.seenKeys)
        return state.copy(inFlight = request, failed = false) to request
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
        return state.copy(
            results = results,
            seenKeys = state.seenKeys + fresh.map { it.key },
            nextCursor = batch.nextCursor,
            endReached = ended,
            inFlight = null,
            emptyBatches = if (fresh.isEmpty()) state.emptyBatches + 1 else 0,
            batches = state.batches + 1,
            resumeTarget = if (ended || results.size >= state.resumeTarget) 0 else state.resumeTarget,
        )
    }

    /** Keeps every loaded row and the cursor, and waits for a retry; a stale failure is ignored. */
    fun applyFailure(
        state: GlobalSearchResultsState,
        request: GlobalSearchPageRequest,
    ): GlobalSearchResultsState {
        val current = request == state.inFlight
        return if (current) state.copy(inFlight = null, failed = true, resumeTarget = 0) else state
    }

    /** Forgets a cancelled request without marking a failure, so the same cursor can be read again. */
    fun cancel(
        state: GlobalSearchResultsState,
        request: GlobalSearchPageRequest,
    ): GlobalSearchResultsState = if (request != state.inFlight) state else state.copy(inFlight = null)

    /** Whether a returning session still has rows to reread before it shows its list. */
    fun needsResumeRead(state: GlobalSearchResultsState): Boolean {
        val idle = state.inFlight == null && !state.endReached
        return state.resuming && idle
    }
}

package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.nostrEventReferences
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.ui.conversation.nostr.MAX_CARDS_PER_MESSAGE
import dev.ipf.whitenoise.android.ui.conversation.nostr.NEWEST_FIRST
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardSnapshots
import dev.ipf.whitenoise.android.ui.conversation.nostr.mayRenderNostrEventCard
import dev.ipf.whitenoise.android.ui.conversation.nostr.readCachedPublicEventCards
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns one conversation's transient event-card snapshots and their bounded cache warm pipeline.
 * MDK remains the durable source. The controller supplies the currently installed and shown
 * windows when asked, so a read observes replacements without retaining a second timeline.
 */
internal class ConversationEventCardPresentation(
    private val appState: WhiteNoiseAppState,
    private val conversationAccountRef: String?,
    private val currentScope: () -> CoroutineScope?,
    private val latestInstalledPage: () -> TimelinePageFfi?,
    private val shownRecords: () -> Map<String, TimelineMessageRecordFfi>,
    private val initialPresentationReady: () -> Boolean,
) {
    val eventCardSnapshots = NostrEventCardSnapshots()

    /**
     * Holds the first page for its cached event cards (#2238) and records how long the read took, so a device run
     * can measure the cold encrypted-cache open. The line carries only a duration and a reference count.
     */
    suspend fun warmInitialEventCards(work: InitialPresentationWarmWork) {
        val trace = PerformanceDiagnostics.begin(PerformanceOperation.CHAT_OPEN)
        val startedAt = SystemClock.elapsedRealtime()
        var references: Int? = null
        try {
            references =
                warmInitialEventCardSnapshots(work, eventCardSnapshots) { batch ->
                    appState.readCachedPublicEventCards(conversationAccountRef, batch)
                }
        } finally {
            val durationMs = SystemClock.elapsedRealtime() - startedAt
            PerformanceDiagnostics.record(
                trace,
                PerformancePhase.EVENT_CARD_CACHE_WARM,
                elapsedMs = durationMs,
                durationMs = durationMs,
                result = if (references == null) PerformanceResult.DROPPED else PerformanceResult.SUCCESS,
                layer = PerformanceLayer.STORAGE,
                count = references,
            )
        }
    }

    /**
     * Reads MDK's cached cards for [records] into [eventCardSnapshots] off the main thread, so their first frame is
     * complete. A failed read leaves each card's own cache read and refresh in charge.
     */
    suspend fun warmEventCardSnapshots(records: List<TimelineMessageRecordFfi>) {
        if (records.isEmpty()) return
        runCatchingCancellable {
            eventCardSnapshots.warm(records) { references ->
                appState.readCachedPublicEventCards(conversationAccountRef, references)
            }
        }
    }

    /**
     * Reads the cached event cards of the rows that will be applied and are not on screen yet, before they are (#2238),
     * and returns the window to apply with those rows' Markdown parsed, so the commit installs that one parse and every
     * card first composes complete. The window is the subscription's newest installed one, else [page], since that is
     * what [applyTimelinePage] commits. A newer window installed while the read runs has its new card rows read too,
     * within what is left of the same budget. The first page has its own hold and is returned as is, and so is a
     * window with no such row, at no cost.
     *
     * The parse and the cache read ride the binding IO hop, so the caller only suspends. Past
     * [LATER_PAGE_EVENT_CARD_WARM_BUDGET_MILLIS] in all, or when a read fails, the window is returned with whatever
     * was parsed and its other cards resolve themselves as before. Callers run this before their `TIMELINE_APPLY`
     * timing starts.
     */
    suspend fun warmLaterPageEventCards(
        page: TimelinePageFfi,
        anchorMessageId: String? = null,
    ): TimelinePageFfi {
        val scope = currentScope() ?: return page
        val startedAt = SystemClock.elapsedRealtime()
        // Keyed by text as well as id: a message edited while it was read names other cards and is read again.
        val read = HashSet<Pair<String, String>>()
        val parsed = mutableListOf<TimelineMessageRecordFfi>()
        var target = page
        var reading = true
        while (reading) {
            val (window, candidates, order) = laterPageCardCandidates(page, read, anchorMessageId)
            target = window
            val remaining = LATER_PAGE_EVENT_CARD_WARM_BUDGET_MILLIS - (SystemClock.elapsedRealtime() - startedAt)
            candidates.mapTo(read) { it.messageIdHex to it.plaintext }
            val warmed =
                candidates
                    .takeIf { it.isNotEmpty() && remaining > 0L }
                    ?.let { readLaterPageEventCards(scope, it, order, remaining) }
            // Whatever was parsed is applied, even past the budget. Read again only when this read settled, since a
            // newer window may have been installed meanwhile.
            warmed?.parsed?.let(parsed::addAll)
            reading = warmed?.settled == true
        }
        return if (read.isEmpty()) page else target.withParsedRecords(parsed)
    }

    /** The window a later page would commit, its rows whose cards to read, and the order to read them in. */
    private data class LaterPageCardCandidates(
        val window: TimelinePageFfi,
        val records: List<TimelineMessageRecordFfi>,
        val order: Comparator<TimelineMessageRecordFfi>,
    )

    /**
     * The window [applyTimelinePage] would commit now — the newest installed one, else [page] — with its rows that may
     * render a card, are not among [read] (message id and text), and are not on screen with that text or still name a
     * card no warm has read. An edited message therefore counts as new, since it may name other cards. The rows are
     * read nearest [anchorMessageId] first when the window holds it, as a jump lands there, else nearest the rows on
     * screen, which a reader paging either way meets first. None before the first page is prepared.
     */
    private suspend fun laterPageCardCandidates(
        page: TimelinePageFfi,
        read: Set<Pair<String, String>>,
        anchorMessageId: String?,
    ): LaterPageCardCandidates =
        withContext(Dispatchers.Main.immediate) {
            val window = latestInstalledPage() ?: page
            val candidates =
                if (!initialPresentationReady()) {
                    emptyList()
                } else {
                    window.messages.filter { record ->
                        mayRenderNostrEventCard(record) &&
                            (record.messageIdHex to record.plaintext) !in read &&
                            needsEventCardRead(record)
                    }
                }
            val anchor = anchorMessageId?.let { id -> window.messages.firstOrNull { it.messageIdHex == id } }
            val order = anchor?.let { nearestTo(it.timelineAt, it.timelineAt) } ?: nearestToShownRows()
            LaterPageCardCandidates(window, candidates, order)
        }

    /**
     * Whether [record]'s cards still need a read: it is not on screen with this text, or it is, but names a card no
     * warm has asked MDK about yet, as a first page cut short by its budget can leave behind.
     */
    private fun needsEventCardRead(record: TimelineMessageRecordFfi): Boolean {
        val shown = shownRecords()[record.messageIdHex]?.takeIf { it.plaintext == record.plaintext } ?: return true
        return nostrEventReferences(shown.contentTokens)
            .take(MAX_CARDS_PER_MESSAGE)
            .any { !eventCardSnapshots.isKnown(it.reference.stableId) }
    }

    /**
     * Orders rows by their distance in time from the rows on screen, then newest first: an older page's newest rows and
     * a newer page's oldest rows border the reader's view. With nothing on screen it is simply newest first.
     */
    private fun nearestToShownRows(): Comparator<TimelineMessageRecordFfi> {
        val shown = shownRecords().values
        if (shown.isEmpty()) return NEWEST_FIRST
        return nearestTo(shown.minOf { it.timelineAt }, shown.maxOf { it.timelineAt })
    }

    /** Orders rows by their distance in time from the range [oldest]..[newest], then newest first. */
    private fun nearestTo(
        oldest: ULong,
        newest: ULong,
    ): Comparator<TimelineMessageRecordFfi> =
        compareBy<TimelineMessageRecordFfi> { record ->
            when {
                record.timelineAt < oldest -> oldest - record.timelineAt
                record.timelineAt > newest -> record.timelineAt - newest
                else -> 0uL
            }
        }.then(NEWEST_FIRST)

    /** What one later-page read finished: the rows it parsed, if the parse finished, and whether every read did. */
    private class LaterPageCardRead(
        val parsed: List<TimelineMessageRecordFfi>?,
        val settled: Boolean,
    )

    /**
     * Parses [candidates] that lack Markdown and reads their cached cards in [order], in its own job on the event-card
     * read lane, so the whole read takes one main-thread turn to resume, and [budgetMillis] can give up on a binding
     * call that never returns instead of waiting it out. Past the budget it still returns the parse if that finished,
     * and every batch read by then keeps its snapshots, so the page applies with what finished. Each read is recorded
     * once it ends, past the budget too, with how long its binding work took.
     */
    private suspend fun readLaterPageEventCards(
        scope: CoroutineScope,
        candidates: List<TimelineMessageRecordFfi>,
        order: Comparator<TimelineMessageRecordFfi>,
        budgetMillis: Long,
    ): LaterPageCardRead {
        val startedAt = SystemClock.elapsedRealtime()
        val laneMillis = AtomicLong(-1L)
        val parsedSoFar = AtomicReference<List<TimelineMessageRecordFfi>?>(null)
        val accountRef = conversationAccountRef
        val warm =
            scope.async {
                // Never fails the session's scope: a failed parse or read only leaves the cards to resolve themselves.
                runCatchingCancellable {
                    onEventCardReadLane {
                        val laneStartedAt = SystemClock.elapsedRealtime()
                        try {
                            val parsed = candidates.map { record -> parsedForCardRead(record) }
                            parsedSoFar.set(parsed)
                            // A read past its budget is abandoned, so it must not write snapshots after the fact.
                            currentCoroutineContext().ensureActive()
                            eventCardSnapshots.warm(parsed, order) { references ->
                                val states = appState.readCachedPublicEventCards(accountRef, references)
                                currentCoroutineContext().ensureActive()
                                states
                            }
                        } finally {
                            // Also when the budget cancelled it, so a dropped read still reports its binding time.
                            laneMillis.set(SystemClock.elapsedRealtime() - laneStartedAt)
                        }
                    }
                }.isSuccess
            }
        val settled = withTimeoutOrNull(budgetMillis) { warm.await() } == true
        if (settled) {
            recordLaterPageEventCardWarm(startedAt, laneMillis.get(), candidates.size, settled = true)
        } else {
            warm.cancel()
            // The lane's binding call cannot be interrupted, so its time is recorded when it does return.
            warm.invokeOnCompletion {
                recordLaterPageEventCardWarm(startedAt, laneMillis.get(), candidates.size, settled = false)
            }
        }
        return LaterPageCardRead(parsedSoFar.get(), settled)
    }

    /** [record] with its Markdown parsed on the calling thread, unless it already carries it. */
    private suspend fun parsedForCardRead(record: TimelineMessageRecordFfi): TimelineMessageRecordFfi {
        if (!needsTimelineMarkdownHydration(record)) return record
        val document = appState.parseMarkdownOrEmpty(record.plaintext)
        return if (document.blocks.isEmpty()) record else record.withMarkdownTokens(document)
    }

    /**
     * Records one later-page card read as `op=chat_history_page phase=event_card_page_warm layer=storage`:
     * `elapsed_ms` from the read's start to its end as seen by the caller, `duration_ms` the binding work on the card
     * lane (parse and cache reads, or 0 if it never ran), the rows it covered, and whether it settled within the
     * budget. Their difference is time spent waiting for a thread or the main thread. Never an id, text or reference.
     */
    private fun recordLaterPageEventCardWarm(
        startedAt: Long,
        laneMillis: Long,
        rows: Int,
        settled: Boolean,
    ) {
        PerformanceDiagnostics.record(
            PerformanceDiagnostics.begin(PerformanceOperation.CHAT_HISTORY_PAGE),
            PerformancePhase.EVENT_CARD_PAGE_WARM,
            elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
            durationMs = laneMillis.coerceAtLeast(0L),
            result = if (settled) PerformanceResult.SUCCESS else PerformanceResult.DROPPED,
            layer = PerformanceLayer.STORAGE,
            count = rows,
        )
    }
}

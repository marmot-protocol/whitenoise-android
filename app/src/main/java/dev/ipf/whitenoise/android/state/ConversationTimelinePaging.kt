package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import dev.ipf.whitenoise.android.state.ConversationWindowUnchangedReason.NOT_READY
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The timeline seam these helpers page; aliased so their signatures fit the column limit. */
private typealias PagingHandle = ConversationTimelineSubscriptionHandle

/** Why a page was asked for; aliased for the same reason as [PagingHandle]. */
internal typealias PagingOrigin = ConversationPagingOrigin

/**
 * How many times a page waits out a not-ready window before giving up.
 *
 * MDK answers not-ready while it repairs read state and retries accepted work, which clears on its
 * own in well under a second. Four attempts spaced by [CONVERSATION_WINDOW_NOT_READY_RETRY_MS] cost
 * at most a second of the reader's time, after which a retry affordance is better than more waiting.
 */
internal const val CONVERSATION_PAGE_NOT_READY_ATTEMPTS = 4

/** What one page attempt did, from the reader's point of view. */
internal enum class ConversationPageLoad {
    /** The window moved and new rows are on screen. */
    ADVANCED,

    /** The window answered but held no rows the timeline did not already have. */
    NO_PROGRESS,

    /** The engine did not answer before the window deadline. */
    TIMED_OUT,

    /** The engine stayed not-ready for the whole retry budget. */
    NOT_READY,

    /** The subscription was replaced or torn down while the page was in flight. */
    INACTIVE,

    /** The page threw; the reader is told and can retry. */
    FAILED,
}

/**
 * Pages the window towards older history.
 *
 * [anchorId] is the oldest row the reader can currently see. MDK places a replacement
 * relative to the window's anchor, so reporting that row first is what keeps the page from landing
 * somewhere else — see `set_visible_anchor` in MDK's window contract. It is ignored when the window
 * no longer retains the row, which is the case for optimistic rows carrying local ids MDK never
 * issued.
 */
@Suppress("TooGenericExceptionCaught", "ReturnCount")
internal suspend fun ConversationController.loadOlderPageInternal(anchorId: String? = null): ConversationPageLoad {
    if (!hasMoreBefore || isLoadingOlder) return ConversationPageLoad.NO_PROGRESS
    val subscription = timelineSubscription ?: return ConversationPageLoad.INACTIVE
    val priorMessageIds = timelineRecords.keys.toSet()
    // A previous loadOlderPage failure leaves `error` set; clear it now
    // that we're actually retrying, otherwise the stale banner sits over
    // a successful retry and a developer can't distinguish "still broken"
    // from "we forgot to clear it".
    pageError = null
    isLoadingOlder = true
    val trace = PerformanceDiagnostics.begin(PerformanceOperation.CHAT_HISTORY_PAGE)
    val startedMs = SystemClock.elapsedRealtime()
    return try {
        // The subscription's paginate_backwards extends the runtime's
        // materialized window backwards by `count` and returns the new
        // authoritative window — already deduped, sorted, head-anchored,
        // and cap-trimmed. We render it by extending the current window.
        val outcome = pageOlderIfActive(subscription, anchorId, trace)
        when (outcome) {
            null -> ConversationPageLoad.INACTIVE
            is TimelinePageOutcome.Unchanged -> unchangedPageLoad(outcome, ConversationSearchPageDirection.OLDER)
            is TimelinePageOutcome.Advanced -> {
                val appliedAtMs = SystemClock.elapsedRealtime()
                var committed = false
                tracedPagingSection(ConversationPagingTraceSection.APPLY) {
                    applyTimelinePage(
                        outcome.page,
                        replaceWindow = false,
                        updatePagination = true,
                        onCommitted = { committed = true },
                    )
                }
                if (!committed) {
                    ConversationPageLoad.INACTIVE
                } else {
                    hasLoadedOlderPages = true
                    failedPageDirection = null
                    protectedTimelineMessageIds.clear()
                    protectedTimelineMessageIds.addAll(timelineRecords.keys)
                    trace.recordPhase(
                        phase = PerformancePhase.PAGE_APPLY,
                        startedMs = appliedAtMs,
                        count = outcome.page.messages.size,
                    )
                    progressPageLoad(priorMessageIds)
                }
            }
        }.also { trace.recordCompletion(it, startedMs) }
    } catch (cancel: CancellationException) {
        // A cancelled page used to leave no trace at all, so a tester could not tell it from one
        // still waiting on the engine. `INACTIVE` closes it as `dropped`.
        trace.recordCompletion(ConversationPageLoad.INACTIVE, startedMs)
        throw cancel
    } catch (throwable: Throwable) {
        trace.recordCompletion(ConversationPageLoad.FAILED, startedMs)
        reportPageFailure(ConversationSearchPageDirection.OLDER, throwable)
        ConversationPageLoad.FAILED
    } finally {
        isLoadingOlder = false
    }
}

/**
 * Pages the window towards newer history, back down to the live tail.
 *
 * [origin] decides what a failure means. An explicit navigation or retry keeps the bottom-edge
 * retry affordance it has always had. An automatic prefetch — the viewport drifting to the newest
 * edge, which a successful send does on its own — recovers quietly instead: the loaded
 * conversation and the sent message are already correct, so the reader is told nothing, the
 * failure is logged with its privacy-safe operation code, and [AutomaticNewerPagingGuard] bounds
 * the retries so the effect cannot re-issue the page on every layout pass (#2764).
 */
@Suppress("TooGenericExceptionCaught", "ReturnCount")
internal suspend fun ConversationController.loadNewerPageInternal(origin: PagingOrigin): ConversationPageLoad {
    val subscription = timelineSubscription
    if (!hasMoreAfter || isLoadingOlder) return ConversationPageLoad.NO_PROGRESS
    if (subscription == null) return ConversationPageLoad.INACTIVE
    val automatic = origin == ConversationPagingOrigin.AUTOMATIC
    if (automatic && automaticNewerPaging.blocked) return ConversationPageLoad.NO_PROGRESS
    val priorMessageIds = timelineRecords.keys.toSet()
    // An opportunistic page must not clear a failure the reader can still act on; it clears only
    // the matching newer-page failure, and only once newer rows have actually arrived.
    if (!automatic) pageError = null
    isLoadingOlder = true
    val trace = PerformanceDiagnostics.begin(PerformanceOperation.CHAT_HISTORY_PAGE)
    val startedMs = SystemClock.elapsedRealtime()
    return try {
        val outcome = pageNewerIfActive(subscription, trace)
        when (outcome) {
            null -> ConversationPageLoad.INACTIVE
            is TimelinePageOutcome.Unchanged ->
                unchangedPageLoad(outcome, ConversationSearchPageDirection.NEWER, origin)
            is TimelinePageOutcome.Advanced -> applyNewerPage(outcome.page, priorMessageIds, trace)
        }.also { trace.recordCompletion(it, startedMs) }
    } catch (cancel: CancellationException) {
        trace.recordCompletion(ConversationPageLoad.INACTIVE, startedMs)
        throw cancel
    } catch (throwable: Throwable) {
        trace.recordCompletion(ConversationPageLoad.FAILED, startedMs)
        reportPageFailure(ConversationSearchPageDirection.NEWER, throwable, origin)
        ConversationPageLoad.FAILED
    } finally {
        isLoadingOlder = false
    }
}

/**
 * Folds a newer page in and settles the state a forward advance clears: the recovered failure, the
 * automatic-paging budget and the protected-row set. Returns [ConversationPageLoad.INACTIVE] when a
 * newer generation superseded the commit.
 */
private suspend fun ConversationController.applyNewerPage(
    page: TimelinePageFfi,
    priorMessageIds: Set<String>,
    trace: PerformanceTrace?,
): ConversationPageLoad {
    val appliedAtMs = SystemClock.elapsedRealtime()
    var committed = false
    tracedPagingSection(ConversationPagingTraceSection.APPLY) {
        applyTimelinePage(
            page,
            replaceWindow = false,
            updatePagination = true,
            reconcileNewExtendedRecords = true,
            onCommitted = { committed = true },
        )
    }
    if (!committed) return ConversationPageLoad.INACTIVE
    clearRecoveredNewerPageFailure()
    automaticNewerPaging.reset()
    protectedTimelineMessageIds.clear()
    if (hasLoadedOlderPages) {
        protectedTimelineMessageIds.addAll(timelineRecords.keys)
    }
    trace.recordPhase(phase = PerformancePhase.PAGE_APPLY, startedMs = appliedAtMs, count = page.messages.size)
    return progressPageLoad(priorMessageIds)
}

/**
 * Retires a newer-page failure once newer rows have arrived.
 *
 * Only the matching direction is cleared, so an older-page failure the reader still has a retry row
 * for, and any unrelated subscription error, survive a forward recovery.
 */
private fun ConversationController.clearRecoveredNewerPageFailure() {
    if (failedPageDirection != ConversationSearchPageDirection.NEWER) return
    failedPageDirection = null
    pageError = null
}

/**
 * "Made progress" = the window grew OR shifted to include ids the timeline did not hold. A page
 * returns a bounded, cap-trimmed full window, so its size can stay constant while its content still
 * advances.
 */
private fun ConversationController.progressPageLoad(priorMessageIds: Set<String>): ConversationPageLoad {
    val advanced =
        timelineRecords.size > priorMessageIds.size ||
            timelineRecords.keys.any { it !in priorMessageIds }
    return if (advanced) ConversationPageLoad.ADVANCED else ConversationPageLoad.NO_PROGRESS
}

/**
 * Reports the reader's oldest visible row, then pages older from the revision that report returns.
 *
 * Both calls stay inside the active-call mutex so a teardown cannot close the native handle between
 * them, and both re-check that this subscription is still the controller's.
 */
private suspend fun ConversationController.pageOlderIfActive(
    handle: PagingHandle,
    anchorId: String?,
    trace: PerformanceTrace? = null,
): TimelinePageOutcome? =
    timelineSubscriptionActiveCallMutex.withLock {
        if (!retainsSubscription(handle)) return@withLock null
        if (anchorId != null && retainsTimelineRecord(anchorId)) {
            val anchorStartedMs = SystemClock.elapsedRealtime()
            val anchored = withContext(Dispatchers.IO) { handle.setVisibleAnchor(anchorId) }
            trace.recordPhase(PerformancePhase.PAGE_ANCHOR, anchorStartedMs, PerformanceLayer.FFI)
            // Re-check before folding anything in, not after. Reconnect reassigns the subscription
            // under liveSubscriptionLock rather than this mutex, so the IO hop above can return a
            // window belonging to a handle this controller has already replaced; applying it first
            // would put a stale window on screen until the new subscription's snapshot lands.
            if (!retainsSubscription(handle)) return@withLock null
            if (anchored != null) {
                applyTimelinePage(anchored, replaceWindow = false, updatePagination = true)
            }
        }
        // Time the window command from here, not from the caller's start: page_window is documented
        // as the engine answering, and anchoring is already its own phase.
        timedWindowCommand(trace) {
            pageWithNotReadyBudget(handle) { it.paginateBackwards(ConversationTimelinePageLimit) }
        }
    }

/** Pages newer under the same active-call guard. */
private suspend fun ConversationController.pageNewerIfActive(
    handle: PagingHandle,
    trace: PerformanceTrace? = null,
): TimelinePageOutcome? =
    timelineSubscriptionActiveCallMutex.withLock {
        if (!retainsSubscription(handle)) return@withLock null
        timedWindowCommand(trace) {
            pageWithNotReadyBudget(handle) { it.paginateForwards(ConversationTimelinePageLimit) }
        }
    }

/**
 * Runs one page, waiting out a not-ready window for a bounded budget.
 *
 * Not-ready is MDK repairing itself in the background, so asking again shortly usually succeeds; the
 * budget is what keeps that from becoming the scroll-frame retry loop this replaces. Teardown is
 * re-checked between attempts so a closing account does not wait out the whole budget.
 */
private suspend fun ConversationController.pageWithNotReadyBudget(
    handle: PagingHandle,
    page: suspend (PagingHandle) -> TimelinePageOutcome,
): TimelinePageOutcome? {
    var outcome = withContext(Dispatchers.IO) { page(handle) }
    var attempt = 1
    while (outcome is TimelinePageOutcome.Unchanged &&
        outcome.reason == NOT_READY &&
        attempt < CONVERSATION_PAGE_NOT_READY_ATTEMPTS
    ) {
        delay(CONVERSATION_WINDOW_NOT_READY_RETRY_MS)
        if (!retainsSubscription(handle)) return null
        outcome = withContext(Dispatchers.IO) { page(handle) }
        attempt += 1
    }
    return outcome
}

/** Whether this subscription is still the controller's live one and the account is not tearing down. */
private fun ConversationController.retainsSubscription(handle: PagingHandle): Boolean =
    synchronized(liveSubscriptionLock) {
        !accountTeardownRequested && timelineSubscription === handle
    }

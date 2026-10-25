package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCacheRead
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardSnapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Newest visible sender metadata worth delaying the route for. */
internal fun initialPresentationProfileSenders(
    records: List<TimelineMessageRecordFfi>,
    maxProfiles: Int = INITIAL_PRESENTATION_PROFILE_LIMIT,
): List<String> {
    if (maxProfiles <= 0) return emptyList()
    val senders = linkedSetOf<String>()
    // Page order is a transport detail — rank by the timeline key instead, so
    // a wrong-direction or unsorted page cannot silently warm the oldest rows.
    val newestFirst =
        records.sortedWith(
            compareByDescending<TimelineMessageRecordFfi> { it.timelineAt }
                .thenByDescending { it.messageIdHex },
        )
    for (record in newestFirst) {
        if (record.sender.isNotBlank()) senders += record.sender
        record.replyPreview
            ?.sender
            ?.takeIf(String::isNotBlank)
            ?.let(senders::add)
        if (senders.size >= maxProfiles) break
    }
    return senders.take(maxProfiles)
}

/**
 * The local presentation a conversation's first page warms before the route shows it: sender profiles, and the
 * messages whose public event cards should restore from MDK's cache on their first frame (#2238).
 *
 * [hydratedRecords] is the page's in-flight Markdown hydration, when it has one. The card warm reads references from
 * its parsed records instead of parsing the same messages a second time.
 */
internal data class InitialPresentationWarmWork(
    val senders: List<String>,
    val eventCardRecords: List<TimelineMessageRecordFfi> = emptyList(),
    val hydratedRecords: Deferred<List<TimelineMessageRecordFfi>>? = null,
) {
    /** True when there is nothing to warm, so readiness need not wait. */
    fun isEmpty(): Boolean = senders.isEmpty() && eventCardRecords.isEmpty()
}

/**
 * Owns the bounded local-presentation barrier for a conversation's first visible page.
 *
 * Sender profiles and event cards warm side by side under separate budgets, both measured from the first page and
 * never restarted by a later one. Profiles keep the short [profileBudgetMillis]: an unwarmed name only fills in late.
 * A page that names event cards also waits for their cache read, up to [eventCardBudgetMillis], because an unwarmed
 * card would start as Loading and then grow under the reader (#2238). The controller keeps such a page unpublished
 * until [onReady], so its rows compose with their cards in place whichever path opened the conversation. Expiring the
 * profile budget never cancels the card warm. A newer authoritative page supersedes the older jobs, so stale work
 * can never release navigation for content that is no longer current.
 */
internal class ConversationInitialPresentationWarmCoordinator(
    private val scope: CoroutineScope,
    private val warmProfiles: suspend (List<String>) -> Unit,
    private val warmEventCards: suspend (InitialPresentationWarmWork) -> Unit,
    private val onReady: () -> Unit,
    private val profileBudgetMillis: Long = INITIAL_PRESENTATION_PROFILE_WARM_BUDGET_MILLIS,
    private val eventCardBudgetMillis: Long = INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS,
) {
    private val preparations = StalenessGuard()
    private var profileJob: Job? = null
    private var eventCardJob: Job? = null
    private var profileDeadline: Job? = null
    private var eventCardDeadline: Job? = null
    private var profileBudgetSpent = false
    private var profilesSettled = true
    private var eventCardsSettled = true
    private var ready = false

    // Completes once onReady has run, or when [scope] ends, since no warm or deadline is left then to run it.
    private val readiness =
        CompletableDeferred<Unit>().also { done ->
            scope.coroutineContext[Job]?.invokeOnCompletion { done.complete(Unit) }
        }

    /** Publishes readiness once and cancels every active warm and deadline. */
    private fun markReady() {
        if (ready) return
        ready = true
        listOf(profileJob, eventCardJob, profileDeadline, eventCardDeadline).forEach { it?.cancel() }
        onReady()
        // Only after onReady, so a caller resumed by it already sees what readiness published.
        readiness.complete(Unit)
    }

    /**
     * Suspends until readiness is published. Once a page is prepared, its budgets bound that wait, and it also ends
     * with [scope], which takes every warm and deadline that could publish readiness with it.
     */
    suspend fun awaitReady() {
        readiness.await()
    }

    /** Publishes readiness once both halves of the current page have settled or run out of budget. */
    private fun markReadyWhenSettled() {
        if (profilesSettled && eventCardsSettled) markReady()
    }

    /** Replaces the pending warm and admits readiness only for the newest page. */
    fun prepare(work: InitialPresentationWarmWork) {
        if (ready) return
        val token = preparations.advance()
        profileJob?.cancel()
        eventCardJob?.cancel()
        if (work.isEmpty()) {
            markReady()
            return
        }
        profilesSettled = work.senders.isEmpty() || profileBudgetSpent
        eventCardsSettled = work.eventCardRecords.isEmpty()
        profileJob =
            if (profilesSettled) null else launchWarm(token, { warmProfiles(work.senders) }) { profilesSettled = true }
        eventCardJob =
            if (eventCardsSettled) null else launchWarm(token, { warmEventCards(work) }) { eventCardsSettled = true }
        startDeadlines(eventCardsPending = !eventCardsSettled)
        markReadyWhenSettled()
    }

    /** Starts each budget once, on the first page that needs it, so later pages cannot extend it. */
    private fun startDeadlines(eventCardsPending: Boolean) {
        if (profileDeadline == null) {
            profileDeadline =
                scope.launch {
                    delay(profileBudgetMillis.coerceAtLeast(1L))
                    profileBudgetSpent = true
                    profileJob?.cancel()
                    profilesSettled = true
                    markReadyWhenSettled()
                }
        }
        if (eventCardsPending && eventCardDeadline == null) {
            eventCardDeadline =
                scope.launch {
                    delay(eventCardBudgetMillis.coerceAtLeast(1L))
                    // Bounded: a cold cache that still has not answered must not hold the route forever.
                    markReady()
                }
        }
    }

    /** Runs one optional warm, then reports it settled only while [token]'s page is still current. */
    private fun launchWarm(
        token: Long,
        warm: suspend () -> Unit,
        onSettled: () -> Unit,
    ): Job =
        scope.launch {
            try {
                warm()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // Profile and card presentation are optional visual warms. The
                // existing lazy paths remain authoritative on a local-read
                // failure, so it must not strand navigation.
            }
            preparations.runIfCurrent(token) {
                onSettled()
                markReadyWhenSettled()
            }
        }
}

/**
 * Reads MDK's cached cards for the first page into [snapshots] and returns how many references it read.
 *
 * Records are taken from the page's Markdown hydration when one is in flight, so the warm reuses that parse rather
 * than repeating it. A record that still has no parsed content names no card here, and its own resolver reads it.
 */
internal suspend fun warmInitialEventCardSnapshots(
    work: InitialPresentationWarmWork,
    snapshots: NostrEventCardSnapshots,
    readCached: NostrEventCacheRead,
): Int {
    val hydrated =
        work.hydratedRecords
            ?.await()
            .orEmpty()
            .associateBy(TimelineMessageRecordFfi::messageIdHex)
    val records = work.eventCardRecords.map { record -> hydrated[record.messageIdHex] ?: record }
    return snapshots.warm(records, readCached = readCached)
}

internal const val INITIAL_PRESENTATION_PROFILE_WARM_BUDGET_MILLIS = 100L

/**
 * How long a first page that names event cards may stay unpublished for their cached state, and how much longer
 * than usual a chat-list tap may wait for it.
 *
 * The read is cache-only and never touches the network. Its one slow case is the first native read after a cold
 * start, which opens the account's encrypted cache and waits for the page's Markdown hydration. That can take several
 * hundred milliseconds on a cold device, far beyond the profile budget, while a warm reopen settles in a few. One
 * second covers the cold case and keeps the worst tap-to-route delay at the point where a tap still reads as
 * answered. Past it the page publishes anyway and each card resolves itself.
 */
internal const val INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS = 1_000L
private const val INITIAL_PRESENTATION_PROFILE_LIMIT = 12

/**
 * How long a later page waits for its cached event cards before it is applied — the first page's card budget, so no
 * page waits longer for a card than an open does. A warm read settles in a few milliseconds, a cold one in tens.
 */
internal const val LATER_PAGE_EVENT_CARD_WARM_BUDGET_MILLIS = INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS

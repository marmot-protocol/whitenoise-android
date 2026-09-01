package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 * Owns the bounded local-profile barrier for a conversation's first visible page.
 * A newer authoritative page supersedes the older job, so stale work can never
 * release navigation for sender metadata that is no longer current.
 */
internal class ConversationInitialPresentationWarmCoordinator(
    private val scope: CoroutineScope,
    private val budgetMillis: Long,
    private val warm: suspend (List<String>) -> Unit,
    private val onReady: () -> Unit,
) {
    private val preparations = StalenessGuard()
    private var job: Job? = null
    private var deadlineJob: Job? = null
    private var ready = false

    /** Publishes readiness once and cancels both the active warm and its deadline. */
    private fun markReady() {
        if (ready) return
        ready = true
        job?.cancel()
        deadlineJob?.cancel()
        onReady()
    }

    /** Replaces the pending warm and admits readiness only for the newest sender page. */
    fun prepare(senders: List<String>) {
        if (ready) return
        val token = preparations.advance()
        job?.cancel()
        if (senders.isEmpty()) {
            job = null
            markReady()
            return
        }
        if (deadlineJob == null) {
            deadlineJob =
                scope.launch {
                    delay(budgetMillis.coerceAtLeast(1L))
                    // Successive authoritative pages may supersede the warm,
                    // but they must not restart the route's total budget.
                    markReady()
                }
        }
        job =
            scope.launch {
                try {
                    warm(senders)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                    // Profile presentation is an optional visual warm. The
                    // existing lazy path remains authoritative on a local-read
                    // failure, so it must not strand navigation.
                }
                preparations.runIfCurrent(token, ::markReady)
            }
    }
}

internal const val INITIAL_PRESENTATION_PROFILE_WARM_BUDGET_MILLIS = 100L
private const val INITIAL_PRESENTATION_PROFILE_LIMIT = 12

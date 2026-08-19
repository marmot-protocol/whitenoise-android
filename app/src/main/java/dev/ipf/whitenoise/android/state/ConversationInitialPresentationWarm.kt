package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Newest visible sender metadata worth delaying the route for. */
internal fun initialPresentationProfileSenders(
    records: List<TimelineMessageRecordFfi>,
    maxProfiles: Int = INITIAL_PRESENTATION_PROFILE_LIMIT,
): List<String> {
    if (maxProfiles <= 0) return emptyList()
    val senders = linkedSetOf<String>()
    for (record in records.asReversed()) {
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
    private var generation = 0L
    private var job: Job? = null

    fun prepare(senders: List<String>) {
        val token = ++generation
        job?.cancel()
        if (senders.isEmpty()) {
            job = null
            onReady()
            return
        }
        job =
            scope.launch {
                try {
                    withTimeoutOrNull(budgetMillis.coerceAtLeast(1L)) {
                        warm(senders)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                    // Profile presentation is an optional visual warm. The
                    // existing lazy path remains authoritative on a local-read
                    // failure, so it must not strand navigation.
                }
                if (token == generation) onReady()
            }
    }
}

internal const val INITIAL_PRESENTATION_PROFILE_WARM_BUDGET_MILLIS = 100L
private const val INITIAL_PRESENTATION_PROFILE_LIMIT = 12

package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Two threads of the IO pool kept for a later page's event-card read (#2238). The whole read runs on it, so each of
 * its binding calls hops IO to IO and resumes here instead of on the main thread, and the read needs one main-thread
 * turn, its final resume, however busy the main thread is. A `limitedParallelism` view of [Dispatchers.IO] gets threads
 * beyond the shared IO limit.
 */
internal object EventCardReadLane {
    val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(EVENT_CARD_READ_PARALLELISM)
}

/** Runs [block] on [EventCardReadLane]. */
internal suspend fun <T> onEventCardReadLane(block: suspend () -> T): T {
    val lane = EventCardReadLane.dispatcher
    return withContext(lane) { block() }
}

private const val EVENT_CARD_READ_PARALLELISM = 2

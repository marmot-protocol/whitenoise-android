package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marks a coroutine running on the event-card read lane (#2238). [WhiteNoiseAppState.marmotIo] runs a binding call
 * inline on the lane's thread when it sees this marker, so a later page's card read never queues behind bulk binding
 * work on the shared IO dispatcher, nor returns to the main thread between its parse and its cache reads.
 */
internal object EventCardReadLane : AbstractCoroutineContextElement(EventCardReadLaneKey) {
    /**
     * Two threads of the IO pool kept for card reads. A `limitedParallelism` view of [Dispatchers.IO] gets threads
     * beyond the shared IO limit, so busy binding work elsewhere cannot hold it up. MarmotKit's binding is thread-safe:
     * the same runtime already serves calls from every IO thread at once.
     */
    val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(EVENT_CARD_READ_PARALLELISM)
}

/** Runs [block] on the event-card read lane, where every nested binding call stays on the lane's thread. */
internal suspend fun <T> onEventCardReadLane(block: suspend () -> T): T {
    val lane = EventCardReadLaneContext
    return withContext(lane) { block() }
}

/** The card lane's dispatcher together with the marker that keeps nested binding calls on it. */
private val EventCardReadLaneContext: CoroutineContext = EventCardReadLane.dispatcher + EventCardReadLane

/** True inside [onEventCardReadLane], where a binding call runs inline instead of hopping to the shared IO pool. */
internal fun CoroutineContext.isOnEventCardReadLane(): Boolean = this[EventCardReadLaneKey] != null

/** The key under which [EventCardReadLane] sits in a coroutine context. */
private object EventCardReadLaneKey : CoroutineContext.Key<EventCardReadLane>

private const val EVENT_CARD_READ_PARALLELISM = 2

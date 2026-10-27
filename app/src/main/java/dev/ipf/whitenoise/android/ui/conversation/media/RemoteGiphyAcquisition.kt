package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.core.RemoteGiphyMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Shares only encoded bytes; each subscriber still owns its decoded presentation and permission. */
internal class RemoteGiphyAcquisition(
    private val scope: CoroutineScope,
    private val cache: RemoteGiphyByteCache,
    private val fetch: suspend (RemoteGiphyMedia.ImageRequest) -> ByteArray?,
) {
    private val lock = Any()
    private val flights = mutableMapOf<Key, Flight>()
    private val retainedFlights = Semaphore(6)
    private val workSlots = Semaphore(6)

    /** A retained controller is an account/runtime-bound owner, never a process-wide permission grant. */
    private data class Key(
        val owner: Any,
        val request: RemoteGiphyMedia.ImageRequest,
    )

    /** Holds a bounded response until every subscriber finishes decoding or cancels. */
    private class Flight {
        val subscribers = mutableMapOf<Any, () -> Boolean>()
        val bytes = CompletableDeferred<ByteArray?>()
        val released = CompletableDeferred<Unit>()
        lateinit var job: Job
    }

    /**
     * Joins before waiting for capacity, then rechecks the cache and live download permission at admission.
     * A cancelled subscriber retires only itself; the last subscriber cancels the transport and its slot.
     */
    @Suppress("ReturnCount") // Three fail-closed exits keep consent and absent-response handling explicit.
    suspend fun <T> useBytes(
        owner: Any,
        request: RemoteGiphyMedia.ImageRequest,
        mayDownload: () -> Boolean,
        consume: suspend (ByteArray) -> T?,
    ): T? {
        if (!mayDownload() && cache.get(request.url) == null) return null
        val key = Key(owner, request)
        val subscriber = Any()
        val flight = subscribe(key, subscriber, mayDownload)
        try {
            flight.job.start()
            val bytes = flight.bytes.await() ?: return null
            return workSlots.withPermit {
                val result = consume(bytes)
                if (currentCoroutineContext().isActive) {
                    if (result != null) cache.put(request.url, bytes) else cache.remove(request.url)
                }
                result
            }
        } finally {
            unsubscribe(key, flight, subscriber)
        }
    }

    /** Registers the first subscriber before the lazy job can inspect download consent. */
    private fun subscribe(
        key: Key,
        subscriber: Any,
        mayDownload: () -> Boolean,
    ): Flight =
        synchronized(lock) {
            flights
                .getOrPut(key) {
                    Flight().also { flight ->
                        flight.job = scope.launch(start = CoroutineStart.LAZY) { acquire(key, flight) }
                    }
                }.also { it.subscribers[subscriber] = mayDownload }
        }

    /** Keeps at most six uncached responses alive, with six total concurrent fetch/decode operations. */
    @Suppress("TooGenericExceptionCaught") // Share transport failures with waiters; cancellation still propagates.
    private suspend fun acquire(key: Key, flight: Flight) {
        try {
            retainedFlights.withPermit {
                val bytes =
                    workSlots.withPermit {
                        cache.get(key.request.url) ?: fetchIfAllowed(key, flight)
                    }
                currentCoroutineContext().ensureActive()
                flight.bytes.complete(bytes)
                if (bytes != null) flight.released.await()
            }
        } catch (cancelled: CancellationException) {
            flight.bytes.cancel(cancelled)
            throw cancelled
        } catch (failure: Exception) {
            flight.bytes.completeExceptionally(failure)
        }
    }

    /** A queued request cannot inherit consent from a subscriber that left or revoked permission. */
    private suspend fun fetchIfAllowed(
        key: Key,
        flight: Flight,
    ): ByteArray? {
        val allowed = synchronized(lock) { flight.subscribers.values.any { it() } }
        if (!allowed) return null
        return fetch(key.request)?.takeIf { it.isNotEmpty() && it.size <= GIPHY_MAX_BODY_BYTES }
    }

    /** Removes by identity so late cancellation cannot retire a replacement flight for the same URL. */
    private fun unsubscribe(
        key: Key,
        flight: Flight,
        subscriber: Any,
    ) {
        synchronized(lock) {
            flight.subscribers.remove(subscriber)
            if (flight.subscribers.isEmpty()) {
                if (flights[key] === flight) flights.remove(key)
                flight.released.complete(Unit)
                flight.job.cancel()
            }
        }
    }
}

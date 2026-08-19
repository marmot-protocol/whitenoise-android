package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the blocking local-profile primitive used by the
 * bounded first-presentation route barrier. Timeline data publishes immediately;
 * MainShell keeps the source route visible until the newest-author warm finishes
 * or its budget expires. These tests pin why the coordinator must await the warm
 * instead of launching it and declaring the destination ready synchronously.
 */
class WarmProfilesBeforePublishTest {
    /** Minimal stand-in for the presentation cache the rows read on first paint. */
    private class FakePresentationCache {
        private val byId = mutableMapOf<String, String>()

        // Off-main local read + apply, mirroring materializeProfileLocally:
        // the value isn't visible until this suspending read completes.
        suspend fun materialize(
            id: String,
            value: String,
        ) {
            withContext(Dispatchers.IO) {
                // Simulate the FFI/local-storage read latency that, under the
                // old fire-and-forget warm, lost the race against publish.
                yieldThenSet { byId[id] = value }
            }
        }

        // Snapshot what a row would read at publish time. A missing entry is the
        // ProfilePresentation.Empty -> flicker case.
        fun read(id: String): String = byId[id] ?: EMPTY

        private suspend fun yieldThenSet(set: () -> Unit) {
            // Hand control back at least once so a non-awaited (launched) warm
            // genuinely interleaves after the synchronous publish, the way the
            // production coroutine did.
            kotlinx.coroutines.yield()
            set()
        }

        companion object {
            const val EMPTY = "<empty>"
        }
    }

    @Test
    fun blockingWarmPopulatesCacheBeforeRouteBarrierRelease() =
        runBlocking {
            val cache = FakePresentationCache()
            val senders = listOf("alice", "bob")

            // The route barrier awaits the local warm before releasing.
            for (id in senders) cache.materialize(id, name(id))
            val observedAtRelease = senders.associateWith(cache::read)

            // Every sender's name is present on the first (and only) paint.
            assertEquals(
                mapOf("alice" to "Alice", "bob" to "Bob"),
                observedAtRelease,
            )
        }

    @Test
    fun cachedDmRowPublicationAwaitsLocalPeerPresentation() =
        runBlocking {
            val cache = FakePresentationCache()
            val peerId = "alice"

            // The chat-list path now follows the same contract as timeline
            // publication: roster projection identifies the peer, then its
            // local profile materialization completes before the row publishes.
            cache.materialize(peerId, name(peerId))
            val observedFirstFrame = cache.read(peerId)

            assertEquals("Alice", observedFirstFrame)
        }

    @Test
    fun routeBarrierWouldObserveEmptyWhenWarmIsFireAndForget() =
        runBlocking {
            val cache = FakePresentationCache()
            val senders = listOf("alice", "bob")
            val warmDone = CompletableDeferred<Unit>()

            // A launch-and-return barrier releases before the warm can land.
            launch {
                for (id in senders) cache.materialize(id, name(id))
                warmDone.complete(Unit)
            }
            val observedAtPublish = senders.associateWith(cache::read)

            // This is the flicker: the publish painted before the warm landed.
            assertEquals(
                mapOf(
                    "alice" to FakePresentationCache.EMPTY,
                    "bob" to FakePresentationCache.EMPTY,
                ),
                observedAtPublish,
            )

            // Sanity: the warm does eventually complete (a frame too late).
            warmDone.await()
            assertEquals("Alice", cache.read("alice"))
        }

    private fun name(id: String): String = id.replaceFirstChar(Char::uppercase)
}

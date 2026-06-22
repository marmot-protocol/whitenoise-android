package dev.ipf.darkmatter.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the #609 fix's ordering contract.
 *
 * The bug the adversarial review flagged: the profile pre-warm on chat open was
 * fire-and-forget. `applyTimelinePage` kicked off asynchronous materialization
 * jobs and then *immediately, synchronously* published the timeline page. The
 * first composition could therefore observe `ProfilePresentation.Empty` for a
 * sender before its (still in-flight) materialization landed — the exact
 * avatar/username "pop-in" flicker the issue is about.
 *
 * The fix makes the warm a *suspending* step that the page-apply path awaits
 * before publishing, so the presentation cache is guaranteed populated by the
 * time the publish (and thus the first composition) runs. In production this is
 * `DarkMatterAppState.warmProfilePresentationsBlocking(...)` awaited inside the
 * now-`suspend` `applyTimelinePage` ahead of `publishTimelineFromIndexes()`.
 *
 * `DarkMatterAppState` needs an Android `Context` + the real Marmot FFI, so it
 * can't be instantiated in a JVM unit test. These tests instead pin the
 * structural invariant the fix relies on — *await the off-main warm, THEN
 * publish* — against a fake materializer, and prove the contrast with the old
 * fire-and-forget shape that produced the flicker. If someone reverts the warm
 * to a launch-and-return, [publishObservesEmptyWhenWarmIsFireAndForget]
 * documents exactly what breaks.
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
    fun awaitedWarmPopulatesCacheBeforePublish() =
        runBlocking {
            val cache = FakePresentationCache()
            val senders = listOf("alice", "bob")

            // The fix: applyTimelinePage awaits the warm, THEN publishes.
            for (id in senders) cache.materialize(id, name(id))
            val observedAtPublish = senders.associateWith(cache::read)

            // Every sender's name is present on the first (and only) paint.
            assertEquals(
                mapOf("alice" to "Alice", "bob" to "Bob"),
                observedAtPublish,
            )
        }

    @Test
    fun publishObservesEmptyWhenWarmIsFireAndForget() =
        runBlocking {
            val cache = FakePresentationCache()
            val senders = listOf("alice", "bob")
            val warmDone = CompletableDeferred<Unit>()

            // The old bug: launch-and-return warm, then publish synchronously
            // without awaiting. Publish wins the race; rows read Empty.
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

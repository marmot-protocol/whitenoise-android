package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OutgoingAttachmentCachePublicationsTest {
    /** A file beyond L1 admission waits for its existing write; unrelated files remain immediately accessible. */
    @Test
    fun cacheMissWaitsForMatchingPublicationOnly() =
        runTest {
            val publications = OutgoingAttachmentCachePublications(backgroundScope)
            val release = CompletableDeferred<Unit>()
            var diskReady = false
            publications.publish("large-zip", "token") {
                release.await()
                diskReady = true
            }
            assertTrue(publications.isPending("large-zip"))
            assertFalse(publications.isPending("different-file"))
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val enteredDiskProbe = CompletableDeferred<Unit>()
                val cardProbe =
                    async {
                        resolveAttachmentCacheAvailability(
                            "large-zip",
                            memoryContains = { false },
                            diskContains = {
                                enteredDiskProbe.complete(Unit)
                                publications.await(it)
                                diskReady
                            },
                        )
                    }
                enteredDiskProbe.await()
                assertFalse(cardProbe.isCompleted)
                publications.await("different-file")
                release.complete(Unit)
                assertTrue(cardProbe.await())
                assertFalse(publications.isPending("large-zip"))
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** Identical handoffs coalesce, while cancelling an observer cannot cancel durable local publication. */
    @Test
    fun observerCancellationLeavesOneSharedPublicationRunning() =
        runTest {
            val publications = OutgoingAttachmentCachePublications(backgroundScope)
            val release = CompletableDeferred<Unit>()
            var writes = 0
            publications.publish("zip", "token") {
                writes++
                release.await()
            }
            publications.publish("zip", "token") { error("duplicate write") }
            val observer = async { publications.await("zip") }
            runCurrent()
            observer.cancelAndJoin()
            assertTrue(observer.isCancelled)
            assertTrue(publications.isPending("zip"))
            release.complete(Unit)
            publications.await("zip")
            assertEquals(1, writes)
        }

    /** A failed encrypted copy leaves the cache unavailable without failing its waiting availability probe. */
    @Test
    fun failedPublicationDoesNotFailAvailabilityProbe() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val publications = OutgoingAttachmentCachePublications(scope)
            val release = CompletableDeferred<Unit>()
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                publications.publish("zip", "token") {
                    release.await()
                    throw IOException("generated encrypted cache write failure")
                }
                val publicationObserver = async { publications.await("zip") }
                val probe =
                    async {
                        resolveAttachmentCacheAvailability(
                            "zip",
                            memoryContains = { false },
                            diskContains = {
                                publications.await(it)
                                false
                            },
                        )
                    }
                runCurrent()
                assertFalse(publicationObserver.isCompleted)
                assertFalse(probe.isCompleted)
                release.complete(Unit)
                publicationObserver.await()
                assertFalse(probe.await())
                assertFalse(publications.isPending("zip"))
            } finally {
                scope.cancel()
                Dispatchers.resetMain()
            }
        }

    /** An old account/cache incarnation completing cannot remove the newer publication owner. */
    @Test
    fun oldCompletionCannotRetireReplacement() =
        runTest {
            val publications = OutgoingAttachmentCachePublications(backgroundScope)
            val old = CompletableDeferred<Unit>()
            val replacement = CompletableDeferred<Unit>()
            publications.publish("zip", "old-token") { old.await() }
            publications.publish("zip", "new-token") { replacement.await() }
            old.complete(Unit)
            runCurrent()
            val observer = async { publications.await("zip") }
            runCurrent()
            assertFalse(observer.isCompleted)
            replacement.complete(Unit)
            observer.await()
        }
}

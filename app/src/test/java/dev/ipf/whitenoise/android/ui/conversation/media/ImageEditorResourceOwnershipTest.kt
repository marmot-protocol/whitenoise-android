package dev.ipf.whitenoise.android.ui.conversation.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ImageEditorResourceOwnershipTest {
    @Test
    fun cancellationAtDispatcherHandoffReclaimsCreatedResource() =
        runBlocking {
            val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val producerStarted = CountDownLatch(1)
            val releaseProducer = CountDownLatch(1)
            var delivered = false
            var cleaned = false
            try {
                val job =
                    launch {
                        delivered =
                            createOwnedResource(
                                workContext = worker,
                                create = {
                                    producerStarted.countDown()
                                    check(releaseProducer.await(5, TimeUnit.SECONDS))
                                    Any()
                                },
                                cleanup = { cleaned = true },
                            ) != null
                    }
                withTimeout(5_000L) {
                    withContext(Dispatchers.IO) {
                        check(producerStarted.await(5, TimeUnit.SECONDS))
                    }
                }

                job.cancel()
                releaseProducer.countDown()
                job.cancelAndJoin()

                assertFalse("a cancelled caller must not receive the resource", delivered)
                assertTrue("producer-owned resource must be reclaimed", cleaned)
            } finally {
                releaseProducer.countDown()
                worker.close()
            }
        }

    @Test(timeout = 10_000L)
    fun previewAndSaveNativeWorkAreSerializedPerEditorSession() =
        runBlocking {
            val mutex = Mutex()
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val active = AtomicInteger(0)
            val maximumActive = AtomicInteger(0)

            val jobs =
                listOf(
                    async {
                        createSerializedOwnedResource(
                            mutex = mutex,
                            workContext = Dispatchers.Default,
                            create = {
                                val now = active.incrementAndGet()
                                maximumActive.updateAndGet { previous -> maxOf(previous, now) }
                                firstEntered.complete(Unit)
                                releaseFirst.await()
                                active.decrementAndGet()
                                Any()
                            },
                            cleanup = {},
                        )
                    },
                    async {
                        firstEntered.await()
                        createSerializedOwnedResource(
                            mutex = mutex,
                            workContext = Dispatchers.Default,
                            create = {
                                val now = active.incrementAndGet()
                                maximumActive.updateAndGet { previous -> maxOf(previous, now) }
                                active.decrementAndGet()
                                Any()
                            },
                            cleanup = {},
                        )
                    },
                )

            firstEntered.await()
            releaseFirst.complete(Unit)
            jobs.awaitAll()

            assertTrue("native render workers must not overlap", maximumActive.get() == 1)
        }

    @Test(timeout = 10_000L)
    fun cancelledOwnerReleasesSourceOnlyAfterNativeWorkUnlocks() =
        runBlocking {
            val mutex = Mutex(locked = true)
            var cleaned = false
            val ownerStarted = CompletableDeferred<Unit>()
            val owner =
                launch {
                    try {
                        ownerStarted.complete(Unit)
                        awaitCancellation()
                    } finally {
                        releaseSerializedOwnedResource(
                            mutex = mutex,
                            resource = Any(),
                            cleanup = { cleaned = true },
                        )
                    }
                }

            ownerStarted.await()
            owner.cancel()
            assertNull("cleanup must wait for native work", withTimeoutOrNull(100L) { owner.join() })
            assertFalse(cleaned)

            mutex.unlock()
            owner.join()
            assertTrue("cancelled owner must still release its resource", cleaned)
        }
}

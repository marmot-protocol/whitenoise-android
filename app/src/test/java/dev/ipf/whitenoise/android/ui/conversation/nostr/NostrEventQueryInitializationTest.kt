package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.nostr.NostrRelayQueryClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NostrEventQueryInitializationTest {
    /** Building the conversation resolver must not initialize networking until a query needs it. */
    @Test
    fun clientIsCreatedOffCallerThreadOnceAcrossConcurrentQueries() =
        runBlocking {
            val callerThread = Thread.currentThread()
            val creations = AtomicInteger()
            val query =
                defaultNostrEventQuery {
                    assertNotEquals(callerThread, Thread.currentThread())
                    creations.incrementAndGet()
                    NostrRelayQueryClient(WebSocket.Factory { _, _ -> error("No endpoint should be opened") })
                }
            assertEquals(0, creations.get())

            List(4) {
                async {
                    val failure = runCatching { query(emptyList(), JSONObject()) }.exceptionOrNull()
                    assertTrue(failure is IllegalArgumentException)
                }
            }.awaitAll()

            assertEquals(1, creations.get())
        }

    /** A cancelled first consumer cannot cancel or duplicate the next consumer's client initialization. */
    @Test
    fun cancellingFirstQueryDoesNotPoisonTheSharedClient() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val creations = AtomicInteger()
            val query =
                defaultNostrEventQuery {
                    creations.incrementAndGet()
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                    NostrRelayQueryClient(WebSocket.Factory { _, _ -> error("No endpoint should be opened") })
                }
            try {
                withTimeout(5_000) {
                    val first = launch { query(emptyList(), JSONObject()) }
                    entered.await()
                    first.cancel()
                    val second =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            runCatching { query(emptyList(), JSONObject()) }.exceptionOrNull()
                        }
                    release.countDown()
                    first.join()
                    assertTrue(first.isCancelled)
                    assertTrue(second.await() is IllegalArgumentException)
                    assertEquals(1, creations.get())
                }
            } finally {
                release.countDown()
            }
        }
}

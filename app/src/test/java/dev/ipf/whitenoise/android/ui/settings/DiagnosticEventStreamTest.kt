package dev.ipf.whitenoise.android.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Diagnostics event stream releases its subscription exactly once on every exit (#3718). */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticEventStreamTest {
    /** A fake subscription that counts explicit releases. */
    private class FakeSubscription {
        val events = Channel<String?>(Channel.UNLIMITED)
        var releases = 0
    }

    /** Leaving the screen while the stream waits for an event releases the subscription. */
    @Test
    fun cancellingWhileWaitingReleasesOnce() =
        runBlocking {
            val subscription = FakeSubscription()
            val waiting = CompletableDeferred<Unit>()
            val job =
                launch {
                    streamDiagnosticEvents(
                        acquire = { keep -> keep(subscription) },
                        next = {
                            waiting.complete(Unit)
                            awaitCancellation()
                        },
                        release = { it.releases++ },
                        onEvent = {},
                    )
                }
            withTimeout(5_000) { waiting.await() }
            job.cancelAndJoin()

            assertEquals(1, subscription.releases)
        }

    /** A cancellation after the subscription was acquired, before its acquisition returned, still releases it. */
    @Test
    fun cancellationDuringAcquisitionReturnStillReleases() =
        runBlocking {
            val subscription = FakeSubscription()
            val acquired = CompletableDeferred<Unit>()
            val job =
                launch {
                    streamDiagnosticEvents<FakeSubscription, String>(
                        acquire = { keep ->
                            keep(subscription)
                            acquired.complete(Unit)
                            awaitCancellation()
                        },
                        next = { error("never reached") },
                        release = { it.releases++ },
                        onEvent = {},
                    )
                }
            withTimeout(5_000) { acquired.await() }
            job.cancelAndJoin()

            assertEquals(1, subscription.releases)
        }

    /** A stream that ends normally delivers its events and releases once. */
    @Test
    fun normalEndDeliversEventsAndReleasesOnce() =
        runBlocking {
            val subscription = FakeSubscription()
            subscription.events.trySend("first")
            subscription.events.trySend("second")
            subscription.events.trySend(null)
            val seen = mutableListOf<String>()

            streamDiagnosticEvents(
                acquire = { keep -> keep(subscription) },
                next = { it.events.receive() },
                release = { it.releases++ },
                onEvent = seen::add,
            )

            assertEquals(listOf("first", "second"), seen)
            assertEquals(1, subscription.releases)
        }

    /** A failed receive propagates to the screen after releasing the subscription once. */
    @Test
    fun receiveFailureReleasesOnceAndPropagates() =
        runBlocking {
            val subscription = FakeSubscription()
            val failure =
                runCatching {
                    streamDiagnosticEvents<FakeSubscription, String>(
                        acquire = { keep -> keep(subscription) },
                        next = { error("stream closed") },
                        release = { it.releases++ },
                        onEvent = {},
                    )
                }.exceptionOrNull()

            assertEquals("stream closed", failure?.message)
            assertEquals(1, subscription.releases)
        }
}

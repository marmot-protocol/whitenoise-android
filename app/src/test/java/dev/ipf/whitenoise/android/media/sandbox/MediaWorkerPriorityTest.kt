package dev.ipf.whitenoise.android.media.sandbox

import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * Foreground priority in the domain-bound worker pool (#3819), through the real transport and pool. Each source's
 * length is its id, so a test can check which input reached which worker generation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class)
class MediaWorkerPriorityTest {
    /** Loads Robolectric's native runtime on the test thread before any decode thread does, as the domain tests do. */
    @Before
    fun loadNativeRuntimeOnTheTestThread() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), validReplyAt = { true })
        val transport = domainLane(context, liveLimit = MediaWorkerPool.MAX_LIVE_GENERATIONS)
        try {
            val bytes = byteArrayOf(1)
            MediaTrustScope.within(A) { transport.decode(bytes, 1, false, MediaDecodePolicy.DECODE) }?.recycle()
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /**
     * When the only slot frees, the conversation on screen gets it ahead of a domain that was already waiting, so an
     * opened chat's images do not queue behind the chat list's avatar decodes. The earlier claim is served after it,
     * by evicting the foreground worker as the last idle one rather than waiting out its deadline.
     */
    @Test
    fun aPrioritizedDomainTakesTheNextSlotAheadOfAnEarlierClaim() {
        val context =
            BindingFixture(
                ApplicationProvider.getApplicationContext(),
                replyAt = { it != 0 },
                validReplyAt = { it != 0 },
                dieOnRetire = true,
            )
        val transport = domainLane(context, liveLimit = 1)
        val held = decodeIn(transport, 1, A)
        try {
            val first = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            val pool = privateField(transport, "pool") as MediaWorkerPool
            val waiting = decodeIn(transport, 2, B)
            awaitDemand(pool, "slotDemand", 1)
            transport.prioritizeDomain(C, prioritized = true)
            val foreground = decodeIn(transport, 3, C)
            awaitDemand(pool, "priorityDemand", 1)
            assertTrue(withWorkerIdentity { first.remote.deliverHeldReply(successfulPixelReply()) })
            finish(held)
            finish(foreground)
            finish(waiting)
            assertEquals(
                listOf(3),
                context.bindings[1]
                    .remote.inputs
                    .toList(),
            )
            assertEquals(
                listOf(2),
                context.bindings[2]
                    .remote.inputs
                    .toList(),
            )
        } finally {
            held.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /** The conversation on screen keeps its idle worker: a new domain evicts another one, even a more recent one. */
    @Test
    fun aPrioritizedDomainsIdleWorkerIsEvictedLast() {
        val context =
            BindingFixture(ApplicationProvider.getApplicationContext(), validReplyAt = { true }, dieOnRetire = true)
        val transport = domainLane(context, liveLimit = 2)
        try {
            transport.prioritizeDomain(A, prioritized = true)
            finish(decodeIn(transport, 1, A))
            finish(decodeIn(transport, 2, B))
            finish(decodeIn(transport, 3, C))
            assertEquals(
                "A's worker stays",
                1L,
                context.bindings[0]
                    .remote.retired.count,
            )
            assertEquals(
                "B's worker was evicted",
                0L,
                context.bindings[1]
                    .remote.retired.count,
            )
            assertEquals(
                listOf(3),
                context.bindings[2]
                    .remote.inputs
                    .toList(),
            )

            transport.prioritizeDomain(A, prioritized = false)
            finish(decodeIn(transport, 4, B))
            assertEquals(
                "A is evictable again once off screen",
                0L,
                context.bindings[0]
                    .remote.retired.count,
            )
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /**
     * A chat closed while its claim waits for a slot stops counting as foreground, so neither that claim nor another
     * domain's waits out its budget once the slot frees.
     */
    @Test
    fun aClaimWhoseChatClosesWhileItWaitsHoldsNoOneBack() {
        val context =
            BindingFixture(
                ApplicationProvider.getApplicationContext(),
                replyAt = { it != 0 },
                validReplyAt = { it != 0 },
                dieOnRetire = true,
            )
        val transport = domainLane(context, liveLimit = 1)
        val held = decodeIn(transport, 1, A)
        try {
            val first = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            transport.prioritizeDomain(C, prioritized = true)
            val closed = decodeIn(transport, 3, C)
            awaitDemand(privateField(transport, "pool") as MediaWorkerPool, "priorityDemand", 1)
            transport.prioritizeDomain(C, prioritized = false)
            val other = decodeIn(transport, 2, B)
            assertTrue(withWorkerIdentity { first.remote.deliverHeldReply(successfulPixelReply()) })
            finish(held)
            listOf(closed, other).forEach { call ->
                call.join()
                assertNotNull("served within its budget", call.result.get())
                call.result.get()?.recycle()
            }
        } finally {
            held.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /**
     * A foreground claim that ends up waiting on its own domain's busy worker no longer counts, so another domain still
     * starts a worker in a free slot instead of waiting for that claim to be served.
     */
    @Test
    fun aClaimWaitingOnItsOwnBusyWorkerLeavesFreeSlotsToOthers() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), connectAt = { it != 0 })
        val pool = MediaWorkerPool(context, "priority", spares = 0, lock = Any(), liveLimit = 3, ended = {})
        val claimed = java.util.concurrent.LinkedBlockingQueue<MediaWorker>()
        pool.prioritize(C)
        val foreground =
            List(2) {
                Thread { pool.claim(C, true, DEADLINE_MS) { true }?.let(claimed::put) }.apply { start() }
            }
        try {
            val starting = context.next()
            awaitDemand(pool, "priorityDemand", foreground.size)
            starting.connection.onServiceConnected(context.component, starting.remote)
            val busy = checkNotNull(claimed.poll(2, TimeUnit.SECONDS)) { "one foreground claim was served" }
            val other = pool.claim(B, true, DEADLINE_MS) { true }
            assertNotNull("B takes a free slot while C's worker is held", other)
            pool.release(busy)
            assertNotNull("the second foreground claim reuses its worker", claimed.poll(2, TimeUnit.SECONDS))
        } finally {
            foreground.forEach { it.join(DEADLINE_MS) }
            pool.close()
            context.dieAll()
        }
    }

    /** Waits for the pool to record demand while the test still controls the occupied worker. */
    private fun awaitDemand(
        pool: MediaWorkerPool,
        field: String,
        expected: Int,
    ) {
        val lock = privateField(pool, "lock")

        fun demand(): Int = synchronized(lock) { privateField(pool, field) as Int }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS)
        while (demand() != expected && System.nanoTime() < deadline) Thread.sleep(1L)
        assertEquals(field, expected, demand())
    }

    /** A domain lane with no spares, so every new domain must start or evict a generation. */
    private fun domainLane(
        context: BindingFixture,
        liveLimit: Int,
    ): IsolatedMediaTransport =
        IsolatedMediaTransport(
            context,
            "images",
            spares = 0,
            deadlineMs = DEADLINE_MS,
            liveLimit = liveLimit,
        )

    /** Starts a decode of a source of length [id] in [domain] on its own thread. */
    private fun decodeIn(
        transport: IsolatedMediaTransport,
        id: Int,
        domain: MediaTrustDomain,
    ): DecodeCall = startDecode(transport, id, domain, sized = true)

    /** Waits for [call], then recycles any pixels it produced. */
    private fun finish(call: DecodeCall) {
        call.join()
        call.result.get()?.recycle()
    }

    private companion object {
        val A = MediaTrustDomain.Conversation("account", "a")
        val B = MediaTrustDomain.Conversation("account", "b")
        val C = MediaTrustDomain.Conversation("account", "c")
        const val DEADLINE_MS = 10_000L
    }
}

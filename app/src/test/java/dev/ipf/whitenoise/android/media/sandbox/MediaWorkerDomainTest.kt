package dev.ipf.whitenoise.android.media.sandbox

import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * Domain-bound worker generations through the real transport and pool. Each source's length is its id and
 * each fake endpoint records the lengths it receives, so a test can check which inputs reached which worker.
 * Android process death and UID separation need the device tests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class)
class MediaWorkerDomainTest {
    /** A domain's later inputs reuse its generation, and no input of another domain ever reaches it. */
    @Test
    fun domainGenerationServesOnlyItsOwnDomain() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), validReplyAt = { true })
        val transport = domainLane(context)
        try {
            for ((value, domain) in listOf(1 to A, 2 to B, 3 to A, 4 to B)) finish(decodeIn(transport, value, domain))
            assertEquals(2, context.bindings.size)
            assertEquals(
                listOf(1, 3),
                context.bindings[0]
                    .remote.inputs
                    .toList(),
            )
            assertEquals(
                listOf(2, 4),
                context.bindings[1]
                    .remote.inputs
                    .toList(),
            )
            assertTrue("A reused generation is not retired", context.bindings.all { it.remote.retired.count == 1L })
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /** Under concurrent requests from more domains than slots, every worker still sees one domain only. */
    @Test
    fun concurrentDomainsNeverShareAGeneration() {
        val context =
            BindingFixture(ApplicationProvider.getApplicationContext(), validReplyAt = { true }, dieOnRetire = true)
        val transport = domainLane(context)
        val domains = listOf(A, B, C, D)
        var peak = 0
        val callers =
            domains.mapIndexed { index, domain ->
                Thread {
                    repeat(INPUTS_PER_DOMAIN) { input -> finish(decodeIn(transport, index * 16 + input + 1, domain)) }
                }.apply { start() }
            }
        try {
            while (callers.any(Thread::isAlive)) {
                peak = maxOf(peak, transport.liveGenerationsForTest())
                Thread.sleep(1)
            }
            callers.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
            val served =
                context.bindings.map { bound ->
                    bound.remote.inputs
                        .map { (it - 1) / 16 }
                        .toSet()
                }
            assertTrue("Every input was served: $served", served.sumOf { it.size } >= domains.size)
            assertTrue("A worker saw two domains: $served", served.all { it.size <= 1 })
            assertEquals(domains.size * INPUTS_PER_DOMAIN, context.bindings.sumOf { it.remote.requests })
            assertTrue("Live generations reached $peak", peak <= MediaWorkerPool.MAX_LIVE_GENERATIONS)
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /** A request in one conversation is admitted and served while another conversation's request is in flight. */
    @Test
    fun crossDomainRequestsAreAdmittedInParallel() {
        val context =
            BindingFixture(
                ApplicationProvider.getApplicationContext(),
                replyAt = { it != 0 },
                validReplyAt = { it != 0 },
            )
        val transport = domainLane(context)
        val held = decodeIn(transport, 1, A)
        try {
            val first = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            val other = decodeIn(transport, 2, B)
            finish(other)
            assertTrue("A's request is still in flight", held.thread.isAlive)
            assertEquals(
                listOf(2),
                context.bindings[1]
                    .remote.inputs
                    .toList(),
            )
            assertTrue(withWorkerIdentity { first.remote.deliverHeldReply(successfulPixelReply()) })
            finish(held)
        } finally {
            held.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /** Evicting the least recently used idle domain frees its slot only once that worker has died. */
    @Test
    fun evictionWaitsForTheVictimsDeath() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), validReplyAt = { true })
        val transport = domainLane(context, liveLimit = 2)
        var third: DecodeCall? = null
        try {
            finish(decodeIn(transport, 1, A))
            finish(decodeIn(transport, 2, B))
            third = decodeIn(transport, 3, C)
            val victim = context.bindings[0]
            assertTrue("The least recently used domain is evicted", victim.remote.retired.await(2, TimeUnit.SECONDS))
            Thread.sleep(FENCE_OBSERVATION_MS)
            assertEquals("No new worker before the victim's death", 2, context.bindings.size)
            assertTrue(third.thread.isAlive)
            victim.remote.die()
            finish(third)
            assertEquals(3, context.bindings.size)
            assertEquals(
                listOf(3),
                context.bindings[2]
                    .remote.inputs
                    .toList(),
            )
            assertEquals(
                1L,
                context.bindings[1]
                    .remote.retired.count,
            )
        } finally {
            third?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /** A failed reply retires the domain's worker, quarantines its source, and the domain waits for that death. */
    @Test
    fun failedReplyRetiresTheDomainWorkerAndQuarantinesItsSource() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), validReplyAt = { it != 0 })
        val transport = domainLane(context)
        var next: DecodeCall? = null
        try {
            finish(decodeIn(transport, 1, A))
            val failed = context.bindings[0]
            assertTrue(failed.remote.retired.await(2, TimeUnit.SECONDS))
            assertEquals(1, refusals(transport).size)
            finish(decodeIn(transport, 1, A))
            assertEquals("Quarantined bytes start no worker", 1, context.bindings.size)
            next = decodeIn(transport, 2, A)
            Thread.sleep(FENCE_OBSERVATION_MS)
            assertEquals("The domain waits for its failed worker's death", 1, context.bindings.size)
            failed.remote.die()
            finish(next)
            assertEquals(
                listOf(2),
                context.bindings[1]
                    .remote.inputs
                    .toList(),
            )
        } finally {
            next?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /** A request without a declared domain, or declared Unique, still gets a single-use generation. */
    @Test
    fun undeclaredDomainStaysSingleUse() {
        val context =
            BindingFixture(ApplicationProvider.getApplicationContext(), validReplyAt = { true }, dieOnRetire = true)
        val transport = domainLane(context)
        try {
            val caller = Thread { transport.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DECODE)?.recycle() }
            caller.start()
            caller.join(TimeUnit.SECONDS.toMillis(2))
            assertFalse(caller.isAlive)
            finish(decodeIn(transport, 2, MediaTrustDomain.Unique))
            finish(decodeIn(transport, 3, MediaTrustDomain.Unique))
            assertEquals(3, context.bindings.size)
            assertTrue(context.bindings.all { it.remote.requests == 1 && it.remote.retired.count == 0L })
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /** An account reset retires every domain's worker, and no old worker serves the new session. */
    @Test
    fun accountResetRetiresEveryDomainWorker() {
        val context =
            BindingFixture(ApplicationProvider.getApplicationContext(), validReplyAt = { true }, dieOnRetire = true)
        val transport = domainLane(context)
        try {
            finish(decodeIn(transport, 1, A))
            finish(decodeIn(transport, 2, B))
            transport.activateSession()
            assertTrue(context.bindings.all { it.remote.retired.await(2, TimeUnit.SECONDS) })
            finish(decodeIn(transport, 3, A))
            assertEquals(3, context.bindings.size)
            assertEquals(
                listOf(3),
                context.bindings[2]
                    .remote.inputs
                    .toList(),
            )
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /** A domain's worker is replaced after its input cap, so no generation serves a domain forever. */
    @Test
    fun domainWorkerIsReplacedAfterItsInputCap() {
        val context =
            BindingFixture(ApplicationProvider.getApplicationContext(), validReplyAt = { true }, dieOnRetire = true)
        val transport = domainLane(context)
        try {
            repeat(MediaWorkerPool.DOMAIN_INPUT_CAP) { finish(decodeIn(transport, it + 1, A)) }
            val spent = context.bindings[0]
            assertTrue(spent.remote.retired.await(2, TimeUnit.SECONDS))
            assertEquals(MediaWorkerPool.DOMAIN_INPUT_CAP, spent.remote.requests)
            finish(decodeIn(transport, MediaWorkerPool.DOMAIN_INPUT_CAP + 1, A))
            assertEquals(2, context.bindings.size)
            assertEquals(1, context.bindings[1].remote.requests)
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /** A domain's worker idle past its timeout is retired without another request. */
    @Test
    fun idleDomainWorkerIsRetired() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), validReplyAt = { true })
        val transport = domainLane(context)
        try {
            finish(decodeIn(transport, 1, A))
            val idle = context.bindings[0]
            assertEquals(1L, idle.remote.retired.count)
            org.robolectric.shadows.ShadowSystemClock
                .advanceBy(java.time.Duration.ofMillis(MediaWorkerPool.DOMAIN_IDLE_MS + 1))
            assertTrue("An idle domain worker is retired", idle.remote.retired.await(2, TimeUnit.SECONDS))
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /** A domain lane without spares, so every binding is a domain's own generation. */
    private fun domainLane(
        context: BindingFixture,
        liveLimit: Int = MediaWorkerPool.MAX_LIVE_GENERATIONS,
    ): IsolatedMediaTransport {
        val deadline = RECOVERY_DEADLINE_MS
        return IsolatedMediaTransport(context, "images", spares = 0, deadlineMs = deadline, liveLimit = liveLimit)
    }

    /** Starts one decode whose source is [id] bytes long, in [domain]. */
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

    /** The session's quarantined source keys. */
    private fun refusals(transport: IsolatedMediaTransport): Map<*, *> {
        val session = privateField(transport, "session")
        return privateField(session, "refusals") as Map<*, *>
    }

    private companion object {
        val A = MediaTrustDomain.Conversation("account", "a")
        val B = MediaTrustDomain.Conversation("account", "b")
        val C = MediaTrustDomain.Conversation("account", "c")
        val D = MediaTrustDomain.PublicProfiles("account")
        const val INPUTS_PER_DOMAIN = 6
        const val FENCE_OBSERVATION_MS = 300L
        const val RECOVERY_DEADLINE_MS = 3_000L
    }
}

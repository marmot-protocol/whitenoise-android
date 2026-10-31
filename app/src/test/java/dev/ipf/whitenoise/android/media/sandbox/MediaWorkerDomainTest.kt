package dev.ipf.whitenoise.android.media.sandbox

import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
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
    /**
     * Runs one complete decode on the test thread first, so Robolectric's native runtime loads there. Loading
     * it first on several decode threads at once aborted the test JVM (`JniConstants: Class not found`).
     */
    @Before
    fun loadNativeRuntimeOnTheTestThread() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), validReplyAt = { true })
        val transport = domainLane(context)
        try {
            val bytes = byteArrayOf(1)
            MediaTrustScope.within(A) { transport.decode(bytes, 1, false, MediaDecodePolicy.DECODE) }?.recycle()
        } finally {
            context.dieAll()
            transport.close()
        }
    }

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

    /**
     * However many domains wait, only requests holding a worker hold descriptors: the rest wait for a worker
     * holding none, and get one as the held requests finish.
     */
    @Test
    fun requestsWaitingForAWorkerHoldNoDescriptors() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = domainLane(context)
        val admitted =
            java.util.concurrent.atomic
                .AtomicInteger()
        transport.admissionObserver = MediaAdmissionObserver { _, entered -> if (entered) admitted.incrementAndGet() }
        val domains = listOf(A, B, C, D, MediaTrustDomain.Local("account"))
        val calls = domains.mapIndexed { index, domain -> decodeIn(transport, index + 1, domain) }
        try {
            repeat(MediaWorkerPool.MAX_LIVE_GENERATIONS) {
                assertTrue(
                    context
                        .next()
                        .remote.decoded
                        .await(2, TimeUnit.SECONDS),
                )
            }
            Thread.sleep(FENCE_OBSERVATION_MS)
            assertEquals(domains.size, admitted.get())
            assertEquals(MediaWorkerPool.MAX_LIVE_GENERATIONS, transport.descriptorHoldersForTest())
            assertTrue(calls.count { it.thread.isAlive } == domains.size)
            context.bindings.take(MediaWorkerPool.MAX_LIVE_GENERATIONS).forEach { bound ->
                assertTrue(withWorkerIdentity { bound.remote.deliverHeldReply(successfulPixelReply()) })
            }
            serveUntilDone(context, calls, heldFrom = MediaWorkerPool.MAX_LIVE_GENERATIONS)
            assertEquals(0, transport.descriptorHoldersForTest())
        } finally {
            calls.forEach(DecodeCall::interruptAndJoin)
            context.dieAll()
            transport.close()
        }
    }

    /**
     * Lets [calls] finish without interrupting them: every evicted worker dies as a cooperative one would, and
     * each binding from index [heldFrom] on answers its held request once it arrives.
     */
    private fun serveUntilDone(
        context: BindingFixture,
        calls: List<DecodeCall>,
        heldFrom: Int,
    ) {
        val died = mutableSetOf<Int>()
        val answered = mutableSetOf<Int>()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(SERVE_SECONDS)
        while (calls.any { it.thread.isAlive } && System.nanoTime() < until) {
            synchronized(context.bindings) { context.bindings.toList() }.forEachIndexed { index, bound ->
                if (bound.remote.retired.count == 0L && died.add(index)) bound.remote.die()
                if (index >= heldFrom && bound.remote.decoded.count == 0L && answered.add(index)) {
                    withWorkerIdentity { bound.remote.deliverHeldReply(successfulPixelReply()) }
                }
            }
            Thread.sleep(POLL_MS)
        }
        calls.forEach(::finish)
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
            val survivor = context.bindings[1].remote
            assertEquals("Only the least recently used domain is evicted", 1L, survivor.retired.count)
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
        } finally {
            third?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /**
     * New domains arriving together, such as several conversations' outgoing drafts, with every slot held by an
     * idle domain, evict as many idle domains at once as domains wait instead of one at a time, and still use no
     * victim's slot before that victim's death.
     */
    @Test
    fun newDomainsEvictIdleDomainsInParallel() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), validReplyAt = { true })
        val transport = domainLane(context)
        val waiting = mutableListOf<DecodeCall>()
        try {
            (1..3).forEach { finish(decodeIn(transport, it, outgoingIn(it))) }
            val victims = context.bindings.toList()
            (4..6).forEach { waiting += decodeIn(transport, it, outgoingIn(it)) }
            assertTrue(
                "Every idle domain is evicted at once",
                victims.all { it.remote.retired.await(2, TimeUnit.SECONDS) },
            )
            Thread.sleep(FENCE_OBSERVATION_MS)
            assertEquals("No new worker before a victim's death", 3, context.bindings.size)
            victims.forEach { it.remote.die() }
            waiting.forEach(::finish)
            assertEquals(6, context.bindings.size)
            assertEquals(
                listOf(setOf(4), setOf(5), setOf(6)),
                context.bindings
                    .drop(3)
                    .map { it.remote.inputs.toSet() }
                    .sortedBy { it.first() },
            )
        } finally {
            waiting.forEach { it.interruptAndJoin() }
            context.dieAll()
            transport.close()
        }
    }

    /** Under concurrent requests from more outgoing domains than slots, every worker serves one domain only. */
    @Test
    fun concurrentOutgoingDomainsNeverShareAWorker() {
        val context =
            BindingFixture(ApplicationProvider.getApplicationContext(), validReplyAt = { true }, dieOnRetire = true)
        val transport = domainLane(context)
        val drafts = (1..DRAFTS).map(::outgoingIn)
        val callers =
            drafts.mapIndexed { index, domain ->
                Thread {
                    repeat(INPUTS_PER_DOMAIN) { input -> finish(decodeIn(transport, index * 16 + input + 1, domain)) }
                }.apply { start() }
            }
        try {
            callers.forEach { it.join(TimeUnit.SECONDS.toMillis(SERVE_SECONDS * 2)) }
            assertTrue("A caller is stuck", callers.none(Thread::isAlive))
            val served =
                context.bindings.map { bound ->
                    bound.remote.inputs
                        .map { (it - 1) / 16 }
                        .toSet()
                }
            assertTrue("A worker saw two domains: $served", served.all { it.size <= 1 })
            assertEquals(drafts.size * INPUTS_PER_DOMAIN, context.bindings.sumOf { it.remote.requests })
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /**
     * A domain's own request racing an eviction never lands on the worker being evicted: the victim is marked
     * retiring under the same lock that chose it, so the domain waits for a fresh worker instead of failing.
     */
    @Test
    fun domainRequestRacingItsEvictionStillSucceeds() {
        repeat(EVICTION_RACE_ROUNDS) { round ->
            val context =
                BindingFixture(ApplicationProvider.getApplicationContext(), validReplyAt = { true }, dieOnRetire = true)
            val transport = domainLane(context, liveLimit = 2)
            try {
                finish(decodeIn(transport, 1, A))
                finish(decodeIn(transport, 2, B))
                val start = java.util.concurrent.CountDownLatch(1)
                val results = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
                val racers =
                    listOf("evicting" to C, "evicted" to A).map { (name, domain) ->
                        Thread {
                            start.await()
                            val bytes = ByteArray(3) { 3 }
                            val image =
                                MediaTrustScope.within(domain) {
                                    transport.decode(bytes, 1, false, MediaDecodePolicy.DECODE)
                                }
                            results[name] = image != null
                            image?.recycle()
                        }.apply { start() }
                    }
                start.countDown()
                racers.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
                assertEquals("round $round: $results", mapOf("evicting" to true, "evicted" to true), results.toMap())
            } finally {
                context.dieAll()
                transport.close()
            }
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
            assertEquals(1, refusalCount(transport))
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

    /**
     * Failing requests in four domains at once quarantine every source without a ledger failure, quarantined
     * sources start no worker, and the bound holds once more sources fail than it keeps.
     */
    @Test
    fun parallelFailuresAcrossDomainsQuarantineEverySource() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), dieOnRetire = true)
        val transport = domainLane(context)
        val domains = listOf(A, B, C, D)
        try {
            val firstFailures = failInParallel(transport, domains, firstId = 1, count = FAILING_INPUTS)
            assertEquals(emptyList<Throwable>(), firstFailures)
            val failed = domains.size * FAILING_INPUTS
            assertEquals(failed, refusalCount(transport))
            val bindings = context.bindings.size
            domains.forEachIndexed { index, domain ->
                repeat(FAILING_INPUTS) { finish(decodeIn(transport, 1 + index * FAILING_INPUTS + it, domain)) }
            }
            assertEquals("Quarantined sources start no worker", bindings, context.bindings.size)
            assertEquals(
                emptyList<Throwable>(),
                failInParallel(transport, domains, firstId = failed + 1, count = OVERFLOW_INPUTS),
            )
            assertEquals(MediaDecodePolicy.MAX_REFUSALS, refusalCount(transport))
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /** Runs [count] failing decodes per domain, the domains in parallel, and returns what callers threw. */
    private fun failInParallel(
        transport: IsolatedMediaTransport,
        domains: List<MediaTrustDomain>,
        firstId: Int,
        count: Int,
    ): List<Throwable> {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val callers =
            domains.mapIndexed { index, domain ->
                Thread {
                    try {
                        repeat(count) {
                            val id = firstId + index * count + it
                            val bytes = ByteArray(id) { id.toByte() }
                            MediaTrustScope
                                .within(domain) { transport.decode(bytes, 1, false, MediaDecodePolicy.DECODE) }
                                ?.let { pixels ->
                                    pixels.recycle()
                                    failures += AssertionError("Source $id decoded")
                                }
                        }
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }.apply { start() }
            }
        callers.forEach { it.join(TimeUnit.SECONDS.toMillis(PARALLEL_FAILURE_SECONDS)) }
        if (callers.any(Thread::isAlive)) failures += AssertionError("A failing caller is stuck")
        return failures.toList()
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

    /** How many source keys the session's refusal ledger holds. */
    private fun refusalCount(transport: IsolatedMediaTransport): Int {
        val session = privateField(transport, "session")
        return (privateField(session, "refusals") as MediaRefusalLedger).size()
    }

    private companion object {
        val A = MediaTrustDomain.Conversation("account", "a")
        val B = MediaTrustDomain.Conversation("account", "b")
        val C = MediaTrustDomain.Conversation("account", "c")
        val D = MediaTrustDomain.PublicProfiles("account")

        /** Conversation [n]'s outgoing draft domain, one per conversation. */
        fun outgoingIn(n: Int): MediaTrustDomain = MediaTrustDomain.Outgoing("account", "conversation-$n")

        const val DRAFTS = 10
        const val INPUTS_PER_DOMAIN = 6
        const val FENCE_OBSERVATION_MS = 300L
        const val SERVE_SECONDS = 5L
        const val EVICTION_RACE_ROUNDS = 40
        const val POLL_MS = 5L
        const val RECOVERY_DEADLINE_MS = 3_000L
        const val FAILING_INPUTS = 12
        const val OVERFLOW_INPUTS = 20
        const val PARALLEL_FAILURE_SECONDS = 60L
    }
}

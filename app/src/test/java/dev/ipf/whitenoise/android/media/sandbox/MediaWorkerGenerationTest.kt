package dev.ipf.whitenoise.android.media.sandbox

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * Per-input worker generations through the real transport and pool. Controlled callbacks exercise
 * ownership and ordering, Android process death and UID separation need the device tests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class)
class MediaWorkerGenerationTest {
    /** Each successful request retires its generation, so sequential distinct sources never share a worker. */
    @Test
    fun successfulRequestRetiresItsGenerationAndTheNextSourceUsesAFreshOne() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val transport = singleLane(context)
        try {
            repeat(SOURCES) { index ->
                val call = startDecode(transport, index + 1)
                val worker = context.next()
                assertTrue(worker.remote.retired.await(2, TimeUnit.SECONDS))
                worker.remote.die()
                call.join()
            }
            assertEquals(SOURCES, context.bindings.size)
            assertTrue(context.bindings.all { it.remote.requests == 1 })
            assertEquals(SOURCES, context.instanceNames.toSet().size)
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /** There is no same-source grouping: identical bytes requested twice still use two generations. */
    @Test
    fun identicalBytesRequestedTwiceStillUseTwoGenerations() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = singleLane(context)
        try {
            repeat(2) {
                val call = startDecode(transport, 1)
                val worker = context.next()
                assertTrue(worker.remote.decoded.await(2, TimeUnit.SECONDS))
                // A valid attested result, so the bytes are not quarantined between the two requests.
                assertTrue(withWorkerIdentity { worker.remote.deliverHeldReply(successfulPixelReply()) })
                assertTrue(worker.remote.retired.await(2, TimeUnit.SECONDS))
                worker.remote.die()
                call.join()
                call.result.get()?.recycle()
            }
            assertEquals(2, context.bindings.size)
            assertTrue(context.bindings.all { it.remote.requests == 1 })
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /** A capability accepts one reply, later replies on a retired generation are refused by the transaction. */
    @Test
    fun lateReplyFromARetiredGenerationIsRejected() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = singleLane(context)
        val firstCall = startDecode(transport, 1)
        var secondCall: DecodeCall? = null
        try {
            val first = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            assertTrue(withWorkerIdentity { first.remote.deliverHeldReply(successfulPixelReply()) })
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            assertFalse(withWorkerIdentity { first.remote.deliverHeldReply(successfulPixelReply()) })
            first.remote.die()
            firstCall.join()
            assertFalse(withWorkerIdentity { first.remote.deliverHeldReply(successfulPixelReply()) })
            secondCall = startDecode(transport, 2)
            val second = context.next()
            assertTrue(second.remote.decoded.await(2, TimeUnit.SECONDS))
            // The old generation's capability stays dead while the new one is live.
            assertFalse(withWorkerIdentity { first.remote.deliverHeldReply(successfulPixelReply()) })
            assertTrue(second.remote.deliverHeldReply())
            assertTrue(second.remote.retired.await(2, TimeUnit.SECONDS))
            second.remote.die()
            secondCall.join()
        } finally {
            firstCall.interruptAndJoin()
            secondCall?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /** An abandoned request revokes its capability, so the worker's late reply is refused. */
    @Test
    fun replyAfterAnInterruptedRequestIsRejected() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = singleLane(context)
        val call = startDecode(transport, 1)
        try {
            val first = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            call.interruptAndJoin()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            assertFalse(withWorkerIdentity { first.remote.deliverHeldReply(successfulPixelReply()) })
            assertNull(call.result.get())
            // Caller interruption is not evidence about the source, so nothing is quarantined.
            assertTrue(refusals(transport).isEmpty())
        } finally {
            call.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /** A worker that dies without replying quarantines those bytes: they are refused without another worker. */
    @Test
    fun workerCrashWithoutAReplyQuarantinesTheSameBytes() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = singleLane(context)
        var next: DecodeCall? = null
        try {
            val crashing = startDecode(transport, 5)
            val first = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            first.remote.die()
            crashing.join()
            assertNull(crashing.result.get())
            assertEquals(1, refusals(transport).size)
            assertNull(transport.decode(byteArrayOf(5), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            assertEquals(1, first.remote.requests)
            next = startDecode(transport, 6)
            val second = context.next()
            assertTrue(second.remote.decoded.await(2, TimeUnit.SECONDS))
            second.remote.die()
            next.join()
        } finally {
            next?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /** A worker that misses the deadline, as a watchdog hang does, quarantines those bytes for the cooldown. */
    @Test
    fun deadlineMissQuarantinesTheSameBytes() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = singleLane(context, deadlineMs = HELD_DEADLINE_MS)
        var next: DecodeCall? = null
        try {
            assertNull(transport.decode(byteArrayOf(5), 1, false, MediaDecodePolicy.DECODE))
            val hung = context.next()
            assertEquals(1, hung.remote.requests)
            assertEquals(1, refusals(transport).size)
            hung.remote.die()
            assertNull(transport.decode(byteArrayOf(5), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            next = startDecode(transport, 6)
            val second = context.next()
            assertTrue(second.remote.decoded.await(2, TimeUnit.SECONDS))
            second.remote.die()
            next.join()
        } finally {
            next?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /** The current session's source quarantine, keyed by operation, edge, option and digest. */
    private fun refusals(transport: IsolatedMediaTransport): Map<*, *> {
        val session = privateField(transport, "session")
        return privateField(session, "refusals") as Map<*, *>
    }

    /** When a replacement cannot bind, the input fails closed and the previous worker never receives it. */
    @Test
    fun failedReplacementFailsClosedWithoutFallingBackToThePreviousWorker() {
        val context =
            BindingFixture(ApplicationProvider.getApplicationContext(), acceptAt = { index -> index == 0 })
        val transport = IsolatedMediaTransport(context, "images", spares = 1, deadlineMs = HELD_DEADLINE_MS)
        try {
            val firstCall = startDecode(transport, 1)
            val first = context.next()
            val refusedSpare = context.next()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            firstCall.join() // No death yet: the death barrier withholds the pixels.
            // The previous worker is still alive and idle, it must not serve the replacement's input.
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            first.remote.die()
            assertNull(transport.decode(byteArrayOf(3), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, first.remote.requests)
            assertTrue(context.bindings.drop(1).all { it.remote.requests == 0 })
            assertTrue(context.isUnbound(refusedSpare))
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /** A startup past its deadline holds its slot until it connects late, then serves the next input cleanly. */
    @Test
    fun startupTimeoutHoldsItsSlotAndALateConnectionServesTheNextInput() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), connect = false)
        val transport = singleLane(context, deadlineMs = HELD_DEADLINE_MS)
        var call: DecodeCall? = null
        try {
            assertNull(transport.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DECODE))
            val first = context.next()
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            assertEquals(0, first.remote.requests)
            first.connection.onServiceConnected(context.component, first.remote)
            call = startDecode(transport, 3)
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            assertEquals(1, first.remote.requests)
            assertEquals(1, context.bindings.size)
            first.remote.die()
            call.join()
        } finally {
            call?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /** Close during an exchange revokes the reply, stops every generation and leaves the spare without input. */
    @Test
    fun closeDuringAnExchangeRevokesTheReplyAndStopsEveryGeneration() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = IsolatedMediaTransport(context, "images", spares = 1)
        val call = startDecode(transport, 1)
        try {
            val first = context.next()
            val spare = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            transport.close()
            assertFalse(withWorkerIdentity { first.remote.deliverHeldReply(successfulPixelReply()) })
            call.join()
            assertNull(call.result.get())
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            assertTrue(spare.remote.retired.await(2, TimeUnit.SECONDS))
            assertTrue(context.isUnbound(first))
            assertTrue(context.isUnbound(spare))
            // Deaths after close only release slots, they never reopen admission.
            first.remote.die()
            spare.remote.die()
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(0, spare.remote.requests)
            assertEquals(2, context.bindings.size)
        } finally {
            call.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /** A worker ignoring RETIRE keeps the next input out and is stopped by Android, only its death admits input. */
    @Test
    fun noncooperativeRetiringWorkerKeepsAdmissionClosedUntilItsDeath() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val transport = IsolatedMediaTransport(context, "images", spares = 1, deadlineMs = HELD_DEADLINE_MS)
        var next: DecodeCall? = null
        try {
            val firstCall = startDecode(transport, 1)
            val first = context.next()
            val spare = context.next()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            firstCall.join()
            assertNull("No pixels without the producing worker's death", firstCall.result.get())
            assertTrue("A worker ignoring RETIRE is stopped through Android", context.isUnbound(first))
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(0, spare.remote.requests)
            assertEquals(1, first.remote.requests)
            assertEquals(2, context.bindings.size)
            first.remote.die()
            next = startDecode(transport, 3)
            assertTrue(spare.remote.retired.await(2, TimeUnit.SECONDS))
            assertEquals(1, spare.remote.requests)
            assertEquals(1, first.remote.requests)
            spare.remote.die()
            next.join()
        } finally {
            next?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /** Live generations never exceed one plus the spare limit, even when retired workers never die. */
    @Test
    fun liveGenerationsStayBoundedWhenRetiredWorkersNeverDie() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        // A larger request is clamped to the pool's two-spare ceiling.
        val transport = IsolatedMediaTransport(context, "images", spares = 5, deadlineMs = HELD_DEADLINE_MS)
        try {
            repeat(SOURCES) { index ->
                assertNull(transport.decode(byteArrayOf(index.toByte()), 1, false, MediaDecodePolicy.DECODE))
                assertTrue(transport.liveGenerationsForTest() <= 1 + MediaWorkerPool.MAX_SPARES)
            }
            assertEquals(1 + MediaWorkerPool.MAX_SPARES, context.bindings.size)
            assertEquals(1, context.bindings.sumOf { it.remote.requests })
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /** The spare is bound while the current input is served and takes the next input once its predecessor dies. */
    @Test
    fun spareIsWarmedDuringServiceAndTakesTheNextInput() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val transport = IsolatedMediaTransport(context, "images", spares = 1)
        var second: DecodeCall? = null
        try {
            val firstCall = startDecode(transport, 1)
            val first = context.next()
            val spare = context.next()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            assertTrue("The spare is live before its predecessor dies", firstCall.thread.isAlive)
            assertEquals(0, spare.remote.requests)
            first.remote.die()
            firstCall.join()
            second = startDecode(transport, 2)
            assertTrue(spare.remote.retired.await(2, TimeUnit.SECONDS))
            assertEquals(1, spare.remote.requests)
            val refill = context.next()
            assertEquals(0, refill.remote.requests)
            assertEquals(3, context.bindings.size)
            spare.remote.die()
            second.join()
        } finally {
            second?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /** A session reset retires clean spares too, so no generation started before it serves the new session. */
    @Test
    fun sessionResetRetiresCleanSparesStartedBeforeIt() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val transport = IsolatedMediaTransport(context, "images", spares = 1)
        var call: DecodeCall? = null
        try {
            val firstCall = startDecode(transport, 1)
            val first = context.next()
            val spare = context.next()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            first.remote.die()
            firstCall.join()
            transport.activateSession()
            assertTrue(spare.remote.retired.await(2, TimeUnit.SECONDS))
            call = startDecode(transport, 2)
            val fresh = context.next()
            assertTrue(fresh.remote.retired.await(2, TimeUnit.SECONDS))
            assertEquals(1, fresh.remote.requests)
            assertEquals(0, spare.remote.requests)
            fresh.remote.die()
            call.join()
        } finally {
            call?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    private companion object {
        const val SOURCES = 3
    }
}

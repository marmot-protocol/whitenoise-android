package dev.ipf.whitenoise.android.media.sandbox

import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * A request's deadline starts at admission and covers its wait for a worker. A missed reply deadline quarantines the
 * source only when the worker had its full time to answer, never because the request spent it waiting for a worker.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class)
class MediaClaimDeadlineTest {
    /**
     * A request that waited longer than the claim allowance for a worker and then missed its reply deadline leaves
     * its source unquarantined, while the same miss without that wait still quarantines it.
     */
    @Test
    fun aLateClaimsMissedDeadlineIsNotEvidenceAgainstItsSource() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport =
            IsolatedMediaTransport(
                context,
                "images",
                spares = 0,
                deadlineMs = DEADLINE_MS,
                liveLimit = 1,
                claimAllowanceMs = CLAIM_ALLOWANCE_MS,
            )
        var busy: DecodeCall? = null
        var late: DecodeCall? = null
        var prompt: DecodeCall? = null
        try {
            busy = startDecode(transport, 1, A, sized = true)
            val first = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            // The only slot is busy, so B's request waits for a worker, here longer than the allowance on the clock
            // its deadline reads.
            late = startDecode(transport, 2, B, sized = true)
            Thread.sleep(SETTLE_MS)
            ShadowSystemClock.advanceBy(Duration.ofMillis(CLAIM_WAIT_MS))
            assertTrue(withWorkerIdentity { first.remote.deliverHeldReply(successfulPixelReply()) })
            busy.join()
            busy.result.get()?.recycle()
            assertTrue("A's idle worker is evicted for B", first.remote.retired.await(2, TimeUnit.SECONDS))
            first.remote.die()
            val second = context.next()
            assertTrue(second.remote.decoded.await(2, TimeUnit.SECONDS))
            late.join()
            assertNull(late.result.get())
            assertEquals("A late claim's missed deadline quarantines nothing", 0, refusalCount(transport))
            assertTrue(second.remote.retired.await(2, TimeUnit.SECONDS))
            second.remote.die()
            // Without the wait, the same miss is evidence: the worker had its full time and did not answer.
            prompt = startDecode(transport, 2, B, sized = true)
            val third = context.next()
            assertTrue("The source was not refused before dispatch", third.remote.decoded.await(2, TimeUnit.SECONDS))
            prompt.join()
            assertNull(prompt.result.get())
            assertEquals(1, refusalCount(transport))
        } finally {
            listOfNotNull(busy, late, prompt).forEach(DecodeCall::interruptAndJoin)
            context.dieAll()
            transport.close()
        }
    }

    /** How many source keys the session's refusal ledger holds. */
    private fun refusalCount(transport: IsolatedMediaTransport): Int {
        val session = privateField(transport, "session")
        return (privateField(session, "refusals") as MediaRefusalLedger).size()
    }

    private companion object {
        val A = MediaTrustDomain.Conversation("account", "a")
        val B = MediaTrustDomain.Conversation("account", "b")
        const val DEADLINE_MS = 1_200L
        const val CLAIM_ALLOWANCE_MS = 200L
        const val CLAIM_WAIT_MS = 500L
        const val SETTLE_MS = 100L
    }
}

package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Domain-bound worker generations on a real isolated service. The debug canary makes each worker report the
 * state and descriptor its previous input left behind, so a test can see which inputs shared a process. The canary
 * takes well under a second, so every pull request runs it as well as the focused containment lane.
 */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
@MediaContainmentAcceptance
class MediaWorkerDomainDeviceTest {
    /**
     * A conversation's later input reaches the worker that kept its earlier input's state and descriptor, while
     * another conversation's input gets a different process holding nothing of the first.
     */
    @Test
    fun retainedStateReachesTheSameDomainOnlyNeverAnotherDomain() {
        val context = MediaWorkerBaselineBinding(InstrumentationRegistry.getInstrumentation().targetContext)
        val transport =
            IsolatedMediaTransport(
                context,
                "domain_canary",
                MediaWorkerPool.DOMAIN_SPARES,
                liveLimit = MediaWorkerPool.MAX_LIVE_GENERATIONS,
            )
        try {
            val sourceA = png(Color.RED)
            val firstA = canary(transport, CONVERSATION_A, sourceA)
            val otherB = canary(transport, CONVERSATION_B, png(Color.BLUE))
            val secondA = canary(transport, CONVERSATION_A, png(Color.GREEN))
            assertEquals(WorkerCanary(firstA.pid, "-", "-"), firstA)
            // Another conversation's input never lands where A's input was.
            assertEquals(WorkerCanary(otherB.pid, "-", "-"), otherB)
            assertNotEquals(firstA.pid, otherB.pid)
            // The same conversation reuses its worker, which still holds A's earlier input: that is the domain's own.
            assertEquals(firstA.pid, secondA.pid)
            assertEquals(digest(sourceA), secondA.previousState)
            assertEquals(digest(sourceA), secondA.previousDescriptor)
        } finally {
            transport.close()
            context.closeObservers()
        }
    }

    /**
     * Two picks of one conversation never share a process: the second pick's worker holds nothing of the first, the
     * first pick's later input still reaches its own worker, and once that pick is retired its next input starts a
     * fresh process that holds nothing either.
     */
    @Test
    fun eachPickKeepsItsOwnWorkerUntilItIsRetired() {
        val context = MediaWorkerBaselineBinding(InstrumentationRegistry.getInstrumentation().targetContext)
        val transport =
            IsolatedMediaTransport(
                context,
                "pick_canary",
                MediaWorkerPool.DOMAIN_SPARES,
                liveLimit = MediaWorkerPool.MAX_LIVE_GENERATIONS,
            )
        try {
            val sourceA = png(Color.RED)
            val firstA = canary(transport, PICK_A, sourceA)
            val pickB = canary(transport, PICK_B, png(Color.BLUE))
            val secondA = canary(transport, PICK_A, png(Color.GREEN))
            // Another pick of the same conversation never lands where pick A's input was.
            assertEquals(WorkerCanary(pickB.pid, "-", "-"), pickB)
            assertNotEquals(firstA.pid, pickB.pid)
            assertEquals(firstA.pid, secondA.pid)
            assertEquals(digest(sourceA), secondA.previousState)
            transport.retireDomain(PICK_A)
            val afterRetire = canary(transport, PICK_A, png(Color.YELLOW))
            // Retiring the pick ends its worker: its next input meets a new process with nothing left behind.
            assertEquals(WorkerCanary(afterRetire.pid, "-", "-"), afterRetire)
            assertNotEquals(firstA.pid, afterRetire.pid)
            assertNotEquals(pickB.pid, afterRetire.pid)
        } finally {
            transport.close()
            context.closeObservers()
        }
    }

    /** One debug canary request in [domain], parsed. */
    private fun canary(
        transport: IsolatedMediaTransport,
        domain: MediaTrustDomain,
        bytes: ByteArray,
    ): WorkerCanary =
        WorkerCanary.parse(
            MediaTrustScope.within(domain) { transport.debugProbeForTest(bytes, MediaDecodePolicy.DEBUG_CANARY) },
        )

    /** The canary's digest form: the first 16 hex characters of SHA-256. */
    private fun digest(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(CANARY_DIGEST_CHARS)

    /** A 2×2 PNG of one [color], distinct bytes per color. */
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        val CONVERSATION_A = MediaTrustDomain.Conversation("device-test", "a")
        val CONVERSATION_B = MediaTrustDomain.Conversation("device-test", "b")
        val PICK_A = MediaTrustDomain.Outgoing("device-test", "a", "pick-a")
        val PICK_B = MediaTrustDomain.Outgoing("device-test", "a", "pick-b")
        const val CANARY_DIGEST_CHARS = 16
    }
}

package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.OutgoingDrafts
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
     * A draft's picks share their draft's outgoing worker: the second pick's input reaches the process that holds the
     * first pick's state. A second conversation's draft never reaches it, and no other domain shares it, not even the
     * same conversation's received media.
     */
    @Test
    fun aDraftsPicksShareTheirDraftsOutgoingWorker() {
        val context = MediaWorkerBaselineBinding(InstrumentationRegistry.getInstrumentation().targetContext)
        val transport =
            IsolatedMediaTransport(
                context,
                "draft_canary",
                MediaWorkerPool.DOMAIN_SPARES,
                liveLimit = MediaWorkerPool.MAX_LIVE_GENERATIONS,
            )
        try {
            val sourceA = png(Color.RED)
            val firstPick = canary(transport, DRAFT_A, sourceA)
            val secondPick = canary(transport, DRAFT_A, png(Color.BLUE))
            val otherDraft = canary(transport, DRAFT_B, png(Color.GREEN))
            val received = canary(transport, CONVERSATION_A, png(Color.YELLOW))
            // The draft's second pick lands where its first pick was, holding that pick's state.
            assertEquals(firstPick.pid, secondPick.pid)
            assertEquals(digest(sourceA), secondPick.previousState)
            // Another conversation's draft, and this conversation's received media, never reach that worker.
            assertEquals(WorkerCanary(otherDraft.pid, "-", "-"), otherDraft)
            assertNotEquals(firstPick.pid, otherDraft.pid)
            assertEquals(WorkerCanary(received.pid, "-", "-"), received)
            assertNotEquals(firstPick.pid, received.pid)
        } finally {
            transport.close()
            context.closeObservers()
        }
    }

    /**
     * Successive drafts of one conversation, by production's draft policy, never share a worker, even within the
     * earlier worker's idle time and input cap. An emptied draft's pick is absent from the next draft's process; a
     * pick made while a send runs starts a fresh process, while the sent pick's thumbnail still lands in its own
     * draft's; and once that send has ended, the next draft's later picks reach only that next draft's process.
     */
    @Test
    fun successiveDraftsNeverShareAWorker() {
        val context = MediaWorkerBaselineBinding(InstrumentationRegistry.getInstrumentation().targetContext)
        val transport =
            IsolatedMediaTransport(
                context,
                "successive_drafts",
                MediaWorkerPool.DOMAIN_SPARES,
                liveLimit = MediaWorkerPool.MAX_LIVE_GENERATIONS,
            )
        val drafts = OutgoingDrafts { draft -> transport.retireDomain(draftDomain(draft)) }
        try {
            val emptied = png(Color.RED)
            val sent = png(Color.BLUE)
            val duringSend = png(Color.GREEN)
            val firstDraft = canary(transport, draftDomain(drafts.draftOf("slot-a")), emptied)
            drafts.retain(emptySet())
            val secondDraft = canary(transport, draftDomain(drafts.draftOf("slot-b")), sent)
            val send = drafts.beginSend(listOf("slot-b"))
            val thirdDraft = canary(transport, draftDomain(drafts.draftOf("slot-c")), duringSend)
            val sentThumbnail = canary(transport, draftDomain(drafts.draftOf("slot-b")), png(Color.YELLOW))
            drafts.retain(setOf("slot-c"))
            drafts.endSend(send)
            val thirdDraftNext = canary(transport, draftDomain(drafts.draftOf("slot-d")), png(Color.CYAN))
            assertEquals(WorkerCanary(firstDraft.pid, "-", "-"), firstDraft)
            // The emptied draft's pick is absent from the next draft's process.
            assertEquals(WorkerCanary(secondDraft.pid, "-", "-"), secondDraft)
            assertNotEquals(firstDraft.pid, secondDraft.pid)
            // A pick made during the send starts a fresh process holding nothing of the sent draft.
            assertEquals(WorkerCanary(thirdDraft.pid, "-", "-"), thirdDraft)
            assertNotEquals(secondDraft.pid, thirdDraft.pid)
            // The sent pick's thumbnail stays in its own draft's process.
            assertEquals(secondDraft.pid, sentThumbnail.pid)
            assertEquals(digest(sent), sentThumbnail.previousState)
            // The next draft's later pick reaches that draft's process only, never the sent draft's.
            assertEquals(thirdDraft.pid, thirdDraftNext.pid)
            assertEquals(digest(duringSend), thirdDraftNext.previousState)
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
        val DRAFT_A = MediaTrustDomain.outgoing("device-test", "a", "draft")
        val DRAFT_B = MediaTrustDomain.outgoing("device-test", "b", "draft")
        const val CANARY_DIGEST_CHARS = 16

        /** The outgoing domain of [draft] in conversation A. */
        fun draftDomain(draft: String): MediaTrustDomain = MediaTrustDomain.outgoing("device-test", "a", draft)
    }
}

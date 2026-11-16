package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Images a visible bubble asks for are fetched before background automatic work (#3819). */
class AttachmentDownloadForegroundOrderTest {
    /** Visible requests go ahead of queued background work, the newest first, as MDK orders its automatic queue. */
    @Test
    fun foregroundRequestsGoAheadOfBackgroundWorkNewestFirst() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val release = CompletableDeferred<Unit>()
                val started = mutableListOf<String>()
                val holder = start(gate, "holder", started) { release.await() }
                val queued =
                    listOf(
                        start(gate, "background-0", started),
                        start(gate, "background-1", started),
                        start(gate, "visible-0", started, foreground = true),
                        start(gate, "visible-1", started, foreground = true),
                    )
                release.complete(Unit)
                (queued + holder).awaitAll()

                assertEquals(
                    listOf("holder", "visible-1", "visible-0", "background-0", "background-1"),
                    started,
                )
            }
        }

    /** A visible bubble joining queued background work moves it forward, but never ahead of a tap. */
    @Test
    fun bringingJoinedWorkForwardKeepsExplicitRequestsFirst() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val release = CompletableDeferred<Unit>()
                val started = mutableListOf<String>()
                val holder = start(gate, "holder", started) { release.await() }
                val queued =
                    listOf(
                        start(gate, "background-0", started),
                        start(gate, "background-1", started),
                        start(gate, "background-2", started),
                        start(gate, "tap", started, priority = AttachmentDownloadPriority.Interactive),
                    )
                assertTrue(gate.promote("background-2", explicit = false))
                assertFalse("nothing queued under that key", gate.promote("absent", explicit = false))
                release.complete(Unit)
                (queued + holder).awaitAll()

                assertEquals(listOf("holder", "tap", "background-2", "background-0", "background-1"), started)
            }
        }

    /** A visible bubble that joins a background owner's queued acquisition brings that owner forward. */
    @Test
    fun aForegroundJoinBringsTheQueuedOwnerForward() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val acquisitions = InFlightAttachmentAcquisitions(this, gate::promote)
                val release = CompletableDeferred<Unit>()
                val started = mutableListOf<String>()
                val holder = start(gate, "holder", started) { release.await() }
                val owners =
                    listOf("background-0", "background-1").map { key ->
                        acquisitions.acquire(key, AttachmentDownloadPriority.Automatic) {
                            gate.withPermit(key, ACCOUNT, AttachmentDownloadPriority.Automatic) { started += key }
                            AttachmentAcquisitionOutcome.LegacyBytes(byteArrayOf())
                        }
                    }
                kotlinx.coroutines.yield()
                val joined =
                    acquisitions.acquire("background-1", AttachmentDownloadPriority.Automatic, foreground = true) {
                        error("a join never starts a second owner")
                    }
                release.complete(Unit)
                holder.await()
                (owners + joined).awaitAll()

                assertEquals(listOf("holder", "background-1", "background-0"), started)
            }
        }

    /**
     * A visible bubble can join background work before that owner's coroutine has registered with the gate. The
     * demand is kept until the key registers, so the owner still enters at the front.
     */
    @Test
    fun aForegroundJoinBeforeTheOwnerRegistersStillBringsItForward() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val acquisitions = InFlightAttachmentAcquisitions(this, gate::promote)
                val release = CompletableDeferred<Unit>()
                val started = mutableListOf<String>()
                val holder = start(gate, "holder", started) { release.await() }
                val owners =
                    listOf("background-0", "background-1").map { key ->
                        acquisitions.acquire(key, AttachmentDownloadPriority.Automatic) {
                            gate.withPermit(key, ACCOUNT, AttachmentDownloadPriority.Automatic) { started += key }
                            AttachmentAcquisitionOutcome.LegacyBytes(byteArrayOf())
                        }
                    }
                // No yield: neither owner has run, so neither has registered with the gate yet.
                val joined =
                    acquisitions.acquire("background-1", AttachmentDownloadPriority.Automatic, foreground = true) {
                        error("a join never starts a second owner")
                    }
                release.complete(Unit)
                holder.await()
                (owners + joined).awaitAll()

                assertEquals(listOf("holder", "background-1", "background-0"), started)
            }
        }

    /** The default gate admits as many downloads at once as MarmotKit's own transfer limit. */
    @Test
    fun defaultGateMatchesMarmotKitsTransferLimit() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate()
                val release = CompletableDeferred<Unit>()
                val started = mutableListOf<String>()
                val jobs = (0 until 6).map { start(gate, "file-$it", started) { release.await() } }
                assertEquals(4, started.size)
                release.complete(Unit)
                jobs.awaitAll()
                assertEquals(6, started.size)
            }
        }

    /** Starts one gated request at once, recording its key when admitted and then running [hold]. */
    private fun CoroutineScope.start(
        gate: AttachmentDownloadGate,
        key: String,
        started: MutableList<String>,
        foreground: Boolean = false,
        priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Automatic,
        hold: suspend () -> Unit = {},
    ): Deferred<Unit> =
        async(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(key, ACCOUNT, priority, foreground) {
                started += key
                hold()
            }
        }

    private companion object {
        const val ACCOUNT = "account-a"
        const val TEST_TIMEOUT = 5_000L
    }
}

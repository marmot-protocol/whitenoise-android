package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.functionBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** MDK delivers transfer state changes without coalescing, so the engine's feed alone decides each transfer. */
class NativeAttachmentFeedTest {
    /** A feed that hands out exactly the snapshots a test chooses to deliver. */
    private class ScriptedFeed : NativeTransferFeed {
        private val deliveries = Channel<Result<AttachmentTransferSnapshotFfi?>>(Channel.UNLIMITED)
        val closed = AtomicBoolean()
        val reads = AtomicInteger()

        /** Blocks like the engine until the test delivers a snapshot or the owner closes the feed. */
        override suspend fun next(): AttachmentTransferSnapshotFfi? {
            reads.incrementAndGet()
            return deliveries.receive().getOrThrow()
        }

        /** Closing wakes a blocked read with end of stream, as the real handle does. */
        override fun close() {
            closed.set(true)
            deliveries.trySend(Result.success(null))
        }

        /** Delivers one complete one-target replacement. */
        fun deliver(state: AttachmentTransferStateFfi) {
            deliveries.trySend(Result.success(AttachmentTransferSnapshotFfi(listOf(status(state)))))
        }

        /** Makes the next read fail with the engine's own typed error. */
        fun fail(error: Throwable) {
            deliveries.trySend(Result.failure(error))
        }
    }

    /** Every state the feed delivers is reported in order, and READY ends observation without another read. */
    @Test
    fun `feed states are reported in order and ready ends the wait`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            launch {
                delay(30)
                feed.deliver(AttachmentTransferStateFfi.DOWNLOADING)
                delay(30)
                feed.deliver(AttachmentTransferStateFfi.READY)
            }
            val states = mutableListOf<AttachmentTransferStateFfi>()

            withTimeout(WAIT_MILLIS) {
                awaitNativeAttachment(feed = feed, onState = { states += it }) { AttachmentTransferStateFfi.QUEUED }
            }

            assertEquals(
                listOf(
                    AttachmentTransferStateFfi.NOT_REQUESTED,
                    AttachmentTransferStateFfi.QUEUED,
                    AttachmentTransferStateFfi.DOWNLOADING,
                    AttachmentTransferStateFfi.READY,
                ),
                states,
            )
            assertEquals("one pre-demand read and one per delivered state", 3, feed.reads.get())
            assertTrue(feed.closed.get())
        }

    /** A terminal state the feed delivers is thrown as the terminal exception. */
    @Test
    fun `a terminal snapshot from the feed is thrown as the terminal exception`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            feed.deliver(AttachmentTransferStateFfi.FAILED)

            val failure =
                assertThrows(NativeAttachmentTerminalException::class.java) {
                    runBlocking {
                        withTimeout(WAIT_MILLIS) {
                            awaitNativeAttachment(feed = feed) { AttachmentTransferStateFfi.QUEUED }
                        }
                    }
                }

            assertEquals(AttachmentTransferStateFfi.FAILED, failure.state)
            assertTrue(feed.closed.get())
        }

    /** The engine's own typed failure reaches the caller unchanged. */
    @Test
    fun `the feed's typed failure is not replaced`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            val typed = MarmotKitException.InvalidMediaReference("synthetic integrity failure")
            feed.fail(typed)

            val failure =
                assertThrows(MarmotKitException.InvalidMediaReference::class.java) {
                    runBlocking {
                        withTimeout(WAIT_MILLIS) {
                            awaitNativeAttachment(feed = feed) { AttachmentTransferStateFfi.QUEUED }
                        }
                    }
                }

            assertSame(typed, failure)
            assertTrue(feed.closed.get())
        }

    /** A pause that ends inside the grace lets observation follow the transfer to READY. */
    @Test
    fun `a pause that ends within the grace continues to ready`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            launch {
                delay(30)
                feed.deliver(AttachmentTransferStateFfi.DOWNLOADING)
                feed.deliver(AttachmentTransferStateFfi.READY)
            }
            val states = mutableListOf<AttachmentTransferStateFfi>()

            withTimeout(WAIT_MILLIS) {
                awaitNativeAttachment(
                    open = { feed },
                    onState = { states += it },
                    pausedGraceMillis = PAUSE_GRACE_MILLIS,
                ) { AttachmentTransferStateFfi.PAUSED }
            }

            assertEquals(AttachmentTransferStateFfi.READY, states.last())
            assertTrue(feed.closed.get())
        }

    /**
     * A transfer still downloading after its pause queues for a download slot again before it goes on, so it cannot
     * run beside the request that took its slot meanwhile (#3819).
     */
    @Test
    fun `a transfer still downloading after its pause waits for a slot again`() =
        runBlocking {
            val gate = AttachmentDownloadGate(parallelism = 1)
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            val otherDone = CompletableDeferred<Unit>()
            val observer = async { observeWithSlot(gate, feed) }
            // The observer must own the only slot first, so "other" can only get it through the pause.
            withTimeout(WAIT_MILLIS) { while (!gate.isAdmitted("paused")) delay(1) }
            val other = async { holdSlot(gate, otherDone) }

            withTimeout(WAIT_MILLIS) { while (!gate.isAdmitted("other")) delay(1) }
            feed.deliver(AttachmentTransferStateFfi.DOWNLOADING)
            feed.deliver(AttachmentTransferStateFfi.READY)
            delay(SETTLE_MILLIS)
            assertTrue("the resumed transfer waits for the slot", observer.isActive)

            otherDone.complete(Unit)
            withTimeout(WAIT_MILLIS) { listOf(observer, other).awaitAll() }
            assertTrue(feed.closed.get())
        }

    /** A pause that ends with the transfer READY finishes at once instead of queuing behind other downloads. */
    @Test
    fun `a pause that ends ready finishes without waiting for a slot`() =
        runBlocking {
            val gate = AttachmentDownloadGate(parallelism = 1)
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            val otherDone = CompletableDeferred<Unit>()
            val observer = async { observeWithSlot(gate, feed) }
            val other = async { holdSlot(gate, otherDone) }

            withTimeout(WAIT_MILLIS) { while (!gate.isAdmitted("other")) delay(1) }
            feed.deliver(AttachmentTransferStateFfi.READY)
            withTimeout(WAIT_MILLIS) { observer.await() }

            assertTrue("the other download still holds the only slot", other.isActive)
            otherDone.complete(Unit)
            other.await()
        }

    /** Holds a slot as another download would, until [done] completes. */
    private suspend fun holdSlot(
        gate: AttachmentDownloadGate,
        done: CompletableDeferred<Unit>,
    ) = gate.withPermit("other", ACCOUNT, AttachmentDownloadPriority.Automatic) { done.await() }

    /** Observes [feed] inside a held gate slot, as a bubble's acquisition does, starting from a paused transfer. */
    private suspend fun observeWithSlot(
        gate: AttachmentDownloadGate,
        feed: ScriptedFeed,
    ) = gate.withPermit("paused", ACCOUNT, AttachmentDownloadPriority.Automatic) {
        awaitNativeAttachment(open = { feed }) { AttachmentTransferStateFfi.PAUSED }
    }

    /** A pause that outlasts the grace ends observation with the paused exception and releases the feed. */
    @Test
    fun `a pause that outlasts the grace ends observation`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }

            assertThrows(NativeAttachmentPausedException::class.java) {
                runBlocking {
                    withTimeout(WAIT_MILLIS) {
                        awaitNativeAttachment(open = { feed }, pausedGraceMillis = PAUSE_GRACE_MILLIS) {
                            AttachmentTransferStateFfi.PAUSED
                        }
                    }
                }
            }

            assertTrue(feed.closed.get())
        }

    /** Acquisition reads the snapshot once, before demand, and never polls it beside the feed. */
    @Test
    fun `acquisition does not poll the snapshot while it waits`() {
        val acquisition = source().readText().functionBody("WhiteNoiseAppState.acquireNativeAttachment")

        assertEquals(1, Regex("""attachmentTransferSnapshot\(""").findAll(acquisition).count())
        assertTrue(acquisition.indexOf("attachmentTransferSnapshot(") < acquisition.indexOf("awaitNativeAttachment("))
    }

    /** Locates the production transfer owner in root- and module-scoped test layouts. */
    private fun source(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/NativeAttachmentTransfers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/NativeAttachmentTransfers.kt"),
        ).firstOrNull(File::exists) ?: error("Missing NativeAttachmentTransfers.kt")

    private companion object {
        /** Builds one transfer status fixture. */
        fun status(state: AttachmentTransferStateFfi) =
            AttachmentTransferStatusFfi(
                reference = null,
                state = state,
                attempt = 0u,
                received = 0u,
                total = null,
                retryAt = null,
            )

        const val WAIT_MILLIS = 5_000L
        const val PAUSE_GRACE_MILLIS = 200L
        const val SETTLE_MILLIS = 100L
        const val ACCOUNT = "account-a"
    }
}

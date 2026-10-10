package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.functionBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The engine coalesces feed snapshots to one per 250 ms, so a faster transfer must not wait for the next. */
class NativeAttachmentFastReadyTest {
    /** A feed that hands out exactly the snapshots a test chooses to deliver, like the rate-limited engine feed. */
    private class ScriptedFeed : NativeTransferFeed {
        private val deliveries = Channel<Result<AttachmentTransferSnapshotFfi?>>(Channel.UNLIMITED)
        val closed = AtomicBoolean()
        val reads = AtomicInteger()
        val delivered = AtomicInteger()

        /** Blocks like the engine until the test delivers a snapshot or the owner closes the feed. */
        override suspend fun next(): AttachmentTransferSnapshotFfi? {
            reads.incrementAndGet()
            return deliveries.receive().getOrThrow().also { delivered.incrementAndGet() }
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

    /** An authoritative READY reaches the caller without waiting for the rate-limited feed to deliver it. */
    @Test
    fun `an authoritative ready is observed before the feed delivers it`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            val peeks = AtomicInteger()
            val states = mutableListOf<AttachmentTransferStateFfi>()

            withTimeout(WAIT_MILLIS) {
                awaitNativeAttachment(
                    feed = feed,
                    onState = { states += it },
                    peek = after(peeks, 3, AttachmentTransferStateFfi.READY),
                ) { AttachmentTransferStateFfi.QUEUED }
            }

            assertEquals(
                listOf(
                    AttachmentTransferStateFfi.NOT_REQUESTED,
                    AttachmentTransferStateFfi.QUEUED,
                    AttachmentTransferStateFfi.READY,
                ),
                states,
            )
            assertTrue("the owned feed must still be released", feed.closed.get())
        }

    /** The feed still decides the outcome when it is the first to deliver. */
    @Test
    fun `the feed wins when it delivers ready first`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            launch {
                delay(40)
                feed.deliver(AttachmentTransferStateFfi.READY)
            }

            withTimeout(WAIT_MILLIS) {
                awaitNativeAttachment(feed = feed, peek = { AttachmentTransferStateFfi.QUEUED }) {
                    AttachmentTransferStateFfi.QUEUED
                }
            }

            assertTrue(feed.closed.get())
            assertEquals("one initial read and one pending read, never a restarted one", 2, feed.reads.get())
        }

    /** An authoritative terminal state stops the cross-checking, and the feed's own typed failure still decides. */
    @Test
    fun `an authoritative terminal state defers to the feed's typed failure`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            val typed = MarmotKitException.InvalidMediaReference("synthetic integrity failure")
            val peeks = AtomicInteger()
            val terminalReadCompleted = CompletableDeferred<Unit>()
            launch {
                withTimeout(WAIT_MILLIS) { terminalReadCompleted.await() }
                feed.fail(typed)
            }

            val failure =
                assertThrows(MarmotKitException.InvalidMediaReference::class.java) {
                    runBlocking {
                        withTimeout(WAIT_MILLIS) {
                            awaitNativeAttachment(
                                feed = feed,
                                peek = {
                                    if (peeks.incrementAndGet() >= 2) {
                                        // Release the feed only after the actual IO read returns successfully.
                                        currentCoroutineContext().job.invokeOnCompletion { cause ->
                                            if (cause == null) terminalReadCompleted.complete(Unit)
                                        }
                                        AttachmentTransferStateFfi.FAILED
                                    } else {
                                        AttachmentTransferStateFfi.QUEUED
                                    }
                                },
                            ) { AttachmentTransferStateFfi.QUEUED }
                        }
                    }
                }

            assertSame("the engine's own failure must not be replaced by a generic terminal state", typed, failure)
            assertTrue("the terminal state was seen before the feed failed", peeks.get() >= 2)
            assertTrue(feed.closed.get())
        }

    /** A terminal state that the feed delivers as a snapshot is still thrown as the terminal exception. */
    @Test
    fun `a terminal snapshot from the feed is thrown as the terminal exception`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            launch {
                delay(60)
                feed.deliver(AttachmentTransferStateFfi.FAILED)
            }

            val failure =
                assertThrows(NativeAttachmentTerminalException::class.java) {
                    runBlocking {
                        withTimeout(WAIT_MILLIS) {
                            awaitNativeAttachment(feed = feed, peek = { AttachmentTransferStateFfi.FAILED }) {
                                AttachmentTransferStateFfi.QUEUED
                            }
                        }
                    }
                }

            assertEquals(AttachmentTransferStateFfi.FAILED, failure.state)
            assertTrue(feed.closed.get())
        }

    /** A feed result that completes while an older authoritative change is being returned is consumed, not dropped. */
    @Test
    fun `a ready the feed delivered during a peek is kept for the next wait`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            val peeks = AtomicInteger()
            val states = mutableListOf<AttachmentTransferStateFfi>()

            withTimeout(WAIT_MILLIS) {
                awaitNativeAttachment(
                    feed = feed,
                    onState = { states += it },
                    peek = {
                        if (peeks.incrementAndGet() == 1) {
                            feed.deliver(AttachmentTransferStateFfi.READY)
                            while (feed.delivered.get() < 2) delay(1)
                            delay(SETTLE_AFTER_DELIVERY_MILLIS)
                        }
                        AttachmentTransferStateFfi.DOWNLOADING
                    },
                ) { AttachmentTransferStateFfi.QUEUED }
            }

            assertEquals("the completed feed read must not be replaced by a new one", 2, feed.reads.get())
            assertEquals(AttachmentTransferStateFfi.READY, states.last())
            assertTrue(feed.closed.get())
        }

    /** A native read that is stuck behind another transaction never holds back a feed that has already delivered. */
    @Test
    fun `a blocked peek does not delay a ready from the feed`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            val started = AtomicBoolean()
            launch {
                while (!started.get()) delay(1)
                feed.deliver(AttachmentTransferStateFfi.READY)
            }

            withTimeout(WAIT_MILLIS) {
                awaitNativeAttachment(
                    feed = feed,
                    peek = {
                        started.set(true)
                        delay(BLOCKED_PEEK_MILLIS)
                        AttachmentTransferStateFfi.QUEUED
                    },
                ) { AttachmentTransferStateFfi.QUEUED }
            }

            assertTrue("the peek was in flight when the feed delivered", started.get())
            assertTrue(feed.closed.get())
        }

    /** A failing authoritative read neither ends the wait nor hides the feed. */
    @Test
    fun `peek failures are ignored and the feed still completes the wait`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            val peeks = AtomicInteger()
            val failedReadCompleted = CompletableDeferred<Unit>()
            launch {
                withTimeout(WAIT_MILLIS) { failedReadCompleted.await() }
                feed.deliver(AttachmentTransferStateFfi.READY)
            }

            withTimeout(WAIT_MILLIS) {
                awaitNativeAttachment(
                    feed = feed,
                    peek = {
                        peeks.incrementAndGet()
                        currentCoroutineContext().job.invokeOnCompletion { cause ->
                            if (cause == null) failedReadCompleted.complete(Unit)
                        }
                        throw IOException("snapshot unavailable")
                    },
                ) { AttachmentTransferStateFfi.QUEUED }
            }

            assertTrue("the failing read was attempted", peeks.get() > 0)
            assertTrue(feed.closed.get())
        }

    /** Without an authoritative read the original feed-only behavior is unchanged. */
    @Test
    fun `without a peek only the feed is consulted`() =
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
        }

    /** An authoritative state equal to the one already reported is not a change and is not reported again. */
    @Test
    fun `an unchanged authoritative state is not reported twice`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            val script =
                ArrayDeque(
                    listOf(
                        AttachmentTransferStateFfi.QUEUED,
                        AttachmentTransferStateFfi.QUEUED,
                        AttachmentTransferStateFfi.DOWNLOADING,
                        AttachmentTransferStateFfi.DOWNLOADING,
                        AttachmentTransferStateFfi.READY,
                    ),
                )
            val states = mutableListOf<AttachmentTransferStateFfi>()

            withTimeout(WAIT_MILLIS) {
                awaitNativeAttachment(
                    feed = feed,
                    onState = { states += it },
                    peek = { script.removeFirstOrNull() ?: AttachmentTransferStateFfi.READY },
                ) { AttachmentTransferStateFfi.QUEUED }
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
        }

    /** Authoritative reads back off to the engine's own cadence, so waiting never reads faster than the feed would. */
    @Test
    fun `authoritative reads are bounded while waiting`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            val peeks = AtomicInteger()
            val samplingStarted = CompletableDeferred<Unit>()
            val owner =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    awaitNativeAttachment(
                        feed = feed,
                        peek = {
                            if (peeks.incrementAndGet() == 3) samplingStarted.complete(Unit)
                            AttachmentTransferStateFfi.QUEUED
                        },
                    ) { AttachmentTransferStateFfi.QUEUED }
                }

            withTimeout(WAIT_MILLIS) { samplingStarted.await() }
            delay(SETTLE_MILLIS)
            val observed = peeks.get()
            owner.cancel()
            owner.join()

            // Start from three actual IO reads; the 700 ms sample must still reject one read per millisecond.
            assertTrue("read $observed times", observed in 3..MAX_PEEKS)
        }

    /** Caller cancellation stops the authoritative reads and releases the owned feed. */
    @Test
    fun `cancellation stops the reads and closes the feed`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            val peeks = AtomicInteger()
            val owner =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    awaitNativeAttachment(
                        feed = feed,
                        peek = {
                            peeks.incrementAndGet()
                            AttachmentTransferStateFfi.QUEUED
                        },
                    ) { AttachmentTransferStateFfi.QUEUED }
                }
            delay(80)
            owner.cancel()
            owner.join()
            val afterCancel = peeks.get()
            delay(300)

            assertTrue(owner.isCancelled)
            assertTrue("cancellation stranded the native subscription", feed.closed.get())
            assertEquals("reads continued after cancellation", afterCancel, peeks.get())
        }

    /** The production acquisition supplies a read-only authoritative read and keeps its original ordering. */
    @Test
    fun `native acquisition passes a read only authoritative peek`() {
        val text = source().readText()
        val acquisition = text.functionBody("WhiteNoiseAppState.acquireNativeAttachment")
        val peek =
            text
                .substringAfter("private suspend fun WhiteNoiseAppState.peekNativeTransferState(")
                .substringBefore("\n\n")

        assertTrue("peek = { peekNativeTransferState(" in acquisition)
        assertTrue("attachmentTransferSnapshot(" in peek)
        val mutating = listOf("Retry", "Cancel", "requestNative", "requestAutomatic", "download", "demand")
        assertFalse("a peek must never demand, retry or cancel", mutating.any { it in peek })
        val observed = acquisition.indexOf("awaitNativeAttachment(")
        assertTrue(observed in 0 until acquisition.indexOf("requestNativeInteractiveAttachment("))
    }

    /** A peek that reports [final] from the [count]th read onward and QUEUED before it. */
    private fun after(
        reads: AtomicInteger,
        count: Int,
        final: AttachmentTransferStateFfi,
    ): NativeStatePeek =
        {
            if (reads.incrementAndGet() >= count) final else AttachmentTransferStateFfi.QUEUED
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
        const val BLOCKED_PEEK_MILLIS = 60_000L
        const val SETTLE_AFTER_DELIVERY_MILLIS = 30L
        const val SETTLE_MILLIS = 700L
        const val MAX_PEEKS = 20
    }
}

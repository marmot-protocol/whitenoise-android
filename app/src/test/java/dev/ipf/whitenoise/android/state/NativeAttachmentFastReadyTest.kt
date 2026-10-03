package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import dev.ipf.whitenoise.android.functionBody
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        private val deliveries = Channel<AttachmentTransferSnapshotFfi?>(Channel.UNLIMITED)
        val closed = AtomicBoolean()
        val reads = AtomicInteger()

        /** Blocks like the engine until the test delivers a snapshot or the owner closes the feed. */
        override suspend fun next(): AttachmentTransferSnapshotFfi? {
            reads.incrementAndGet()
            return deliveries.receive()
        }

        /** Closing wakes a blocked read with end of stream, as the real handle does. */
        override fun close() {
            closed.set(true)
            deliveries.trySend(null)
        }

        /** Delivers one complete one-target replacement. */
        fun deliver(state: AttachmentTransferStateFfi) {
            deliveries.trySend(AttachmentTransferSnapshotFfi(listOf(status(state))))
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

    /** An authoritative terminal failure is thrown as the terminal exception without waiting for the feed. */
    @Test
    fun `an authoritative terminal state fails without waiting for the feed`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            val peeks = AtomicInteger()

            val failure =
                assertThrows(NativeAttachmentTerminalException::class.java) {
                    runBlocking {
                        withTimeout(WAIT_MILLIS) {
                            awaitNativeAttachment(
                                feed = feed,
                                peek = after(peeks, 2, AttachmentTransferStateFfi.FAILED),
                            ) { AttachmentTransferStateFfi.QUEUED }
                        }
                    }
                }

            assertEquals(AttachmentTransferStateFfi.FAILED, failure.state)
            assertTrue(feed.closed.get())
        }

    /** A failing authoritative read neither ends the wait nor hides the feed. */
    @Test
    fun `peek failures are ignored and the feed still completes the wait`() =
        runBlocking {
            val feed = ScriptedFeed().apply { deliver(AttachmentTransferStateFfi.NOT_REQUESTED) }
            val peeks = AtomicInteger()
            launch {
                delay(80)
                feed.deliver(AttachmentTransferStateFfi.READY)
            }

            withTimeout(WAIT_MILLIS) {
                awaitNativeAttachment(
                    feed = feed,
                    peek = {
                        peeks.incrementAndGet()
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

            delay(SETTLE_MILLIS)
            val observed = peeks.get()
            owner.cancel()
            owner.join()

            // 10, 20, 40, 80, 160, then 250 ms windows: roughly eight reads in 700 ms, never one per millisecond.
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
        const val SETTLE_MILLIS = 700L
        const val MAX_PEEKS = 20
    }
}

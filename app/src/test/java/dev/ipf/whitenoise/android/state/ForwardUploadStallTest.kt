package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A forward's destination upload must not look stalled for minutes (#2556). MDK bounds a transfer once it runs, so
 * the watchdog bounds only the wait before it starts: a counter that never leaves its first reading cancels the native
 * transfer and the upload call and fails as a stall. A started transfer, whose counter MDK raises to a per-file
 * high-water mark that can stand still while a smaller album item or a fallback server catches up, is never cancelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardUploadStallTest {
    private var cancels = 0
    private val nativeCancelled = CompletableDeferred<Unit>()

    /** A transfer that never starts is cancelled at the bound, even when the queued native call ignores the cancel. */
    @Test
    fun aTransferThatNeverStartsIsAbandonedAndReportedAsStalled() =
        runTest {
            val failure = runCatching { watch(progress = { 0L }) { awaitCancellation() } }.exceptionOrNull()

            assertTrue("$failure", failure is ForwardUploadStalledException)
            assertEquals(1, cancels)
            assertEquals(FORWARD_UPLOAD_START_MILLIS, testScheduler.currentTime)
            assertTrue("the caller was never cancelled", coroutineContext.isActive)
        }

    /** A native call that answers the cancel with its own error still reports the stall. */
    @Test
    fun aNativeErrorAfterTheCancelIsReportedAsStalled() =
        runTest {
            val failure =
                runCatching {
                    watch(progress = { 0L }) {
                        nativeCancelled.await()
                        throw IllegalStateException("native transfer cancelled")
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is ForwardUploadStalledException)
            assertEquals(1, cancels)
        }

    /**
     * Once the counter moves the transfer is MDK's to bound: a high-water mark that then stands still for far longer
     * than the start bound, as while a smaller album item uploads, is never cancelled.
     */
    @Test
    fun aStartedTransferIsNeverCancelledWhileItsHighWaterMarkStandsStill() =
        runTest {
            var counter = 0L

            val result =
                watch(progress = { counter }) {
                    delay(FORWARD_UPLOAD_START_MILLIS / 2)
                    counter = LARGE_ITEM_BYTES
                    delay(FORWARD_UPLOAD_START_MILLIS * 5)
                    "uploaded"
                }

            assertEquals("uploaded", result)
            assertEquals(0, cancels)
        }

    /** An upload that finishes inside the bound returns its value and stops the watchdog. */
    @Test
    fun aFinishedUploadStopsTheWatchdog() =
        runTest {
            assertEquals("uploaded", watch(progress = { 0L }) { "uploaded" })
            advanceTimeBy(FORWARD_UPLOAD_START_MILLIS * 2)
            runCurrent()

            assertEquals(0, cancels)
        }

    /** A counter that becomes unreadable stops the watchdog, never the upload. */
    @Test
    fun anUnreadableCounterNeverFailsTheUpload() =
        runTest {
            var reads = 0
            val result =
                watch(progress = { if (reads++ == 0) 0L else throw IllegalStateException("counter closed") }) {
                    delay(FORWARD_UPLOAD_START_MILLIS * 3)
                    "uploaded"
                }

            assertEquals("uploaded", result)
            assertEquals(0, cancels)
        }

    /** A transfer that fails on its own, before the bound, keeps its own error. */
    @Test
    fun anOrdinaryFailureKeepsItsOwnError() =
        runTest {
            val own = IOException("server refused")

            val failure = runCatching { watch(progress = { 0L }) { throw own } }.exceptionOrNull()

            // Coroutine stack-trace recovery may hand back a copy, so the type and message identify it.
            assertEquals(own.javaClass, failure?.javaClass)
            assertEquals(own.message, failure?.message)
            assertEquals(0, cancels)
        }

    /** Cancelling the caller, such as the user's Cancel, stays a cancellation and is never reported as a stall. */
    @Test
    fun theCallersCancellationStaysACancellation() =
        runTest {
            val upload =
                async(start = CoroutineStart.UNDISPATCHED) {
                    watch(progress = { 0L }) { awaitCancellation() }
                }
            advanceTimeBy(FORWARD_UPLOAD_START_MILLIS / 2)
            upload.cancel()
            runCurrent()

            assertTrue(upload.isCancelled)
            assertEquals(0, cancels)
        }

    /** A cancel that fails in the binding still abandons the upload and reports the stall. */
    @Test
    fun aFailingNativeCancelStillAbandonsTheUpload() =
        runTest {
            val failure =
                runCatching {
                    withUploadStartWatchdog(
                        startMillis = FORWARD_UPLOAD_START_MILLIS,
                        progress = { 0L },
                        cancel = { throw IllegalStateException("control closed") },
                        pollContext = EmptyCoroutineContext,
                    ) { awaitCancellation() }
                }.exceptionOrNull()

            assertTrue("$failure", failure is ForwardUploadStalledException)
        }

    /** Runs [upload] under the watchdog with test-clock polling and a cancel that releases the fake transfer. */
    private suspend fun <T> watch(
        progress: () -> Long,
        upload: suspend () -> T,
    ): T =
        withUploadStartWatchdog(
            startMillis = FORWARD_UPLOAD_START_MILLIS,
            progress = progress,
            cancel = {
                cancels += 1
                nativeCancelled.complete(Unit)
            },
            pollContext = EmptyCoroutineContext,
            upload = upload,
        )

    private companion object {
        const val LARGE_ITEM_BYTES = 6_000_000L
    }
}

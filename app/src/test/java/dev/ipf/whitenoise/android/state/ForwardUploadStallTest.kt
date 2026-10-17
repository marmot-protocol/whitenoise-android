package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A forward's destination upload must not look stalled for minutes (#2556). The watchdog cancels a transfer whose
 * byte counter stands still for the stall window and reports it as a stalled upload, and leaves every other outcome,
 * including a slow transfer that keeps moving and the caller's own cancellation, exactly as it was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardUploadStallTest {
    private var cancels = 0
    private val nativeCancelled = CompletableDeferred<Unit>()

    /** A counter that stands still for the window cancels the native transfer and fails as a stalled upload. */
    @Test
    fun aTransferThatStopsMovingIsCancelledAndReportedAsStalled() =
        runTest {
            val failure =
                runCatching {
                    watch(progress = { 512L }) {
                        nativeCancelled.await()
                        throw IllegalStateException("native transfer cancelled")
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is ForwardUploadStalledException)
            assertEquals(1, cancels)
            assertEquals(STALL_MILLIS, testScheduler.currentTime)
        }

    /** A slow transfer whose counter keeps moving is never cancelled, however long it takes. */
    @Test
    fun aSlowTransferThatKeepsMovingIsNeverCancelled() =
        runTest {
            var bytes = 0L

            val result =
                watch(progress = { bytes }) {
                    repeat(SLOW_STEPS) {
                        delay(STALL_MILLIS / 2)
                        bytes += 1
                    }
                    "uploaded"
                }

            assertEquals("uploaded", result)
            assertEquals(0, cancels)
        }

    /** An upload that finishes inside the window returns its value and stops the watchdog. */
    @Test
    fun aFinishedUploadStopsTheWatchdog() =
        runTest {
            assertEquals("uploaded", watch(progress = { 0L }) { "uploaded" })
            advanceTimeBy(STALL_MILLIS * 2)
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
                    delay(STALL_MILLIS * 3)
                    "uploaded"
                }

            assertEquals("uploaded", result)
            assertEquals(0, cancels)
        }

    /** A transfer that fails on its own, before any stall, keeps its own error. */
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
                    watch(progress = { 0L }) { CompletableDeferred<String>().await() }
                }
            advanceTimeBy(STALL_MILLIS / 2)
            upload.cancel()
            runCurrent()

            assertTrue(upload.isCancelled)
            assertEquals(0, cancels)
        }

    /** Runs [upload] under the watchdog with test-clock polling and a cancel that releases the fake transfer. */
    private suspend fun <T> watch(
        progress: () -> Long,
        upload: suspend () -> T,
    ): T =
        withUploadStallWatchdog(
            stallMillis = STALL_MILLIS,
            progress = progress,
            cancel = {
                cancels += 1
                nativeCancelled.complete(Unit)
            },
            pollContext = EmptyCoroutineContext,
            upload = upload,
        )

    private companion object {
        const val STALL_MILLIS = 60_000L
        const val SLOW_STEPS = 8
    }
}

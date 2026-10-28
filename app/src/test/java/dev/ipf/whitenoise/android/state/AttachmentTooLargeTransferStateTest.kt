package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.media.AttachmentTooLargeToPresentException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * A body acquired but larger than a byte-returning read may hold has not failed to transfer: the coordinator settles
 * it on whether it is retained, and only its awaiters, which wanted the bytes, see the read refused.
 */
class AttachmentTooLargeTransferStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val coordinator = AttachmentTransferCoordinator(scope)

    /** Cancels any owner a test left running. */
    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A retained body over the read budget settles Available, never Failed, and its awaiter still gets the refusal. */
    @Test
    fun retainedBodyOverTheReadBudgetSettlesAvailable() =
        runBlocking {
            val refusal = tooLarge()
            val transfer = coordinator.request("file", load = { throw refusal }) { true }

            assertSame(refusal, awaitFailure(transfer))
            assertEquals(AttachmentTransferState.Available, coordinator.state("file", false).value)
        }

    /** A body over the read budget that is no longer retained settles NotRetained, which offers Download, not Retry. */
    @Test
    fun unretainedBodyOverTheReadBudgetSettlesNotRetained() =
        runBlocking {
            coordinator.request("file", load = { throw tooLarge() }) { false }.let { awaitFailure(it) }

            assertEquals(AttachmentTransferState.NotRetained, coordinator.state("file", false).value)
        }

    /** A Retry of a body over the read budget settles Available again instead of returning to Failed. */
    @Test
    fun retryOfARetainedBodyOverTheReadBudgetStaysAvailable() =
        runBlocking {
            repeat(2) {
                coordinator.request("file", load = { throw tooLarge() }) { true }.let { awaitFailure(it) }
                assertEquals(AttachmentTransferState.Available, coordinator.state("file", false).value)
            }
        }

    /** Every other load failure still settles Failed, even when the probe would report a retained body. */
    @Test
    fun anOrdinaryLoadFailureStillSettlesFailed() =
        runBlocking {
            coordinator.request("file", load = { throw IOException("network") }) { true }.let { awaitFailure(it) }

            assertEquals(AttachmentTransferState.Failed, coordinator.state("file", false).value)
        }

    /** The refusal a read over the 32 MiB presentation budget raises, before any array is allocated. */
    private fun tooLarge() = AttachmentTooLargeToPresentException(BUDGET, declaredBytes = BUDGET + 1)

    /** Waits for [transfer] to end and returns what it threw. */
    private suspend fun awaitFailure(transfer: kotlinx.coroutines.Deferred<ByteArray>): Throwable =
        withTimeout(5_000) {
            try {
                transfer.await()
                fail("the transfer returned bytes")
                error("unreachable")
            } catch (expected: IOException) {
                expected
            }
        }

    private companion object {
        const val BUDGET = ATTACHMENT_PRESENTATION_MAX_BYTES
    }
}

package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Pins request-generation and account/runtime ownership for service readiness callbacks. */
class NativePushFallbackRuntimeReadinessTest {
    private val runtime = AppMarmotRuntime("test", inertMarmot())

    /** Repeated reconciliation shares one request until its exact service callback establishes readiness. */
    @Test
    fun sameOwnerReusesPendingRequestAndExactAcknowledgementMakesItReady() {
        val readiness = NativePushFallbackRuntimeReadiness()
        val owner = owner(account = "a", switchGeneration = 1L)

        val generation = readiness.request(owner)

        assertEquals(generation, readiness.request(owner))
        assertEquals(owner, readiness.acknowledge(generation) { true })
        assertTrue(readiness.isReady(owner))
    }

    /** Returning to the same account under a newer switch epoch rejects the first lifetime's callback. */
    @Test
    fun replacementOwnerRejectsTheOlderGenerationAcrossAccountReturn() {
        val readiness = NativePushFallbackRuntimeReadiness()
        val firstOwner = owner(account = "a", switchGeneration = 1L)
        val firstGeneration = readiness.request(firstOwner)
        val replacementOwner = owner(account = "a", switchGeneration = 3L)
        val replacementGeneration = readiness.request(replacementOwner)

        assertNull(readiness.acknowledge(firstGeneration) { true })
        assertFalse(readiness.isReady(firstOwner))
        assertEquals(replacementOwner, readiness.acknowledge(replacementGeneration) { true })
        assertTrue(readiness.isReady(replacementOwner))
    }

    /** A stale service teardown cannot erase readiness established for a replacement owner. */
    @Test
    fun staleInvalidationCannotClearANewerReadyGeneration() {
        val readiness = NativePushFallbackRuntimeReadiness()
        val firstGeneration = readiness.request(owner(account = "a", switchGeneration = 1L))
        val replacementOwner = owner(account = "b", switchGeneration = 2L)
        val replacementGeneration = readiness.request(replacementOwner)
        readiness.acknowledge(replacementGeneration) { true }

        readiness.invalidate(firstGeneration)

        assertTrue(readiness.isReady(replacementOwner))
    }

    /** Service success is discarded when AppState ownership changed while startup was pending. */
    @Test
    fun acknowledgementAfterOwnerLossCannotEstablishReadiness() {
        val readiness = NativePushFallbackRuntimeReadiness()
        val owner = owner(account = "a", switchGeneration = 1L)
        val generation = readiness.request(owner)

        assertNull(readiness.acknowledge(generation) { false })
        assertFalse(readiness.isReady(owner))
    }

    /** A newer explicit delivery intent replaces pending work even when every account/runtime key is unchanged. */
    @Test
    fun newDeliveryIntentRejectsTheOlderPendingRequest() {
        val readiness = NativePushFallbackRuntimeReadiness()
        val firstOwner = owner(account = "a", switchGeneration = 1L, intentGeneration = 1L)
        val firstGeneration = readiness.request(firstOwner)
        val replacementOwner = owner(account = "a", switchGeneration = 1L, intentGeneration = 2L)
        val replacementGeneration = readiness.request(replacementOwner)

        assertNull(readiness.acknowledge(firstGeneration) { true })
        assertEquals(replacementOwner, readiness.acknowledge(replacementGeneration) { true })
        assertTrue(readiness.isReady(replacementOwner))
    }

    /**
     * A waiter already suspended on its pending request completes only after the exact service
     * generation acknowledges its live owner; a wrong generation leaves it waiting (#3727).
     */
    @Test
    fun waiterRequiresExactLiveAcknowledgement() =
        runTest {
            val readiness = NativePushFallbackRuntimeReadiness()
            val owner = owner(account = "a", switchGeneration = 1L)
            val generation = readiness.request(owner)
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { readiness.await(owner) }
            assertFalse("the waiter suspends on the pending request", waiting.isCompleted)

            assertNull(readiness.acknowledge(generation + 1) { true })
            runCurrent()
            assertFalse("a wrong generation does not release the waiter", waiting.isCompleted)

            assertEquals(owner, readiness.acknowledge(generation) { true })
            assertTrue(withTimeout(WAITER_BOUND_MS) { waiting.await() })
        }

    /** Superseding a pending service generation releases an already-suspended old waiter as rejected. */
    @Test
    fun supersededWaiterCompletesFalse() =
        runTest {
            val readiness = NativePushFallbackRuntimeReadiness()
            val first = owner(account = "a", switchGeneration = 1L)
            readiness.request(first)
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { readiness.await(first) }
            assertFalse("the waiter suspends on the pending request", waiting.isCompleted)

            readiness.request(owner(account = "b", switchGeneration = 2L))

            assertFalse(withTimeout(WAITER_BOUND_MS) { waiting.await() })
        }

    /** Explicit invalidation releases an already-suspended waiter without manufacturing readiness. */
    @Test
    fun invalidatedWaiterCompletesFalse() =
        runTest {
            val readiness = NativePushFallbackRuntimeReadiness()
            val owner = owner(account = "a", switchGeneration = 1L)
            val generation = readiness.request(owner)
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { readiness.await(owner) }
            assertFalse("the waiter suspends on the pending request", waiting.isCompleted)

            readiness.invalidate(generation)

            assertFalse(withTimeout(WAITER_BOUND_MS) { waiting.await() })
            assertFalse(readiness.isReady(owner))
        }

    /** Builds one owner whose switch generation distinguishes A-to-B-to-A lifetimes. */
    private fun owner(
        account: String,
        switchGeneration: Long,
        intentGeneration: Long = 1L,
    ) = NativePushFallbackOwner(
        accountRef = account,
        runtime = runtime,
        runtimeGeneration = 1,
        accountSwitchGeneration = switchGeneration,
        intentGeneration = intentGeneration,
    )

    /** Supplies an identity-only runtime dependency; no native method is exercised here. */
    private fun inertMarmot(): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "NativePushFallbackRuntimeReadinessTestMarmot"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> throw UnsupportedOperationException("Unexpected Marmot call: ${method.name}")
            }
        } as MarmotInterface

    private companion object {
        /** Virtual-time bound for a released waiter to complete. */
        const val WAITER_BOUND_MS = 1_000L
    }
}

package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.RelayEndpointClassificationFfi
import dev.ipf.marmotkit.RelayEndpointPolicyFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class KeyPackageDeletionDnsDeadlineTest {
    /** An unresponsive resolver must terminate with recovery before the caller's longer safety timeout. */
    @Test
    fun stalledResolutionReturnsRecoveryAndCancelsTheLookup() =
        runTest {
            var cancelled = false
            var deleted = false
            val result =
                withTimeout(9_000) {
                    StandardTestDispatcher(testScheduler).deleteKeyPackageThroughSafeSourceRelays(
                        sourceRelays = listOf("wss://stalled.example"),
                        classify = ::allowEveryRelay,
                        resolve = {
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled = true
                            }
                        },
                        delete = { deleted = true },
                    )
                }

            assertEquals(KeyPackageDeletionResult.HostVerificationUnavailable, result)
            assertEquals(true, cancelled)
            assertFalse(deleted)
            assertEquals(2_000L, testScheduler.currentTime)
        }

    /** One failed host consumes only its own budget, leaving a later public source eligible. */
    @Test
    fun stalledHostDoesNotPreventTheNextUsableSource() =
        runTest {
            var deletedThrough: List<String>? = null
            val result =
                withTimeout(9_000) {
                    StandardTestDispatcher(testScheduler).deleteKeyPackageThroughSafeSourceRelays(
                        sourceRelays = listOf("wss://stalled.example", "wss://online.example"),
                        classify = ::allowEveryRelay,
                        resolve = { host ->
                            if (host == "stalled.example") awaitCancellation() else arrayOf(publicAddress())
                        },
                        delete = { deletedThrough = it },
                    )
                }

            assertEquals(KeyPackageDeletionResult.Deleted, result)
            assertEquals(listOf("wss://online.example"), deletedThrough)
            assertEquals(2_000L, testScheduler.currentTime)
        }

    /** Stalled work surrounding a public answer cannot renew the budget or discard that answer. */
    @Test
    fun totalDeadlineRetainsCompletedPublicAnswersAndCancelsOutstandingWork() =
        runTest {
            var started = 0
            var cancelled = 0
            var active = 0
            var maxActive = 0
            var deletedThrough: List<String>? = null
            val result =
                withTimeout(12_000) {
                    StandardTestDispatcher(testScheduler).deleteKeyPackageThroughSafeSourceRelays(
                        sourceRelays =
                            listOf(
                                "wss://stalled-prefix.example",
                                "wss://online.example",
                            ) + stalledRelays(),
                        classify = ::allowEveryRelay,
                        resolve = { host ->
                            if (host == "online.example") {
                                arrayOf(publicAddress())
                            } else {
                                started += 1
                                active += 1
                                maxActive = maxOf(maxActive, active)
                                try {
                                    awaitCancellation()
                                } finally {
                                    cancelled += 1
                                    active -= 1
                                }
                            }
                        },
                        delete = { deletedThrough = it },
                    )
                }

            assertEquals(KeyPackageDeletionResult.Deleted, result)
            assertEquals(listOf("wss://online.example"), deletedThrough)
            // The total deadline can begin one final lookup at the host-timeout boundary.
            assertTrue(started in 1..5)
            assertEquals(started, cancelled)
            assertEquals(0, active)
            assertEquals(1, maxActive)
            assertEquals(8_000L, testScheduler.currentTime)
        }

    /** A total deadline with no verified source returns recovery, never a speculative native deletion. */
    @Test
    fun totalDeadlineWithoutPublicAnswersReturnsRecovery() =
        runTest {
            var deleted = false
            val result =
                withTimeout(12_000) {
                    StandardTestDispatcher(testScheduler).deleteKeyPackageThroughSafeSourceRelays(
                        sourceRelays = stalledRelays(),
                        classify = ::allowEveryRelay,
                        resolve = { awaitCancellation() },
                        delete = { deleted = true },
                    )
                }

            assertEquals(KeyPackageDeletionResult.HostVerificationUnavailable, result)
            assertFalse(deleted)
            assertEquals(8_000L, testScheduler.currentTime)
        }

    /** Caller cancellation while DNS is suspended must propagate and drain the in-flight lookup. */
    @Test
    fun parentCancellationDoesNotBecomeRecoveryOrDeletion() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var cancelled = false
            var returned = false
            var deleted = false
            val caller =
                async {
                    StandardTestDispatcher(testScheduler).deleteKeyPackageThroughSafeSourceRelays(
                        sourceRelays = listOf("wss://stalled.example"),
                        classify = ::allowEveryRelay,
                        resolve = {
                            started.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled = true
                            }
                        },
                        delete = { deleted = true },
                    )
                    returned = true
                }

            withTimeout(9_000) { started.await() }
            caller.cancelAndJoin()
            assertTrue(caller.isCancelled)
            assertTrue(cancelled)
            assertFalse(returned)
            assertFalse(deleted)
            assertEquals(0L, testScheduler.currentTime)
        }

    /** A stale account wins over timeout recovery and cannot start another host or delete. */
    @Test
    fun accountSwitchDuringStalledLookupSupersedesTimeoutRecovery() =
        runTest {
            var accountActive = true
            var lookups = 0
            var deleted = false
            val result =
                withTimeout(9_000) {
                    StandardTestDispatcher(testScheduler).deleteKeyPackageThroughSafeSourceRelays(
                        sourceRelays = stalledRelays(),
                        classify = ::allowEveryRelay,
                        resolve = {
                            lookups += 1
                            accountActive = false
                            awaitCancellation()
                        },
                        accountStillActive = { accountActive },
                        delete = { deleted = true },
                    )
                }

            assertEquals(KeyPackageDeletionResult.Superseded, result)
            assertEquals(1, lookups)
            assertFalse(deleted)
            assertEquals(2_000L, testScheduler.currentTime)
        }

    /** Supplies more stalled hosts than the shared deadline can visit sequentially. */
    private fun stalledRelays(): List<String> = List(10) { "wss://stalled-$it.example" }

    /** Keeps endpoint classification deterministic while isolating the DNS deadline contract. */
    private fun allowEveryRelay(relays: List<String>): List<RelayEndpointClassificationFfi> =
        relays.map { relay ->
            RelayEndpointClassificationFfi(
                endpoint = relay,
                normalizedEndpoint = relay,
                policy = RelayEndpointPolicyFfi.ALLOWED,
            )
        }

    /** Creates a public answer without using the network. */
    private fun publicAddress(): InetAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
}

package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.RelayEndpointClassificationFfi
import dev.ipf.marmotkit.RelayEndpointPolicyFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class KeyPackageDeletionDnsDeadlineTest {
    /** An unresponsive resolver must terminate with recovery before the caller's longer safety timeout. */
    @Test
    fun stalledResolutionReturnsRecoveryAndCancelsTheLookup() =
        runBlocking {
            var cancelled = false
            var deleted = false
            val result =
                withTimeout(9_000) {
                    deleteKeyPackageThroughSafeSourceRelays(
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
        }

    /** One failed host consumes only its own budget, leaving a later public source eligible. */
    @Test
    fun stalledHostDoesNotPreventTheNextUsableSource() =
        runBlocking {
            var deletedThrough: List<String>? = null
            val result =
                withTimeout(9_000) {
                    deleteKeyPackageThroughSafeSourceRelays(
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
        }

    /** A hostile stalled suffix cannot renew the total budget or discard already verified public sources. */
    @Test
    fun totalDeadlineRetainsCompletedPublicAnswersAndCancelsOutstandingWork() =
        runBlocking {
            var started = 0
            var cancelled = 0
            var active = 0
            var maxActive = 0
            var deletedThrough: List<String>? = null
            val result =
                withTimeout(12_000) {
                    deleteKeyPackageThroughSafeSourceRelays(
                        sourceRelays = listOf("wss://online.example") + stalledRelays(),
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
            assertTrue(started in 1..4)
            assertEquals(started, cancelled)
            assertEquals(0, active)
            assertEquals(1, maxActive)
        }

    /** A total deadline with no verified source returns recovery, never a speculative native deletion. */
    @Test
    fun totalDeadlineWithoutPublicAnswersReturnsRecovery() =
        runBlocking {
            var deleted = false
            val result =
                withTimeout(12_000) {
                    deleteKeyPackageThroughSafeSourceRelays(
                        sourceRelays = stalledRelays(),
                        classify = ::allowEveryRelay,
                        resolve = { awaitCancellation() },
                        delete = { deleted = true },
                    )
                }

            assertEquals(KeyPackageDeletionResult.HostVerificationUnavailable, result)
            assertFalse(deleted)
        }

    /** Caller cancellation while DNS is suspended must propagate and drain the in-flight lookup. */
    @Test
    fun parentCancellationDoesNotBecomeRecoveryOrDeletion() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            var cancelled = false
            var returned = false
            var deleted = false
            val caller =
                async {
                    deleteKeyPackageThroughSafeSourceRelays(
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
        }

    /** A stale account wins over timeout recovery and cannot start another host or delete. */
    @Test
    fun accountSwitchDuringStalledLookupSupersedesTimeoutRecovery() =
        runBlocking {
            var accountActive = true
            var lookups = 0
            var deleted = false
            val result =
                withTimeout(9_000) {
                    deleteKeyPackageThroughSafeSourceRelays(
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

package dev.ipf.whitenoise.android.state

import android.net.DnsResolver
import android.os.CancellationSignal
import dev.ipf.marmotkit.RelayEndpointClassificationFfi
import dev.ipf.marmotkit.RelayEndpointPolicyFfi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36])
@OptIn(ExperimentalCoroutinesApi::class)
class KeyPackageDeletionDnsResolverTest {
    /** Real adapter aggregation must retain an unsafe answer from either family so deletion cannot dispatch. */
    @Test
    fun unsafeAddressInEitherFamilyBlocksDeletion() =
        runTest {
            for (unsafeType in listOf(DnsResolver.TYPE_A, DnsResolver.TYPE_AAAA)) {
                var deleteCalls = 0
                val result =
                    deleteWithDnsQuery(
                        query = { _, type, _, callback ->
                            val address =
                                if (type == unsafeType) {
                                    InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
                                } else {
                                    publicAddress()
                                }
                            callback.onAnswer(listOf(address), 0)
                        },
                        delete = { deleteCalls++ },
                    )

                assertEquals(KeyPackageDeletionResult.NoUsableRelay, result)
                assertEquals(0, deleteCalls)
            }
        }

    /** A completely verified source reaches the destructive boundary once, including single-family DNS. */
    @Test
    fun publicAddressAndSuccessfulEmptyFamilyReachDeletionOnce() =
        runTest {
            var deleteCalls = 0
            val result =
                deleteWithDnsQuery(
                    query = { _, type, _, callback ->
                        callback.onAnswer(if (type == DnsResolver.TYPE_A) listOf(publicAddress()) else emptyList(), 0)
                    },
                    delete = {
                        assertEquals(listOf("wss://relay.example"), it)
                        deleteCalls++
                    },
                )

            assertEquals(KeyPackageDeletionResult.Deleted, result)
            assertEquals(1, deleteCalls)
        }

    /** Failed family verification is surfaced as recoverable DNS unavailability, never a native deletion. */
    @Test
    fun failedFamilyDoesNotReachDeletionBoundary() =
        runTest {
            var deleteCalls = 0
            val result =
                deleteWithDnsQuery(
                    query = { _, type, _, callback ->
                        if (type == DnsResolver.TYPE_A) {
                            callback.onAnswer(listOf(publicAddress()), 0)
                        } else {
                            callback.onError(dnsError())
                        }
                    },
                    delete = { deleteCalls++ },
                )

            assertEquals(KeyPackageDeletionResult.HostVerificationUnavailable, result)
            assertEquals(0, deleteCalls)
        }

    /** Explicit family queries preserve a valid single-family host even when its other family is NODATA. */
    @Test
    fun queriesBothRecordTypesAndAcceptsSuccessfulEmptyFamily() =
        runTest {
            for (emptyType in listOf(DnsResolver.TYPE_A, DnsResolver.TYPE_AAAA)) {
                val types = mutableListOf<Int>()
                val signals = mutableListOf<CancellationSignal>()
                val result =
                    resolveKeyPackageDeletionHost("relay.example") { _, type, signal, callback ->
                        types += type
                        signals += signal
                        callback.onAnswer(if (type == emptyType) emptyList() else listOf(publicAddress()), 0)
                    }

                assertEquals(setOf(DnsResolver.TYPE_A, DnsResolver.TYPE_AAAA), types.toSet())
                assertEquals(2, types.size)
                assertNotSame(signals[0], signals[1])
                assertEquals(listOf(publicAddress()), result?.toList())
            }
        }

    /** A successful family cannot hide failure or a non-success response from the other family. */
    @Test
    fun oneSuccessfulFamilyCannotMaskOtherFamilyFailure() =
        runTest {
            for (failedType in listOf(DnsResolver.TYPE_A, DnsResolver.TYPE_AAAA)) {
                for (protocolError in listOf(false, true)) {
                    val result =
                        resolveKeyPackageDeletionHost("relay.example") { _, type, _, callback ->
                            when {
                                type != failedType -> callback.onAnswer(listOf(publicAddress()), 0)
                                protocolError -> callback.onAnswer(emptyList(), 2)
                                else -> callback.onError(dnsError())
                            }
                        }
                    assertNull(result)
                }
            }
        }

    /** A completed public family cannot release a host while the other family remains unverifiable. */
    @Test
    fun successfulFamilyCannotMaskStalledFamilyAndItsLateUnsafeAnswer() =
        runTest {
            for (stalledType in listOf(DnsResolver.TYPE_A, DnsResolver.TYPE_AAAA)) {
                lateinit var stalledSignal: CancellationSignal
                lateinit var stalledCallback: DnsResolver.Callback<List<InetAddress>>
                val result =
                    withTimeoutOrNull(10) {
                        resolveKeyPackageDeletionHost("relay.example") { _, type, signal, callback ->
                            if (type == stalledType) {
                                stalledSignal = signal
                                stalledCallback = callback
                            } else {
                                callback.onAnswer(listOf(publicAddress()), 0)
                            }
                        }
                    }

                assertNull(result)
                assertTrue(stalledSignal.isCanceled)
                stalledCallback.onAnswer(listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))), 0)
            }
        }

    /** A synchronous failure tears down a query that was already started for the sibling family. */
    @Test
    fun secondFamilyStartFailureCancelsTheFirstPlatformQuery() =
        runTest {
            val signals = mutableListOf<CancellationSignal>()
            val result =
                resolveKeyPackageDeletionHost("relay.example") { _, type, signal, _ ->
                    signals += signal
                    if (type == DnsResolver.TYPE_AAAA) throw IOException("offline")
                }

            assertNull(result)
            assertEquals(2, signals.size)
            assertTrue(signals.all { it.isCanceled })
        }

    /** Asynchronous family failure must also cancel a sibling already waiting for its platform reply. */
    @Test
    fun familyErrorCancelsOutstandingSiblingQuery() =
        runTest {
            val signals = mutableListOf<CancellationSignal>()
            val callbacks = mutableListOf<DnsResolver.Callback<List<InetAddress>>>()
            val caller =
                async {
                    resolveKeyPackageDeletionHost("relay.example") { _, _, signal, callback ->
                        signals += signal
                        callbacks += callback
                    }
                }
            runCurrent()
            assertEquals(2, callbacks.size)
            callbacks[1].onError(dnsError())

            assertNull(caller.await())
            assertTrue(signals[0].isCanceled)
            callbacks[0].onAnswer(listOf(publicAddress()), 0)
        }

    /** A successful callback preserves every answer for the caller's all-address safety check. */
    @Test
    fun successfulAnswerWinsOverDuplicateCallbacksAndLateStartFailure() =
        runTest {
            val addresses = listOf(publicAddress(), InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            val result =
                resolveKeyPackageDeletionHost("relay.example") { host, _, signal, callback ->
                    assertEquals("relay.example", host)
                    assertFalse(signal.isCanceled)
                    callback.onAnswer(addresses, 0)
                    callback.onAnswer(emptyList(), 0)
                    callback.onError(dnsError())
                    throw IOException("late failure")
                }

            assertEquals(addresses, result?.toList())
        }

    /** DNS protocol errors and empty replies cannot become usable deletion destinations. */
    @Test
    fun unsuccessfulAndEmptyRepliesAreUnavailable() =
        runTest {
            assertNull(
                resolveKeyPackageDeletionHost("relay.example") { _, _, _, callback ->
                    callback.onAnswer(listOf(publicAddress()), 3)
                },
            )
            assertNull(
                resolveKeyPackageDeletionHost("relay.example") { _, _, _, callback ->
                    callback.onAnswer(emptyList(), 0)
                },
            )
            assertNull(
                resolveKeyPackageDeletionHost("relay.example") { _, _, _, callback ->
                    callback.onError(dnsError())
                },
            )
        }

    /** A platform query-start exception is recoverable and does not expose its host or failure detail. */
    @Test
    fun synchronousQueryFailureIsUnavailable() =
        runTest {
            assertNull(
                resolveKeyPackageDeletionHost("relay.example") { _, _, _, _ -> throw IOException("offline") },
            )
        }

    /** The caller's deadline cancels the Android query and makes subsequent callbacks harmless. */
    @Test
    fun timeoutCancelsThePlatformSignalAndIgnoresLateCallbacks() =
        runTest {
            val signals = mutableListOf<CancellationSignal>()
            val callbacks = mutableListOf<DnsResolver.Callback<List<InetAddress>>>()
            val result =
                withTimeoutOrNull(10) {
                    resolveKeyPackageDeletionHost("stalled.example") { _, _, querySignal, queryCallback ->
                        signals += querySignal
                        callbacks += queryCallback
                    }
                }

            assertNull(result)
            assertEquals(2, signals.size)
            assertTrue(signals.all { it.isCanceled })
            callbacks.forEach { callback ->
                callback.onAnswer(listOf(publicAddress()), 0)
                callback.onError(dnsError())
            }
        }

    /** Explicit caller cancellation must stay cancellation instead of returning DNS recovery. */
    @Test
    fun parentCancellationCancelsThePlatformSignalWithoutReturningAnAnswer() =
        runTest {
            val signals = mutableListOf<CancellationSignal>()
            val callbacks = mutableListOf<DnsResolver.Callback<List<InetAddress>>>()
            var returned = false
            val caller =
                async(start = CoroutineStart.UNDISPATCHED) {
                    resolveKeyPackageDeletionHost("stalled.example") { _, _, querySignal, queryCallback ->
                        signals += querySignal
                        callbacks += queryCallback
                    }
                    returned = true
                }
            runCurrent()
            caller.cancelAndJoin()
            assertTrue(caller.isCancelled)
            assertEquals(2, signals.size)
            assertTrue(signals.all { it.isCanceled })
            assertFalse(returned)
            callbacks.forEach { callback ->
                callback.onAnswer(listOf(publicAddress()), 0)
                callback.onError(dnsError())
            }
        }

    /** Numeric public literals are parsed locally, without starting a DNS query. */
    @Test
    fun numericAddressesDoNotUseDns() =
        runTest {
            listOf("8.8.8.8", "2606:4700:4700::1111").forEach { host ->
                val result = resolveKeyPackageDeletionHost(host) { _, _, _, _ -> error("numeric address queried") }
                assertNotNull(result)
                assertEquals(1, result?.size)
            }
        }

    /** Invalid colon-shaped IPv6 input cannot fall through to hostname lookup. */
    @Test
    fun malformedIpv6IsRejectedWithoutDns() =
        runTest {
            assertNull(
                resolveKeyPackageDeletionHost("bad::address::") { _, _, _, _ -> error("invalid IPv6 queried") },
            )
        }

    /** Uses raw bytes so this fixture never depends on real DNS. */
    private fun publicAddress(): InetAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))

    /** Exercises the real DNS/safety boundary without native classification or publication. */
    private suspend fun deleteWithDnsQuery(
        query: KeyPackageDeletionDnsQuery,
        delete: suspend (List<String>) -> Unit,
    ): KeyPackageDeletionResult =
        deleteKeyPackageThroughSafeSourceRelays(
            sourceRelays = listOf("wss://relay.example"),
            classify = { endpoints ->
                endpoints.map { RelayEndpointClassificationFfi(it, it, RelayEndpointPolicyFfi.ALLOWED) }
            },
            resolve = { host -> resolveKeyPackageDeletionHost(host, query) },
            delete = delete,
        )

    /** Models the platform-owned error; its constructor is not public on API30. */
    private fun dnsError(): DnsResolver.DnsException =
        DnsResolver.DnsException::class.java
            .getDeclaredConstructor(Int::class.javaPrimitiveType, Throwable::class.java)
            .apply { isAccessible = true }
            .newInstance(DnsResolver.ERROR_SYSTEM, IOException("offline"))
}

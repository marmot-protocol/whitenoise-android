package dev.ipf.whitenoise.android.state

import android.net.DnsResolver
import android.os.CancellationSignal
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class KeyPackageDeletionDnsResolverTest {
    /** A successful callback preserves every answer for the caller's all-address safety check. */
    @Test
    fun successfulAnswerWinsOverDuplicateCallbacksAndLateStartFailure() =
        runTest {
            val addresses = listOf(publicAddress(), InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            val result =
                resolveKeyPackageDeletionHost("relay.example") { host, signal, callback ->
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
                resolveKeyPackageDeletionHost("relay.example") { _, _, callback ->
                    callback.onAnswer(listOf(publicAddress()), 3)
                },
            )
            assertNull(
                resolveKeyPackageDeletionHost("relay.example") { _, _, callback ->
                    callback.onAnswer(emptyList(), 0)
                },
            )
            assertNull(
                resolveKeyPackageDeletionHost("relay.example") { _, _, callback ->
                    callback.onError(dnsError())
                },
            )
        }

    /** A platform query-start exception is recoverable and does not expose its host or failure detail. */
    @Test
    fun synchronousQueryFailureIsUnavailable() =
        runTest {
            assertNull(
                resolveKeyPackageDeletionHost("relay.example") { _, _, _ -> throw IOException("offline") },
            )
        }

    /** The caller's deadline cancels the Android query and makes subsequent callbacks harmless. */
    @Test
    fun timeoutCancelsThePlatformSignalAndIgnoresLateCallbacks() =
        runTest {
            lateinit var signal: CancellationSignal
            lateinit var callback: DnsResolver.Callback<List<InetAddress>>
            val result =
                withTimeoutOrNull(10) {
                    resolveKeyPackageDeletionHost("stalled.example") { _, querySignal, queryCallback ->
                        signal = querySignal
                        callback = queryCallback
                    }
                }

            assertNull(result)
            assertTrue(signal.isCanceled)
            callback.onAnswer(listOf(publicAddress()), 0)
            callback.onError(dnsError())
        }

    /** Explicit caller cancellation must stay cancellation instead of returning DNS recovery. */
    @Test
    fun parentCancellationCancelsThePlatformSignalWithoutReturningAnAnswer() =
        runTest {
            lateinit var signal: CancellationSignal
            lateinit var callback: DnsResolver.Callback<List<InetAddress>>
            var returned = false
            val caller =
                async(start = CoroutineStart.UNDISPATCHED) {
                    resolveKeyPackageDeletionHost("stalled.example") { _, querySignal, queryCallback ->
                        signal = querySignal
                        callback = queryCallback
                    }
                    returned = true
                }

            caller.cancelAndJoin()
            assertTrue(caller.isCancelled)
            assertTrue(signal.isCanceled)
            assertFalse(returned)
            callback.onAnswer(listOf(publicAddress()), 0)
            callback.onError(dnsError())
        }

    /** Numeric public literals are parsed locally, without starting a DNS query. */
    @Test
    fun numericAddressesDoNotUseDns() =
        runTest {
            listOf("8.8.8.8", "2606:4700:4700::1111").forEach { host ->
                val result = resolveKeyPackageDeletionHost(host) { _, _, _ -> error("numeric address queried") }
                assertNotNull(result)
                assertEquals(1, result?.size)
            }
        }

    /** Invalid colon-shaped IPv6 input cannot fall through to hostname lookup. */
    @Test
    fun malformedIpv6IsRejectedWithoutDns() =
        runTest {
            assertNull(
                resolveKeyPackageDeletionHost("bad::address::") { _, _, _ -> error("invalid IPv6 queried") },
            )
        }

    /** Uses raw bytes so this fixture never depends on real DNS. */
    private fun publicAddress(): InetAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))

    /** Models the platform-owned error; its constructor is not public on API30. */
    private fun dnsError(): DnsResolver.DnsException =
        DnsResolver.DnsException::class.java
            .getDeclaredConstructor(Int::class.javaPrimitiveType, Throwable::class.java)
            .apply { isAccessible = true }
            .newInstance(DnsResolver.ERROR_SYSTEM, IOException("offline"))
}

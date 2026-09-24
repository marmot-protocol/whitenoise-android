package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Regression coverage for cancelling DNS-pinned requests during connection setup. */
class SafeHttpsGetConnectCancellationTest {
    @Test
    fun cancellationDuringConnectStopsBeforeTryingAnotherAddress() {
        val enteredConnect = CountDownLatch(1)
        val first = BlockingConnectHttpConnection(URL(TEST_URL), enteredConnect)
        var cancelRequest: (() -> Unit)? = null
        var connectionAttempts = 0
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result =
                executor.submit<ByteArray?> {
                    SafeHttpsGet.get(
                        url = TEST_URL,
                        maxBodyBytes = 32,
                        connectTimeoutMillis = 5_000,
                        readTimeoutMillis = 5_000,
                        registerCancellation = { cancelRequest = it },
                        dependencies =
                            SafeHttpsGetDependencies(
                                resolve = {
                                    arrayOf(
                                        InetAddress.getByName("8.8.8.8"),
                                        InetAddress.getByName("1.1.1.1"),
                                    )
                                },
                                openPinnedConnection = { request ->
                                    SafeHttpsGet.openPinnedConnection(
                                        request = request,
                                        connectionFactory = { _, _, _, _, _ ->
                                            connectionAttempts += 1
                                            if (connectionAttempts == 1) {
                                                first
                                            } else {
                                                error("Cancellation must stop address fallback")
                                            }
                                        },
                                    )
                                },
                            ),
                    )
                }

            assertTrue(enteredConnect.await(1, TimeUnit.SECONDS))
            requireNotNull(cancelRequest).invoke()

            assertNull(result.get(1, TimeUnit.SECONDS))
            assertTrue(first.disconnected)
            assertEquals(1, connectionAttempts)
        } finally {
            executor.shutdownNow()
        }
    }

    private class BlockingConnectHttpConnection(
        url: URL,
        private val enteredConnect: CountDownLatch,
    ) : HttpURLConnection(url) {
        private val disconnectedSignal = CountDownLatch(1)

        @Volatile var disconnected = false

        override fun connect() {
            enteredConnect.countDown()
            disconnectedSignal.await(5, TimeUnit.SECONDS)
            throw IOException("connect cancelled")
        }

        override fun disconnect() {
            disconnected = true
            disconnectedSignal.countDown()
        }

        override fun usingProxy(): Boolean = false
    }

    private companion object {
        const val TEST_URL = "https://example.test/path"
    }
}

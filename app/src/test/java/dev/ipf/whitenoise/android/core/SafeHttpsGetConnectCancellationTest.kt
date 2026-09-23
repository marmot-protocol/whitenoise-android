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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Regression coverage for cancelling DNS-pinned requests during connection setup. */
class SafeHttpsGetConnectCancellationTest {
    @Test
    fun cancellationDuringConnectStopsBeforeTryingAnotherAddress() {
        val enteredConnect = CountDownLatch(1)
        val first = BlockingConnectHttpConnection(URL(TEST_URL), enteredConnect)
        val cancelled = AtomicBoolean(false)
        val activeConnection = AtomicReference<HttpURLConnection?>(null)
        var connectionAttempts = 0
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result =
                executor.submit<HttpURLConnection?> {
                    SafeHttpsGet.openPinnedConnection(
                        request = request(cancelled, activeConnection),
                        connectionFactory = { _, _, _, _, _ ->
                            connectionAttempts += 1
                            if (connectionAttempts == 1) first else error("Cancellation must stop address fallback")
                        },
                    )
                }

            assertTrue(enteredConnect.await(1, TimeUnit.SECONDS))
            cancelled.set(true)
            activeConnection.getAndSet(null)?.disconnect()

            assertNull(result.get(1, TimeUnit.SECONDS))
            assertTrue(first.disconnected)
            assertEquals(1, connectionAttempts)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun request(
        cancelled: AtomicBoolean,
        activeConnection: AtomicReference<HttpURLConnection?>,
    ): SafeHttpsPinnedRequest =
        SafeHttpsPinnedRequest(
            parsed = URL(TEST_URL),
            addresses = arrayOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("1.1.1.1")),
            requestDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
            connectTimeoutMillis = 5_000,
            readTimeoutMillis = 5_000,
            requestHeaders = emptyMap(),
            activate = { connection -> activateUnlessCancelled(connection, cancelled, activeConnection) },
            isCancelled = cancelled::get,
        )

    private fun activateUnlessCancelled(
        connection: HttpURLConnection,
        cancelled: AtomicBoolean,
        activeConnection: AtomicReference<HttpURLConnection?>,
    ): Boolean {
        activeConnection.set(connection)
        if (!cancelled.get()) return true
        connection.disconnect()
        return false
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

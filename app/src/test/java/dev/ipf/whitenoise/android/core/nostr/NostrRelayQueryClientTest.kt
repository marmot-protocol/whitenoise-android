package dev.ipf.whitenoise.android.core.nostr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NostrRelayQueryClientTest {
    @Test
    fun publicRelayDnsRejectsUnsafeHostsAndRebindingAnswers() {
        val publicAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        val privateAddress = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))

        assertEquals(
            listOf(publicAddress),
            PublicRelayDns { listOf(publicAddress) }.lookup("relay.example"),
        )
        assertThrows(UnknownHostException::class.java) {
            PublicRelayDns { listOf(publicAddress) }.lookup("127.0.0.1")
        }
        assertThrows(UnknownHostException::class.java) {
            PublicRelayDns { listOf(privateAddress) }.lookup("rebind.example")
        }
        assertThrows(UnknownHostException::class.java) {
            PublicRelayDns { listOf(publicAddress, privateAddress) }.lookup("mixed.example")
        }
    }

    @Test
    fun endpointCapLimitsOneQueryToFourConfiguredRelays() {
        val servers = List(5) { eoseRelay() }
        val httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        try {
            val result =
                runBlocking {
                    NostrRelayQueryClient(httpClient)
                        .query(
                            relayUrls = servers.map { it.webSocketUrl() },
                            filter = JSONObject().put("ids", JSONArray().put("a".repeat(64))),
                            timeoutMillis = 2_000,
                        )
                }

            assertEquals(4, result.completedRelayCount)
            assertEquals(0, result.failedRelayCount)
            assertEquals(listOf(1, 1, 1, 1, 0), servers.map { it.requestCount })
        } finally {
            httpClient.connectionPool.evictAll()
            httpClient.dispatcher.executorService.shutdownNow()
            servers.forEach { it.shutdown() }
        }
    }

    @Test
    fun oneFailedRelayDoesNotDiscardACompletedRelay() {
        val server = eoseRelay()
        val httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        try {
            val result =
                runBlocking {
                    NostrRelayQueryClient(httpClient)
                        .query(
                            relayUrls = listOf(server.webSocketUrl(), "ws://127.0.0.1:1"),
                            filter = JSONObject().put("kinds", JSONArray().put(1)),
                            timeoutMillis = 2_000,
                        )
                }

            assertEquals(1, result.completedRelayCount)
            assertEquals(1, result.failedRelayCount)
            assertEquals(emptyList<NostrEvent>(), result.events)
        } finally {
            httpClient.connectionPool.evictAll()
            httpClient.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }

    @Test
    fun eoseWithoutEventsCompletesAndClosesTheSocket() {
        val server = eoseRelay()
        val httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val factory = TrackingWebSocketFactory(httpClient)
        try {
            val result =
                runBlocking {
                    NostrRelayQueryClient(factory).query(
                        relayUrls = listOf(server.webSocketUrl()),
                        filter = JSONObject().put("kinds", JSONArray().put(1)),
                        timeoutMillis = 2_000,
                    )
                }

            assertTrue(result.events.isEmpty())
            assertEquals(1, result.completedRelayCount)
            assertEquals(1, factory.socket.closeCalls.get())
            assertEquals(0, factory.socket.cancelCalls.get())
        } finally {
            close(httpClient, server)
        }
    }

    @Test
    fun closedBeforeEoseWithoutEventsReportsFailure() {
        val server = closedRelay()
        val httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        try {
            val error =
                assertThrows(IOException::class.java) {
                    runBlocking {
                        NostrRelayQueryClient(httpClient).query(
                            relayUrls = listOf(server.webSocketUrl()),
                            filter = JSONObject().put("kinds", JSONArray().put(1)),
                            timeoutMillis = 2_000,
                        )
                    }
                }

            assertEquals("Public relay query failed", error.message)
            assertTrue(
                generateSequence<Throwable>(error) { it.cause }
                    .mapNotNull(Throwable::message)
                    .any { it.contains("rate-limited") },
            )
        } finally {
            close(httpClient, server)
        }
    }

    @Test
    fun reachingTheEventLimitCompletesWithoutWaitingForEose() {
        val eventId = "a".repeat(64)
        val server = eventOnlyRelay(eventId)
        val httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        try {
            val result =
                runBlocking {
                    NostrRelayQueryClient(httpClient).query(
                        relayUrls = listOf(server.webSocketUrl()),
                        filter = JSONObject().put("ids", JSONArray().put(eventId)),
                        timeoutMillis = 2_000,
                        maxEvents = 1,
                    )
                }

            assertEquals(listOf(eventId), result.events.map(NostrEvent::id))
            assertEquals(1, result.completedRelayCount)
        } finally {
            close(httpClient, server)
        }
    }

    @Test
    fun timeoutCancelsTheSocketAndReportsFailure() {
        val relay = silentRelay()
        val httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val factory = TrackingWebSocketFactory(httpClient)
        try {
            val error =
                assertThrows(IOException::class.java) {
                    runBlocking {
                        NostrRelayQueryClient(factory).query(
                            relayUrls = listOf(relay.server.webSocketUrl()),
                            filter = JSONObject().put("kinds", JSONArray().put(1)),
                            timeoutMillis = 100,
                        )
                    }
                }

            assertEquals("Public relay query failed", error.message)
            val causeMessages =
                generateSequence<Throwable>(error) { it.cause }
                    .mapNotNull(Throwable::message)
                    .toList()
            assertTrue(causeMessages.contains("Relay request timed out"))
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is NostrRelayTimeoutException })
            assertEquals(0, factory.socket.closeCalls.get())
            assertEquals(1, factory.socket.cancelCalls.get())
        } finally {
            close(httpClient, relay.server)
        }
    }

    @Test
    fun queuedRelayGetsItsOwnSocketTimeoutAfterAcquiringPermit() {
        val silentRelays = List(3) { silentRelay() }
        val completingRelay = eoseRelay()
        val httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        try {
            val result =
                runBlocking {
                    withTimeout(1_000) {
                        NostrRelayQueryClient(httpClient, maxConcurrentSockets = 1)
                            .query(
                                relayUrls =
                                    silentRelays.map { it.server.webSocketUrl() } +
                                        completingRelay.webSocketUrl(),
                                filter = JSONObject().put("kinds", JSONArray().put(1)),
                                timeoutMillis = 100,
                            )
                    }
                }

            assertEquals(1, result.completedRelayCount)
            assertEquals(3, result.failedRelayCount)
            assertEquals(1, completingRelay.requestCount)
        } finally {
            httpClient.connectionPool.evictAll()
            httpClient.dispatcher.executorService.shutdownNow()
            silentRelays.forEach { it.server.shutdown() }
            completingRelay.shutdown()
        }
    }

    @Test
    fun coroutineCancellationCancelsOnceAndIgnoresLateEose() =
        runBlocking {
            val relay = silentRelay()
            val httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            val factory = TrackingWebSocketFactory(httpClient)
            try {
                val query =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        NostrRelayQueryClient(factory).query(
                            relayUrls = listOf(relay.server.webSocketUrl()),
                            filter = JSONObject().put("kinds", JSONArray().put(1)),
                            timeoutMillis = 10_000,
                        )
                    }
                val request = relay.takeRequest()

                query.cancel()
                try {
                    query.await()
                    fail("cancelled query resumed")
                } catch (_: CancellationException) {
                    // A late terminal relay message must not revive this coroutine.
                }

                factory.deliverText(
                    JSONArray().put("EOSE").put(request.getString(1)).toString(),
                )
                assertTrue(query.isCancelled)
                assertEquals(0, factory.socket.closeCalls.get())
                assertEquals(1, factory.socket.cancelCalls.get())
            } finally {
                close(httpClient, relay.server)
            }
        }

    private fun eoseRelay(): MockWebServer =
        MockWebServer().apply {
            start()
            enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            val request = JSONArray(text)
                            if (request.optString(0) == "REQ") {
                                webSocket.send(JSONArray().put("EOSE").put(request.getString(1)).toString())
                            }
                        }

                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            webSocket.close(code, reason)
                        }
                    },
                ),
            )
        }

    private fun closedRelay(): MockWebServer =
        MockWebServer().apply {
            start()
            enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            val request = JSONArray(text)
                            if (request.optString(0) == "REQ") {
                                webSocket.send(
                                    JSONArray()
                                        .put("CLOSED")
                                        .put(request.getString(1))
                                        .put("rate-limited")
                                        .toString(),
                                )
                            }
                        }

                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            webSocket.close(code, reason)
                        }
                    },
                ),
            )
        }

    private fun eventOnlyRelay(eventId: String): MockWebServer =
        MockWebServer().apply {
            start()
            enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            val request = JSONArray(text)
                            if (request.optString(0) == "REQ") {
                                val event =
                                    JSONObject()
                                        .put("id", eventId)
                                        .put("pubkey", "b".repeat(64))
                                        .put("created_at", 1)
                                        .put("kind", 1)
                                        .put("tags", JSONArray())
                                        .put("content", "event")
                                        .put("sig", "c".repeat(128))
                                webSocket.send(
                                    JSONArray()
                                        .put("EVENT")
                                        .put(request.getString(1))
                                        .put(event)
                                        .toString(),
                                )
                            }
                        }

                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            webSocket.close(code, reason)
                        }
                    },
                ),
            )
        }

    private fun silentRelay(): RecordingRelay {
        val listener = RecordingRelayListener()
        val server =
            MockWebServer().apply {
                start()
                enqueue(MockResponse().withWebSocketUpgrade(listener))
            }
        return RecordingRelay(server, listener)
    }

    private fun MockWebServer.webSocketUrl(): String = url("/relay").toString().replaceFirst("http", "ws")

    private fun close(
        httpClient: OkHttpClient,
        server: MockWebServer,
    ) {
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdownNow()
        server.shutdown()
    }

    private class TrackingWebSocketFactory(
        private val delegate: WebSocket.Factory,
    ) : WebSocket.Factory {
        private lateinit var clientListener: WebSocketListener
        lateinit var socket: TrackingWebSocket

        override fun newWebSocket(
            request: Request,
            listener: WebSocketListener,
        ): WebSocket {
            clientListener = listener
            val forwardingListener =
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) = listener.onOpen(webSocket, response)

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) = listener.onMessage(webSocket, text)

                    override fun onMessage(
                        webSocket: WebSocket,
                        bytes: ByteString,
                    ) = listener.onMessage(webSocket, bytes)

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) = listener.onClosing(webSocket, code, reason)

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) = listener.onClosed(webSocket, code, reason)

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) = listener.onFailure(webSocket, t, response)
                }
            return TrackingWebSocket(delegate.newWebSocket(request, forwardingListener)).also {
                socket = it
            }
        }

        fun deliverText(text: String) {
            clientListener.onMessage(socket, text)
        }
    }

    private class TrackingWebSocket(
        private val delegate: WebSocket,
    ) : WebSocket by delegate {
        val closeCalls = AtomicInteger()
        val cancelCalls = AtomicInteger()

        override fun close(
            code: Int,
            reason: String?,
        ): Boolean {
            closeCalls.incrementAndGet()
            return delegate.close(code, reason)
        }

        override fun cancel() {
            cancelCalls.incrementAndGet()
            delegate.cancel()
        }
    }

    private data class RecordingRelay(
        val server: MockWebServer,
        val listener: RecordingRelayListener,
    ) {
        fun takeRequest(): JSONArray = listener.takeRequest()
    }

    private class RecordingRelayListener : WebSocketListener() {
        private val requests = LinkedBlockingQueue<JSONArray>()

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            requests += JSONArray(text)
        }

        fun takeRequest(): JSONArray = requests.poll(5, TimeUnit.SECONDS) ?: error("relay did not receive REQ")
    }
}

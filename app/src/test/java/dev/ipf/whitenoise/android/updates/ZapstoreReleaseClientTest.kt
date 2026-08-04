package dev.ipf.whitenoise.android.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ZapstoreReleaseClientTest {
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var webSocketFactory: TrackingWebSocketFactory
    private lateinit var client: ZapstoreReleaseClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        webSocketFactory = TrackingWebSocketFactory(httpClient)
        client =
            ZapstoreReleaseClient(
                httpClient = webSocketFactory,
                relayUrl = server.url("/relay").toString().replaceFirst("http", "ws"),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdownNow()
    }

    @Test
    fun fetchEventsKeepsOnlyEventsForItsSubscription() {
        val relay =
            enqueueRelay { webSocket, request ->
                val subscriptionId = request.getString(1)
                webSocket.send(eventMessage("another-subscription", eventJson("b".repeat(64))))
                webSocket.send(JSONArray().put("EOSE").put("another-subscription").toString())
                webSocket.send(eventMessage(subscriptionId, eventJson("a".repeat(64))))
                webSocket.send(JSONArray().put("EOSE").put(subscriptionId).toString())
            }

        val events = runBlocking { client.fetchEvents(FILTER) }

        assertEquals(listOf("a".repeat(64)), events.map(NostrEvent::id))
        val request = relay.takeRequest()
        assertEquals("REQ", request.getString(0))
        assertEquals(FILTER.toString(), request.getJSONObject(2).toString())
        relay.awaitClosing()
        assertEquals(1, relay.closingCount.get())
        assertEquals(1, webSocketFactory.socket.closeCalls.get())
        assertEquals(0, webSocketFactory.socket.cancelCalls.get())
    }

    @Test
    fun fetchEventsCompletesOnEose() {
        val relay =
            enqueueRelay { webSocket, request ->
                webSocket.send(JSONArray().put("EOSE").put(request.getString(1)).toString())
            }

        val events = runBlocking { client.fetchEvents(FILTER) }

        assertTrue(events.isEmpty())
        relay.awaitClosing()
        assertEquals(1, relay.closingCount.get())
        assertEquals(1, webSocketFactory.socket.closeCalls.get())
        assertEquals(0, webSocketFactory.socket.cancelCalls.get())
    }

    @Test
    fun fetchEventsCompletesOnClosed() {
        val relay =
            enqueueRelay { webSocket, request ->
                val subscriptionId = request.getString(1)
                webSocket.send(eventMessage(subscriptionId, eventJson("a".repeat(64))))
                webSocket.send(
                    JSONArray()
                        .put("CLOSED")
                        .put(subscriptionId)
                        .put("finished")
                        .toString(),
                )
            }

        val events = runBlocking { client.fetchEvents(FILTER) }

        assertEquals(listOf("a".repeat(64)), events.map(NostrEvent::id))
        relay.awaitClosing()
        assertEquals(1, relay.closingCount.get())
        assertEquals(1, webSocketFactory.socket.closeCalls.get())
        assertEquals(0, webSocketFactory.socket.cancelCalls.get())
    }

    @Test
    fun fetchEventsCompletesOnServerInitiatedWebSocketClose() {
        val relay =
            enqueueRelay { webSocket, request ->
                val subscriptionId = request.getString(1)
                webSocket.send(eventMessage(subscriptionId, eventJson("a".repeat(64))))
                webSocket.close(1000, "done")
            }

        val events = runBlocking { client.fetchEvents(FILTER) }

        assertEquals(listOf("a".repeat(64)), events.map(NostrEvent::id))
        relay.awaitClosing()
        assertEquals(1, relay.closingCount.get())
        assertEquals(1, webSocketFactory.socket.closeCalls.get())
        assertEquals(0, webSocketFactory.socket.cancelCalls.get())
    }

    @Test
    fun fetchEventsReportsRelayFailureAndClosesOnce() {
        enqueueRelay { webSocket, _ -> webSocket.cancel() }

        val error =
            assertThrows(IOException::class.java) {
                runBlocking { client.fetchEvents(FILTER) }
            }

        assertEquals("Zapstore relay request failed", error.message)
        webSocketFactory.awaitFailure()
        assertEquals(1, webSocketFactory.failureCallbacks.get())
        assertEquals(1, webSocketFactory.socket.closeCalls.get())
        assertEquals(0, webSocketFactory.socket.cancelCalls.get())
    }

    @Test
    fun fetchEventsConvertsTimeoutAndCancelsOnce() {
        enqueueRelay { _, _ -> }

        val error =
            assertThrows(IOException::class.java) {
                runBlocking { client.fetchEvents(FILTER, timeoutMillis = 100) }
            }

        assertEquals("Zapstore relay request timed out", error.message)
        webSocketFactory.awaitFailure()
        assertEquals(1, webSocketFactory.failureCallbacks.get())
        assertEquals(0, webSocketFactory.socket.closeCalls.get())
        assertEquals(1, webSocketFactory.socket.cancelCalls.get())
    }

    @Test
    fun fetchEventsCancellationDoesNotResumeAndCancelsOnce() =
        runBlocking {
            val relay = enqueueRelay { _, _ -> }
            val fetch =
                async(start = CoroutineStart.UNDISPATCHED) {
                    client.fetchEvents(FILTER, timeoutMillis = 10_000)
                }
            val request = relay.takeRequest()
            val subscriptionId = request.getString(1)

            fetch.cancel()
            try {
                fetch.await()
                fail("cancelled fetch resumed")
            } catch (_: CancellationException) {
                // Expected: a late relay terminal message must not revive this coroutine.
            }

            webSocketFactory.deliverText(JSONArray().put("EOSE").put(subscriptionId).toString())
            assertTrue(fetch.isCancelled)
            webSocketFactory.awaitFailure()
            assertEquals(1, webSocketFactory.failureCallbacks.get())
            assertEquals(0, webSocketFactory.socket.closeCalls.get())
            assertEquals(1, webSocketFactory.socket.cancelCalls.get())
        }

    private fun enqueueRelay(onRequest: (WebSocket, JSONArray) -> Unit): RelayWebSocketListener {
        val listener = RelayWebSocketListener(onRequest)
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        return listener
    }

    private fun eventMessage(
        subscriptionId: String,
        event: JSONObject,
    ): String =
        JSONArray()
            .put("EVENT")
            .put(subscriptionId)
            .put(event)
            .toString()

    private fun eventJson(id: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("pubkey", "c".repeat(64))
            .put("created_at", 1L)
            .put("kind", 30063)
            .put("tags", JSONArray())
            .put("content", "")
            .put("sig", "d".repeat(128))

    private class RelayWebSocketListener(
        private val onRequest: (WebSocket, JSONArray) -> Unit,
    ) : WebSocketListener() {
        private val requests = LinkedBlockingQueue<JSONArray>()
        private val closingSeen = CountDownLatch(1)
        val closingCount = AtomicInteger()

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            val request = JSONArray(text)
            requests += request
            onRequest(webSocket, request)
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            closingCount.incrementAndGet()
            closingSeen.countDown()
            webSocket.close(code, reason)
        }

        fun takeRequest(): JSONArray = requests.poll(5, TimeUnit.SECONDS) ?: error("relay did not receive REQ")

        fun awaitClosing() {
            assertTrue("client did not close the relay socket", closingSeen.await(5, TimeUnit.SECONDS))
        }
    }

    private class TrackingWebSocketFactory(
        private val delegate: WebSocket.Factory,
    ) : WebSocket.Factory {
        private val failureSeen = CountDownLatch(1)
        private lateinit var clientListener: WebSocketListener
        val failureCallbacks = AtomicInteger()
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
                    ) {
                        try {
                            listener.onFailure(webSocket, t, response)
                        } finally {
                            failureCallbacks.incrementAndGet()
                            failureSeen.countDown()
                        }
                    }
                }
            return TrackingWebSocket(delegate.newWebSocket(request, forwardingListener)).also { socket = it }
        }

        fun deliverText(text: String) {
            clientListener.onMessage(socket, text)
        }

        fun awaitFailure() {
            assertTrue("client WebSocket failure callback did not run", failureSeen.await(5, TimeUnit.SECONDS))
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

    private companion object {
        val FILTER: JSONObject = JSONObject().put("kinds", JSONArray().put(30063))
    }
}

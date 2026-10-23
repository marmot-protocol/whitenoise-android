package dev.ipf.whitenoise.android.core.nostr

import dev.ipf.whitenoise.android.updates.ZapstoreReleaseClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class NostrRelayReceiveLimitCoverageTest {
    @Test
    fun productionReleaseTransportInstallsTheReceiveBoundary() {
        val productionClient = ZapstoreReleaseClient.defaultHttpClient()
        assertEquals(1, productionClient.interceptors.count { it is NostrRelayReceiveLimitInterceptor })
    }

    @Test
    fun configuredTransportCompletesQueriesAndDoesNotOfferCompression() {
        val server = MockWebServer()
        val client = client()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val subscription = JSONArray(text).getString(1)
                        webSocket.send("ignored".encodeUtf8())
                        webSocket.send(JSONArray().put("EOSE").put(subscription).toString())
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
        server.start()
        try {
            val result =
                runBlocking {
                    NostrRelayQueryClient(client).query(
                        listOf(server.url("/").toString()),
                        JSONObject(),
                        timeoutMillis = 2000,
                    )
                }
            assertEquals(1, result.completedRelayCount)
            assertNull(server.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Sec-WebSocket-Extensions"))
        } finally {
            close(client, server)
        }
    }

    @Test
    fun configuredTransportRejectsOversizeAndAllowsANewHealthyQuery() {
        val server = MockWebServer()
        val client = OkHttpClient.Builder().addInterceptor(NostrRelayReceiveLimitInterceptor(128)).build()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        // Same guard with a small test ceiling: no memory-pressure fixture.
                        val subscription = JSONArray(text).getString(1)
                        webSocket.send(JSONArray().put("EOSE").put(subscription).toString() + " ".repeat(129))
                    }
                },
            ),
        )
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        webSocket.send(JSONArray().put("EOSE").put(JSONArray(text).getString(1)).toString())
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
        server.start()
        try {
            val failure =
                assertThrows(IOException::class.java) {
                    runBlocking {
                        NostrRelayQueryClient(client).query(
                            listOf(server.url("/").toString()),
                            JSONObject(),
                            timeoutMillis = 2000,
                        )
                    }
                }
            assertTrue(
                generateSequence<Throwable>(failure) { it.cause }.any {
                    it is ProtocolException && it.message == "Relay message exceeds receive limit"
                },
            )
            val result =
                runBlocking {
                    NostrRelayQueryClient(client).query(
                        listOf(server.url("/").toString()),
                        JSONObject(),
                        timeoutMillis = 2000,
                    )
                }
            assertEquals(1, result.completedRelayCount)
        } finally {
            close(client, server)
        }
    }

    @Test
    fun extensionBearingUpgradeFailsBeforeMessageDelivery() {
        val server = MockWebServer()
        val client = client()
        val failures = LinkedBlockingQueue<Throwable>()
        val opened = LinkedBlockingQueue<Boolean>()
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(object : WebSocketListener() {})
                .setHeader("Sec-WebSocket-Extensions", "permessage-deflate"),
        )
        server.start()
        try {
            client.newWebSocket(
                Request.Builder().url(server.url("/")).build(),
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        opened.add(true)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        failures.add(t)
                    }
                },
            )
            val failure = failures.poll(2, TimeUnit.SECONDS)
            assertNotNull(failure)
            assertEquals("Relay message extensions are unsupported", failure?.message)
            assertFalse(opened.isNotEmpty())
        } finally {
            close(client, server)
        }
    }

    @Test
    fun cancellingConfiguredTransportTerminatesTheUpgradedSocket() {
        val server = MockWebServer()
        val client = client()
        val opened = LinkedBlockingQueue<WebSocket>()
        val failures = LinkedBlockingQueue<Throwable>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.start()
        try {
            client.newWebSocket(
                Request.Builder().url(server.url("/")).build(),
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        opened.add(webSocket)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        failures.add(t)
                    }
                },
            )
            val socket = opened.poll(2, TimeUnit.SECONDS)
            assertNotNull(socket)
            socket?.cancel()
            assertNotNull(failures.poll(2, TimeUnit.SECONDS))
        } finally {
            close(client, server)
        }
    }

    private fun client(): OkHttpClient =
        OkHttpClient
            .Builder()
            .limitNostrRelayMessages()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    private fun close(
        client: OkHttpClient,
        server: MockWebServer,
    ) {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        server.shutdown()
    }
}

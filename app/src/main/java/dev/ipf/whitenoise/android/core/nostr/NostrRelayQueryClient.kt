package dev.ipf.whitenoise.android.core.nostr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class NostrRelayQueryResult(
    val events: List<NostrEvent>,
    val completedRelayCount: Int,
    val failedRelayCount: Int,
)

/**
 * Small, bounded public-event query primitive shared by app features. Every
 * endpoint gets one exact, finite subscription that closes at EOSE, failure,
 * timeout, or coroutine cancellation.
 */
internal class NostrRelayQueryClient(
    private val webSocketFactory: WebSocket.Factory = defaultHttpClient(),
    maxConcurrentSockets: Int = DEFAULT_MAX_CONCURRENT_SOCKETS,
) {
    private val socketPermits = Semaphore(maxConcurrentSockets.coerceIn(1, DEFAULT_MAX_CONCURRENT_SOCKETS))

    suspend fun query(
        relayUrls: List<String>,
        filter: JSONObject,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        maxEvents: Int = DEFAULT_MAX_EVENTS,
    ): NostrRelayQueryResult =
        coroutineScope {
            val endpoints =
                relayUrls
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .take(MAX_RELAY_ENDPOINTS)
            require(endpoints.isNotEmpty()) { "At least one relay is required" }
            val eventLimit = maxEvents.coerceIn(1, ABSOLUTE_MAX_EVENTS)
            val filterJson = filter.toString()
            val outcomes =
                endpoints
                    .map { relayUrl ->
                        async(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                Result.success(
                                    socketPermits.withPermit {
                                        withTimeout(timeoutMillis) {
                                            queryRelay(relayUrl, JSONObject(filterJson), eventLimit)
                                        }
                                    },
                                )
                            } catch (timeout: TimeoutCancellationException) {
                                Result.failure(IOException("Relay request timed out", timeout))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: IOException) {
                                Result.failure(error)
                            } catch (error: JSONException) {
                                Result.failure(error)
                            } catch (error: IllegalArgumentException) {
                                Result.failure(error)
                            }
                        }
                    }.awaitAll()
            val completed = outcomes.count { it.isSuccess }
            val failures = outcomes.mapNotNull(Result<List<NostrEvent>>::exceptionOrNull)
            if (completed == 0) {
                throw IOException("Public relay query failed", failures.firstOrNull())
            }
            val events =
                outcomes
                    .mapNotNull(Result<List<NostrEvent>>::getOrNull)
                    .flatten()
                    .distinctBy(NostrEvent::id)
                    .take(eventLimit)
            NostrRelayQueryResult(
                events = events,
                completedRelayCount = completed,
                failedRelayCount = failures.size,
            )
        }

    private suspend fun queryRelay(
        relayUrl: String,
        filter: JSONObject,
        maxEvents: Int,
    ): List<NostrEvent> =
        suspendCancellableCoroutine { continuation ->
            val subscriptionId = "public-event-${UUID.randomUUID()}"
            val completed = AtomicBoolean(false)
            val events = LinkedHashMap<String, NostrEvent>()
            val socket = AtomicReference<WebSocket?>()

            fun finish(result: Result<List<NostrEvent>>) {
                if (!completed.compareAndSet(false, true)) return
                runCatching { socket.get()?.close(NORMAL_CLOSE_CODE, "done") }
                if (!continuation.isActive) return
                result.onSuccess { continuation.resume(it) }.onFailure { continuation.resumeWithException(it) }
            }

            val listener =
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send(
                            JSONArray()
                                .put("REQ")
                                .put(subscriptionId)
                                .put(filter)
                                .toString(),
                        )
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (text.length <= MAX_RELAY_MESSAGE_CHARS) {
                            runCatching { JSONArray(text) }.getOrNull()?.let { message ->
                                when (message.optString(0)) {
                                    "EVENT" -> {
                                        val belongsToSubscription = message.optString(1) == subscriptionId
                                        if (belongsToSubscription && events.size < maxEvents) {
                                            message.readEvent()?.let { event ->
                                                if (event.content.length <= MAX_EVENT_CONTENT_CHARS) {
                                                    events.putIfAbsent(event.id, event)
                                                }
                                            }
                                        }
                                    }
                                    "EOSE", "CLOSED" ->
                                        if (message.optString(1) == subscriptionId) {
                                            finish(Result.success(events.values.toList()))
                                        }
                                    "NOTICE" -> Unit
                                }
                            }
                        }
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        bytes: ByteString,
                    ) = Unit

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        finish(Result.failure(IOException("Relay request failed", t)))
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        finish(Result.success(events.values.toList()))
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        finish(Result.success(events.values.toList()))
                    }
                }

            val request =
                try {
                    Request.Builder().url(relayUrl).build()
                } catch (error: IllegalArgumentException) {
                    finish(Result.failure(IOException("Relay request failed", error)))
                    return@suspendCancellableCoroutine
                }
            val createdSocket = webSocketFactory.newWebSocket(request, listener)
            socket.set(createdSocket)
            if (completed.get()) {
                runCatching { createdSocket.close(NORMAL_CLOSE_CODE, "done") }
            }
            continuation.invokeOnCancellation {
                if (!completed.compareAndSet(false, true)) return@invokeOnCancellation
                runCatching { socket.get()?.cancel() }
            }
        }

    private companion object {
        const val MAX_RELAY_ENDPOINTS = 4
        const val DEFAULT_MAX_CONCURRENT_SOCKETS = 3
        const val DEFAULT_MAX_EVENTS = 20
        const val ABSOLUTE_MAX_EVENTS = 64
        const val DEFAULT_TIMEOUT_MILLIS = 8_000L
        const val MAX_RELAY_MESSAGE_CHARS = 256 * 1024
        const val MAX_EVENT_CONTENT_CHARS = 128 * 1024
        const val NORMAL_CLOSE_CODE = 1_000

        val sharedDefaultHttpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        fun defaultHttpClient(): OkHttpClient = sharedDefaultHttpClient
    }
}

private fun JSONArray.readEvent(): NostrEvent? =
    optJSONObject(2)?.let { json ->
        runCatching { NostrEvent.fromJson(json) }.getOrNull()
    }

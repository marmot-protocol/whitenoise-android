package dev.ipf.darkmatter.updates

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ZapstoreReleaseClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val relayUrl: String = ZAPSTORE_RELAY,
    private val publisherPubkey: String = ZAPSTORE_PUBLISHER_PUBKEY,
) {
    suspend fun fetchLatest(
        appId: String = AppUpdateConstants.DARKMATTER_ZAPSTORE_APP_ID,
        installedVersion: String? = null,
    ): ZapstoreLatestRelease? {
        val appEvent =
            fetchEvents(appEventFilter(appId), FETCH_TIMEOUT_MS)
                .filter { event ->
                    event.kind == KIND_ZAPSTORE_APP &&
                        event.pubkey == publisherPubkey &&
                        event.hasTag("d", appId) &&
                        NostrEventVerifier.verifies(event)
                }.maxByOrNull { it.createdAt }
                ?: return null

        val address = appEvent.tagValues("a").firstNotNullOfOrNull { ZapstoreAddress.parse(it, publisherPubkey, appId) } ?: return null
        val releaseEvent =
            fetchEvents(releaseEventFilter(address.dTag), FETCH_TIMEOUT_MS)
                .firstOrNull { event ->
                    event.kind == KIND_ZAPSTORE_RELEASE &&
                        event.pubkey == publisherPubkey &&
                        event.hasTag("d", address.dTag) &&
                        NostrEventVerifier.verifies(event)
                } ?: return null

        val version = releaseEvent.firstTagValue("d")?.let { ZapstoreAddress.versionFromReleaseDTag(it, appId) } ?: address.version
        val releasesBehind =
            installedVersion
                ?.takeIf { it.isNotBlank() }
                ?.let { installed ->
                    runCatching { CalVer.releasesBehind(installed, fetchReleaseHistoryVersions(appId)) }.getOrNull()
                }
        return ZapstoreLatestRelease(version = version, releasesBehind = releasesBehind)
    }

    private suspend fun fetchReleaseHistoryVersions(appId: String): List<String> =
        fetchEvents(releaseHistoryFilter(), FETCH_TIMEOUT_MS)
            .asSequence()
            .filter { event ->
                event.kind == KIND_ZAPSTORE_RELEASE &&
                    event.pubkey == publisherPubkey &&
                    NostrEventVerifier.verifies(event)
            }.mapNotNull { event -> event.firstTagValue("d")?.let { ZapstoreAddress.versionFromReleaseDTag(it, appId) } }
            .toList()

    private fun appEventFilter(appId: String): JSONObject =
        JSONObject()
            .put("kinds", JSONArray().put(KIND_ZAPSTORE_APP))
            .put("authors", JSONArray().put(publisherPubkey))
            .put("#d", JSONArray().put(appId))
            .put("limit", 1)

    private fun releaseEventFilter(dTag: String): JSONObject =
        JSONObject()
            .put("kinds", JSONArray().put(KIND_ZAPSTORE_RELEASE))
            .put("authors", JSONArray().put(publisherPubkey))
            .put("#d", JSONArray().put(dTag))
            .put("limit", 1)

    private fun releaseHistoryFilter(): JSONObject =
        JSONObject()
            .put("kinds", JSONArray().put(KIND_ZAPSTORE_RELEASE))
            .put("authors", JSONArray().put(publisherPubkey))
            .put("limit", RELEASE_HISTORY_LIMIT)

    private suspend fun fetchEvents(
        filter: JSONObject,
        timeoutMillis: Long,
    ): List<NostrEvent> =
        try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val subscriptionId = "dm-update-${UUID.randomUUID()}"
                    val completed = AtomicBoolean(false)
                    val events = mutableListOf<NostrEvent>()
                    lateinit var socket: WebSocket

                    fun finish(result: Result<List<NostrEvent>>) {
                        if (!completed.compareAndSet(false, true)) return
                        runCatching { socket.close(1000, "done") }
                        result
                            .onSuccess { continuation.resume(it) }
                            .onFailure { continuation.resumeWithException(it) }
                    }

                    val listener =
                        object : WebSocketListener() {
                            override fun onOpen(
                                webSocket: WebSocket,
                                response: Response,
                            ) {
                                val request = JSONArray().put("REQ").put(subscriptionId).put(filter)
                                webSocket.send(request.toString())
                            }

                            override fun onMessage(
                                webSocket: WebSocket,
                                text: String,
                            ) {
                                val message = runCatching { JSONArray(text) }.getOrNull() ?: return
                                when (message.optString(0)) {
                                    "EVENT" -> {
                                        if (message.optString(1) != subscriptionId) return
                                        val event = message.optJSONObject(2)?.let(NostrEvent.Companion::fromJson) ?: return
                                        events += event
                                    }

                                    "EOSE" -> {
                                        if (message.optString(1) == subscriptionId) finish(Result.success(events.toList()))
                                    }

                                    "CLOSED" -> {
                                        if (message.optString(1) == subscriptionId) finish(Result.success(events.toList()))
                                    }

                                    "NOTICE" -> Unit
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
                                finish(Result.failure(IOException("Zapstore relay request failed", t)))
                            }

                            override fun onClosed(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String,
                            ) {
                                finish(Result.success(events.toList()))
                            }
                        }

                    socket = httpClient.newWebSocket(Request.Builder().url(relayUrl).build(), listener)
                    continuation.invokeOnCancellation { runCatching { socket.cancel() } }
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw IOException("Zapstore relay request timed out", error)
        }

    companion object {
        const val ZAPSTORE_RELAY = "wss://relay.zapstore.dev"
        const val ZAPSTORE_PUBLISHER_PUBKEY = "75d737c3472471029c44876b330d2284288a42779b591a2ed4daa1c6c07efaf7"
        private const val KIND_ZAPSTORE_APP = 32267
        private const val KIND_ZAPSTORE_RELEASE = 30063
        private const val FETCH_TIMEOUT_MS = 10_000L
        private const val RELEASE_HISTORY_LIMIT = 100

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
    }
}

internal data class ZapstoreAddress(
    val dTag: String,
    val version: String,
) {
    companion object {
        fun parse(
            value: String,
            publisherPubkey: String,
            appId: String,
        ): ZapstoreAddress? {
            val prefix = "30063:$publisherPubkey:$appId@"
            val version = value.removePrefix(prefix).takeIf { it.length != value.length && it.isNotBlank() } ?: return null
            return ZapstoreAddress(dTag = "$appId@$version", version = version)
        }

        fun versionFromReleaseDTag(
            dTag: String,
            appId: String,
        ): String? = dTag.removePrefix("$appId@").takeIf { it.length != dTag.length && it.isNotBlank() }
    }
}

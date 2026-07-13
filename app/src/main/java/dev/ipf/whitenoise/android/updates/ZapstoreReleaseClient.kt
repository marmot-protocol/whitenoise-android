package dev.ipf.whitenoise.android.updates

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

private const val KIND_ZAPSTORE_APP = 32267
private const val KIND_ZAPSTORE_RELEASE = 30063

class ZapstoreReleaseClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val relayUrl: String = ZAPSTORE_RELAY,
    private val publisherPubkey: String = ZAPSTORE_PUBLISHER_PUBKEY,
) {
    suspend fun fetchLatest(
        appId: String = AppUpdateConstants.WHITENOISE_ZAPSTORE_APP_ID,
        installedVersion: String? = null,
    ): ZapstoreLatestRelease? {
        val appEvent = fetchLatestAppEvent(appId) ?: return null
        val releaseDTag = ZapstoreEvents.releaseDTagFromAppEvent(appEvent, appId, publisherPubkey) ?: return null
        val releaseEvent = fetchReleaseEvent(appId, releaseDTag) ?: return null
        val latestVersion = ZapstoreEvents.versionFromReleaseEvent(releaseEvent, appId, publisherPubkey, releaseDTag) ?: return null
        // The kind-32267 app event points at the current release but does not
        // expose full app-scoped release history. Keep the v1 banner
        // dismissible by leaving releasesBehind unknown instead of scanning a
        // publisher-wide capped kind-30063 window that can miss Dark Matter.
        return ZapstoreLatestRelease(version = latestVersion, releasesBehind = null)
    }

    private suspend fun fetchLatestAppEvent(appId: String): NostrEvent? =
        fetchEvents(appEventFilter(appId), FETCH_TIMEOUT_MS)
            .asSequence()
            .filter { event -> ZapstoreEvents.releaseDTagFromAppEvent(event, appId, publisherPubkey) != null }
            .maxByOrNull(NostrEvent::createdAt)

    private suspend fun fetchReleaseEvent(
        appId: String,
        releaseDTag: String,
    ): NostrEvent? =
        fetchEvents(releaseEventFilter(releaseDTag), FETCH_TIMEOUT_MS)
            .asSequence()
            .filter { event -> ZapstoreEvents.versionFromReleaseEvent(event, appId, publisherPubkey, releaseDTag) != null }
            .maxByOrNull(NostrEvent::createdAt)

    private fun appEventFilter(appId: String): JSONObject =
        JSONObject()
            .put("kinds", JSONArray().put(KIND_ZAPSTORE_APP))
            .put("authors", JSONArray().put(publisherPubkey))
            .put("#d", JSONArray().put(appId))
            .put("limit", FETCH_EVENT_LIMIT)

    private fun releaseEventFilter(releaseDTag: String): JSONObject =
        JSONObject()
            .put("kinds", JSONArray().put(KIND_ZAPSTORE_RELEASE))
            .put("authors", JSONArray().put(publisherPubkey))
            .put("#d", JSONArray().put(releaseDTag))
            .put("limit", FETCH_EVENT_LIMIT)

    internal suspend fun fetchEvents(
        filter: JSONObject,
        timeoutMillis: Long = FETCH_TIMEOUT_MS,
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
                                        val event =
                                            message
                                                .optJSONObject(2)
                                                ?.let { json -> runCatching { NostrEvent.fromJson(json) }.getOrNull() }
                                                ?: return
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

        // Same Zapstore publisher key used by White Noise's canonical Zapstore
        // lookup; this is the trust anchor for signed app/release events.
        const val ZAPSTORE_PUBLISHER_PUBKEY = "75d737c3472471029c44876b330d2284288a42779b591a2ed4daa1c6c07efaf7"
        private const val FETCH_TIMEOUT_MS = 10_000L
        private const val FETCH_EVENT_LIMIT = 5

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
    }
}

internal object ZapstoreEvents {
    fun releaseDTagFromAppEvent(
        event: NostrEvent,
        appId: String,
        publisherPubkey: String,
    ): String? {
        if (event.kind != KIND_ZAPSTORE_APP) return null
        if (event.pubkey != publisherPubkey) return null
        if (event.firstTagValue("d") != appId) return null
        if (!NostrEventVerifier.verifies(event)) return null
        return event.firstTagValue("a")?.let { ZapstoreAddress.releaseDTagFromAppATag(it, appId, publisherPubkey) }
    }

    fun versionFromReleaseEvent(
        event: NostrEvent,
        appId: String,
        publisherPubkey: String,
        releaseDTag: String,
    ): String? {
        if (event.kind != KIND_ZAPSTORE_RELEASE) return null
        if (event.pubkey != publisherPubkey) return null
        val dTag = event.firstTagValue("d") ?: return null
        if (dTag != releaseDTag) return null
        if (!NostrEventVerifier.verifies(event)) return null
        return ZapstoreAddress.versionFromReleaseDTag(dTag, appId)
    }
}

internal object ZapstoreAddress {
    private val calVerTagVersion = Regex("\\d+(?:\\.\\d+)*")

    fun releaseDTagFromAppATag(
        aTag: String,
        appId: String,
        publisherPubkey: String,
    ): String? {
        val prefix = "$KIND_ZAPSTORE_RELEASE:$publisherPubkey:$appId@"
        val version = aTag.removePrefix(prefix).takeIf { it.length != aTag.length && it.isNotBlank() } ?: return null
        return version.takeIf(calVerTagVersion::matches)?.let { "$appId@$it" }
    }

    fun versionFromReleaseDTag(
        dTag: String,
        appId: String,
    ): String? {
        val version = dTag.removePrefix("$appId@").takeIf { it.length != dTag.length && it.isNotBlank() } ?: return null
        return version.takeIf(calVerTagVersion::matches)
    }
}

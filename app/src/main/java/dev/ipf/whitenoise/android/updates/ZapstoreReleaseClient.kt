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

private const val KIND_ZAPSTORE_RELEASE = 30063

class ZapstoreReleaseClient(
    private val httpClient: WebSocket.Factory = defaultHttpClient(),
    private val relayUrl: String = ZAPSTORE_RELAY,
    private val publisherPubkey: String = ZAPSTORE_PUBLISHER_PUBKEY,
) {
    suspend fun fetchLatest(
        appId: String = AppUpdateConstants.WHITENOISE_ZAPSTORE_APP_ID,
        installedVersion: String? = null,
    ): ZapstoreLatestRelease? {
        // Zapstore's kind-32267 app event does not carry an `a` pointer to the
        // current release, so the latest version is read from the app's own
        // kind-30063 release events — scoped by their `i` identifier tag and
        // trusted only when signed by the publisher.
        val versions =
            fetchAppReleaseEvents(appId)
                .asSequence()
                .mapNotNull { event -> ZapstoreEvents.latestReleaseVersion(event, appId, publisherPubkey) }
                .distinct()
                .toList()
        val latestVersion = versions.maxWithOrNull { a, b -> CalVer.compare(a, b) } ?: return null
        val releasesBehind = installedVersion?.let { CalVer.releasesBehind(it, versions) }
        return ZapstoreLatestRelease(version = latestVersion, releasesBehind = releasesBehind)
    }

    private suspend fun fetchAppReleaseEvents(appId: String): List<NostrEvent> = fetchEvents(appReleaseEventsFilter(appId), FETCH_TIMEOUT_MS)

    private fun appReleaseEventsFilter(appId: String): JSONObject =
        JSONObject()
            .put("kinds", JSONArray().put(KIND_ZAPSTORE_RELEASE))
            .put("authors", JSONArray().put(publisherPubkey))
            .put("#i", JSONArray().put(appId))
            .put("limit", RELEASE_QUERY_LIMIT)

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
                        // A timeout can cancel the continuation before onFailure
                        // fires from socket.cancel(); don't resume it after that.
                        if (!continuation.isActive) return
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

                            override fun onClosing(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String,
                            ) {
                                finish(Result.success(events.toList()))
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
                    continuation.invokeOnCancellation {
                        if (!completed.compareAndSet(false, true)) return@invokeOnCancellation
                        runCatching { socket.cancel() }
                    }
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

        // Enough to cover an app's full kind-30063 release history in one query;
        // the newest is chosen by CalVer, so relay ordering does not matter.
        private const val RELEASE_QUERY_LIMIT = 50

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
    }
}

internal object ZapstoreEvents {
    /**
     * Latest-release discovery: read the version from a kind-30063 release
     * event, trusting it only when signed by [publisherPubkey] and bound to
     * [appId]. Signature is the trust gate; app-binding stops another app's
     * release under the same publisher (e.g. Dark Matter) being read as this
     * app's.
     */
    fun latestReleaseVersion(
        event: NostrEvent,
        appId: String,
        publisherPubkey: String,
    ): String? {
        if (event.kind != KIND_ZAPSTORE_RELEASE) return null
        if (event.pubkey != publisherPubkey) return null
        if (!NostrEventVerifier.verifies(event)) return null
        return releaseVersionForApp(event, appId)
    }

    /**
     * App-binding + version extraction without the signature gate, kept
     * separate so the live event shape — an `i` identifier tag plus an explicit
     * `version` tag, with the `d` tag `appId@version` as fallback — stays
     * unit-testable.
     */
    internal fun releaseVersionForApp(
        event: NostrEvent,
        appId: String,
    ): String? {
        val dTag = event.firstTagValue("d")
        val boundToApp = event.firstTagValue("i") == appId || dTag?.startsWith("$appId@") == true
        if (!boundToApp) return null
        val fromVersionTag = event.firstTagValue("version")?.let { ZapstoreAddress.asCalVerVersion(it) }
        return fromVersionTag ?: dTag?.let { ZapstoreAddress.versionFromReleaseDTag(it, appId) }
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

    fun asCalVerVersion(version: String): String? = version.takeIf(calVerTagVersion::matches)

    fun versionFromReleaseDTag(
        dTag: String,
        appId: String,
    ): String? {
        val version = dTag.removePrefix("$appId@").takeIf { it.length != dTag.length && it.isNotBlank() } ?: return null
        return version.takeIf(calVerTagVersion::matches)
    }
}

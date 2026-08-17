package dev.ipf.whitenoise.android.updates

import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import dev.ipf.whitenoise.android.core.nostr.NostrEventVerifier
import dev.ipf.whitenoise.android.core.nostr.NostrRelayQueryClient
import dev.ipf.whitenoise.android.core.nostr.NostrRelayTimeoutException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val KIND_ZAPSTORE_RELEASE = 30063

class ZapstoreReleaseClient internal constructor(
    private val httpClient: WebSocket.Factory,
    private val relayUrl: String,
    private val publisherPubkey: String = ZAPSTORE_PUBLISHER_PUBKEY,
) {
    constructor() : this(defaultHttpClient(), ZAPSTORE_RELAY, ZAPSTORE_PUBLISHER_PUBKEY)

    private val relayQueryClient = NostrRelayQueryClient(httpClient, maxConcurrentSockets = 1)

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
            relayQueryClient
                .query(
                    relayUrls = listOf(relayUrl),
                    filter = filter,
                    timeoutMillis = timeoutMillis,
                    maxEvents = RELEASE_QUERY_LIMIT,
                ).events
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            throw IOException(
                if (error.hasRelayTimeoutCause()) {
                    "Zapstore relay request timed out"
                } else {
                    "Zapstore relay request failed"
                },
                error,
            )
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

private fun Throwable.hasRelayTimeoutCause(): Boolean = generateSequence(this) { it.cause }.any { it is NostrRelayTimeoutException }

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

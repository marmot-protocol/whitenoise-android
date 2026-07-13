package dev.ipf.whitenoise.android.updates

import org.json.JSONArray
import org.json.JSONObject

private const val KIND_ZAPSTORE_ASSET = 3063
private const val KIND_ZAPSTORE_RELEASE = 30063

internal object ZapstoreApkAssetResolver {
    suspend fun resolveApkAsset(
        client: ZapstoreReleaseClient,
        version: String,
        platformId: String,
        appId: String = AppUpdateConstants.WHITENOISE_ZAPSTORE_APP_ID,
        publisherPubkey: String = ZapstoreReleaseClient.ZAPSTORE_PUBLISHER_PUBKEY,
    ): ZapstoreApkAsset? {
        val releaseDTag = "$appId@$version"
        val releaseEvent = fetchReleaseEvent(client, appId, releaseDTag, publisherPubkey) ?: return null
        val releaseVersion =
            ZapstoreEvents.versionFromReleaseEvent(
                event = releaseEvent,
                appId = appId,
                publisherPubkey = publisherPubkey,
                releaseDTag = releaseDTag,
            ) ?: return null
        if (releaseVersion != version) return null
        val assetIds =
            ZapstoreAssetEvents.assetEventIdsFromReleaseEvent(
                event = releaseEvent,
                appId = appId,
                publisherPubkey = publisherPubkey,
                releaseDTag = releaseDTag,
            ) ?: return null
        val assetEvents = client.fetchEvents(assetEventFilter(assetIds, publisherPubkey))
        return ZapstoreAssetEvents.selectUniqueApkAsset(
            events = assetEvents,
            referencedIds = assetIds,
            appId = appId,
            version = version,
            platformId = platformId,
            publisherPubkey = publisherPubkey,
        )
    }

    private suspend fun fetchReleaseEvent(
        client: ZapstoreReleaseClient,
        appId: String,
        releaseDTag: String,
        publisherPubkey: String,
    ): NostrEvent? =
        client
            .fetchEvents(releaseEventFilter(releaseDTag, publisherPubkey))
            .asSequence()
            .filter { event -> ZapstoreEvents.versionFromReleaseEvent(event, appId, publisherPubkey, releaseDTag) != null }
            .maxByOrNull(NostrEvent::createdAt)

    private fun releaseEventFilter(
        releaseDTag: String,
        publisherPubkey: String,
    ): JSONObject =
        JSONObject()
            .put("kinds", JSONArray().put(KIND_ZAPSTORE_RELEASE))
            .put("authors", JSONArray().put(publisherPubkey))
            .put("#d", JSONArray().put(releaseDTag))
            .put("limit", 5)

    private fun assetEventFilter(
        ids: Collection<String>,
        publisherPubkey: String,
    ): JSONObject =
        JSONObject()
            .put("kinds", JSONArray().put(KIND_ZAPSTORE_ASSET))
            .put("authors", JSONArray().put(publisherPubkey))
            .put("ids", JSONArray(ids.toList()))
            .put("limit", ids.size.coerceAtLeast(1))
}

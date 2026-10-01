package dev.ipf.whitenoise.android.updates

import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ZapstoreAssetEventsTest {
    @Test
    fun releaseWithoutAssetReferencesReturnsNull() {
        val releaseEvent = signedEvent(SIGNED_RELEASE_EVENT_JSON)
        assertNull(
            ZapstoreAssetEvents.assetEventIdsFromReleaseEvent(
                event = releaseEvent,
                appId = APP_ID,
                publisherPubkey = TEST_PUBLISHER_PUBKEY,
                releaseDTag = "$APP_ID@$VERSION",
                verifyEvent = { true },
            ),
        )
    }

    @Test
    fun selectUniqueApkAssetRejectsAmbiguousMatches() {
        val asset = assetEvent(baseAssetTags())
        val second = asset.copy(id = "b".repeat(64), createdAt = 2L)
        assertNull(
            ZapstoreAssetEvents.selectUniqueApkAsset(
                events = listOf(asset, second),
                referencedIds = setOf(asset.id, second.id),
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
                publisherPubkey = TEST_PUBLISHER_PUBKEY,
                verifyEvent = { true },
            ),
        )
    }

    @Test
    fun selectUniqueApkAssetRejectsUnverifiedEvents() {
        // All ID/tag/publisher checks pass, so only the rejecting verifier blocks this asset.
        val asset = assetEvent(baseAssetTags())
        assertNull(
            ZapstoreAssetEvents.selectUniqueApkAsset(
                events = listOf(asset),
                referencedIds = setOf(asset.id),
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
                publisherPubkey = TEST_PUBLISHER_PUBKEY,
                verifyEvent = { false },
            ),
        )
    }

    @Test
    fun rejectingVerifierBlocksReleaseAssetReferences() {
        val releaseEvent =
            signedEvent(SIGNED_RELEASE_EVENT_JSON).copy(
                tags =
                    signedEvent(SIGNED_RELEASE_EVENT_JSON).tags +
                        listOf(
                            listOf("e", ASSET_ID.uppercase()),
                            listOf("e", "b".repeat(64), "wss://relay.example"),
                        ),
            )
        assertNull(
            ZapstoreAssetEvents.assetEventIdsFromReleaseEvent(
                event = releaseEvent,
                appId = APP_ID,
                publisherPubkey = TEST_PUBLISHER_PUBKEY,
                releaseDTag = "$APP_ID@$VERSION",
                verifyEvent = { false },
            ),
        )
    }

    @Test
    fun acceptingVerifierSelectsSingleAssetAndChecksReferenceAndPublisher() {
        val event = assetEvent(baseAssetTags())

        fun select(candidate: NostrEvent): ZapstoreApkAsset? =
            ZapstoreAssetEvents.selectUniqueApkAsset(
                events = listOf(candidate),
                referencedIds = setOf(event.id),
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
                publisherPubkey = TEST_PUBLISHER_PUBKEY,
                verifyEvent = { true },
            )
        assertEquals(event.id, select(event)?.eventId)
        assertNull(select(event.copy(id = "b".repeat(64))))
        assertNull(select(event.copy(pubkey = "b".repeat(64))))
    }

    @Test
    fun acceptingVerifierExtractsNormalizedReleaseReferences() {
        val event =
            signedEvent(SIGNED_RELEASE_EVENT_JSON).copy(
                tags = listOf(listOf("d", "$APP_ID@$VERSION"), listOf("e", ASSET_ID.uppercase())),
            )
        assertEquals(
            setOf(ASSET_ID),
            ZapstoreAssetEvents.assetEventIdsFromReleaseEvent(
                event,
                APP_ID,
                TEST_PUBLISHER_PUBKEY,
                "$APP_ID@$VERSION",
                verifyEvent = { true },
            ),
        )
    }

    @Test
    fun parseApkAssetTagsAcceptsAbsentSizeTag() {
        val event = assetEvent(baseAssetTags())
        val asset =
            ZapstoreAssetEvents.parseApkAssetTags(
                event = event,
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
            )
        assertNotNull(asset)
        assertNull(asset?.sizeBytes)
    }

    @Test
    fun parseApkAssetTagsAcceptsPositiveDecimalSizeTag() {
        val event = assetEvent(baseAssetTags() + listOf(listOf("size", "12345")))
        val asset =
            ZapstoreAssetEvents.parseApkAssetTags(
                event = event,
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
            )
        assertNotNull(asset)
        assertEquals(12_345L, asset?.sizeBytes)
    }

    @Test
    fun parseApkAssetTagsRejectsMalformedZeroNegativeOrDuplicateSizeTags() {
        val malformed = assetEvent(baseAssetTags() + listOf(listOf("size", "abc")))
        val zero = assetEvent(baseAssetTags() + listOf(listOf("size", "0")))
        val negative = assetEvent(baseAssetTags() + listOf(listOf("size", "-1")))
        val duplicate =
            assetEvent(
                baseAssetTags() +
                    listOf(
                        listOf("size", "100"),
                        listOf("size", "200"),
                    ),
            )

        assertNull(
            ZapstoreAssetEvents.parseApkAssetTags(
                event = malformed,
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
            ),
        )
        assertNull(
            ZapstoreAssetEvents.parseApkAssetTags(
                event = zero,
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
            ),
        )
        assertNull(
            ZapstoreAssetEvents.parseApkAssetTags(
                event = negative,
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
            ),
        )
        assertNull(
            ZapstoreAssetEvents.parseApkAssetTags(
                event = duplicate,
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
            ),
        )
    }

    @Test
    fun parseApkAssetTagsRejectsAmbiguousSingletonSecurityTags() {
        val duplicateAppId =
            assetEvent(
                listOf(
                    listOf("i", APP_ID),
                    listOf("i", "org.parres.other"),
                    listOf("version", VERSION),
                    listOf("x", SHA256),
                    listOf("m", AndroidAbi.APK_MIME),
                    listOf("f", PLATFORM_ID),
                    listOf("url", "https://cdn.example.com/app.apk"),
                ),
            )
        val duplicateVersion =
            assetEvent(
                baseAssetTags() +
                    listOf(
                        listOf("version", VERSION),
                        listOf("version", "2026.6.21"),
                    ),
            )
        val duplicateHash =
            assetEvent(
                baseAssetTags() +
                    listOf(
                        listOf("x", SHA256),
                        listOf("x", "d".repeat(64)),
                    ),
            )
        val duplicateMime =
            assetEvent(
                baseAssetTags() +
                    listOf(
                        listOf("m", AndroidAbi.APK_MIME),
                        listOf("m", "application/octet-stream"),
                    ),
            )

        assertNull(
            ZapstoreAssetEvents.parseApkAssetTags(
                event = duplicateAppId,
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
            ),
        )
        assertNull(
            ZapstoreAssetEvents.parseApkAssetTags(
                event = duplicateVersion,
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
            ),
        )
        assertNull(
            ZapstoreAssetEvents.parseApkAssetTags(
                event = duplicateHash,
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
            ),
        )
        assertNull(
            ZapstoreAssetEvents.parseApkAssetTags(
                event = duplicateMime,
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
            ),
        )
    }

    @Test
    fun parseApkAssetTagsReturnsFullyBoundAsset() {
        val event = assetEvent(baseAssetTags() + listOf(listOf("size", "4096")))
        val asset =
            ZapstoreAssetEvents.parseApkAssetTags(
                event = event,
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
            )

        assertNotNull(asset)
        assertEquals(event.id.lowercase(), asset?.eventId)
        assertEquals(APP_ID, asset?.appId)
        assertEquals(VERSION, asset?.version)
        assertEquals(SHA256, asset?.sha256Hex)
        assertEquals("https://cdn.example.com/app.apk", asset?.downloadUrl)
        assertEquals(4_096L, asset?.sizeBytes)
        assertEquals(setOf(PLATFORM_ID), asset?.platformIds)
    }

    private fun baseAssetTags(): List<List<String>> =
        listOf(
            listOf("i", APP_ID),
            listOf("version", VERSION),
            listOf("x", SHA256),
            listOf("m", AndroidAbi.APK_MIME),
            listOf("f", PLATFORM_ID),
            listOf("url", "https://cdn.example.com/app.apk"),
        )

    private fun assetEvent(tags: List<List<String>>): NostrEvent =
        NostrEvent(
            id = ASSET_ID,
            pubkey = TEST_PUBLISHER_PUBKEY,
            createdAt = 1L,
            kind = ASSET_KIND,
            tags = tags,
            content = "",
            sig = "0".repeat(128),
        )

    private fun signedEvent(json: String): NostrEvent = NostrEvent.fromJson(JSONObject(json)) ?: error("fixture")

    private companion object {
        private const val ASSET_KIND = 3063
        private const val APP_ID = "org.parres.darkmatter"
        private const val VERSION = "2026.6.20"
        private const val TEST_PUBLISHER_PUBKEY = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        private val ASSET_ID = "a".repeat(64)
        private val SHA256 = "c".repeat(64)
        private const val PLATFORM_ID = "android-arm64-v8a"
        private const val SIGNED_RELEASE_EVENT_JSON =
            "{\"id\":\"753ec8cfa65fa30e118c1311253deea089efc40e5c008e507194ad17898fd087\",\"pubkey\":\"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\",\"created_at\":1800000100,\"kind\":30063,\"tags\":[[\"d\",\"org.parres.darkmatter@2026.6.20\"],[\"summary\",\"Dark Matter release\"]],\"content\":\"\",\"sig\":\"4320d14456f14da853d5213bc677ea8e0bb3253dfaca20b46193236709135c4a6c62e46d318a83829a69a4061b0224eb1708c47684d11d3effa1cefa25aa1167\"}"
    }
}

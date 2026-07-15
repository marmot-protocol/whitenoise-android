package dev.ipf.whitenoise.android.updates

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
            ),
        )
    }

    @Test
    fun selectUniqueApkAssetRejectsAmbiguousMatches() {
        val asset =
            NostrEvent(
                id = ASSET_ID,
                pubkey = TEST_PUBLISHER_PUBKEY,
                createdAt = 1L,
                kind = 3063,
                tags =
                    listOf(
                        listOf("i", APP_ID),
                        listOf("version", VERSION),
                        listOf("x", SHA256),
                        listOf("m", AndroidAbi.APK_MIME),
                        listOf("f", PLATFORM_ID),
                        listOf("url", "https://cdn.example.com/app.apk"),
                    ),
                content = "",
                sig = "0".repeat(128),
            )
        assertNull(
            ZapstoreAssetEvents.selectUniqueApkAsset(
                events = listOf(asset, asset.copy(id = "b".repeat(64))),
                referencedIds = setOf(ASSET_ID, "b".repeat(64)),
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
                publisherPubkey = TEST_PUBLISHER_PUBKEY,
            ),
        )
    }

    @Test
    fun selectUniqueApkAssetRejectsUnverifiedEvents() {
        val asset =
            NostrEvent(
                id = ASSET_ID,
                pubkey = TEST_PUBLISHER_PUBKEY,
                createdAt = 1L,
                kind = 3063,
                tags =
                    listOf(
                        listOf("i", APP_ID),
                        listOf("version", VERSION),
                        listOf("x", SHA256),
                        listOf("m", AndroidAbi.APK_MIME),
                        listOf("f", PLATFORM_ID),
                        listOf("url", "https://cdn.example.com/app.apk"),
                    ),
                content = "",
                sig = "0".repeat(128),
            )
        assertNull(
            ZapstoreAssetEvents.selectUniqueApkAsset(
                events = listOf(asset),
                referencedIds = setOf(ASSET_ID),
                appId = APP_ID,
                version = VERSION,
                platformId = PLATFORM_ID,
                publisherPubkey = TEST_PUBLISHER_PUBKEY,
            ),
        )
    }

    @Test
    fun releaseWithMutatedTagsFailsVerification() {
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
            ),
        )
    }

    @Test
    fun parseApkAssetTagsAcceptsAbsentSizeTag() {
        val event = assetEventWithComputedId(baseAssetTags())
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
        val event = assetEventWithComputedId(baseAssetTags() + listOf(listOf("size", "12345")))
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
        val malformed = assetEventWithComputedId(baseAssetTags() + listOf(listOf("size", "abc")))
        val zero = assetEventWithComputedId(baseAssetTags() + listOf(listOf("size", "0")))
        val negative = assetEventWithComputedId(baseAssetTags() + listOf(listOf("size", "-1")))
        val duplicate =
            assetEventWithComputedId(
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
            assetEventWithComputedId(
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
            assetEventWithComputedId(
                baseAssetTags() +
                    listOf(
                        listOf("version", VERSION),
                        listOf("version", "2026.6.21"),
                    ),
            )
        val duplicateHash =
            assetEventWithComputedId(
                baseAssetTags() +
                    listOf(
                        listOf("x", SHA256),
                        listOf("x", "d".repeat(64)),
                    ),
            )
        val duplicateMime =
            assetEventWithComputedId(
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
        val event = assetEventWithComputedId(baseAssetTags() + listOf(listOf("size", "4096")))
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

    private fun assetEventWithComputedId(tags: List<List<String>>): NostrEvent {
        val placeholder =
            NostrEvent(
                id = "0".repeat(64),
                pubkey = TEST_PUBLISHER_PUBKEY,
                createdAt = 1L,
                kind = 3063,
                tags = tags,
                content = "",
                sig = "0".repeat(128),
            )
        return placeholder.copy(id = placeholder.computedIdHex())
    }

    private fun signedEvent(json: String): NostrEvent = NostrEvent.fromJson(JSONObject(json)) ?: error("fixture")

    private companion object {
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

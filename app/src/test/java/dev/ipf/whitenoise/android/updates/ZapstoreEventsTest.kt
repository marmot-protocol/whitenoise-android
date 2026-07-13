package dev.ipf.whitenoise.android.updates

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZapstoreEventsTest {
    @Test
    fun extractsReleaseVersionFromSignedAppAndReleaseEvents() {
        val appEvent = signedEvent(SIGNED_APP_EVENT_JSON)
        assertTrue(NostrEventVerifier.verifies(appEvent))

        val releaseDTag = ZapstoreEvents.releaseDTagFromAppEvent(appEvent, APP_ID, TEST_PUBLISHER_PUBKEY)
        assertEquals("$APP_ID@$VERSION", releaseDTag)

        val releaseEvent = signedEvent(SIGNED_RELEASE_EVENT_JSON)
        assertTrue(NostrEventVerifier.verifies(releaseEvent))
        assertEquals(
            VERSION,
            ZapstoreEvents.versionFromReleaseEvent(
                event = releaseEvent,
                appId = APP_ID,
                publisherPubkey = TEST_PUBLISHER_PUBKEY,
                releaseDTag = releaseDTag!!,
            ),
        )
    }

    @Test
    fun rejectsAppEventsForWrongAuthorWrongAppOrInvalidSignature() {
        val appEvent = signedEvent(SIGNED_APP_EVENT_JSON)

        assertNull(
            ZapstoreEvents.releaseDTagFromAppEvent(
                event = appEvent,
                appId = APP_ID,
                publisherPubkey = "0".repeat(64),
            ),
        )
        assertNull(ZapstoreEvents.releaseDTagFromAppEvent(appEvent, "org.parres.whitenoise", TEST_PUBLISHER_PUBKEY))

        val mutatedSignature = appEvent.copy(sig = "0".repeat(128))
        assertFalse(NostrEventVerifier.verifies(mutatedSignature))
        assertNull(ZapstoreEvents.releaseDTagFromAppEvent(mutatedSignature, APP_ID, TEST_PUBLISHER_PUBKEY))
    }

    @Test
    fun rejectsReleaseEventsForWrongDTagOrInvalidSignature() {
        val releaseEvent = signedEvent(SIGNED_RELEASE_EVENT_JSON)

        assertNull(
            ZapstoreEvents.versionFromReleaseEvent(
                event = releaseEvent,
                appId = APP_ID,
                publisherPubkey = TEST_PUBLISHER_PUBKEY,
                releaseDTag = "$APP_ID@2026.6.21",
            ),
        )

        val mutatedSignature = releaseEvent.copy(sig = "0".repeat(128))
        assertFalse(NostrEventVerifier.verifies(mutatedSignature))
        assertNull(
            ZapstoreEvents.versionFromReleaseEvent(
                event = mutatedSignature,
                appId = APP_ID,
                publisherPubkey = TEST_PUBLISHER_PUBKEY,
                releaseDTag = "$APP_ID@$VERSION",
            ),
        )
    }

    private fun signedEvent(json: String): NostrEvent = NostrEvent.fromJson(JSONObject(json)) ?: error("valid signed Nostr event fixture")

    private companion object {
        private const val APP_ID = "org.parres.darkmatter"
        private const val VERSION = "2026.6.20"
        private const val TEST_PUBLISHER_PUBKEY = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        private const val SIGNED_APP_EVENT_JSON =
            "{\"id\":\"9a60ae153ed1258f9036eb1cfbdb6c076d4994b3b30e5cf5b904fc9968f58a77\",\"pubkey\":\"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\",\"created_at\":1800000000,\"kind\":32267,\"tags\":[[\"d\",\"org.parres.darkmatter\"],[\"a\",\"30063:79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798:org.parres.darkmatter@2026.6.20\"]],\"content\":\"\",\"sig\":\"d62ddc6048e421f8e4de49b9cb57832a5d5882ade724798062062670dd0a8890543343bdddcb13324d9807712711230fd593eae50708a605b66b7d13bd10cafb\"}"
        private const val SIGNED_RELEASE_EVENT_JSON =
            "{\"id\":\"753ec8cfa65fa30e118c1311253deea089efc40e5c008e507194ad17898fd087\",\"pubkey\":\"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\",\"created_at\":1800000100,\"kind\":30063,\"tags\":[[\"d\",\"org.parres.darkmatter@2026.6.20\"],[\"summary\",\"Dark Matter release\"]],\"content\":\"\",\"sig\":\"4320d14456f14da853d5213bc677ea8e0bb3253dfaca20b46193236709135c4a6c62e46d318a83829a69a4061b0224eb1708c47684d11d3effa1cefa25aa1167\"}"
    }
}

package dev.ipf.whitenoise.android.updates

import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import dev.ipf.whitenoise.android.core.nostr.NostrEventVerifier
import dev.ipf.whitenoise.android.fuzz.FuzzSyntheticCorpusReplay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZapstoreEventsTest {
    @Test
    fun replaysSyntheticFuzzCorpus() {
        FuzzSyntheticCorpusReplay.replaySuite(FuzzSyntheticCorpusReplay.Suite.ZapstoreEvents)
    }

    // --- Latest-release discovery (signature-gated). Reads the version straight
    // from a kind-30063 release event, since Zapstore's app event carries no
    // pointer to the current release. ---

    @Test
    fun latestReleaseVersionReadsSignedReleaseBoundByDTag() {
        val releaseEvent = signedEvent(SIGNED_RELEASE_EVENT_JSON)
        assertTrue(NostrEventVerifier.verifies(releaseEvent))
        assertEquals(VERSION, ZapstoreEvents.latestReleaseVersion(releaseEvent, APP_ID, TEST_PUBLISHER_PUBKEY))
    }

    @Test
    fun latestReleaseVersionRejectsWrongAuthorWrongAppOrInvalidSignature() {
        val releaseEvent = signedEvent(SIGNED_RELEASE_EVENT_JSON)

        assertNull(ZapstoreEvents.latestReleaseVersion(releaseEvent, APP_ID, "0".repeat(64)))
        // A release for another app under the same publisher must not be read as
        // this app's latest — this is the Dark Matter / White Noise boundary.
        assertNull(ZapstoreEvents.latestReleaseVersion(releaseEvent, "org.parres.whitenoise", TEST_PUBLISHER_PUBKEY))

        val mutatedSignature = releaseEvent.copy(sig = "0".repeat(128))
        assertFalse(NostrEventVerifier.verifies(mutatedSignature))
        assertNull(ZapstoreEvents.latestReleaseVersion(mutatedSignature, APP_ID, TEST_PUBLISHER_PUBKEY))
    }

    // --- Exact-d-tag validation used by the download/asset-resolution path. ---

    @Test
    fun versionFromReleaseEventMatchesExactDTagAndRejectsMismatchOrBadSignature() {
        val releaseEvent = signedEvent(SIGNED_RELEASE_EVENT_JSON)

        assertEquals(
            VERSION,
            ZapstoreEvents.versionFromReleaseEvent(releaseEvent, APP_ID, TEST_PUBLISHER_PUBKEY, "$APP_ID@$VERSION"),
        )
        assertNull(
            ZapstoreEvents.versionFromReleaseEvent(releaseEvent, APP_ID, TEST_PUBLISHER_PUBKEY, "$APP_ID@2026.6.21"),
        )

        val mutatedSignature = releaseEvent.copy(sig = "0".repeat(128))
        assertNull(
            ZapstoreEvents.versionFromReleaseEvent(mutatedSignature, APP_ID, TEST_PUBLISHER_PUBKEY, "$APP_ID@$VERSION"),
        )
    }

    // --- Pure app-binding + version extraction against the live Zapstore event
    // shape (an `i` identifier tag plus an explicit `version` tag, with the `d`
    // tag `appId@version` as fallback). ---

    @Test
    fun releaseVersionForAppPrefersExplicitVersionTag() {
        val event = releaseEvent(identifierTag = APP_ID, versionTag = "2026.5.22", dTag = "$APP_ID@2026.5.22")
        assertEquals("2026.5.22", ZapstoreEvents.releaseVersionForApp(event, APP_ID))
    }

    @Test
    fun releaseVersionForAppFallsBackToDTagSuffixWhenVersionTagAbsent() {
        val event = releaseEvent(identifierTag = APP_ID, versionTag = null, dTag = "$APP_ID@2026.5.7")
        assertEquals("2026.5.7", ZapstoreEvents.releaseVersionForApp(event, APP_ID))
    }

    @Test
    fun releaseVersionForAppBindsByDTagWhenIdentifierTagAbsent() {
        val event = releaseEvent(identifierTag = null, versionTag = null, dTag = "$APP_ID@2026.4.1")
        assertEquals("2026.4.1", ZapstoreEvents.releaseVersionForApp(event, APP_ID))
    }

    @Test
    fun releaseVersionForAppRejectsAnotherApp() {
        val event =
            releaseEvent(
                identifierTag = "org.parres.whitenoise",
                versionTag = "2026.5.22",
                dTag = "org.parres.whitenoise@2026.5.22",
            )
        assertNull(ZapstoreEvents.releaseVersionForApp(event, APP_ID))
    }

    @Test
    fun releaseVersionForAppIgnoresNonCalVerVersionTag() {
        val event = releaseEvent(identifierTag = APP_ID, versionTag = "beta", dTag = "$APP_ID@2026.1.1")
        assertEquals("2026.1.1", ZapstoreEvents.releaseVersionForApp(event, APP_ID))
    }

    private fun signedEvent(json: String): NostrEvent = NostrEvent.fromJson(JSONObject(json)) ?: error("valid signed Nostr event fixture")

    private fun releaseEvent(
        identifierTag: String?,
        versionTag: String?,
        dTag: String,
    ): NostrEvent {
        val tags = JSONArray()
        identifierTag?.let { tags.put(JSONArray().put("i").put(it)) }
        versionTag?.let { tags.put(JSONArray().put("version").put(it)) }
        tags.put(JSONArray().put("d").put(dTag))
        val json =
            JSONObject()
                .put("id", "0".repeat(64))
                .put("pubkey", TEST_PUBLISHER_PUBKEY)
                .put("created_at", 1800000100L)
                .put("kind", 30063)
                .put("tags", tags)
                .put("content", "")
                .put("sig", "0".repeat(128))
        return NostrEvent.fromJson(json) ?: error("synthetic release event")
    }

    private companion object {
        private const val APP_ID = "org.parres.darkmatter"
        private const val VERSION = "2026.6.20"
        private const val TEST_PUBLISHER_PUBKEY = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

        // BIP-340 signature-locked fixture: a real kind-30063 release event
        // signed by the secp256k1 generator key. Its content — and therefore its
        // signature — must not be rebranded, so it stays `org.parres.darkmatter`.
        private const val SIGNED_RELEASE_EVENT_JSON =
            "{\"id\":\"753ec8cfa65fa30e118c1311253deea089efc40e5c008e507194ad17898fd087\",\"pubkey\":\"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\",\"created_at\":1800000100,\"kind\":30063,\"tags\":[[\"d\",\"org.parres.darkmatter@2026.6.20\"],[\"summary\",\"Dark Matter release\"]],\"content\":\"\",\"sig\":\"4320d14456f14da853d5213bc677ea8e0bb3253dfaca20b46193236709135c4a6c62e46d318a83829a69a4061b0224eb1708c47684d11d3effa1cefa25aa1167\"}"
    }
}

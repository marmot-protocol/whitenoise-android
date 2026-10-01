package dev.ipf.whitenoise.android.core.nostr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fast real-MDK correctness checks, with no application/account/database startup. */
@RunWith(AndroidJUnit4::class)
class NostrEventVerifierInstrumentedTest {
    @Test
    fun acceptsSignedReleaseAndTextEvents() {
        assertTrue("signed release fixture", Bip340ComparisonBridge.replacementFullEvent())
        assertTrue("signed text fixture", NativeVerifierCorrectnessBridge.acceptsSignedTextEvent())
    }

    @Test
    fun rejectsMutationsOfEverySignedField() {
        assertTrue(Bip340ComparisonBridge.replacementRejectsMutations())
        assertTrue(Bip340ComparisonBridge.replacementRejectsMutatedEvent())
    }

    @Test
    fun rejectsContentForgeryEvenWithRecomputedCanonicalId() {
        assertTrue(Bip340ComparisonBridge.replacementRejectsForgedEvent())
    }

    @Test
    fun rejectsOutOfRangeSignatureScalars() {
        assertTrue(NativeVerifierCorrectnessBridge.rejectsOutOfRangeSignatureScalars())
    }

    @Test
    fun rejectsOutOfRangePublicKeyWithMatchingCanonicalId() {
        assertTrue(NativeVerifierCorrectnessBridge.rejectsOutOfRangePublicKey())
    }

    @Test
    fun rejectsInRangeOffCurvePublicKeyWithMatchingCanonicalId() {
        assertTrue(NativeVerifierCorrectnessBridge.rejectsInRangeOffCurvePublicKey())
    }

    @Test
    fun malformedFieldsAndKindsOutsideUnsigned16BitRangeFailClosed() {
        assertTrue(NativeVerifierCorrectnessBridge.rejectsMalformedFields())
    }

    @Test
    fun independentlySignedUnicodeAndNumericBoundariesCrossAndroidToRust() {
        val fixtures = boundaryFixtures()
        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index)
            assertEquals(
                fixture.getString("name"),
                fixture.getBoolean("expected"),
                NativeVerifierCorrectnessBridge.verifiesFixtureJson(fixture.getJSONObject("event").toString()),
            )
        }
    }

    @Test
    fun unicodeContentAndTagForgeriesFailEvenAfterRecomputingId() {
        val original = boundaryFixtures().getJSONObject(0).getJSONObject("event")
        val contentForgery = JSONObject(original.toString()).put("content", original.getString("content") + "!")
        val tagForgery = JSONObject(original.toString()).apply { getJSONArray("tags").getJSONArray(0).put(1, "forged") }
        for (forgery in listOf(contentForgery, tagForgery)) {
            assertFalse(NativeVerifierCorrectnessBridge.verifiesFixtureJson(forgery.toString()))
            assertFalse(NativeVerifierCorrectnessBridge.verifiesFixtureWithRecomputedId(forgery.toString()))
        }
    }

    private fun boundaryFixtures(): JSONArray =
        InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("nostr-verifier-boundaries.json")
            .bufferedReader()
            .use { JSONArray(it.readText()) }
}

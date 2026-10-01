package dev.ipf.whitenoise.android.core.nostr

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fast real-MDK correctness checks, with no application/account/database startup. */
@RunWith(AndroidJUnit4::class)
class NostrEventVerifierInstrumentedTest {
    @Test
    fun acceptsSignedReleaseAndTextEvents() {
        assertTrue(Bip340ComparisonBridge.replacementFullEvent())
        assertTrue(NativeVerifierCorrectnessBridge.acceptsSignedTextEvent())
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
    fun rejectsOffCurvePublicKeyWithMatchingCanonicalId() {
        assertTrue(NativeVerifierCorrectnessBridge.rejectsOffCurvePublicKey())
    }

    @Test
    fun malformedFieldsAndKindsOutsideUnsigned16BitRangeFailClosed() {
        assertTrue(NativeVerifierCorrectnessBridge.rejectsMalformedFields())
    }
}

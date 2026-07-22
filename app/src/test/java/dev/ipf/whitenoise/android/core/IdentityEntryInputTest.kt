package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityEntryInputTest {
    // bech32 keys are prefix + 58 chars from the bech32 alphabet.
    private val sampleNsec = "nsec1" + "q".repeat(58)
    private val sampleNpub = "npub1" + "a".repeat(58)

    @Test
    fun classifiesWellFormedBech32Keys() {
        assertEquals(IdentityEntryInput.Kind.SecretKey, IdentityEntryInput.classify(sampleNsec))
        assertEquals(IdentityEntryInput.Kind.PublicKey, IdentityEntryInput.classify(sampleNpub))
        assertEquals(IdentityEntryInput.Kind.SecretKey, IdentityEntryInput.classify("  $sampleNsec  "))
    }

    @Test
    fun classifiesEverythingElseAsInvalid() {
        assertEquals(IdentityEntryInput.Kind.Invalid, IdentityEntryInput.classify(""))
        assertEquals(IdentityEntryInput.Kind.Invalid, IdentityEntryInput.classify("   "))
        assertEquals(IdentityEntryInput.Kind.Invalid, IdentityEntryInput.classify("hello"))
        // Wrong length.
        assertEquals(IdentityEntryInput.Kind.Invalid, IdentityEntryInput.classify("nsec1abc"))
        assertEquals(IdentityEntryInput.Kind.Invalid, IdentityEntryInput.classify(sampleNsec + "q"))
        // 'b' is outside the bech32 alphabet.
        assertEquals(IdentityEntryInput.Kind.Invalid, IdentityEntryInput.classify("npub1" + "b".repeat(58)))
        // bech32 is lowercase-only.
        assertEquals(IdentityEntryInput.Kind.Invalid, IdentityEntryInput.classify(sampleNsec.uppercase()))
        // Raw hex keys are not accepted by the nsec/npub entry contract.
        assertEquals(IdentityEntryInput.Kind.Invalid, IdentityEntryInput.classify("ab".repeat(32)))
    }

    @Test
    fun normalizesScannedKeyPayloads() {
        assertEquals(sampleNsec, IdentityEntryInput.scannedValue(sampleNsec))
        assertEquals(sampleNpub, IdentityEntryInput.scannedValue(" $sampleNpub "))
        assertEquals(sampleNsec, IdentityEntryInput.scannedValue("nostr:$sampleNsec"))
        assertEquals(sampleNpub, IdentityEntryInput.scannedValue("NOSTR:$sampleNpub"))
        // Profile QR payloads carry a deep link; the npub inside is the key.
        assertEquals(sampleNpub, IdentityEntryInput.scannedValue("whitenoise://profile/$sampleNpub"))
    }

    @Test
    fun rejectsScannedPayloadsThatAreNotKeys() {
        assertNull(IdentityEntryInput.scannedValue(""))
        assertNull(IdentityEntryInput.scannedValue("https://example.com"))
        assertNull(IdentityEntryInput.scannedValue("nostr:not-a-key"))
        assertNull(IdentityEntryInput.scannedValue("some scanned text"))
    }

    @Test
    fun classifiesNcryptsecAsSecretKey() {
        val sampleNcryptsec = "ncryptsec1" + "q".repeat(80)
        assertEquals(IdentityEntryInput.Kind.SecretKey, IdentityEntryInput.classify(sampleNcryptsec))
        assertEquals(sampleNcryptsec, IdentityEntryInput.scannedValue("nostr:$sampleNcryptsec"))
        assertEquals(sampleNcryptsec, IdentityEntryInput.scannedValue("  $sampleNcryptsec  "))
    }

    @Test
    fun rejectsMalformedNcryptsecShapes() {
        assertEquals(IdentityEntryInput.Kind.Invalid, IdentityEntryInput.classify("ncryptsec1short"))
        assertEquals(IdentityEntryInput.Kind.Invalid, IdentityEntryInput.classify("ncryptsec1" + "B".repeat(80)))
        assertEquals(IdentityEntryInput.Kind.Invalid, IdentityEntryInput.classify("ncryptsec1" + "q".repeat(400)))
    }

    @Test
    fun pastesRecognizedKeysNormalizedAndOtherTextAsIs() {
        assertEquals(sampleNsec, IdentityEntryInput.pasteValue("nostr:$sampleNsec"))
        assertEquals(sampleNpub, IdentityEntryInput.pasteValue("whitenoise://profile/$sampleNpub"))
        // Not shaped like a key: paste what the user copied and let the
        // engine's inline error handle it on submit.
        assertEquals("mangled-key", IdentityEntryInput.pasteValue("  mangled-key "))
        assertNull(IdentityEntryInput.pasteValue(null))
        assertNull(IdentityEntryInput.pasteValue("   "))
    }
}

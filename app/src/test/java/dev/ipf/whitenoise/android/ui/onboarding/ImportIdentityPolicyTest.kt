package dev.ipf.whitenoise.android.ui.onboarding

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityEntryInput
import dev.ipf.whitenoise.android.state.permitsDirectIdentityImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportIdentityPolicyTest {
    private val sampleNsec = "nsec1" + "q".repeat(58)
    private val sampleNpub = "npub1" + "a".repeat(58)
    private val sampleHexPubkey = "0000000000000000000000000000000000000000000000000000000000000001"

    @Test
    fun publicKeyMapsToDedicatedErrorNotGenericImportFailure() {
        assertEquals(IdentityEntryInput.Kind.PublicKey, IdentityEntryInput.classify(sampleNpub))
        assertEquals(R.string.sign_in_error_public_key, importIdentityErrorRes(sampleNpub))
    }

    @Test
    fun invalidKeyStillMapsToInvalidKeyError() {
        assertEquals(R.string.identity_entry_error_invalid_key, importIdentityErrorRes("not-a-key"))
    }

    @Test
    fun secretKeyFailureStillMapsToImportFailed() {
        assertEquals(R.string.identity_entry_error_import_failed, importIdentityErrorRes(sampleNsec))
    }

    @Test
    fun rawHexPublicKeyBlockedFromDirectImport() {
        assertEquals(IdentityEntryInput.Kind.Invalid, IdentityEntryInput.classify(sampleHexPubkey))
        assertFalse(permitsDirectIdentityImport(sampleHexPubkey))
        // Encrypted backups are recognized input but never direct-importable:
        // the engine's login accepts plaintext keys only.
        assertFalse(permitsDirectIdentityImport("ncryptsec1" + "q".repeat(80)))
    }

    @Test
    fun npubBlockedFromDirectImport() {
        assertFalse(permitsDirectIdentityImport(sampleNpub))
    }

    @Test
    fun nsecPermittedForDirectImport() {
        assertTrue(permitsDirectIdentityImport(sampleNsec))
    }
}

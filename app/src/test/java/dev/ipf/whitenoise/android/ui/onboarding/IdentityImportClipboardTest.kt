package dev.ipf.whitenoise.android.ui.onboarding

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityImportClipboardTest {
    private val sampleNsec = "nsec1" + "q".repeat(58)
    private val sampleNpub = "npub1" + "a".repeat(58)

    @Test
    fun clearsClipboardAfterSuccessfulSecretKeyImport() =
        runBlocking {
            var cleared = 0
            var importedValue: String? = null

            val imported =
                importIdentityAndClearClipboardOnSuccess(
                    identity = sampleNsec,
                    importIdentity = { value ->
                        importedValue = value
                        true
                    },
                    clearClipboard = { cleared += 1 },
                )

            assertTrue(imported)
            assertEquals(sampleNsec, importedValue)
            assertEquals(1, cleared)
        }

    @Test
    fun leavesClipboardUntouchedAfterSuccessfulPublicKeyImport() =
        runBlocking {
            var cleared = 0

            val imported =
                importIdentityAndClearClipboardOnSuccess(
                    identity = sampleNpub,
                    importIdentity = { true },
                    clearClipboard = { cleared += 1 },
                )

            assertTrue(imported)
            assertEquals(0, cleared)
        }

    @Test
    fun leavesClipboardUntouchedAfterFailedImport() =
        runBlocking {
            var cleared = 0

            val imported =
                importIdentityAndClearClipboardOnSuccess(
                    identity = "not-a-key",
                    importIdentity = { false },
                    clearClipboard = { cleared += 1 },
                )

            assertFalse(imported)
            assertEquals(0, cleared)
        }
}

package dev.ipf.whitenoise.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Encrypted export goes through the same one-shot device-authentication gate as raw export. */
@RunWith(RobolectricTestRunner::class)
class PrivateKeyExportAuthenticationTest {
    /** Encrypted export waits for a fresh challenge and ignores success after its dialog is reopened. */
    @Test
    fun encryptedExportWaitsForAuthenticationInItsOwnDialog() {
        var pending: (() -> Unit)? = null
        val exports = mutableListOf<Pair<Boolean, KeyExportDestination>>()
        val ui = ProfileKeysUiState().apply { openEncryptedExport() }
        val authenticate = RawKeyExportAuthentication { pending = it }
        val export =
            authenticate.gateExport(ui, { true }) { encrypted, destination ->
                exports += encrypted to destination
            }

        ui.enterPassphrase()
        export(true, KeyExportDestination.Preview)
        assertTrue(exports.isEmpty())
        ui.cancelExport()
        ui.openEncryptedExport()
        ui.enterPassphrase()
        checkNotNull(pending).invoke()
        assertTrue(exports.isEmpty())

        export(true, KeyExportDestination.File)
        assertTrue(exports.isEmpty())
        checkNotNull(pending).invoke()
        assertEquals(listOf(true to KeyExportDestination.File), exports)
    }

    /** An invalid passphrase never opens a challenge, and clearing it during one rejects the result. */
    @Test
    fun encryptedExportRequiresValidPassphraseAroundAuthentication() {
        var requests = 0
        var pending: (() -> Unit)? = null
        var delivered = false
        val ui = ProfileKeysUiState().apply { openEncryptedExport() }
        val authenticate =
            RawKeyExportAuthentication {
                requests++
                pending = it
            }
        val export = authenticate.gateExport(ui, { true }) { _, _ -> delivered = true }

        export(true, KeyExportDestination.File)
        assertEquals(0, requests)

        ui.enterPassphrase()
        export(true, KeyExportDestination.File)
        assertEquals(1, requests)
        ui.clearPasswords()
        checkNotNull(pending).invoke()
        assertFalse(delivered)
    }

    /** Dismissing the password dialog retires a pending challenge before the dialog disappears. */
    @Test
    fun encryptedDismissalStartRejectsAuthentication() {
        var pending: (() -> Unit)? = null
        var delivered = false
        val ui = ProfileKeysUiState().apply { openEncryptedExport() }
        val authentication = RawKeyExportAuthentication { pending = it }
        val export = authentication.gateExport(ui, { true }) { _, _ -> delivered = true }

        ui.enterPassphrase()
        export(true, KeyExportDestination.File)
        ui.cancelExportPreparation()
        assertTrue(ui.passwordDialog)
        checkNotNull(pending).invoke()

        assertFalse(delivered)
    }

    /** Leaving the screen rejects a challenge that succeeds afterwards. */
    @Test
    fun encryptedExportRejectsAResultAfterTheScreenStops() {
        var pending: (() -> Unit)? = null
        var delivered = false
        val ui = ProfileKeysUiState().apply { openEncryptedExport() }
        val authentication = RawKeyExportAuthentication { pending = it }
        val export = authentication.gateExport(ui, { true }) { _, _ -> delivered = true }

        ui.enterPassphrase()
        export(true, KeyExportDestination.Preview)
        ui.stopSensitiveOperations()
        checkNotNull(pending).invoke()

        assertFalse(delivered)
    }

    /** Fills both encrypted-export fields with a passphrase that satisfies the export rules. */
    private fun ProfileKeysUiState.enterPassphrase() {
        password.edit { replace(0, length, PASSPHRASE) }
        confirmation.edit { replace(0, length, PASSPHRASE) }
    }

    private companion object {
        const val PASSPHRASE = "Long test passphrase 48!"
    }
}

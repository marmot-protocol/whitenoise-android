package dev.ipf.whitenoise.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentitySecretExportStateTest {
    @Test
    fun requestShowsMaskedConfirmation() {
        val state =
            identitySecretExportState(
                IdentitySecretExportState(),
                IdentitySecretExportAction.Request,
            )

        assertTrue(state.confirmationVisible)
        assertFalse(state.revealed)
        assertFalse(maskedIdentitySecret(SECRET, state.revealed).contains(SECRET))
    }

    @Test
    fun revealRequiresExplicitActionInsideConfirmation() {
        val requested = IdentitySecretExportState(confirmationVisible = true)
        val revealed = identitySecretExportState(requested, IdentitySecretExportAction.ToggleReveal)

        assertTrue(revealed.revealed)
        assertEquals(SECRET, maskedIdentitySecret(SECRET, revealed.revealed))
    }

    @Test
    fun revealIsIgnoredBeforeConfirmationAndCancelClearsSensitiveState() {
        assertEquals(
            IdentitySecretExportState(),
            identitySecretExportState(IdentitySecretExportState(), IdentitySecretExportAction.ToggleReveal),
        )
        assertEquals(
            IdentitySecretExportState(),
            identitySecretExportState(
                IdentitySecretExportState(confirmationVisible = true, revealed = true),
                IdentitySecretExportAction.Cancel,
            ),
        )
    }

    private companion object {
        const val SECRET = "nsec1this-must-never-be-a-label-or-description"
    }
}

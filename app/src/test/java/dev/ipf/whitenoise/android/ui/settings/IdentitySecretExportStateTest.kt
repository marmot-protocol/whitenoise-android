package dev.ipf.whitenoise.android.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancelledSessionRejectsExportThatResumesLater() =
        runTest {
            val suspendedExport = CompletableDeferred<String?>()
            var activeSession = 1L
            var consumedSecret: String? = null
            var accepted: Boolean? = null

            launch {
                accepted =
                    exportIdentitySecretForSession(
                        sessionId = activeSession,
                        exporter = { suspendedExport.await() },
                        isSessionActive = { it == activeSession },
                        onExported = { consumedSecret = it },
                    )
            }
            runCurrent()

            activeSession++
            suspendedExport.complete(SECRET)
            advanceUntilIdle()

            assertEquals(false, accepted)
            assertNull(consumedSecret)
        }

    private companion object {
        const val SECRET = "nsec1this-must-never-be-a-label-or-description"
    }
}

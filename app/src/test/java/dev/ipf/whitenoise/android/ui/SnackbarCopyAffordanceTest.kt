package dev.ipf.whitenoise.android.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.NoticeTier
import dev.ipf.whitenoise.android.state.privacySafeErrorPresentation
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.common.snackbarShowsCopyAffordance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #796: the snackbar Copy affordance must be gated by the explicit
 * explicit privacy-safe report set at the toast's emit site — not by a
 * heuristic over the message body or a legacy `copyable` flag alone.
 */
class SnackbarCopyAffordanceTest {
    @Test
    fun copyableErrorToastShowsCopyAffordance() {
        assertTrue(
            snackbarShowsCopyAffordance(
                ToastSnackbarVisuals(
                    message = "Couldn't send message\nTry again",
                    copyable = true,
                    copyText = "operation=MESSAGE_SEND\nerror=CONNECTIVITY",
                ),
            ),
        )
    }

    @Test
    fun copyableFlagWithoutSafeReportDoesNotCopyVisibleText() {
        assertFalse(
            snackbarShowsCopyAffordance(
                ToastSnackbarVisuals(message = "Validation failed for alice@example.test", copyable = true),
            ),
        )
    }

    @Test
    fun nonCopyableToastHidesCopyAffordanceRegardlessOfBody() {
        // Not flagged at the emit site: no copy icon, even for a long,
        // diagnostic-looking body — the flag decides, not the message text.
        assertFalse(
            snackbarShowsCopyAffordance(
                ToastSnackbarVisuals(message = "npub1qqqsyqcyq5rqwzqfpqyp3zxw", copyable = false),
            ),
        )
        assertFalse(snackbarShowsCopyAffordance(ToastSnackbarVisuals(message = "Copied")))
    }

    @Test
    fun plainShowSnackbarMessagesNeverShowCopyAffordance() {
        // Direct hostState.showSnackbar("...") calls produce the default
        // Material visuals, which carry no copyable flag.
        assertFalse(snackbarShowsCopyAffordance(fakeVisuals(actionLabel = null)))
    }

    @Test
    fun actionableSnackbarsNeverShowCopyAffordance() {
        // The action slot (e.g. the chat-list "Undo") must stay untouched.
        assertFalse(snackbarShowsCopyAffordance(fakeVisuals(actionLabel = "Undo")))
    }

    @Test
    fun appNoticesUseTierAppropriateLifetimeAndCopyPayload() {
        val confirmation = ToastSnackbarVisuals(message = "Saved")
        val error =
            ToastSnackbarVisuals(
                message = "Couldn\'t save\nTry again",
                copyable = true,
                tier = NoticeTier.ActionableError,
                copyText = "operation=SAVE\nerror=CONNECTIVITY",
            )

        assertEquals(SnackbarDuration.Short, confirmation.duration)
        assertEquals(SnackbarDuration.Indefinite, error.duration)
        assertEquals("operation=SAVE\nerror=CONNECTIVITY", error.copyText)
        assertTrue(error.withDismissAction)
    }

    @Test
    fun migratedFailureShowsDiagnosticCopy() {
        val presentation =
            privacySafeErrorPresentation(
                operationCode = "MEDIA_LIBRARY_FILE_OPEN",
                throwable = java.io.IOException("token=private https://user:pass@example.test"),
                message = AppText.Plain("Couldn't open this file."),
                appVersion = "test",
                androidVersion = "test",
                occurredAtUtc = "2026-08-15T12:00:00Z",
            )
        val visuals =
            ToastSnackbarVisuals(
                message = (presentation.message as AppText.Plain).value,
                copyable = true,
                copyText = presentation.report,
            )

        assertTrue(snackbarShowsCopyAffordance(visuals))
        assertTrue(visuals.copyText?.contains("operation=MEDIA_LIBRARY_FILE_OPEN") == true)
        assertFalse(visuals.copyText?.contains("user:pass") == true)
        assertFalse(visuals.copyText?.contains("private") == true)
    }

    private fun fakeVisuals(actionLabel: String?): SnackbarVisuals =
        object : SnackbarVisuals {
            override val message: String = "Something happened"
            override val actionLabel: String? = actionLabel
            override val withDismissAction: Boolean = false
            override val duration: SnackbarDuration = SnackbarDuration.Short
        }
}

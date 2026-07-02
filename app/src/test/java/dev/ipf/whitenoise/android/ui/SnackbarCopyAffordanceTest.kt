package dev.ipf.whitenoise.android.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #796: the snackbar Copy affordance must be gated by the explicit
 * `copyable` flag set at the toast's emit site — not by a heuristic over the
 * message body, and never as a default for every non-actionable snackbar.
 */
class SnackbarCopyAffordanceTest {
    @Test
    fun copyableErrorToastShowsCopyAffordance() {
        assertTrue(
            snackbarShowsCopyAffordance(
                ToastSnackbarVisuals(message = "Couldn't send message\nRelayError: timed out", copyable = true),
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

    private fun fakeVisuals(actionLabel: String?): SnackbarVisuals =
        object : SnackbarVisuals {
            override val message: String = "Something happened"
            override val actionLabel: String? = actionLabel
            override val withDismissAction: Boolean = false
            override val duration: SnackbarDuration = SnackbarDuration.Short
        }
}

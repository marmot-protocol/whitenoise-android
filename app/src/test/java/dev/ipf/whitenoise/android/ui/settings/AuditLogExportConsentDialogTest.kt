package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuditLogExportConsentDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exportRunsOnlyAfterExplicitConfirmation() {
        var confirms = 0
        var dismisses = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                AuditLogExportConsentDialog(
                    onDismiss = { dismisses += 1 },
                    onConfirm = { confirms += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.export_audit_logs_confirm_title)).assertIsDisplayed()
        assertEquals(0, confirms)
        composeRule.onNodeWithText(string(R.string.export_audit_logs_confirm_action)).performClick()
        assertEquals(1, confirms)
        assertEquals(0, dismisses)
    }

    /** The destination choice is offered only after the acknowledgement, and picks exactly one. */
    @Test
    fun destinationDialogOffersSaveAndShareIndependently() {
        var saves = 0
        var shares = 0
        var dismisses = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                AuditLogExportDestinationDialog(
                    onDismiss = { dismisses += 1 },
                    onSave = { saves += 1 },
                    onShare = { shares += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.export_audit_logs_destination_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.export_audit_logs_save)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.export_audit_logs_share)).assertIsDisplayed()

        composeRule.onNodeWithText(string(R.string.export_audit_logs_save)).performClick()
        assertEquals(1, saves)
        assertEquals(0, shares)
        assertEquals(0, dismisses)
    }

    /** Sharing is the other destination and never also triggers a save. */
    @Test
    fun destinationDialogShareDoesNotSave() {
        var saves = 0
        var shares = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                AuditLogExportDestinationDialog(
                    onDismiss = {},
                    onSave = { saves += 1 },
                    onShare = { shares += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.export_audit_logs_share)).performClick()
        assertEquals(1, shares)
        assertEquals(0, saves)
    }

    private fun string(resource: Int): String =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getString(resource)
}

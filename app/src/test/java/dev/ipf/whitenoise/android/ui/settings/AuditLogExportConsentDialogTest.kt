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

    private fun string(resource: Int): String =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getString(resource)
}

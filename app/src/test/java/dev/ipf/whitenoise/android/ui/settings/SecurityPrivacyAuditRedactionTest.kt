package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
class SecurityPrivacyAuditRedactionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun turningOffRedactionOpensConfirmationAndOnlyConfirmApplies() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val confirmTitle = context.getString(R.string.redact_audit_data_confirm_title)
        val confirmAction = context.getString(R.string.redact_audit_data_confirm_action)
        val cancel = context.getString(R.string.cancel)
        val applied = mutableListOf<Boolean>()

        composeRule.setContent {
            WhiteNoiseTheme {
                AuditRedactionSwitch(
                    checked = true,
                    enabled = true,
                    busy = false,
                    onApplyRedaction = { applied += it },
                )
            }
        }

        composeRule.onNodeWithTag(AUDIT_REDACTION_SWITCH_TAG).performClick()
        composeRule.onNodeWithText(confirmTitle).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(emptyList<Boolean>(), applied) }

        composeRule.onNodeWithText(cancel).performClick()
        composeRule.runOnIdle { assertEquals(emptyList<Boolean>(), applied) }

        composeRule.onNodeWithTag(AUDIT_REDACTION_SWITCH_TAG).performClick()
        composeRule.onNodeWithText(confirmAction).performClick()
        composeRule.runOnIdle { assertEquals(listOf(false), applied) }
    }

    @Test
    fun turningOnRedactionAppliesImmediatelyWithoutConfirmation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val confirmTitle = context.getString(R.string.redact_audit_data_confirm_title)
        val applied = mutableListOf<Boolean>()

        composeRule.setContent {
            WhiteNoiseTheme {
                AuditRedactionSwitch(
                    checked = false,
                    enabled = true,
                    busy = false,
                    onApplyRedaction = { applied += it },
                )
            }
        }

        composeRule.onNodeWithTag(AUDIT_REDACTION_SWITCH_TAG).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(true), applied)
        }
        composeRule.onNodeWithText(confirmTitle).assertDoesNotExist()
    }
}

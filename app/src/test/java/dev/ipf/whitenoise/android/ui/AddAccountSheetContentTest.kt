package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingAction
import dev.ipf.whitenoise.android.ui.onboarding.importIdentityErrorRes
import dev.ipf.whitenoise.android.ui.profile.AddAccountSheetContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AddAccountSheetContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun string(res: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(res)

    private fun setContent(
        amberSignerAvailable: Boolean,
        identity: String = "",
        inFlightAction: OnboardingAction = OnboardingAction.Idle,
        importErrorRes: Int? = null,
        onCreate: () -> Unit = {},
        onLoginWithAmber: () -> Unit = {},
        onImport: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                AddAccountSheetContent(
                    amberSignerAvailable = amberSignerAvailable,
                    inFlightAction = inFlightAction,
                    identity = identity,
                    importErrorRes = importErrorRes,
                    onCreate = onCreate,
                    onLoginWithAmber = onLoginWithAmber,
                    onIdentityChange = {},
                    onErrorChange = {},
                    onImport = onImport,
                )
            }
        }
    }

    @Test
    fun amberRowOnlyOffersWhenSignerInstalled() {
        setContent(amberSignerAvailable = false)
        composeRule.onNodeWithText(string(R.string.onboarding_login_with_amber)).assertDoesNotExist()
    }

    @Test
    fun amberRowFiresLogin() {
        var amberTaps = 0
        setContent(amberSignerAvailable = true, onLoginWithAmber = { amberTaps++ })
        composeRule.onNodeWithText(string(R.string.onboarding_login_with_amber)).performClick()
        assertEquals(1, amberTaps)
    }

    @Test
    fun secretKeyFormHiddenUntilDisclosed() {
        setContent(amberSignerAvailable = true)
        composeRule.onNodeWithText(string(R.string.sign_in_secret_key_help)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.import_existing_identity)).performClick()
        composeRule.onNodeWithText(string(R.string.sign_in_secret_key_help)).assertExists()
    }

    @Test
    fun importButtonGatesOnIdentityText() {
        setContent(amberSignerAvailable = false, identity = "")
        composeRule.onNodeWithText(string(R.string.import_existing_identity)).performClick()
        // Row label and confirm button share the label; the button is the enabled-gated one.
        composeRule
            .onAllNodesWithText(string(R.string.import_existing_identity))[1]
            .assertIsNotEnabled()
    }

    @Test
    fun importButtonEnabledWithIdentityAndFiresImport() {
        var imports = 0
        setContent(amberSignerAvailable = false, identity = "nsec1example", onImport = { imports++ })
        composeRule.onNodeWithText(string(R.string.import_existing_identity)).performClick()
        composeRule
            .onAllNodesWithText(string(R.string.import_existing_identity))[1]
            .assertIsEnabled()
        composeRule.onAllNodesWithText(string(R.string.import_existing_identity))[1].performClick()
        assertEquals(1, imports)
    }

    @Test
    fun allRowsDisabledWhileBusy() {
        setContent(amberSignerAvailable = true, inFlightAction = OnboardingAction.Creating)
        composeRule.onNodeWithText(string(R.string.onboarding_login_with_amber)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.import_existing_identity)).assertIsNotEnabled()
    }

    @Test
    fun publicKeyImportFailureShowsDedicatedError() {
        val npub = "npub1" + "a".repeat(58)
        val errorRes = importIdentityErrorRes(npub)
        setContent(amberSignerAvailable = false, identity = npub, importErrorRes = errorRes)
        composeRule.onNodeWithText(string(R.string.import_existing_identity)).performClick()
        composeRule.onNodeWithText(string(R.string.sign_in_error_public_key)).assertExists()
    }
}

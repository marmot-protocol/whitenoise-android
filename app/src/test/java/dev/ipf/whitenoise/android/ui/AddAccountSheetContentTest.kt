package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    /** The prototype Sign Up entry invokes its form-opening callback once and never invokes import. */
    @Test fun signUpInvokesItsFormOpeningCallback() {
        var creates = 0
        var imports = 0
        setContent(amberSignerAvailable = true, onCreate = { creates++ }, onImport = { imports++ })
        composeRule.onNodeWithTag("onboarding.welcome.sign_up").performClick()
        assertEquals(1, creates)
        assertEquals(0, imports)
    }

    @Test
    fun amberRowOnlyOffersWhenSignerInstalled() {
        setContent(amberSignerAvailable = false)
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").performClick()
        composeRule.onNodeWithText(string(R.string.onboarding_login_with_amber)).assertDoesNotExist()
    }

    @Test
    fun amberRowFiresLogin() {
        var amberTaps = 0
        setContent(amberSignerAvailable = true, onLoginWithAmber = { amberTaps++ })
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").performClick()
        composeRule.onNodeWithText(string(R.string.onboarding_login_with_amber)).performClick()
        assertEquals(1, amberTaps)
    }

    @Test
    fun secretKeyFormHiddenUntilDisclosed() {
        setContent(amberSignerAvailable = true)
        composeRule.onNodeWithTag("onboarding.sign_in.private_key").assertDoesNotExist()
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").performClick()
        composeRule.onNodeWithTag("onboarding.sign_in.private_key").assertExists()
    }

    @Test
    fun importButtonGatesOnIdentityText() {
        setContent(amberSignerAvailable = false, identity = "")
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").performClick()
        // Opening Sign In does not grant import while the secure field is empty.
        composeRule
            .onNodeWithTag("onboarding.sign_in.action")
            .assertIsNotEnabled()
    }

    @Test
    fun importButtonEnabledWithIdentityAndFiresImport() {
        var imports = 0
        setContent(amberSignerAvailable = false, identity = "nsec1" + "q".repeat(58), onImport = { imports++ })
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").performClick()
        composeRule
            .onNodeWithTag("onboarding.sign_in.action")
            .assertIsEnabled()
        composeRule.onNodeWithTag("onboarding.sign_in.action").performClick()
        assertEquals(1, imports)
    }

    @Test
    fun allRowsDisabledWhileBusy() {
        setContent(amberSignerAvailable = true, inFlightAction = OnboardingAction.Creating)
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").assertIsNotEnabled()
        composeRule.onNodeWithTag("onboarding.welcome.sign_up").assertIsNotEnabled()
    }

    @Test
    fun publicKeyImportFailureShowsDedicatedError() {
        val npub = "npub1" + "a".repeat(58)
        val errorRes = importIdentityErrorRes(npub)
        setContent(amberSignerAvailable = false, identity = npub, importErrorRes = errorRes)
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").performClick()
        composeRule.onNodeWithText(string(R.string.sign_in_error_public_key)).assertExists()
    }
}

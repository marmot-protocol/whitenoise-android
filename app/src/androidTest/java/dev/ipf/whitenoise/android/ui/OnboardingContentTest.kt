package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingContent
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingSavedAccountUi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

private const val WELCOME_MARK_TAG = "onboarding.welcome.mark"
private const val WELCOME_SIGN_UP_TAG = "onboarding.welcome.sign_up"
private const val SIGN_IN_KEY_TAG = "onboarding.sign_in.private_key"
private const val SIGN_IN_ACTION_TAG = "onboarding.sign_in.action"
private const val SIGN_IN_AMBER_TAG = "onboarding.sign_in.amber"

/** The onboarding surfaces on a real device: the welcome mark and actions, then the private-key page. */
class OnboardingContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val nsec = "nsec1" + "q".repeat(58)
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The welcome page offers Sign Up and Sign In under the mark, and Sign In opens the private-key page. */
    @Test
    fun landingShowsCreateAndSignInBeforePrivateKeyPage() {
        var createClicks = 0
        var importedIdentity: String? = null

        composeRule.setContent {
            WhiteNoiseTheme {
                var identity by remember { mutableStateOf("") }
                OnboardingContent(
                    identity = identity,
                    creatingIdentity = false,
                    signingInBusy = false,
                    onIdentityChange = { identity = it },
                    onCreateIdentity = { createClicks += 1 },
                    onImportIdentity = { importedIdentity = it },
                )
            }
        }

        composeRule
            .onNodeWithTag(WELCOME_MARK_TAG)
            .assertIsDisplayed()
            // The mark is described by the flavour's app name ("White Noise Dev" on dev builds).
            .assertContentDescriptionEquals(context.getString(R.string.app_name))
        composeRule.onNodeWithText("Sign Up").assertIsDisplayed()
        composeRule.onNodeWithText("Sign In").assertIsDisplayed()
        composeRule.onNodeWithTag(SIGN_IN_KEY_TAG).assertDoesNotExist()

        composeRule.onNodeWithText("Sign Up").performClick()
        composeRule.runOnIdle { assertEquals(1, createClicks) }

        composeRule.onNodeWithText("Sign In").performClick()
        composeRule.onNodeWithTag(SIGN_IN_KEY_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Sign Up").assertDoesNotExist()

        composeRule.onNodeWithTag(SIGN_IN_KEY_TAG).performTextInput(nsec)
        composeRule.onNodeWithTag(SIGN_IN_ACTION_TAG).performClick()
        composeRule.runOnIdle { assertEquals(nsec, importedIdentity) }
    }

    /** Creating an identity turns Sign Up into a disabled, announced in-progress button. */
    @Test
    fun createIdentityButtonShowsProgressWhileCreating() {
        composeRule.setContent {
            WhiteNoiseTheme {
                OnboardingContent(
                    identity = "",
                    creatingIdentity = true,
                    signingInBusy = false,
                    onIdentityChange = {},
                    onCreateIdentity = {},
                    onImportIdentity = {},
                )
            }
        }

        composeRule.onNodeWithText("Creating Identity").assertIsDisplayed()
        composeRule
            .onNodeWithTag(WELCOME_SIGN_UP_TAG)
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "In progress"))
    }

    /** A sign-in in flight keeps the private-key page up with its action busy and disabled. */
    @Test
    fun signInButtonShowsProgressWhileImporting() {
        composeRule.setContent {
            WhiteNoiseTheme {
                var identity by remember { mutableStateOf(nsec) }
                OnboardingContent(
                    identity = identity,
                    creatingIdentity = false,
                    signingInBusy = true,
                    onIdentityChange = { identity = it },
                    onCreateIdentity = {},
                    onImportIdentity = {},
                )
            }
        }

        composeRule.onNodeWithText("Signing in…").assertIsDisplayed()
        composeRule.onNodeWithTag(SIGN_IN_ACTION_TAG).assertIsNotEnabled()
    }

    /** A retained account gets a one-tap resume above Sign In and Sign Up; Amber waits on the sign-in page. */
    @Test
    fun retainedAccountOffersOneTapResumeWithoutHidingOtherSignInOptions() {
        var resumedLabel: String? = null
        val account =
            OnboardingSavedAccountUi(
                label = "amber-account",
                accountIdHex = "01".repeat(32),
                displayName = "Amber User",
                shortIdentity = "npub1amber…user",
                avatarUrl = null,
            )

        composeRule.setContent {
            WhiteNoiseTheme {
                OnboardingContent(
                    identity = "",
                    creatingIdentity = false,
                    signingInBusy = false,
                    onIdentityChange = {},
                    onCreateIdentity = {},
                    onImportIdentity = {},
                    amberSignerAvailable = true,
                    savedAccounts = listOf(account),
                    onContinueWithSavedAccount = { resumedLabel = it },
                )
            }
        }

        composeRule.onNodeWithText("Continue as Amber User").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Sign In").assertIsDisplayed()
        composeRule.onNodeWithText("Sign Up").assertIsDisplayed()
        composeRule.onNodeWithTag(SIGN_IN_AMBER_TAG).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals("amber-account", resumedLabel) }

        composeRule.onNodeWithText("Sign In").performClick()
        composeRule.onNodeWithTag(SIGN_IN_AMBER_TAG).assertIsDisplayed()
    }
}

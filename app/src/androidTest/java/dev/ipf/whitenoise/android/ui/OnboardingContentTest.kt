package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingContent
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingSavedAccountUi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnboardingContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val nsec = "nsec1" + "q".repeat(58)

    @Test
    fun landingShowsCreateAndSignInBeforeNsecEntryPage() {
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

        composeRule.onNodeWithContentDescription("White Noise logo").assertIsDisplayed()
        composeRule.onNodeWithText("Sign Up").assertIsDisplayed()
        composeRule.onNodeWithText("Sign In").assertIsDisplayed()
        composeRule.onNodeWithText("Import Existing Identity").assertDoesNotExist()
        composeRule.onNodeWithText("Nostr nsec").assertDoesNotExist()

        composeRule.onNodeWithText("Sign Up").performClick()
        composeRule.runOnIdle { assertEquals(1, createClicks) }

        composeRule.onNodeWithText("Sign In").performClick()
        composeRule.onNodeWithText("Nostr nsec").assertIsDisplayed()
        composeRule.onNodeWithText("Import Existing Identity").assertDoesNotExist()

        composeRule.onNodeWithText("Nostr nsec").performTextInput(nsec)
        composeRule.onNodeWithText("Sign in").performClick()
        composeRule.runOnIdle { assertEquals(nsec, importedIdentity) }
    }

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
        composeRule.onNodeWithContentDescription("Creating identity").assertIsDisplayed()
    }

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

        composeRule.onNodeWithContentDescription("Sign in").assertIsDisplayed()
    }

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

        composeRule.onNodeWithContentDescription("Continue as Amber User").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("npub1amber…user").assertIsDisplayed()
        composeRule.onNodeWithText("Sign In").assertIsDisplayed()
        composeRule.onNodeWithText("Sign Up").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in with Amber").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("amber-account", resumedLabel) }
    }
}

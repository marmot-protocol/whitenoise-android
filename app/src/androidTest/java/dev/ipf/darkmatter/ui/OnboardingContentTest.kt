package dev.ipf.darkmatter.ui

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
import dev.ipf.darkmatter.ui.theme.DarkMatterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnboardingContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun landingShowsCreateAndSignInBeforeNsecEntryPage() {
        var createClicks = 0
        var importedIdentity: String? = null

        composeRule.setContent {
            DarkMatterTheme {
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

        composeRule.onNodeWithContentDescription("Dark Matter shield").assertIsDisplayed()
        composeRule.onNodeWithText("Create New Identity").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed()
        composeRule.onNodeWithText("Import Existing Identity").assertDoesNotExist()
        composeRule.onNodeWithText("Nostr nsec").assertDoesNotExist()

        composeRule.onNodeWithText("Create New Identity").performClick()
        composeRule.runOnIdle { assertEquals(1, createClicks) }

        composeRule.onNodeWithText("Sign in").performClick()
        composeRule.onNodeWithText("Nostr nsec").assertIsDisplayed()
        composeRule.onNodeWithText("Import Existing Identity").assertDoesNotExist()

        composeRule.onNodeWithText("Nostr nsec").performTextInput("nsec1example")
        composeRule.onNodeWithText("Sign in").performClick()
        composeRule.runOnIdle { assertEquals("nsec1example", importedIdentity) }
    }

    @Test
    fun createIdentityButtonShowsProgressWhileCreating() {
        composeRule.setContent {
            DarkMatterTheme {
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
}

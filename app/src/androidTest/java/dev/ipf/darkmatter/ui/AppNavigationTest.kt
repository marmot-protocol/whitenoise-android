package dev.ipf.darkmatter.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.ipf.darkmatter.ui.theme.DarkMatterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun avatarButtonOpensSettingsWithoutDrawerNavigation() {
        var settingsClicks = 0

        composeRule.setContent {
            DarkMatterTheme {
                AccountAvatarButton(
                    title = "Ada Lovelace",
                    seed = "ada",
                    pictureUrl = null,
                    size = 40.dp,
                    onClick = { settingsClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open navigation").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open settings").assertIsDisplayed()
        composeRule.onNodeWithText("AL").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Open settings").performClick()
        composeRule.runOnIdle { assertEquals(1, settingsClicks) }
    }

    @Test
    fun settingsTopBarReturnsToChatListWithBackLink() {
        var backClicks = 0

        composeRule.setContent {
            DarkMatterTheme {
                SettingsTopBar(onBackToChats = { backClicks += 1 })
            }
        }

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Chats").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open navigation").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Back to chats").performClick()
        composeRule.runOnIdle { assertEquals(1, backClicks) }
    }

    @Test
    fun loadingScreenUsesDarkMatterBranding() {
        composeRule.setContent {
            DarkMatterTheme {
                LoadingScreen()
            }
        }

        composeRule.onNodeWithText("Loading Dark Matter").assertIsDisplayed()
        composeRule.onNodeWithText("Starting Marmot").assertDoesNotExist()
    }

    @Test
    fun accountHeaderSeparatesSelectorFromQrAction() {
        var selectorClicks = 0
        var qrClicks = 0

        composeRule.setContent {
            DarkMatterTheme {
                SettingsAccountHeader(
                    title = "Main Identity",
                    subtitle = "npub1abc...xyz",
                    seed = "main-identity",
                    pictureUrl = null,
                    onOpenAccountSelector = { selectorClicks += 1 },
                    onOpenQr = { qrClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Switch account").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("My QR code").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Switch account").performClick()
        composeRule.runOnIdle {
            assertEquals(1, selectorClicks)
            assertEquals(0, qrClicks)
        }

        composeRule.onNodeWithContentDescription("My QR code").performClick()
        composeRule.runOnIdle {
            assertEquals(1, selectorClicks)
            assertEquals(1, qrClicks)
        }
    }
}

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
import dev.ipf.darkmatter.ui.theme.DarkMatterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QuickActionFabMenuTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun expandsToNewChatScannerAndCreateGroupActions() {
        var newChatClicks = 0
        var scannerClicks = 0
        var createGroupClicks = 0

        composeRule.setContent {
            DarkMatterTheme {
                var expanded by remember { mutableStateOf(false) }
                QuickActionFabMenu(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    onScanQr = { scannerClicks += 1 },
                    onNewChat = { newChatClicks += 1 },
                    onCreateGroup = { createGroupClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText("New Chat").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Open quick actions").performClick()
        composeRule.onNodeWithContentDescription("Close quick actions").assertIsDisplayed()
        composeRule.onNodeWithText("New Chat").assertIsDisplayed()
        composeRule.onNodeWithText("New Group").assertIsDisplayed()
        composeRule.onNodeWithText("Scan QR Code").assertIsDisplayed()

        composeRule.onNodeWithText("New Chat").performClick()
        composeRule.runOnIdle { assertEquals(1, newChatClicks) }

        composeRule.onNodeWithContentDescription("Open quick actions").performClick()
        composeRule.onNodeWithText("Scan QR Code").performClick()
        composeRule.runOnIdle { assertEquals(1, scannerClicks) }

        composeRule.onNodeWithContentDescription("Open quick actions").performClick()
        composeRule.onNodeWithText("New Group").performClick()
        composeRule.runOnIdle { assertEquals(1, createGroupClicks) }
    }
}

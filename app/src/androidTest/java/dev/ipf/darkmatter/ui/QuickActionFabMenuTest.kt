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
    fun expandsToProfileScannerAndCreateGroupActions() {
        var profileClicks = 0
        var scannerClicks = 0
        var createGroupClicks = 0

        composeRule.setContent {
            DarkMatterTheme {
                var expanded by remember { mutableStateOf(false) }
                QuickActionFabMenu(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    onMyProfile = { profileClicks += 1 },
                    onScanQr = { scannerClicks += 1 },
                    onCreateGroup = { createGroupClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText("My Profile").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Open quick actions").performClick()
        composeRule.onNodeWithContentDescription("Close quick actions").assertIsDisplayed()
        composeRule.onNodeWithText("My Profile").assertIsDisplayed()
        composeRule.onNodeWithText("Scan QR Code").assertIsDisplayed()
        composeRule.onNodeWithText("Create Group").assertIsDisplayed()

        composeRule.onNodeWithText("My Profile").performClick()
        composeRule.runOnIdle { assertEquals(1, profileClicks) }

        composeRule.onNodeWithContentDescription("Open quick actions").performClick()
        composeRule.onNodeWithText("Scan QR Code").performClick()
        composeRule.runOnIdle { assertEquals(1, scannerClicks) }

        composeRule.onNodeWithContentDescription("Open quick actions").performClick()
        composeRule.onNodeWithText("Create Group").performClick()
        composeRule.runOnIdle { assertEquals(1, createGroupClicks) }
    }
}

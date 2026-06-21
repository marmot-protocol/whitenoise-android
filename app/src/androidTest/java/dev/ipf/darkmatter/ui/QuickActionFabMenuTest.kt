package dev.ipf.darkmatter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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

    /**
     * Regression for issue #452: while the quick-action menu is expanded, a tap
     * on the chat-list area must be absorbed by the dismiss scrim — it dismisses
     * the menu and must NOT fall through to the chat row underneath (which would
     * open a chat the user didn't intend to open).
     *
     * `ChatsScreen` is private and needs the full app-state graph to compose, so
     * this test reproduces the exact layering the fix introduces: a clickable
     * backdrop (standing in for a chat row) with the dismiss scrim layered above
     * it, gated on the expanded flag — mirroring `ChatsScreen`'s content Box.
     */
    @Test
    fun expandedScrimAbsorbsOutsideTapAndDoesNotFallThroughToRow() {
        var rowClicks = 0

        composeRule.setContent {
            DarkMatterTheme {
                var expanded by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    // Stand-in for a chat row under the menu: a full-bleed
                    // clickable that "opens a chat" when tapped.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag("chatRow")
                            .clickable { rowClicks += 1 },
                    )
                    // The dismiss scrim from ChatsScreen (issue #452): only
                    // present while expanded, absorbs the tap, and dismisses.
                    if (expanded) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("scrim")
                                .pointerInput(Unit) {
                                    detectTapGestures { expanded = false }
                                },
                        )
                    }
                    QuickActionFabMenu(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        onScanQr = {},
                        onNewChat = {},
                        onCreateGroup = {},
                    )
                }
            }
        }

        // Collapsed: a backdrop tap reaches the row (normal open-chat behavior).
        composeRule.onNodeWithTag("chatRow").performClick()
        composeRule.runOnIdle { assertEquals(1, rowClicks) }

        // Open the menu, then tap outside it (on the scrim covering the row).
        composeRule.onNodeWithContentDescription("Open quick actions").performClick()
        composeRule.onNodeWithText("New Chat").assertIsDisplayed()
        composeRule.onNodeWithTag("scrim").performClick()

        composeRule.runOnIdle {
            // Tap was absorbed by the scrim: the row did NOT open again...
            assertEquals(1, rowClicks)
        }
        // ...and the menu dismissed, so the actions are gone and the scrim with
        // them.
        composeRule.onNodeWithText("New Chat").assertDoesNotExist()
        composeRule.onNodeWithTag("scrim").assertDoesNotExist()
    }
}

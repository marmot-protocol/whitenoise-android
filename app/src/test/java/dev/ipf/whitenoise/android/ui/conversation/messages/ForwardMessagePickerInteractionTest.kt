package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.ui.common.WHITE_NOISE_TOP_BAR_BACK_TAG
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_HEX
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_REF
import dev.ipf.whitenoise.android.ui.share.GROUP_A
import dev.ipf.whitenoise.android.ui.share.GROUP_B
import dev.ipf.whitenoise.android.ui.share.PEER_A
import dev.ipf.whitenoise.android.ui.share.PEER_B
import dev.ipf.whitenoise.android.ui.share.appStateWithDirectChats
import dev.ipf.whitenoise.android.ui.share.applyLocalDirectChat
import dev.ipf.whitenoise.android.ui.share.chatRow
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.profile
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ForwardMessagePickerInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Wide, short screens retain an actual destination and confirmation action at maximum supported text size. */
    @Test
    @Config(sdk = [36], qualifiers = "w780dp-h360dp-land-mdpi")
    fun largeLandscapeKeepsDestinationAndConfirmationReachable() {
        val groupId = "20".repeat(32)
        val peerId = "40".repeat(32)
        val state = appStateWithDirectChats(groupId to peerId, profiles = mutableMapOf(peerId to profile("Alice")))
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                WhiteNoiseTheme {
                    ForwardMessagePickerContent(
                        appState = state,
                        messageCount = 11,
                        attachmentCount = 11,
                        originGroupIdHex = "ff".repeat(32),
                        sourceAccountRef = ACCOUNT_REF,
                        onDismiss = {},
                        onForward = { _, _ -> true },
                    )
                }
            }
        }
        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Alice"))
        composeRule.onNodeWithText("Alice").performClick().assertIsDisplayed()
        val viewport = composeRule.onNodeWithTag("forward.destinations").getUnclippedBoundsInRoot()
        val viewportHeight = (viewport.bottom - viewport.top).value
        org.junit.Assert.assertTrue("A full minimum touch target must fit", viewportHeight >= 48f)
        composeRule.onNodeWithText("Forward to 1 chat").assertIsDisplayed()
    }

    @Test
    fun chatRowRemainsSelectableAndUnselectableAcrossRepeatedTaps() {
        val groupId = "20".repeat(32)
        val peerId = "40".repeat(32)
        val appState =
            appStateWithDirectChats(
                groupId to peerId,
                profiles = mutableMapOf(peerId to profile("Person 1")),
                accounts = listOf(testAccount(ACCOUNT_REF, ACCOUNT_HEX)),
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    ForwardMessagePickerContent(
                        appState = appState,
                        messageCount = 2,
                        attachmentCount = 1,
                        originGroupIdHex = "ff".repeat(32),
                        sourceAccountRef = ACCOUNT_REF,
                        onDismiss = {},
                        onForward = { _, _ -> true },
                    )
                }
            }
        }

        val row = composeRule.onNodeWithText("Person 1")
        repeat(10) { tap ->
            row.performClick()
            if (tap % 2 == 0) row.assertIsSelected() else row.assertIsNotSelected()
        }
    }

    /** The bar's Close leaves the picker even while search is open; only system Back closes the search first. */
    @Test
    fun barCloseDismissesThePickerEvenWithSearchOpen() {
        val state = appStateWithDirectChats("20".repeat(32) to "40".repeat(32))
        var dismissed = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ForwardMessagePickerContent(
                    appState = state,
                    messageCount = 1,
                    attachmentCount = 0,
                    originGroupIdHex = "ff".repeat(32),
                    sourceAccountRef = ACCOUNT_REF,
                    onDismiss = { dismissed++ },
                    onForward = { _, _ -> true },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Search chats").performClick()
        composeRule.onNodeWithTag("forward.searchField").assertExists()
        composeRule.onNodeWithTag(WHITE_NOISE_TOP_BAR_BACK_TAG).performClick()
        composeRule.waitForIdle()
        org.junit.Assert.assertEquals(1, dismissed)
    }

    @Test
    fun searchOpensInTheBarAndCloseClearsItWhileSingleAccountAvatarIsNotAnAction() {
        val groupId = "20".repeat(32)
        val peerId = "40".repeat(32)
        val state = appStateWithDirectChats(groupId to peerId, profiles = mutableMapOf(peerId to profile("Alice")))
        composeRule.setContent {
            WhiteNoiseTheme {
                ForwardMessagePickerContent(
                    appState = state,
                    messageCount = 1,
                    attachmentCount = 0,
                    originGroupIdHex = "ff".repeat(32),
                    sourceAccountRef = ACCOUNT_REF,
                    onDismiss = {},
                    onForward = { _, _ -> true },
                )
            }
        }
        composeRule.onNodeWithTag("forward.selectAll").assertDoesNotExist()
        composeRule.onNodeWithTag(FORWARD_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG).assertHasNoClickAction()
        val avatar = composeRule.onNodeWithTag(FORWARD_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG).getUnclippedBoundsInRoot()
        val title = composeRule.onNodeWithTag("forward.title").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("the sending avatar precedes the title", avatar.right <= title.left)
        org.junit.Assert.assertTrue("avatar keeps its 48dp target", (avatar.right - avatar.left).value >= 48f)
        composeRule.onNodeWithContentDescription("Search chats").performClick()
        composeRule.onNodeWithTag("forward.searchField").performTextInput("missing")
        composeRule.onNodeWithText("Alice").assertDoesNotExist()
        composeRule.onNodeWithTag("forward.selectAll").assertIsNotEnabled()
        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("forward.searchField").assertDoesNotExist()
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithTag("forward.selectAll").assertDoesNotExist()
    }

    @Test
    fun scopedBulkSelectionIsDisabledUntilCompleteAndPreservesOutsideSelections() {
        val selected = androidx.compose.runtime.mutableStateOf(listOf("outside"))
        val complete = androidx.compose.runtime.mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme {
                ForwardVisibleSelectionAction(
                    selected = selected.value,
                    visibleIds = listOf("a", "b"),
                    complete = complete.value,
                    onSelectionChange = { selected.value = it },
                )
            }
        }
        composeRule.onNodeWithTag("forward.selectAll").assertIsNotEnabled()
        composeRule.runOnIdle { complete.value = true }
        composeRule.onNodeWithTag("forward.selectAll").assertIsEnabled().performClick()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(listOf("outside", "a", "b"), selected.value) }
        composeRule.onNodeWithTag("forward.selectAll").performClick()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(listOf("outside"), selected.value) }
    }

    @Test
    fun pinnedBoundaryUsesTheFilteredListAndDisappearsForANonblankQuery() {
        val state =
            emptyAppState(
                profiles =
                    mutableMapOf(
                        PEER_A to profile("Person A"),
                        PEER_B to profile("Person B"),
                    ),
            )
        val controller =
            ChatsController(state, ACCOUNT_REF) { _, _ -> emptyList() }
        controller.applyLocalDirectChat(GROUP_A, ACCOUNT_HEX, PEER_A)
        controller.applyLocalDirectChat(GROUP_B, ACCOUNT_HEX, PEER_B)
        controller.applyChatListRow(
            chatRow(GROUP_A)
                .copy(pinned = true),
        )
        state.attachChatsController(controller)
        composeRule.setContent {
            WhiteNoiseTheme {
                ForwardMessagePickerContent(
                    appState = state,
                    messageCount = 1,
                    attachmentCount = 0,
                    originGroupIdHex = "ff".repeat(32),
                    sourceAccountRef = ACCOUNT_REF,
                    onDismiss = {},
                    onForward = { _, _ -> true },
                )
            }
        }
        composeRule.onNodeWithTag("chat-list-pinned-boundary-divider").assertExists()
        composeRule.onNodeWithContentDescription("Search chats").performClick()
        composeRule.onNodeWithTag("forward.searchField").performTextInput("Person")
        composeRule.onNodeWithText("Person A").assertExists()
        composeRule.onNodeWithText("Person B").assertExists()
        composeRule.onNodeWithTag("chat-list-pinned-boundary-divider").assertDoesNotExist()
    }
}

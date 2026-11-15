package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_HEAD_INPUT_GATE_MILLIS
import dev.ipf.whitenoise.android.ui.common.LocalWindowLayout
import dev.ipf.whitenoise.android.ui.common.WindowLayout
import dev.ipf.whitenoise.android.ui.common.WindowWidthClass
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Two-pane chat layout across the 840dp boundary, through the production frame and shell. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class TwoPaneChatLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** The conversation pane keeps its composition when the list appears or disappears beside it. */
    @Test
    fun crossingTheExpandedWidthKeepsTheConversationPaneComposition() {
        val width = mutableStateOf(EXPANDED_WIDTH)
        val conversationIdentities = mutableListOf<Any>()
        composeRule.setContent {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.fromSize(DpSize(width.value, HEIGHT))) {
                TwoPaneChatFrame(
                    twoPane = LocalWindowLayout.current.widthClass == WindowWidthClass.Expanded,
                    listPane = { Text("list") },
                ) {
                    val identity = remember { Any() }
                    SideEffect {
                        if (conversationIdentities.lastOrNull() !== identity) conversationIdentities += identity
                    }
                    Text("conversation")
                }
            }
        }

        composeRule.onNodeWithTag(TWO_PANE_LIST_TAG).assertIsDisplayed()
        width.value = BELOW_EXPANDED_WIDTH
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TWO_PANE_LIST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("conversation").assertIsDisplayed()
        width.value = EXPANDED_WIDTH
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TWO_PANE_LIST_TAG).assertIsDisplayed()

        assertEquals(1, conversationIdentities.size)
    }

    /** With no chat open, the list sits beside a placeholder; opening a chat keeps the list on screen. */
    @Test
    fun expandedWidthOpensTheChatBesideTheList() {
        val width = mutableStateOf(EXPANDED_WIDTH)
        withMountedShell(width) { fixture, holder, _ ->
            composeRule.onNodeWithTag(TWO_PANE_LIST_TAG).assertIsDisplayed()
            composeRule.onNodeWithTag(CONVERSATION_PANE_PLACEHOLDER_TAG).assertIsDisplayed()
            composeRule.onNodeWithText("Select a chat").assertIsDisplayed()

            openTarget(fixture, holder)

            composeRule.onNodeWithTag(CONVERSATION_PANE_PLACEHOLDER_TAG).assertDoesNotExist()
            composeRule.onNodeWithTag("chat.row.${fixture.target.groupIdHex}").assertIsDisplayed()
        }
    }

    /** System Back leaves the side-by-side chat open; at phone width it closes the chat as before. */
    @Test
    fun systemBackClosesTheConversationOnlyAtCompactWidth() {
        val width = mutableStateOf(EXPANDED_WIDTH)
        withMountedShell(width) { fixture, holder, fallThroughBacks ->
            openTarget(fixture, holder)

            pressBack()
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)
            assertEquals(1, fallThroughBacks())

            width.value = BELOW_EXPANDED_WIDTH
            settle()
            pressBack()
            awaitCondition { holder.selectedChat.value == null }
            assertEquals(1, fallThroughBacks())
        }
    }

    /** Folding or shrinking keeps the open chat and its draft; widening again brings the list back. */
    @Test
    fun crossingTheExpandedWidthKeepsTheOpenChatAndDraft() {
        val width = mutableStateOf(EXPANDED_WIDTH)
        withMountedShell(width) { fixture, holder, _ ->
            openTarget(fixture, holder)
            composer().performTextInput(DRAFT)
            settle()

            width.value = BELOW_EXPANDED_WIDTH
            settle()
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)
            composeRule.onNodeWithTag(TWO_PANE_LIST_TAG).assertDoesNotExist()
            composer().assertTextContains(DRAFT)

            width.value = EXPANDED_WIDTH
            settle()
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)
            composeRule.onNodeWithTag(TWO_PANE_LIST_TAG).assertIsDisplayed()
            composer().assertTextContains(DRAFT)
        }
    }

    /** Mounts the production shell under a test-controlled window width and always releases its jobs. */
    private fun withMountedShell(
        width: MutableState<Dp>,
        exercise: (ReturnFixture, MainShellStateHolder, () -> Int) -> Unit,
    ) {
        val fixture = ReturnFixture()
        val mounted = mutableStateOf(true)
        var fallThroughBacks = 0
        val holder = MainShellStateHolder(fixture.appState, SavedStateHandle())
        val controller =
            holder.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, fixture.appState.runtimeGeneration)
        fixture.appState.setAppInForeground(true)
        composeRule.setContent {
            // Registered before the shell, so it only sees presses that no shell handler consumed.
            BackHandler { fallThroughBacks += 1 }
            if (mounted.value) {
                CompositionLocalProvider(
                    LocalWindowLayout provides WindowLayout.fromSize(DpSize(width.value, HEIGHT)),
                ) {
                    WhiteNoiseTheme { MainShell(appState = fixture.appState, stateHolder = holder) }
                }
            }
        }
        try {
            awaitCondition { controller.items.size == fixture.rows.size && !controller.isLoading }
            composeRule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 500L)
            composeRule.waitForIdle()
            exercise(fixture, holder) { fallThroughBacks }
        } finally {
            composeRule.runOnIdle {
                mounted.value = false
                holder.release()
            }
            composeRule.waitForIdle()
        }
    }

    private fun openTarget(
        fixture: ReturnFixture,
        holder: MainShellStateHolder,
    ) {
        composeRule.onNodeWithTag("chat.row.${fixture.target.groupIdHex}").assertIsDisplayed().performClick()
        awaitCondition { holder.selectedChat.value?.id == fixture.target.groupIdHex }
        settle()
    }

    /** The conversation composer, never a text field inside the list pane. */
    private fun composer() =
        composeRule.onNode(hasSetTextAction() and !hasAnyAncestor(hasTestTag(TWO_PANE_LIST_TAG)))

    private fun pressBack() {
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun settle() {
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    /** Polls only for observable asynchronous shell/controller settlement. */
    private fun awaitCondition(condition: () -> Boolean) {
        composeRule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        val EXPANDED_WIDTH = 840.dp
        val BELOW_EXPANDED_WIDTH = 839.dp
        val HEIGHT = 800.dp
        const val DRAFT = "Draft kept across the fold"
    }
}

package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_HEAD_INPUT_GATE_MILLIS
import dev.ipf.whitenoise.android.ui.common.LocalWindowLayout
import dev.ipf.whitenoise.android.ui.common.WindowLayout
import dev.ipf.whitenoise.android.ui.common.WindowWidthClass
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * Short, wide windows such as a phone held horizontally split the panes evenly after the divider,
     * with the list capped for very wide short windows; taller expanded windows keep the fixed 360dp
     * list (#3866).
     */
    @Test
    fun shortExpandedWindowsBalanceThePanes() {
        val size = mutableStateOf(DpSize(EXPANDED_WIDTH, SHORT_HEIGHT))
        mountFrame(size)
        val expected =
            listOf(
                DpSize(840.dp, 400.dp) to 419.5.dp,
                DpSize(960.dp, 432.dp) to 479.5.dp,
                DpSize(1080.dp, 432.dp) to ShortWindowListMaxWidth,
                DpSize(1920.dp, 400.dp) to ShortWindowListMaxWidth,
                DpSize(840.dp, 480.dp) to TwoPaneListWidth,
                DpSize(1280.dp, 800.dp) to TwoPaneListWidth,
            )
        expected.forEach { (window, listWidth) ->
            size.value = window
            composeRule.waitForIdle()
            val list = composeRule.onNodeWithTag(TWO_PANE_LIST_TAG).getUnclippedBoundsInRoot()
            val conversation = composeRule.onNodeWithTag(FRAME_CONVERSATION_TAG).getUnclippedBoundsInRoot()
            assertEquals("list at $window", listWidth.value, (list.right - list.left).value, PANE_TOLERANCE)
            assertTrue(
                "conversation is never narrower than the list at $window",
                (conversation.right - conversation.left).value >= (list.right - list.left).value - PANE_TOLERANCE,
            )
            assertEquals(
                "panes fill $window",
                window.width.value,
                (conversation.right - list.left).value,
                PANE_TOLERANCE,
            )
        }
    }

    /**
     * Side by side, a camera cutout pads only the pane on its edge: the conversation beside the list
     * must not leave the cutout's width empty before its messages, nor the list beside the conversation.
     */
    @Test
    fun eachPaneAppliesOnlyTheCutoutOnItsOwnEdge() {
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            val window = DpSize(960.dp, 432.dp)
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.fromSize(window)) {
                Box(Modifier.size(window)) {
                    TwoPaneChatFrame(twoPane = true, listPane = { InsetContent(LIST_CONTENT_TAG) }) {
                        InsetContent(CONVERSATION_CONTENT_TAG)
                    }
                }
            }
        }
        val cases = listOf(Insets.of(CUTOUT_PX, 0, 0, 0), Insets.of(0, 0, CUTOUT_PX, 0))
        cases.forEach { cutout ->
            composeRule.runOnUiThread {
                val insets =
                    WindowInsetsCompat
                        .Builder()
                        .setInsets(WindowInsetsCompat.Type.displayCutout(), cutout)
                        .build()
                ViewCompat.dispatchApplyWindowInsets(view.rootView, insets)
            }
            composeRule.waitForIdle()
            val list = composeRule.onNodeWithTag(TWO_PANE_LIST_TAG).getUnclippedBoundsInRoot()
            val listContent = composeRule.onNodeWithTag(LIST_CONTENT_TAG).getUnclippedBoundsInRoot()
            val conversation = composeRule.onNodeWithTag(CONVERSATION_CONTENT_TAG).getUnclippedBoundsInRoot()
            assertEquals("list start at $cutout", cutout.left.toFloat(), (listContent.left - list.left).value, 0.5f)
            assertEquals("list end at $cutout", 0f, (list.right - listContent.right).value, 0.5f)
            assertEquals(
                "conversation start at $cutout",
                list.right.value + 1f,
                conversation.left.value,
                PANE_TOLERANCE,
            )
            assertEquals("conversation end at $cutout", 960f - cutout.right, conversation.right.value, 0.5f)
        }
    }

    /** A pane that pads for horizontal safe-drawing insets, so its bounds show the inset it received. */
    @Composable
    private fun InsetContent(tag: String) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .testTag(tag),
        )
    }

    /** Crossing the 480dp short-window height changes only the proportion, never the conversation. */
    @Test
    fun crossingTheShortHeightKeepsTheConversationPaneComposition() {
        val size = mutableStateOf(DpSize(EXPANDED_WIDTH, SHORT_HEIGHT))
        val identities = mountFrame(size)
        size.value = DpSize(EXPANDED_WIDTH, 480.dp)
        composeRule.waitForIdle()
        size.value = DpSize(EXPANDED_WIDTH, SHORT_HEIGHT)
        composeRule.waitForIdle()
        assertEquals(1, identities.size)
    }

    /** Mounts only the frame inside a window of [size]; returns each conversation-pane composition identity. */
    private fun mountFrame(size: MutableState<DpSize>): List<Any> {
        val identities = mutableListOf<Any>()
        composeRule.setContent {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.fromSize(size.value)) {
                Box(Modifier.size(size.value)) {
                    TwoPaneChatFrame(twoPane = true, listPane = { Text("list") }) {
                        val identity = remember { Any() }
                        SideEffect { if (identities.lastOrNull() !== identity) identities += identity }
                        Box(Modifier.fillMaxSize().testTag(FRAME_CONVERSATION_TAG))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return identities
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
    private fun composer() = composeRule.onNode(hasSetTextAction() and !hasAnyAncestor(hasTestTag(TWO_PANE_LIST_TAG)))

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
        val SHORT_HEIGHT = 400.dp
        const val PANE_TOLERANCE = 1f
        const val FRAME_CONVERSATION_TAG = "frame_conversation"
        const val LIST_CONTENT_TAG = "list_inset_content"
        const val CONVERSATION_CONTENT_TAG = "conversation_inset_content"

        /** Cutout depth in pixels; the test runs at mdpi, so also its width in dp. */
        const val CUTOUT_PX = 59
        const val DRAFT = "Draft kept across the fold"
    }
}

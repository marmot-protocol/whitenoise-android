package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.emptyTimelinePage
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_HEAD_INPUT_GATE_MILLIS
import dev.ipf.whitenoise.android.ui.common.LocalWindowLayout
import dev.ipf.whitenoise.android.ui.common.WindowLayout
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Route behaviour the production shell must keep while its route state moves behind a single owner. Each test drives
 * the real [MainShell] and asserts an outcome, never source text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellRouteContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** A switch to another account closes the open conversation and leaves no open context behind. */
    @Test
    fun anOrdinaryAccountSwitchClosesTheOpenConversation() {
        withMountedShell(mutableStateOf(COMPACT_WIDTH)) { fixture, holder ->
            openTarget(fixture, holder)

            composeRule.runOnIdle { fixture.appState.replaceActiveAccount(OTHER_ACCOUNT_REF) }
            awaitCondition { holder.selectedChat.value == null }

            assertEquals(ConversationOpenContext(), holder.selectedChatOpenContext.value)
            assertEquals(MainSection.Chats.name, holder.sectionState.sectionName)
            assertNull(holder.sectionState.settingsDetailName)
        }
    }

    /**
     * A signed-out moment, as a wipe passes through, closes the open conversation at once, and the same account
     * coming back does not reopen it.
     */
    @Test
    fun aSignedOutMomentClosesTheConversationForGood() {
        withMountedShell(mutableStateOf(COMPACT_WIDTH)) { fixture, holder ->
            openTarget(fixture, holder)

            composeRule.runOnIdle { fixture.appState.replaceActiveAccount(null) }
            awaitCondition { holder.selectedChat.value == null }
            assertEquals(ConversationOpenContext(), holder.selectedChatOpenContext.value)
            assertEquals(emptyList<ConversationController>(), fixture.appState.attachedConversationControllers())

            composeRule.runOnIdle { fixture.appState.replaceActiveAccount(ConversationTimelineTestIds.ACCOUNT_REF) }
            settle()
            assertNull(holder.selectedChat.value)
            assertEquals(MainSection.Chats.name, holder.sectionState.sectionName)
        }
    }

    /** An open Settings screen returns to Chats in the signed-out moment and stays there on the next account. */
    @Test
    fun aSignedOutMomentLeavesSettingsForChatsOnTheNextAccount() {
        withMountedShell(mutableStateOf(COMPACT_WIDTH)) { fixture, holder ->
            composeRule.runOnIdle {
                holder.sectionState.sectionName = MainSection.Settings.name
                holder.sectionState.settingsDetailName = SettingsDetail.Appearance.name
            }
            settle()
            assertEquals(SettingsDetail.Appearance.name, holder.sectionState.settingsDetailName)

            composeRule.runOnIdle { fixture.appState.replaceActiveAccount(null) }
            awaitCondition { holder.sectionState.sectionName == MainSection.Chats.name }
            assertNull(holder.sectionState.settingsDetailName)

            composeRule.runOnIdle { fixture.appState.replaceActiveAccount(OTHER_ACCOUNT_REF) }
            settle()
            assertEquals(MainSection.Chats.name, holder.sectionState.sectionName)
            assertNull(holder.sectionState.settingsDetailName)
        }
    }

    /** Folding and unfolding keeps the one attached conversation controller, not a rebuilt copy. */
    @Test
    fun crossingTheExpandedWidthKeepsTheSameConversationController() {
        val width = mutableStateOf(EXPANDED_WIDTH)
        withMountedShell(width) { fixture, holder ->
            openTarget(fixture, holder)
            val opened = fixture.appState.attachedConversationControllers().single()

            width.value = COMPACT_WIDTH
            settle()
            assertSame(opened, fixture.appState.attachedConversationControllers().single())
            composeRule.onNodeWithTag(TWO_PANE_LIST_TAG).assertDoesNotExist()

            width.value = EXPANDED_WIDTH
            settle()
            assertSame(opened, fixture.appState.attachedConversationControllers().single())
            composeRule.onNodeWithTag(TWO_PANE_LIST_TAG).assertIsDisplayed()
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)
        }
    }

    /**
     * Beside the list, tapping another chat keeps the open conversation on screen while the tapped one is still
     * preparing: the pane never falls back to its placeholder, and the selection moves once the new one is ready.
     */
    @Test
    fun besideTheListTheOpenConversationStaysWhileTheTappedOnePrepares() {
        val secondOpenMayAnswer = CompletableDeferred<Unit>()
        val fixture =
            ReturnFixture(
                timelineScripts = List(2) { ScriptedConversationTimelineSubscription(emptyTimelinePage()) },
                distinctGroupsPerRow = true,
                beforeTimelineOpen = { index -> if (index == 1) secondOpenMayAnswer.await() },
            )
        try {
            withMountedShell(mutableStateOf(EXPANDED_WIDTH), fixture) { _, holder ->
                openTarget(fixture, holder)
                val next = fixture.rows.first()

                // Hold the clock so the probe lands inside the pending-open bound.
                composeRule.mainClock.autoAdvance = false
                composeRule.onNodeWithTag("chat.row.${next.groupIdHex}").assertIsDisplayed().performClick()
                composeRule.mainClock.advanceTimeBy(PENDING_OPEN_PROBE_MILLIS)
                shadowOf(Looper.getMainLooper()).idle()

                composeRule.onNodeWithTag(CONVERSATION_PANE_PLACEHOLDER_TAG).assertDoesNotExist()
                conversationComposer().assertIsDisplayed()
                assertNotEquals(next.groupIdHex, holder.selectedChat.value?.id)

                secondOpenMayAnswer.complete(Unit)
                composeRule.mainClock.autoAdvance = true
                awaitCondition { holder.selectedChat.value?.id == next.groupIdHex }
                settle()
                composeRule.onNodeWithTag(CONVERSATION_PANE_PLACEHOLDER_TAG).assertDoesNotExist()
                conversationComposer().assertIsDisplayed()
            }
        } finally {
            // A reader left suspended would hold a shared IO worker for every later test.
            secondOpenMayAnswer.complete(Unit)
        }
    }

    /** The conversation composer, never a text field inside the list pane. */
    private fun conversationComposer(): SemanticsNodeInteraction {
        val outsideTheListPane = !hasAnyAncestor(hasTestTag(TWO_PANE_LIST_TAG))
        return composeRule.onNode(hasSetTextAction() and outsideTheListPane)
    }

    /** Mounts the production shell under a test-controlled window width and always releases its jobs. */
    private fun withMountedShell(
        width: MutableState<Dp>,
        fixture: ReturnFixture = ReturnFixture(),
        exercise: (ReturnFixture, MainShellStateHolder) -> Unit,
    ) {
        val mounted = mutableStateOf(true)
        val holder = MainShellStateHolder(fixture.appState, SavedStateHandle())
        val controller =
            holder.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, fixture.appState.runtimeGeneration)
        fixture.appState.setAppInForeground(true)
        composeRule.setContent {
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
            exercise(fixture, holder)
        } finally {
            composeRule.runOnIdle {
                mounted.value = false
                holder.release()
            }
            composeRule.waitForIdle()
        }
    }

    /** Opens the fixture's target chat from its list row and waits for the route to settle. */
    private fun openTarget(
        fixture: ReturnFixture,
        holder: MainShellStateHolder,
    ) {
        composeRule.onNodeWithTag("chat.row.${fixture.target.groupIdHex}").assertIsDisplayed().performClick()
        awaitCondition { holder.selectedChat.value?.id == fixture.target.groupIdHex }
        settle()
    }

    /** Lets route transitions and their one-frame retention finish. */
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
        val COMPACT_WIDTH = 839.dp
        val HEIGHT = 800.dp
        const val OTHER_ACCOUNT_REF = "other-contract-account"

        /** Well inside the 300 ms pending-open bound, so the tapped chat is still being prepared. */
        const val PENDING_OPEN_PROBE_MILLIS = 100L
    }
}

/** Uses the same private account setter as the mounted settings isolation tests. */
private fun WhiteNoiseAppState.replaceActiveAccount(accountRef: String?) {
    WhiteNoiseAppState::class.java
        .getDeclaredMethod("setActiveAccountRef", String::class.java)
        .apply { isAccessible = true }
        .invoke(this, accountRef)
}

/** Reads attached route controllers under the same lock used by production state. */
@Suppress("UNCHECKED_CAST")
internal fun WhiteNoiseAppState.attachedConversationControllers(): List<ConversationController> {
    val lock =
        WhiteNoiseAppState::class.java
            .getDeclaredField("conversationControllerLock")
            .apply { isAccessible = true }
            .get(this)
            .let(::requireNotNull)
    val controllers =
        WhiteNoiseAppState::class.java
            .getDeclaredField("conversationControllers")
            .apply { isAccessible = true }
            .get(this) as Set<ConversationController>
    return synchronized(lock) { controllers.toList() }
}

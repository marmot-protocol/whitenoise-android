package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    /** Asserts every route audit this test drove found a well-formed stack and a matching saved section. */
    @get:Rule
    val routeAudit = ShellRouteAuditRule()

    private val shell = MountedContractShell(composeRule)

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
                holder.restoreSection(MainSection.Settings, SettingsDetail.Appearance)
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
     * Beside the list, tapping another chat clears the selection at once but keeps the open conversation's message
     * on screen while the tapped one is still preparing. The tapped chat becomes the selection, and its message
     * replaces the first one, once it is ready.
     */
    @Test
    fun besideTheListTheOpenConversationStaysWhileTheTappedOnePrepares() {
        val secondOpenReached = CompletableDeferred<Unit>()
        val secondOpenMayAnswer = CompletableDeferred<Unit>()
        val nextGroup = ReturnFixture(distinctGroupsPerRow = true).rows.first().groupIdHex
        val fixture =
            ReturnFixture(
                timelineScripts =
                    listOf(
                        messageScript(ConversationTimelineTestIds.GROUP_ID, "ab", OPEN_CHAT_MESSAGE),
                        messageScript(nextGroup, "cd", TAPPED_CHAT_MESSAGE),
                    ),
                distinctGroupsPerRow = true,
                beforeTimelineOpen = { index ->
                    if (index == 1) {
                        secondOpenReached.complete(Unit)
                        secondOpenMayAnswer.await()
                    }
                },
            )
        try {
            withMountedShell(mutableStateOf(EXPANDED_WIDTH), fixture) { _, holder ->
                openTarget(fixture, holder)
                awaitCondition { composed(OPEN_CHAT_MESSAGE) == 1 }

                // Hold the clock so the probe lands inside the pending-open bound.
                composeRule.mainClock.autoAdvance = false
                composeRule.onNodeWithTag("chat.row.$nextGroup").assertIsDisplayed().performClick()
                composeRule.mainClock.advanceTimeBy(PENDING_OPEN_PROBE_MILLIS)
                awaitHeldClock { secondOpenReached.isCompleted }

                // The tap retires the open conversation at once, so nothing is selected while the tapped chat
                // prepares. What keeps the pane filled is the retired conversation, still composed.
                assertNull(holder.selectedChat.value)
                assertEquals(1, composed(OPEN_CHAT_MESSAGE))
                assertEquals(0, composed(TAPPED_CHAT_MESSAGE))
                composeRule.onNodeWithTag(CONVERSATION_PANE_PLACEHOLDER_TAG).assertDoesNotExist()
                conversationComposer().assertIsDisplayed()

                secondOpenMayAnswer.complete(Unit)
                composeRule.mainClock.autoAdvance = true
                awaitCondition { holder.selectedChat.value?.id == nextGroup }
                awaitCondition { composed(TAPPED_CHAT_MESSAGE) == 1 }
                settle()
                assertEquals(0, composed(OPEN_CHAT_MESSAGE))
                composeRule.onNodeWithTag(CONVERSATION_PANE_PLACEHOLDER_TAG).assertDoesNotExist()
                conversationComposer().assertIsDisplayed()
            }
        } finally {
            // A reader left suspended would hold a shared IO worker for every later test.
            secondOpenMayAnswer.complete(Unit)
        }
    }

    /** A one-message timeline for [groupIdHex] whose only message reads [text]. */
    private fun messageScript(
        groupIdHex: String,
        idByte: String,
        text: String,
    ): ScriptedConversationTimelineSubscription {
        val record = timelineRecord(idByte.repeat(32), 1uL).copy(groupIdHex = groupIdHex, plaintext = text)
        return ScriptedConversationTimelineSubscription(timelinePage(record))
    }

    /** How many composed nodes outside the list pane carry [text], so a list preview can never count. */
    private fun composed(text: String): Int {
        val outsideTheListPane = !hasAnyAncestor(hasTestTag(TWO_PANE_LIST_TAG))
        val carriers = composeRule.onAllNodes(hasText(text, substring = true) and outsideTheListPane, true)
        return carriers.fetchSemanticsNodes().size
    }

    /** Waits in real time for [condition] without moving the held test clock. */
    private fun awaitHeldClock(condition: () -> Boolean) {
        val deadline = System.nanoTime() + HELD_CLOCK_WAIT_NANOS
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(HELD_CLOCK_POLL_MILLIS)
        }
        assertTrue("the tapped chat never asked for its timeline", condition())
    }

    /** The conversation composer, never a text field inside the list pane. */
    private fun conversationComposer(): SemanticsNodeInteraction {
        val outsideTheListPane = !hasAnyAncestor(hasTestTag(TWO_PANE_LIST_TAG))
        return composeRule.onNode(hasSetTextAction() and outsideTheListPane)
    }

    /** Mounts the production shell at [width] for [exercise]. */
    private fun withMountedShell(
        width: MutableState<Dp>,
        fixture: ReturnFixture = ReturnFixture(),
        exercise: (ReturnFixture, MainShellStateHolder) -> Unit,
    ) = shell.withMountedShell(width, fixture, exercise)

    /** Opens the fixture's target chat from its list row. */
    private fun openTarget(
        fixture: ReturnFixture,
        holder: MainShellStateHolder,
    ) = shell.openTarget(fixture, holder)

    /** Lets route transitions finish. */
    private fun settle() = shell.settle()

    /** Waits for asynchronous shell settlement. */
    private fun awaitCondition(condition: () -> Boolean) = shell.awaitCondition(condition)

    private companion object {
        val EXPANDED_WIDTH = 661.dp
        val COMPACT_WIDTH = 660.dp
        const val OTHER_ACCOUNT_REF = "other-contract-account"

        /** Well inside the 300 ms pending-open bound, so the tapped chat is still being prepared. */
        const val PENDING_OPEN_PROBE_MILLIS = 100L
        const val OPEN_CHAT_MESSAGE = "open chat only message"
        const val TAPPED_CHAT_MESSAGE = "tapped chat only message"
        const val HELD_CLOCK_WAIT_NANOS = 10_000_000_000L
        const val HELD_CLOCK_POLL_MILLIS = 10L
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

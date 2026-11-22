package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.ipf.whitenoise.android.notifications.setAppLockScreenVisibleForTest
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.group.ChatDetailsDestination
import dev.ipf.whitenoise.android.ui.group.GROUP_RELAYS_SCREEN_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The app lock and an account switch as a reader would see them, frame by frame with the clock held: from the first
 * frame composed after the boundary, nothing of the open conversation, its details or a chat-details screen above it
 * is composed. Each level is shown before the boundary, so its absence afterwards is the boundary's doing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellBoundaryFrameSampleContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts every route audit this test drove found a well-formed stack and a matching saved section. */
    @get:Rule
    val routeAudit = ShellRouteAuditRule()

    private val shell = MountedContractShell(composeRule)
    private val app = MountedContractApp(composeRule)

    /** No frame after the lock shows composes the open conversation's message. */
    @Test
    fun noFrameUnderTheLockComposesTheConversation() {
        app.withMountedApp(messageFixture()) { fixture, holder ->
            app.openTarget(fixture, holder)
            app.awaitCondition { composed(hasText(MESSAGE, substring = true)) }

            val lock = { fixture.appState.setAppLockScreenVisibleForTest(true) }
            val leaked = framesAfter(lock) { conversationComposed() }

            assertEquals("frames under the lock that composed the conversation", emptyList<Int>(), leaked)
            app.unlock(fixture.appState)
        }
    }

    /** No frame after the lock shows composes chat details. */
    @Test
    fun noFrameUnderTheLockComposesChatDetails() {
        app.withMountedApp(messageFixture()) { fixture, holder ->
            app.openTarget(fixture, holder)
            openDetails(app::awaitCondition)

            val leaked = framesAfter({ fixture.appState.setAppLockScreenVisibleForTest(true) }) { detailsComposed() }

            assertEquals("frames under the lock that composed chat details", emptyList<Int>(), leaked)
            app.unlock(fixture.appState)
        }
    }

    /** No frame after the lock shows composes the chat-details screen open above the conversation. */
    @Test
    fun noFrameUnderTheLockComposesALiftedChatDetailsScreen() {
        app.withMountedApp(messageFixture()) { fixture, holder ->
            app.openTarget(fixture, holder)
            openRelays(app::awaitCondition)

            val leaked = framesAfter({ fixture.appState.setAppLockScreenVisibleForTest(true) }) { relaysComposed() }

            assertEquals("frames under the lock that composed the relays screen", emptyList<Int>(), leaked)
            app.unlock(fixture.appState)
        }
    }

    /**
     * No frame after an account switch composes the previous account's chat-details screen, its details or its
     * conversation, and the route stack is the chat list from the first frame on.
     */
    @Test
    fun noFrameAfterAnAccountSwitchComposesALiftedChatDetailsScreen() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH), messageFixture()) { fixture, holder ->
            shell.openTarget(fixture, holder)
            openRelays(shell::awaitCondition)

            val stacks = mutableListOf<List<ShellRoute>>()
            val leaked =
                framesAfter({ fixture.appState.replaceActiveAccount(OTHER_ACCOUNT_REF) }) {
                    stacks += holder.shellNavigator.stack
                    relaysComposed() || detailsComposed() || conversationComposed()
                }

            assertEquals("frames after the switch that composed the previous chat", emptyList<Int>(), leaked)
            val first = shell.firstCommitFor(OTHER_ACCOUNT_REF)
            assertEquals("the first composition after the switch composed only the list", CHATS_ROOT, first.stack)
            assertEquals("the stack is the chat list from the first frame", List(FRAMES_WATCHED) { CHATS_ROOT }, stacks)
        }
    }

    /**
     * The previous account's Settings screen is not in the first composition committed after a switch. Sampling
     * frames alone cannot tell this apart from a reset done by an effect just before the first sample, so the
     * assertion is on the first committed composition with the new account active.
     */
    @Test
    fun theFirstCompositionAfterAnAccountSwitchHasNoSettingsScreen() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH), messageFixture()) { fixture, holder ->
            composeRule.runOnIdle { holder.restoreSection(MainSection.Settings, SettingsDetail.Appearance) }
            shell.awaitCondition { composed(hasTestTag(APPEARANCE_COLORS_TAG)) }

            val leaked =
                framesAfter({ fixture.appState.replaceActiveAccount(OTHER_ACCOUNT_REF) }) {
                    composed(hasTestTag(APPEARANCE_COLORS_TAG))
                }

            val first = shell.firstCommitFor(OTHER_ACCOUNT_REF)
            assertEquals("the first composition after the switch composed only the list", CHATS_ROOT, first.stack)
            assertEquals("frames after the switch that composed the previous Settings screen", emptyList<Int>(), leaked)
        }
    }

    /**
     * The conversation shell keys its content by chat, so a fresh open context for the chat that stays the root (a
     * notification re-land) replaces the content in its one slot and never leaves an outgoing copy of the same chat
     * that could close the screen the live copy shows. The relays screen stays composed on every frame.
     */
    @Test
    fun aSameChatReLandKeepsTheLiftedChatDetailsScreenOnEveryFrame() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH), messageFixture()) { fixture, holder ->
            shell.openTarget(fixture, holder)
            openRelays(shell::awaitCondition)
            val root = requireNotNull(conversationRoot(holder.shellNavigator.stack))
            val id = RELAND_ID
            val fresh = ConversationOpenContext(notificationOpenRequestId = id, notificationRouteTraceRequestId = id)

            val relanded = { holder.dispatchShellNav(ShellNavEvent.CommitNotificationRoute(root.copy(open = fresh))) }
            val gone = framesAfter(relanded) { !relaysComposed() }

            assertEquals("frames after the re-land without the relays screen", emptyList<Int>(), gone)
            assertEquals(fresh, holder.selectedChatOpenContext.value)
            val relays = ShellRoute.ChatDetailsScreen(ChatDetailsDestination.Relays)
            assertEquals(relays, holder.shellNavigator.stack.last())
        }
    }

    /**
     * Runs [boundary] on the main thread with the clock held, then composes [FRAMES_WATCHED] frames one at a time and
     * returns the numbers of those in which [leaks] held.
     */
    private fun framesAfter(
        boundary: () -> Unit,
        leaks: () -> Boolean,
    ): List<Int> = composeRule.framesAfter(FRAMES_WATCHED, boundary, leaks)

    /** Opens chat details from the conversation header and checks it is on screen. */
    private fun openDetails(await: (() -> Boolean) -> Unit) {
        await { composed(hasText(MESSAGE, substring = true)) }
        composeRule.onNode(conversationHeader()).performClick()
        await { detailsComposed() }
    }

    /** Opens chat details and then its relays screen, and checks the relays screen is on screen. */
    private fun openRelays(await: (() -> Boolean) -> Unit) {
        openDetails(await)
        composeRule.onNode(hasTestTag(RELAYS_ENTRY_TAG)).performScrollTo().performClick()
        await { relaysComposed() }
    }

    /** True while the conversation's message is composed. */
    private fun conversationComposed(): Boolean = composed(hasText(MESSAGE, substring = true))

    /** True while the chat-details list is composed. */
    private fun detailsComposed(): Boolean = composed(hasTestTag(DETAILS_TAG))

    /** True while the relays screen is composed. */
    private fun relaysComposed(): Boolean = composed(hasTestTag(GROUP_RELAYS_SCREEN_TAG))

    /** True while any node satisfies [matcher], counted in the unmerged tree so no parent can hide one. */
    private fun composed(matcher: SemanticsMatcher): Boolean {
        val nodes = composeRule.onAllNodes(matcher, useUnmergedTree = true)
        return nodes.fetchSemanticsNodes().isNotEmpty()
    }

    /** The conversation header, the one clickable heading on the conversation, which opens its details. */
    private fun conversationHeader() = hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /** A conversation whose one message tells it apart, with one ready script per open. */
    private fun messageFixture(): ReturnFixture {
        val message =
            timelineRecord("9a".repeat(32), 1uL)
                .copy(groupIdHex = ConversationTimelineTestIds.GROUP_ID, plaintext = MESSAGE)
        return ReturnFixture(
            timelineScripts = List(OPEN_SCRIPTS) { ScriptedConversationTimelineSubscription(timelinePage(message)) },
        )
    }

    private companion object {
        val COMPACT_WIDTH = 839.dp
        const val MESSAGE = "frame sampled boundary only message"
        const val DETAILS_TAG = "chat_info.list"
        const val RELAYS_ENTRY_TAG = "chat_info.relays"
        const val OTHER_ACCOUNT_REF = "other-frame-sampled-account"
        const val OPEN_SCRIPTS = 3
        const val RELAND_ID = 4_004L
        const val APPEARANCE_COLORS_TAG = "appearance.colors.group"

        /** Longer than any route exit motion, so retained exit content would be seen. */
        const val FRAMES_WATCHED = 60
    }
}

/**
 * Runs [boundary] on the main thread with the clock held and delivers its writes before the next frame, as the
 * runtime does for a main-thread write, then composes [frames] frames one at a time and returns the numbers of those
 * in which [leaks] held. The clock is released again afterwards.
 */
internal fun AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>.framesAfter(
    frames: Int,
    boundary: () -> Unit,
    leaks: () -> Boolean,
): List<Int> {
    mainClock.autoAdvance = false
    try {
        runOnUiThread {
            boundary()
            Snapshot.sendApplyNotifications()
        }
        return (1..frames).filter {
            mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
            leaks()
        }
    } finally {
        mainClock.autoAdvance = true
    }
}

/** Uses the same private account setter as the other mounted account-switch contract tests. */
private fun WhiteNoiseAppState.replaceActiveAccount(accountRef: String?) {
    WhiteNoiseAppState::class.java
        .getDeclaredMethod("setActiveAccountRef", String::class.java)
        .apply { isAccessible = true }
        .invoke(this, accountRef)
}

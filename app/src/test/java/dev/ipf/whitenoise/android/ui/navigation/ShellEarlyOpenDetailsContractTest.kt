package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.notifications.NotificationScenario
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationGroupStateSubscription
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * A notification for the other local account opens its conversation before the account switch lands. This pins
 * what the shell shows when the reader opens that conversation's details inside that window and the switch then
 * settles, with the switch held and released by the test instead of raced on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ShellEarlyOpenDetailsContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Asserts every route audit this test drove found a well-formed stack and a matching saved section. */
    @get:Rule
    val routeAudit = ShellRouteAuditRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gate = AccountRouteOrderGate(preloadFinishesFirst = true)
    private val activationHold = AccountActivationHold()
    private val appState =
        NotificationRouteHarness.appState(
            context,
            NotificationRouteHarness.holdingActivation(
                NotificationRouteHarness.fakeMarmot(gate, failBroadBind = false),
                activationHold,
            ),
        )
    private val holder = MainShellStateHolder(appState, SavedStateHandle())
    private val routed = NotificationRouteHarness.routedTarget(TARGET_ACCOUNT)
    private val inbound = mutableStateOf<NotificationTarget?>(routed.notificationTarget)
    private val inboundRequestId = mutableLongStateOf(routed.notificationRequestId)

    /** Frees every held engine read and the shell's process-scoped work so nothing outlives the case. */
    @After
    fun tearDown() {
        gate.releasePreload.countDown()
        gate.releaseActivation.countDown()
        activationHold.releaseAll()
        composeRule.runOnIdle { holder.release() }
        appState.mutationsScope.cancel()
    }

    /**
     * The other account's conversation is open and its switch is still pending when the reader opens details.
     * Details stays an undrawn protected surface until that account is active, and when the switch lands the
     * conversation is rebuilt on a new controller with details closed, keeping its selection and open context.
     */
    @Test
    fun detailsOpenedBeforeTheSwitchLandsCloseWhenTheConversationIsRebuilt() {
        mountShellWithTheTap()
        awaitCondition("the conversation never opened ahead of the switch") { openedAheadOfTheSwitch() }
        assertEquals(NotificationRouteHarness.SOURCE_ACCOUNT, appState.activeAccountRef)
        val earlyContext = holder.selectedChatOpenContext.value
        val earlyController = appState.attachedConversationControllers().single()
        assertEquals(TARGET_ACCOUNT, earlyController.boundAccountRef)

        composeRule.onNode(conversationHeader()).performClick()
        awaitCondition("details never replaced the transcript") { !transcriptShown() }
        assertFalse("details must not draw for an account that is not active yet", tagShown(DETAILS_TAG))
        assertEquals(SHARED_GROUP, holder.selectedChat.value?.id)
        assertSame(earlyController, appState.attachedConversationControllers().single())

        activationHold.read(0).release.countDown()
        awaitCondition("the switch never landed on a rebuilt chat") { landedOnAnotherController(earlyController) }
        repeat(SETTLE_PUMPS) { pump() }

        assertFalse("details must stay closed after the switch lands", tagShown(DETAILS_TAG))
        assertTrue(transcriptShown())
        assertEquals(1, composed(hasText(TARGET_ACCOUNT_MESSAGE, substring = true)))
        assertEquals(0, composed(hasText(SOURCE_ACCOUNT_MESSAGE, substring = true)))
        assertEquals(SHARED_GROUP, holder.selectedChat.value?.id)
        assertEquals(earlyContext, holder.selectedChatOpenContext.value)
        assertEquals(MainSection.Chats.name, holder.sectionState.sectionName)
        val landed = appState.attachedConversationControllers().single()
        assertNotSame(earlyController, landed)
        assertEquals(TARGET_ACCOUNT, landed.boundAccountRef)
    }

    /**
     * What is composed while the switch is still held. The early conversation composes the target account's message
     * under the source account, and with details open no node carries text, a description or an input, for as long
     * as the switch stays pending. Back returns to that conversation on the same controller.
     */
    @Test
    fun whileTheSwitchIsHeldDetailsComposeNothingReadable() {
        mountShellWithTheTap()
        awaitCondition("the conversation never opened ahead of the switch") { openedAheadOfTheSwitch() }
        val earlyController = appState.attachedConversationControllers().single()
        assertEquals(NotificationRouteHarness.SOURCE_ACCOUNT, appState.activeAccountRef)
        assertEquals(1, composed(hasText(TARGET_ACCOUNT_MESSAGE, substring = true)))
        assertEquals(0, composed(hasText(SOURCE_ACCOUNT_MESSAGE, substring = true)))
        assertTrue(composed(readable()) > 0)

        composeRule.onNode(conversationHeader()).performClick()
        awaitCondition("details never replaced the transcript") { !transcriptShown() }
        val readableWhileHeld =
            List(HELD_PUMPS) {
                pump()
                composed(readable())
            }

        assertEquals("readable nodes at each held sample", List(HELD_PUMPS) { 0 }, readableWhileHeld)
        assertEquals(NotificationRouteHarness.SOURCE_ACCOUNT, appState.activeAccountRef)
        assertEquals(SHARED_GROUP, holder.selectedChat.value?.id)

        Espresso.pressBack()
        awaitCondition("Back never returned to the held conversation") { transcriptShown() }
        assertEquals(1, composed(hasText(TARGET_ACCOUNT_MESSAGE, substring = true)))
        assertEquals(0, composed(hasText(SOURCE_ACCOUNT_MESSAGE, substring = true)))
        assertEquals(NotificationRouteHarness.SOURCE_ACCOUNT, appState.activeAccountRef)
        assertSame(earlyController, appState.attachedConversationControllers().single())
    }

    /**
     * A second tap for the same account and chat arrives while the first tap's switch is still held. The newer
     * request takes the route over: the open conversation carries its request, on a rebuilt controller, and when
     * the account lands the conversation is still open. The older request does not turn the landing into a reset.
     */
    @Test
    fun aRepeatTapWhileTheSwitchIsHeldTakesOverAndStillLandsOpen() {
        mountShellWithTheTap()
        awaitCondition("the conversation never opened ahead of the switch") { openedAheadOfTheSwitch() }
        val earlyController = appState.attachedConversationControllers().single()
        assertEquals(routed.notificationRequestId, holder.selectedChatOpenContext.value.notificationRouteTraceRequestId)

        composeRule.runOnIdle {
            inboundRequestId.longValue = REPEAT_REQUEST_ID
            inbound.value = routed.notificationTarget
        }
        awaitCondition("the repeat tap never took the route over") { routeCarries(REPEAT_REQUEST_ID) }
        assertEquals(NotificationRouteHarness.SOURCE_ACCOUNT, appState.activeAccountRef)
        assertEquals(SHARED_GROUP, holder.selectedChat.value?.id)
        assertEquals(TARGET_ACCOUNT, holder.selectedChatOpenContext.value.pinnedAccountRef)
        assertNotSame(earlyController, appState.attachedConversationControllers().single())

        activationHold.releaseAll()
        awaitCondition("the switch never landed") { appState.activeAccountRef == TARGET_ACCOUNT && transcriptShown() }
        repeat(SETTLE_PUMPS) { pump() }

        assertTrue(transcriptShown())
        assertEquals(SHARED_GROUP, holder.selectedChat.value?.id)
        assertTrue(routeCarries(REPEAT_REQUEST_ID))
        assertEquals(TARGET_ACCOUNT, appState.attachedConversationControllers().single().boundAccountRef)
    }

    /**
     * The same tap repeated after the switch has settled is a different case from a repeat during the held switch:
     * the conversation is already open on the active account, and it keeps its controller.
     */
    @Test
    fun aRepeatTapAfterTheSwitchSettlesKeepsTheSameController() {
        mountShellWithTheTap()
        activationHold.releaseAll()
        awaitCondition("the switch never landed") { appState.activeAccountRef == TARGET_ACCOUNT && transcriptShown() }
        repeat(SETTLE_PUMPS) { pump() }
        val settledController = appState.attachedConversationControllers().single()
        val settledOpenRequestId = holder.selectedChatOpenContext.value.notificationOpenRequestId

        composeRule.runOnIdle {
            inboundRequestId.longValue = REPEAT_REQUEST_ID
            inbound.value = routed.notificationTarget
        }
        awaitCondition("the repeat tap was never handled") { inbound.value == null }
        repeat(SETTLE_PUMPS) { pump() }
        assertTrue(routeCarries(REPEAT_REQUEST_ID))

        val relanded = holder.selectedChatOpenContext.value
        assertTrue("a re-land uses a fresh open request", relanded.notificationOpenRequestId > settledOpenRequestId)
        assertNull("a settled re-land carries no pinned account", relanded.pinnedAccountRef)
        assertTrue(transcriptShown())
        assertEquals(SHARED_GROUP, holder.selectedChat.value?.id)
        assertEquals(TARGET_ACCOUNT, appState.activeAccountRef)
        assertSame(settledController, appState.attachedConversationControllers().single())
        assertEquals(1, composed(hasText(TARGET_ACCOUNT_MESSAGE, substring = true)))
    }

    /**
     * The held switch lands while the open conversation is still pinned to the arriving account and still carries a
     * notification trace, but no longer the trace of the request whose early open is active: that open was superseded
     * by another route into the same chat. The owner rebind keeps such a pinned route, so only the shell's account
     * switch event takes it down, and master resets to the chat list with nothing of the conversation composed.
     */
    @Test
    fun aSupersededEarlyOpenTakesThePrivacyResetWhenTheSwitchLands() {
        mountShellWithTheTap()
        awaitCondition("the conversation never opened ahead of the switch") { openedAheadOfTheSwitch() }
        val early = holder.selectedChatOpenContext.value
        val superseded = early.copy(notificationRouteTraceRequestId = SUPERSEDING_TRACE_ID)
        composeRule.runOnIdle {
            val root = requireNotNull(conversationRoot(holder.shellNavigator.stack))
            holder.dispatchShellNav(ShellNavEvent.CommitNotificationRoute(root.copy(open = superseded)))
        }
        awaitCondition("the route never took the superseding trace") { routeCarries(SUPERSEDING_TRACE_ID) }
        // The conversation is on screen, pinned to the arriving account, before the switch lands.
        assertEquals(TARGET_ACCOUNT, holder.selectedChatOpenContext.value.pinnedAccountRef)
        assertEquals(NotificationRouteHarness.SOURCE_ACCOUNT, appState.activeAccountRef)
        assertTrue(transcriptShown())
        assertEquals(1, composed(hasText(TARGET_ACCOUNT_MESSAGE, substring = true)))

        activationHold.releaseAll()
        awaitCondition("the switch never landed") { appState.activeAccountRef == TARGET_ACCOUNT }
        repeat(SETTLE_PUMPS) { pump() }

        assertEquals(CHATS_ROOT, holder.shellNavigator.stack)
        assertNull(holder.selectedChat.value)
        assertFalse("nothing of the superseded conversation stays composed", transcriptShown())
        assertEquals(0, composed(hasText(TARGET_ACCOUNT_MESSAGE, substring = true)))
        assertEquals(0, composed(hasText(SOURCE_ACCOUNT_MESSAGE, substring = true)))
    }

    /** True once the open conversation was committed by notification request [requestId]. */
    private fun routeCarries(requestId: Long): Boolean {
        val committedBy = holder.selectedChatOpenContext.value.notificationRouteTraceRequestId
        return committedBy == requestId
    }

    /** How many composed nodes satisfy [matcher], counted in the unmerged tree so no parent can hide one. */
    private fun composed(matcher: SemanticsMatcher): Int {
        val matching = composeRule.onAllNodes(matcher, useUnmergedTree = true)
        return matching.fetchSemanticsNodes().size
    }

    /** Any node a reader or a screen reader could take content from: text, a description or a text input. */
    private fun readable(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) or
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) or
            SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)

    /** Mounts the production shell with the other account's tap pending and its broad chat-list bind unheld. */
    private fun mountShellWithTheTap() {
        gate.releaseActivation.countDown()
        appState.liveSubscriptionOverrides.conversation = conversations()
        appState.setAppInForeground(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                MainShell(
                    appState = appState,
                    stateHolder = holder,
                    inboundNotificationTarget = inbound.value,
                    inboundNotificationRequestId = inboundRequestId.longValue,
                    onNotificationTargetHandled = { _, _ -> inbound.value = null },
                )
            }
        }
    }

    /** True while the switch is held at its first engine read and the routed conversation is already revealed. */
    private fun openedAheadOfTheSwitch(): Boolean {
        val held = activationHold.read(0).started.count == 0L
        return held && holder.selectedChat.value?.id == SHARED_GROUP && transcriptShown()
    }

    /** True once the target account is active and the revealed conversation runs on a controller other than [early]. */
    private fun landedOnAnotherController(early: ConversationController): Boolean {
        val current = appState.attachedConversationControllers().singleOrNull()
        return appState.activeAccountRef == TARGET_ACCOUNT && transcriptShown() && current != null && current !== early
    }

    /** True once the conversation transcript is revealed. */
    private fun transcriptShown(): Boolean = tagShown(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)

    /**
     * One short scripted timeline for the shared group, answered per account: each account reads only its own
     * message, so the text on screen says which account the conversation was read through.
     */
    private fun conversations(): ConversationLiveSubscriptions {
        val group = conversationTimelineTestGroup().copy(groupIdHex = SHARED_GROUP)
        val notified = timelineRecord(NotificationScenario.NOTIFIED_ID, 1uL).copy(groupIdHex = SHARED_GROUP)
        return ConversationLiveSubscriptions(
            openTimeline = { account, _, _ ->
                val text = if (account == TARGET_ACCOUNT) TARGET_ACCOUNT_MESSAGE else SOURCE_ACCOUNT_MESSAGE
                ScriptedConversationTimelineSubscription(timelinePage(notified.copy(plaintext = text)))
            },
            openGroupState = { _, _ -> ScriptedConversationGroupStateSubscription(group) },
        )
    }

    /** True while a node carrying [tag] is composed. */
    private fun tagShown(tag: String): Boolean = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** The conversation header, the one clickable heading on the conversation, which opens its details. */
    private fun conversationHeader() = hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /** Runs composition and the main looper once, then yields to the route's real worker threads. */
    private fun pump() {
        composeRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        Thread.sleep(POLL_MILLIS)
        ShadowLooper.idleMainLooper(POLL_MILLIS, TimeUnit.MILLISECONDS)
    }

    /** Pumps until [condition] holds, and fails naming [what] never happened. */
    private fun awaitCondition(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(NotificationRouteHarness.ROUTE_TIMEOUT_MILLIS)
        while (System.nanoTime() <= deadline) {
            pump()
            if (condition()) return
        }
        throw AssertionError(
            "$what never happened: active=${appState.activeAccountRef} selected=${holder.selectedChat.value?.id} " +
                "held=${activationHold.read(0).started.count == 0L} details=${tagShown(DETAILS_TAG)}",
        )
    }

    private companion object {
        const val TARGET_ACCOUNT = NotificationRouteHarness.TARGET_ACCOUNT
        val SHARED_GROUP = NotificationRouteHarness.SHARED_GROUP
        const val DETAILS_TAG = "chat_info.list"
        const val TARGET_ACCOUNT_MESSAGE = "target account only message"
        const val SOURCE_ACCOUNT_MESSAGE = "source account only message"
        const val POLL_MILLIS = 20L

        /** Any request id other than the first tap's. */
        const val REPEAT_REQUEST_ID = 1_001L

        /** A notification trace that belongs to no early open, the trace of the route that superseded it. */
        const val SUPERSEDING_TRACE_ID = 2_002L

        /** About a second of held switch, long enough for a late draw of details to show. */
        const val HELD_PUMPS = 25

        /** Long enough for a late reopen of details to show after the switch lands. */
        const val SETTLE_PUMPS = 50
    }
}

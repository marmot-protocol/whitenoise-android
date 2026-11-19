package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
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
        assertEquals(SHARED_GROUP, holder.selectedChat.value?.id)
        assertEquals(earlyContext, holder.selectedChatOpenContext.value)
        assertEquals(MainSection.Chats.name, holder.sectionState.sectionName)
        val landed = appState.attachedConversationControllers().single()
        assertNotSame(earlyController, landed)
        assertEquals(TARGET_ACCOUNT, landed.boundAccountRef)
    }

    /** Mounts the production shell with the other account's tap pending and its broad chat-list bind unheld. */
    private fun mountShellWithTheTap() {
        gate.releaseActivation.countDown()
        appState.liveSubscriptionOverrides.conversation = conversations()
        appState.setAppInForeground(true)
        val inbound = mutableStateOf<NotificationTarget?>(routed.notificationTarget)
        composeRule.setContent {
            WhiteNoiseTheme {
                MainShell(
                    appState = appState,
                    stateHolder = holder,
                    inboundNotificationTarget = inbound.value,
                    inboundNotificationRequestId = routed.notificationRequestId,
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

    /** One short scripted timeline for the shared group, so the transcript can reveal. */
    private fun conversations(): ConversationLiveSubscriptions {
        val group = conversationTimelineTestGroup().copy(groupIdHex = SHARED_GROUP)
        val record = timelineRecord(NotificationScenario.NOTIFIED_ID, 1uL).copy(groupIdHex = SHARED_GROUP)
        return ConversationLiveSubscriptions(
            openTimeline = { _, _, _ -> ScriptedConversationTimelineSubscription(timelinePage(record)) },
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
        const val POLL_MILLIS = 20L

        /** Long enough for a late reopen of details to show after the switch lands. */
        const val SETTLE_PUMPS = 50
    }
}

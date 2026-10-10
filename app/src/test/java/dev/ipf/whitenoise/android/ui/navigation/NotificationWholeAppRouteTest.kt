package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.InboundIntentRouting
import dev.ipf.whitenoise.android.notifications.NotificationScenario
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.BootstrapAttemptCoordinator
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ChatListWindowHandle
import dev.ipf.whitenoise.android.state.ChatsSubscriptionHandle
import dev.ipf.whitenoise.android.state.ConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.MarmotWindowTestFakes.windowSnapshot
import dev.ipf.whitenoise.android.state.ScriptedConversationGroupStateSubscription
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.WarmResumeRenderedFrame
import dev.ipf.whitenoise.android.state.WarmResumeRenderedSurface
import dev.ipf.whitenoise.android.state.WarmResumeTrace
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.WhiteNoiseApp
import dev.ipf.whitenoise.android.ui.common.STARTUP_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The whole app, not just its shell: the production root, the real shell holder, a real broad chat-list
 * bind that the engine answers only when released, and a notification tap for the inactive account.
 *
 * Shell-level route tests mount MainShell alone and stub the broad bind with an error, so they cannot see
 * what the root does between the route's commit and the conversation. This one watches the surfaces the
 * root actually draws through [WarmResumeTrace], which is where an intermediate loading or chat-list
 * frame, or a conversation that waits for the broad bind, becomes visible.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class NotificationWholeAppRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val harnesses = mutableListOf<Harness>()

    /** Releases every held engine call and the shell holders so no gated worker outlives its case. */
    @After
    fun tearDown() {
        harnesses.forEach { it.close() }
    }

    /**
     * The conversation reaches its first frame while the broad chat-list bind is still held, and the root
     * never draws a loading or chat-list surface between the tap and the conversation. A route that waits
     * for the bind, or one that deadlocks behind it, leaves the transcript hidden for as long as the bind is.
     */
    @Test
    fun conversationFirstFrameDoesNotWaitForTheHeldBroadBindAndNoIntermediateSurfaceDraws() {
        val harness = Harness()
        harness.mount()

        harness.awaitCondition("the route never reached the conversation while the broad bind was held") {
            harness.handled.get() && harness.transcriptVisible()
        }

        assertEquals(NotificationScenario.TARGET_ACCOUNT_REF, harness.app.activeAccountRef)
        assertTrue("the broad bind must still be held", harness.gate.releaseActivation.count == 1L)
        harness.assertNoIntermediateSurface()
    }

    /** Releasing the held bind afterwards completes it without moving the reader off the conversation. */
    @Test
    fun releasingTheBroadBindAfterTheFirstFrameKeepsTheConversationAndLoadsTheChatList() {
        val harness = Harness()
        harness.mount()
        harness.awaitCondition("the route never reached the conversation") {
            harness.handled.get() && harness.transcriptVisible()
        }

        harness.gate.releaseActivation.countDown()

        harness.awaitCondition("the chat list never finished its deferred bind") {
            harness.shell.localProjectionAvailable(
                harness.app.activeAccountRef,
                harness.app.runtimeGeneration,
            )
        }
        assertTrue("the conversation must stay selected", harness.shell.selectedChat.value != null)
        assertTrue(harness.transcriptVisible())
        harness.assertNoIntermediateSurface()
    }

    /**
     * Back before the conversation's first frame ends the route: the priority window is released, the
     * deferred chat-list bind runs once the engine answers, and the app lands on the chat list instead of
     * a loading surface that never ends.
     */
    @Test
    fun backBeforeTheFirstFrameReleasesTheWindowAndLandsOnTheChatList() {
        val harness = Harness(holdTimeline = true)
        harness.mount()
        harness.awaitCondition("the conversation never opened behind the held timeline") {
            harness.handled.get() && harness.shell.selectedChat.value != null
        }
        assertFalse("the first frame cannot have committed with the timeline held", harness.transcriptVisible())
        assertTrue(
            "the priority window is held until the first frame",
            harness.shell.notificationFirstFrame.gate != null,
        )

        harness.pressBack()
        harness.awaitCondition("Back never released the priority window") {
            harness.shell.notificationFirstFrame.gate == null
        }
        harness.gate.releaseActivation.countDown()

        harness.awaitCondition("the chat list never appeared after Back") {
            WarmResumeTrace.renderedSurfaceFrames().lastOrNull()?.surface == WarmResumeRenderedSurface.ChatList
        }
        assertEquals(null, harness.shell.selectedChat.value)
    }

    /** A repeated tap on the same message advances the request and keeps the conversation, with no loading frame. */
    @Test
    fun repeatedSameTargetTapKeepsTheConversationWithoutAnIntermediateSurface() {
        val harness = Harness()
        harness.mount()
        harness.awaitCondition("the first tap never reached the conversation") {
            harness.handled.get() && harness.transcriptVisible()
        }

        harness.tap(NotificationScenario.TARGET_ACCOUNT_REF)

        harness.awaitCondition("the repeated tap was never consumed") { harness.handledTargets.size >= 2 }
        harness.awaitCondition("the conversation left the screen after the repeated tap") {
            harness.transcriptVisible()
        }
        assertEquals(
            NotificationScenario.GROUP_ID,
            harness.shell.selectedChat.value
                ?.id,
        )
        harness.assertNoIntermediateSurface()
    }

    /**
     * A, then B, then A again before B can land: the newer request supersedes, B's priority window is
     * released, and the app ends on A's conversation instead of a loading surface owned by a dead request.
     */
    @Test
    fun aSupersedingTapForTheSourceAccountReleasesTheTargetsWindow() {
        val harness = Harness(preloadFinishesFirst = false)
        harness.mount()
        harness.awaitCondition("the target route never started") { harness.gate.preloadStarted.count == 0L }

        harness.tap(NotificationScenario.SOURCE_ACCOUNT_REF)
        harness.gate.releasePreload.countDown()

        harness.awaitCondition("the superseding tap never reached the conversation") {
            harness.handledTargets.any { it.startsWith(NotificationScenario.SOURCE_ACCOUNT_REF) } &&
                harness.transcriptVisible()
        }
        harness.awaitCondition("the superseded request kept its priority window") {
            harness.shell.notificationFirstFrame.gate == null
        }
        assertEquals(NotificationScenario.SOURCE_ACCOUNT_REF, harness.app.activeAccountRef)
    }

    /** A tap for an account that does not exist fails the route: it says so and releases the priority window. */
    @Test
    fun aTapForAnUnknownAccountFailsTheRouteAndReleasesTheWindow() {
        val harness = Harness()
        harness.mount()
        harness.awaitCondition("the first route never settled") { harness.handled.get() && harness.transcriptVisible() }

        harness.tap("account-z")

        harness.awaitCondition("the failed tap was never consumed") { harness.handledTargets.size >= 2 }
        harness.awaitCondition("the failed route kept its priority window") {
            harness.shell.notificationFirstFrame.gate == null
        }
        assertTrue("the reader is told the account is gone", harness.app.toast != null)
    }

    /** One mounted app: a two-account runtime, a gated engine and the production root around it. */
    private inner class Harness(
        preloadFinishesFirst: Boolean = true,
        holdTimeline: Boolean = false,
    ) {
        val gate = AccountRouteOrderGate(preloadFinishesFirst)
        private val timelineGate = CountDownLatch(if (holdTimeline) 1 else 0)
        val handled = AtomicBoolean(false)
        val handledTargets = CopyOnWriteArrayList<String>()
        val app: WhiteNoiseAppState =
            NotificationRouteHarness.appState(context, NotificationRouteHarness.fakeMarmot(gate, failBroadBind = false))
        val shell = MainShellStateHolder(app, SavedStateHandle())
        private var routing: InboundIntentRouting =
            NotificationRouteHarness.routedTarget(NotificationScenario.TARGET_ACCOUNT_REF)
        private val inbound = mutableStateOf<NotificationTarget?>(routing.notificationTarget)
        private val requestId = mutableLongStateOf(routing.notificationRequestId)
        private val pendingAttempt = CompletableDeferred<Unit>()
        private val timeline =
            ScriptedConversationTimelineSubscription(
                timelinePage(
                    *(0 until MESSAGE_COUNT)
                        .map { index ->
                            val id = if (index == NOTIFIED_INDEX) NotificationScenario.NOTIFIED_ID else filler(index)
                            timelineRecord(id, (index + 1).toULong()).copy(groupIdHex = NotificationScenario.GROUP_ID)
                        }.toTypedArray(),
                ),
            )
        private var rootView: View? = null
        private val conversationGroup = conversationTimelineTestGroup().copy(groupIdHex = NotificationScenario.GROUP_ID)
        private val conversations =
            ConversationLiveSubscriptions(
                openTimeline = { _, _, _ ->
                    check(timelineGate.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        "timeline was never released"
                    }
                    timeline
                },
                openGroupState = { _, _ -> ScriptedConversationGroupStateSubscription(conversationGroup) },
            )

        init {
            harnesses += this
            app.liveSubscriptionOverrides.chatList = chatListSeam()
            app.liveSubscriptionOverrides.conversation = conversations
            // The app opens on its ready surface; only the broad chat-list bind is slow, which is the case under test.
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("setPhase", AppPhase::class.java)
                .apply { isAccessible = true }
                .invoke(app, AppPhase.Ready)
            val attempts =
                WhiteNoiseAppState::class.java
                    .getDeclaredField("bootstrapAttempts")
                    .apply { isAccessible = true }
                    .get(app) as BootstrapAttemptCoordinator
            runBlocking { attempts.currentOrStart { pendingAttempt } }
            app.setAppInForeground(true)
        }

        /**
         * Mounts the whole app with the tap pending. The test clock is advanced one frame at a time by
         * [pumpFrame], so each composition pass is one draw and a surface that exists for a single frame
         * is still recorded, which is the granularity a device's draw listener has.
         */
        fun mount() {
            WarmResumeTrace.resetRenderedSurfaceFrames()
            composeRule.mainClock.autoAdvance = false
            composeRule.setContent {
                rootView = LocalView.current
                WhiteNoiseTheme {
                    WhiteNoiseApp(
                        appState = app,
                        mainShellStateHolder = shell,
                        warmResumeTraceToken = 1,
                        warmResumeEpoch = 1,
                        inboundNotificationTarget = inbound.value,
                        inboundNotificationRequestId = requestId.longValue,
                        onNotificationTargetHandled = { target, handledRequestId ->
                            handled.set(true)
                            handledTargets += "${target.accountRef}:$handledRequestId"
                            if (handledRequestId == requestId.longValue) inbound.value = null
                        },
                    )
                }
            }
        }

        /** Taps the conversation's own Back button, with the clock auto-advancing only while the click is injected. */
        fun pressBack() {
            composeRule.mainClock.autoAdvance = true
            try {
                composeRule
                    .onNodeWithContentDescription(context.getString(R.string.back))
                    .performClick()
            } finally {
                composeRule.mainClock.autoAdvance = false
            }
        }

        /** Delivers another tap for [accountRef], as a second notification or a re-tap would, advancing the request. */
        fun tap(accountRef: String) {
            routing = NotificationRouteHarness.routedTarget(accountRef, current = routing)
            composeRule.runOnIdle {
                inbound.value = routing.notificationTarget
                requestId.longValue = routing.notificationRequestId
            }
        }

        /** True while the root's loading surface is on screen, which a stuck route would leave there. */
        private fun startupSurfaceShown(): Boolean {
            val nodes = composeRule.onAllNodesWithTag(STARTUP_LOADING_TEST_TAG).fetchSemanticsNodes()
            return nodes.isNotEmpty()
        }

        /** True once the root has revealed the conversation transcript for the routed message. */
        fun transcriptVisible(): Boolean =
            composeRule
                .onAllNodesWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
                .fetchSemanticsNodes()
                .isNotEmpty()

        /** No loading or chat-list surface may have drawn between the tap and the conversation. */
        fun assertNoIntermediateSurface() {
            val forbidden =
                setOf(
                    WarmResumeRenderedSurface.StartupLoading,
                    WarmResumeRenderedSurface.FullScreenLoading,
                    WarmResumeRenderedSurface.ChatList,
                )
            val frames: List<WarmResumeRenderedFrame> = WarmResumeTrace.renderedSurfaceFrames()
            assertTrue("the root drew no surface at all, so the sequence proves nothing: $frames", frames.isNotEmpty())
            assertFalse(
                "an intermediate surface drew between the tap and the conversation: ${frames.map { it.surface }}",
                frames.any { it.surface in forbidden },
            )
        }

        /**
         * Runs one frame of composition, then the draw callbacks the root registered. Robolectric never
         * schedules the window's own traversal, so the draw dispatch is issued here, once per frame.
         */
        fun pumpFrame() {
            composeRule.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper()
            val observer = requireNotNull(rootView).viewTreeObserver
            ViewTreeObserver::class.java
                .getDeclaredMethod("dispatchOnDraw")
                .apply { isAccessible = true }
                .invoke(observer)
            ShadowLooper.idleMainLooper()
        }

        /** Pumps frames and yields to the engine's real workers until [condition] holds. */
        fun awaitCondition(
            failureMessage: String,
            condition: () -> Boolean,
        ) {
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ROUTE_TIMEOUT_MILLIS)
            while (System.nanoTime() <= deadlineNanos) {
                pumpFrame()
                if (condition()) return
                // The route also uses real worker threads, which need wall-clock time between frames.
                Thread.sleep(POLL_MILLIS)
            }
            throw AssertionError(
                "$failureMessage: frames=${WarmResumeTrace.renderedSurfaceFrames().map { it.surface }} " +
                    "handled=${handled.get()} active=${app.activeAccountRef} phase=${app.phase} " +
                    "selected=${shell.selectedChat.value?.id} ctx=${shell.selectedChatOpenContext.value} " +
                    "startupTag=${startupSurfaceShown()} " +
                    "localProjection=${shell.localProjectionAvailable(app.activeAccountRef, app.runtimeGeneration)}",
            )
        }

        /** The engine's chat-list seam: the target account's broad bind answers only once the test releases it. */
        private fun chatListSeam() =
            ChatListLiveSubscriptions(
                openChatListWindow = { account, view ->
                    if (account == NotificationScenario.TARGET_ACCOUNT_REF) {
                        gate.broadBindStarted.countDown()
                        check(gate.releaseActivation.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                            "the broad bind was never released"
                        }
                    }
                    EmptyWindow(view)
                },
                openChats = { _, _ -> NoGroupRecords },
            )

        /** Releases the held bind and the shell holder, and ends the seeded bootstrap attempt. */
        fun close() {
            gate.releaseActivation.countDown()
            gate.releasePreload.countDown()
            timelineGate.countDown()
            pendingAttempt.complete(Unit)
            composeRule.mainClock.autoAdvance = true
            composeRule.runOnIdle { shell.release() }
        }
    }

    /** An empty chat-list window whose stream never ends, as a quiet live account's would not. */
    private class EmptyWindow(
        private val view: ChatListViewFfi,
    ) : ChatListWindowHandle {
        /** The empty window's first replacement at [sequence]. */
        private fun frame(sequence: ULong = 0uL) = windowSnapshot(view, emptyList(), sequence)

        /** The complete first replacement. */
        override fun snapshot(): ChatListWindowSnapshotFfi = frame()

        /** Nothing changes, so the wait for a replacement never completes. */
        override suspend fun next(): ChatListWindowSnapshotFfi? = awaitCancellation()

        /** Paging an empty window returns it unchanged. */
        override suspend fun page(
            sequence: ULong,
            direction: ChatListPageDirectionFfi,
            count: UInt,
        ): ChatListWindowSnapshotFfi = frame(sequence)

        /** Anchoring an empty window returns it unchanged. */
        override suspend fun setVisibleAnchor(
            sequence: ULong,
            groupIdHex: String,
        ): ChatListWindowSnapshotFfi = frame(sequence)

        /** An empty window is already at its top. */
        override suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi = frame(sequence)

        /** Nothing native to release. */
        override fun close() = Unit
    }

    /** A group-record stream with no groups and no updates. */
    private object NoGroupRecords : ChatsSubscriptionHandle {
        /** No group records. */
        override fun snapshot(): List<AppGroupRecordFfi> = emptyList()

        /** No updates ever arrive. */
        override suspend fun next(): AppGroupRecordFfi? = awaitCancellation()

        /** Nothing native to release. */
        override fun close() = Unit
    }

    private companion object {
        const val MESSAGE_COUNT = 12
        const val NOTIFIED_INDEX = 6
        const val POLL_MILLIS = 20L
        const val ROUTE_TIMEOUT_MILLIS = 30_000L

        /** A deterministic 64-hex id for an unnamed message. */
        fun filler(index: Int): String = index.toString(16).padStart(2, '0').repeat(32)
    }
}

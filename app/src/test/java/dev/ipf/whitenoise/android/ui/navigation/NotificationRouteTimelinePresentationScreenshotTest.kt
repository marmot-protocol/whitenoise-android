package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.notifications.InboundIntentRouting
import dev.ipf.whitenoise.android.notifications.NotificationNavigation
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.notifications.routeInboundIntent
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ConversationTimelineTestDraftPersistence
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupRosterLoadState
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.assertTimelineSubscriptionSnapshotBeforeFirstNextWindow
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.state.awaitOpenedTimelineSubscriptionsClosed
import dev.ipf.whitenoise.android.state.conversationTimelineReconnectFixtures
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.notificationChatListRow
import dev.ipf.whitenoise.android.state.timelineMessageIds
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.state.transcriptPresentationNeedsRetry
import dev.ipf.whitenoise.android.state.usesDirectTranscriptChrome
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_INITIAL_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.lang.reflect.Proxy
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TARGET_ACCOUNT = "bob"
private val TARGET_ACCOUNT_ID = "ee".repeat(32)
private const val SENDER_NAME = "Peer Example"
private val THIRD_MEMBER_ID = "ff".repeat(32)
private const val NOTIFIED_BODY = "notified body"
private const val TAP_TOKEN = "timeline-route-token"
private const val ROUTE_TIMEOUT_MILLIS = 10_000L
private const val POLL_INTERVAL_MILLIS = 10L

/**
 * Production-route regressions for notification-owned conversation lifetime,
 * reconnect delivery (#2233), and account-isolated first-frame chrome (#2231).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class NotificationRouteTimelinePresentationScreenshotTest : NotificationRouteTimelinePresentationFixture() {
    /** A terminal notification route restores the established removed-member surface instead of loading forever. */
    @Test
    fun notificationRoute_terminalEvictionShowsTheNonEditableRemovedState() {
        val directHarness = DirectNotificationConversationHarness(composeRule)
        val fixture =
            directHarness.create(
                ConversationLiveSubscriptions(
                    openTimeline = { _, _, _ -> error("GroupStateError::UseAfterEviction") },
                    openGroupState = { _, _ -> error("terminal timeline open must not bind group state") },
                ),
            )
        val mounted = mutableStateOf(true)
        try {
            awaitConversationCondition { fixture.controller.terminalConversationUnavailable }
            assertNull(fixture.controller.error)
            directHarness.mount(fixture, mounted, notificationOpenRequestId = { 41L })
            composeRule.waitForIdle()

            composeRule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertDoesNotExist()
            val context = ApplicationProvider.getApplicationContext<Context>()
            composeRule.onNodeWithText(context.getString(R.string.you_are_no_longer_a_member)).assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.no_messages_yet)).assertIsDisplayed()
            composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            composeRule.onRoot().captureRoboImage(
                "src/test/snapshots/notification_route_terminal_eviction_light.png",
            )
        } finally {
            directHarness.dispose(fixture, mounted)
        }
    }

    /** A reconnect must publish the notified row through the existing mounted route controller. */
    @Test
    fun notificationRoutedReconnectShowsNotifiedMessageWithoutRecreatingController() {
        val fixtures = conversationTimelineReconnectFixtures()
        val routeGate = NotificationRouteGate(exactPreloadBeforeBroadBind = true)
        val appState =
            notificationRouteAppState(
                scriptedSubscriptions = fixtures.scriptedSubscriptions,
                routeGate = routeGate,
            )
        val routed = routedTarget(TARGET_ACCOUNT)
        val handled = AtomicBoolean(false)
        val inboundRequestId = mutableStateOf(routed.notificationRequestId)
        val shellMounted = mutableStateOf(true)

        var mountedController: ConversationController? = null
        try {
            mountNotificationRoute(appState, routed, handled, inboundRequestId, shellMounted)
            mountedController = awaitMountedNotificationConversation(routeGate, handled, appState)

            reconnectWhileBackground(
                appState = appState,
                firstSubscription = fixtures.firstSubscription,
                mountedController = mountedController,
                scriptedSubscriptions = fixtures.scriptedSubscriptions,
            )
            resumeAfterForeground(
                appState = appState,
                inboundRequestId = inboundRequestId,
                mountedController = mountedController,
            )

            assertNotificationReconnectPresentation(
                mountedController = mountedController,
                appState = appState,
                scriptedSubscriptions = fixtures.scriptedSubscriptions,
                replacementSubscription = fixtures.replacementSubscription,
            )
        } finally {
            routeGate.releasePreload.countDown()
            routeGate.releaseBroadBind.countDown()
            try {
                disposeNotificationRoute(shellMounted, appState, mountedController)
            } finally {
                awaitOpenedTimelineSubscriptionsClosed(fixtures.scriptedSubscriptions)
            }
        }
    }

    /** The exact projection may open before broad binding without exposing a guessed target roster. */
    @Test
    fun twoMemberNotificationRoute_exactPreloadBeforeBroadBind_neverRevealsUnknownGroupChrome() {
        verifyTwoMemberNotificationFirstFrame(
            exactPreloadBeforeBroadBind = true,
            captureScreenshot = true,
        )
    }

    /** Target-account activation before the exact preload must not borrow the prior account's sender chrome. */
    @Test
    fun twoMemberNotificationRoute_targetAccountBeforeExactPreload_neverRevealsUnknownGroupChrome() {
        verifyTwoMemberNotificationFirstFrame(
            exactPreloadBeforeBroadBind = false,
            captureScreenshot = false,
        )
    }

    /** The real notification route preserves the compact leading edge in narrow RTL at enlarged type. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w320dp-h780dp-mdpi")
    fun twoMemberNotificationRoute_amoledLargeRtl_keepsCompactFirstFrame() {
        verifyTwoMemberNotificationFirstFrame(
            exactPreloadBeforeBroadBind = false,
            captureScreenshot = true,
            amoledLargeRtl = true,
        )
    }

    /** Failed cold membership releases deferred activation and offers recovery without revealing guessed rows. */
    @Test
    fun notificationRoute_unknownRosterFailureOffersRetryWithoutRevealingTranscript() {
        verifyUnknownRosterRecovery(emptyTimeline = false)
    }

    /** An empty authoritative page must retain the same finite roster-retry path instead of showing an empty state. */
    @Test
    fun notificationRoute_emptyTimelineRosterFailureRetriesOnTheSameController() {
        verifyUnknownRosterRecovery(emptyTimeline = true)
    }

    /** Exercises cold roster failure and recovery after either an empty or populated authoritative first page. */
    @Suppress("LongMethod") // Keeps the route failure and deferred-bind release in one lifecycle assertion.
    private fun verifyUnknownRosterRecovery(emptyTimeline: Boolean) {
        val initialPage =
            if (emptyTimeline) {
                timelinePage()
            } else {
                timelinePage(
                    timelineRecord(
                        messageId = ConversationTimelineTestIds.MESSAGE_B,
                        timelineAt = 2uL,
                        plaintext = NOTIFIED_BODY,
                    ),
                )
            }
        val timelineSubscription =
            ScriptedConversationTimelineSubscription(
                initialPage,
            )
        val scriptedSubscriptions =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(timelineSubscription),
                group = conversationTimelineTestGroup(),
            )
        val routeGate =
            NotificationRouteGate(
                exactPreloadBeforeBroadBind = true,
                holdRoster = true,
                rosterFails = true,
            )
        val appState = notificationRouteAppState(scriptedSubscriptions, routeGate)
        val routed = routedTarget(TARGET_ACCOUNT)
        val handled = AtomicBoolean(false)
        val inboundRequestId = mutableStateOf(routed.notificationRequestId)
        val shellMounted = mutableStateOf(true)
        var mountedController: ConversationController? = null
        try {
            mountNotificationRoute(appState, routed, handled, inboundRequestId, shellMounted)
            mountedController =
                awaitMountedNotificationConversation(
                    routeGate = routeGate,
                    handled = handled,
                    appState = appState,
                    expectedMessageIds =
                        if (emptyTimeline) {
                            emptyList()
                        } else {
                            listOf(ConversationTimelineTestIds.MESSAGE_B)
                        },
                )
            awaitCondition(
                failureMessage = { "target roster read did not start: ${routeState(appState, mountedController)}" },
            ) {
                routeGate.rosterStarted.count == 0L
            }
            assertEquals(1L, routeGate.targetBroadBindStarted.count)
            assertTrue(
                "a routed screen with held roster evidence must not suppress a fresh target message",
                appState.shouldPostIncomingTargetNotification(),
            )

            routeGate.releaseRoster.countDown()
            awaitCondition { mountedController.memberRosterState == GroupRosterLoadState.FAILED }
            composeRule.waitUntil(timeoutMillis = ROUTE_TIMEOUT_MILLIS) {
                routeGate.targetBroadBindStarted.count == 0L
            }

            assertEquals(TARGET_ACCOUNT, mountedController.boundAccountRef)
            assertFalse(mountedController.membersVerified)
            assertFalse(mountedController.usesDirectTranscriptChrome)
            assertTrue(mountedController.transcriptPresentationNeedsRetry)
            assertTrue(
                "a recoverable roster failure must keep future target notifications enabled",
                appState.shouldPostIncomingTargetNotification(),
            )
            composeRule
                .onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG)
                .assertDoesNotExist()
            val context = ApplicationProvider.getApplicationContext<Context>()
            composeRule
                .onNodeWithText(context.getString(R.string.error_conversation_membership_unavailable))
                .assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.no_messages_yet)).assertDoesNotExist()
            assertTrue(
                composeRule
                    .onAllNodesWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
            if (!emptyTimeline) {
                composeRule.onRoot().captureRoboImage(
                    "src/test/snapshots/notification_route_unknown_roster_recovery_light.png",
                )
            }

            routeGate.rosterFails.set(false)
            composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
            awaitCondition { mountedController.membersVerified && mountedController.usesDirectTranscriptChrome }
            awaitCondition(
                failureMessage = { "revealed target transcript never claimed notification ownership" },
            ) {
                !appState.shouldPostIncomingTargetNotification()
            }
            if (emptyTimeline) {
                composeRule.onNodeWithText(context.getString(R.string.no_messages_yet)).assertIsDisplayed()
                composeRule.onRoot().captureRoboImage(
                    "src/test/snapshots/notification_route_empty_timeline_recovery_light.png",
                )
            } else {
                composeRule.onNodeWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE).assertIsDisplayed()
                composeRule.onNodeWithText(NOTIFIED_BODY).assertIsDisplayed()
            }
            composeRule.onNodeWithText(SENDER_NAME, useUnmergedTree = true).assertDoesNotExist()
            assertSame(mountedController, appState.attachedConversationControllersForTest().single())
        } finally {
            routeGate.releasePreload.countDown()
            routeGate.releaseBroadBind.countDown()
            routeGate.releaseRoster.countDown()
            try {
                disposeNotificationRoute(shellMounted, appState, mountedController)
            } finally {
                awaitOpenedTimelineSubscriptionsClosed(scriptedSubscriptions)
            }
        }
    }

    /**
     * Mounts the production notification route with a source-account roster
     * cached for the same group while the target roster is deliberately held.
     */
    @Suppress("LongMethod") // One route lifetime proves account isolation before and after the roster boundary.
    private fun verifyTwoMemberNotificationFirstFrame(
        exactPreloadBeforeBroadBind: Boolean,
        captureScreenshot: Boolean,
        amoledLargeRtl: Boolean = false,
    ) {
        val timelineSubscription =
            ScriptedConversationTimelineSubscription(
                timelinePage(
                    timelineRecord(
                        messageId = ConversationTimelineTestIds.MESSAGE_B,
                        timelineAt = 2uL,
                        plaintext = NOTIFIED_BODY,
                    ),
                ),
            )
        val scriptedSubscriptions =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(timelineSubscription),
                group = conversationTimelineTestGroup(),
            )
        val routeGate =
            NotificationRouteGate(
                exactPreloadBeforeBroadBind = exactPreloadBeforeBroadBind,
                holdRoster = true,
            )
        val appState = notificationRouteAppState(scriptedSubscriptions, routeGate)
        appState.cacheGroupMemberSnapshot(
            accountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            groupIdHex = ConversationTimelineTestIds.GROUP_ID,
            members = sourceAccountThreeMemberSnapshot(),
        )
        assertNull(
            appState.cachedGroupMemberSnapshot(
                accountRef = TARGET_ACCOUNT,
                groupIdHex = ConversationTimelineTestIds.GROUP_ID,
            ),
        )
        val routed = routedTarget(TARGET_ACCOUNT)
        val handled = AtomicBoolean(false)
        val inboundRequestId = mutableStateOf(routed.notificationRequestId)
        val shellMounted = mutableStateOf(true)
        var mountedController: ConversationController? = null
        val originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            mountNotificationRoute(
                appState = appState,
                routed = routed,
                handled = handled,
                inboundRequestId = inboundRequestId,
                shellMounted = shellMounted,
                amoledLargeRtl = amoledLargeRtl,
            )
            mountedController =
                awaitMountedNotificationConversation(
                    routeGate = routeGate,
                    handled = handled,
                    appState = appState,
                    expectedMessageIds = listOf(ConversationTimelineTestIds.MESSAGE_B),
                )
            awaitCondition(
                failureMessage = { "target roster read did not start: ${routeState(appState, mountedController)}" },
            ) {
                routeGate.rosterStarted.count == 0L
            }
            composeRule.waitUntil(timeoutMillis = ROUTE_TIMEOUT_MILLIS) {
                composeRule
                    .onAllNodesWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            assertEquals(TARGET_ACCOUNT, mountedController.boundAccountRef)
            assertFalse("compact presentation must retain semantic-group ownership", mountedController.isDm)
            assertEquals(0, mountedController.memberCount)
            assertFalse(mountedController.membersVerified)
            assertFalse(mountedController.usesDirectTranscriptChrome)
            assertTrue(
                composeRule
                    .onAllNodesWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
            assertEquals(1L, routeGate.targetBroadBindStarted.count)

            routeGate.releaseRoster.countDown()
            awaitCondition {
                mountedController.membersVerified && mountedController.usesDirectTranscriptChrome
            }
            composeRule.waitUntil(timeoutMillis = ROUTE_TIMEOUT_MILLIS) {
                composeRule
                    .onAllNodesWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            composeRule.onNodeWithText(NOTIFIED_BODY).assertIsDisplayed()
            composeRule.onNodeWithText(SENDER_NAME, useUnmergedTree = true).assertDoesNotExist()
            composeRule
                .onNodeWithText(IdentityFormatter.initials(SENDER_NAME), useUnmergedTree = true)
                .assertDoesNotExist()
            if (captureScreenshot) {
                composeRule
                    .onRoot()
                    .captureRoboImage(
                        if (amoledLargeRtl) {
                            "src/test/snapshots/notification_route_two_member_group_first_frame_amoled_large_rtl.png"
                        } else {
                            "src/test/snapshots/notification_route_two_member_group_first_frame_light.png"
                        },
                    )
            }
            composeRule.waitUntil(timeoutMillis = ROUTE_TIMEOUT_MILLIS) {
                routeGate.targetBroadBindStarted.count == 0L
            }
            verifyMountedRosterTransitions(mountedController, routeGate, amoledLargeRtl)
        } finally {
            TimeZone.setDefault(originalTimeZone)
            routeGate.releasePreload.countDown()
            routeGate.releaseBroadBind.countDown()
            routeGate.releaseRoster.countDown()
            try {
                disposeNotificationRoute(shellMounted, appState, mountedController)
            } finally {
                awaitOpenedTimelineSubscriptionsClosed(scriptedSubscriptions)
            }
        }
    }

    /** Proves the notification-mounted controller still follows authoritative 2 -> 3 -> 2 roster changes. */
    private fun verifyMountedRosterTransitions(
        mountedController: ConversationController,
        routeGate: NotificationRouteGate,
        rtl: Boolean,
    ) {
        val directBounds = composeRule.onNodeWithText(NOTIFIED_BODY).getUnclippedBoundsInRoot()
        routeGate.includeThirdMember.set(true)
        runBlocking { mountedController.retryMembers() }
        awaitCondition {
            mountedController.memberCount == 3 && !mountedController.usesDirectTranscriptChrome
        }
        composeRule.onNodeWithText(SENDER_NAME, useUnmergedTree = true).assertIsDisplayed()
        val groupBounds = composeRule.onNodeWithText(NOTIFIED_BODY).getUnclippedBoundsInRoot()
        assertTrue(
            "three-member transcript must reserve a logical-leading avatar gutter",
            if (rtl) groupBounds.right < directBounds.right else groupBounds.left > directBounds.left,
        )

        routeGate.includeThirdMember.set(false)
        runBlocking { mountedController.retryMembers() }
        awaitCondition {
            mountedController.memberCount == 2 && mountedController.usesDirectTranscriptChrome
        }
        composeRule.onNodeWithText(SENDER_NAME, useUnmergedTree = true).assertDoesNotExist()
        composeRule
            .onNodeWithText(IdentityFormatter.initials(SENDER_NAME), useUnmergedTree = true)
            .assertDoesNotExist()
        val restoredBounds = composeRule.onNodeWithText(NOTIFIED_BODY).getUnclippedBoundsInRoot()
        assertEquals(directBounds.left.value, restoredBounds.left.value, 0.5f)
        assertEquals(directBounds.right.value, restoredBounds.right.value, 0.5f)
        assertFalse(mountedController.isDm)

        routeGate.rosterFails.set(true)
        runBlocking { mountedController.retryMembers() }
        assertEquals(GroupRosterLoadState.READY, mountedController.memberRosterState)
        assertTrue(
            "failed refresh retains the last authoritative two-member chrome",
            mountedController.usesDirectTranscriptChrome,
        )
        assertFalse(mountedController.transcriptPresentationNeedsRetry)
        composeRule.onNodeWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE).assertIsDisplayed()
        composeRule.onNodeWithText(SENDER_NAME, useUnmergedTree = true).assertDoesNotExist()
    }
}

/** Shared production-route lifecycle fixture kept separate from the six behavioral scenarios. */
@Suppress("TooManyFunctions") // Route setup, teardown, and bounded polling form one lifecycle harness.
abstract class NotificationRouteTimelinePresentationFixture {
    @get:Rule
    val composeRule = createComposeRule()

    /** Drives MainShell's production inbound-intent state machine under deterministic appearance settings. */
    protected fun mountNotificationRoute(
        appState: WhiteNoiseAppState,
        routed: InboundIntentRouting,
        handled: AtomicBoolean,
        inboundRequestId: MutableState<Long>,
        shellMounted: MutableState<Boolean>,
        amoledLargeRtl: Boolean = false,
    ) {
        appState.setAppInForeground(true)
        composeRule.setContent {
            if (shellMounted.value) {
                var inboundTarget by remember { mutableStateOf(routed.notificationTarget) }
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (amoledLargeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    WhiteNoiseTheme(
                        darkTheme = amoledLargeRtl,
                        amoled = amoledLargeRtl,
                        fontScale = if (amoledLargeRtl) 1.6f else 1f,
                    ) {
                        MainShell(
                            appState = appState,
                            inboundNotificationTarget = inboundTarget,
                            inboundNotificationRequestId = inboundRequestId.value,
                            onNotificationTargetHandled = { _, _ ->
                                handled.set(true)
                                inboundTarget = null
                            },
                        )
                    }
                }
            }
        }
    }

    /** Disposes the shell and clears every controller attached by this fixture's app state. */
    protected fun disposeNotificationRoute(
        shellMounted: MutableState<Boolean>,
        appState: WhiteNoiseAppState,
        mountedController: ConversationController?,
    ) {
        val fixtureControllers = mutableListOf<ConversationController>()
        mountedController?.let(fixtureControllers::add)
        try {
            composeRule.runOnIdle {
                appState.attachedConversationControllersForTest().forEach { controller ->
                    fixtureControllers.addIfAbsentByIdentity(controller)
                }
                shellMounted.value = false
            }
            composeRule.waitForIdle()
        } finally {
            appState.attachedConversationControllersForTest().forEach { controller ->
                fixtureControllers.addIfAbsentByIdentity(controller)
            }
            fixtureControllers.forEach { controller ->
                appState.detachConversationController(controller)
                controller.onCleared()
            }
        }
    }

    /**
     * Releases either the exact preload or target broad-list bind first. The
     * separate state-holder regression owns the pre-activation route race.
     */
    internal fun awaitMountedNotificationConversation(
        routeGate: NotificationRouteGate,
        handled: AtomicBoolean,
        appState: WhiteNoiseAppState,
        expectedMessageIds: List<String> = listOf(ConversationTimelineTestIds.MESSAGE_A),
    ): ConversationController {
        // This fixture owns preload versus broad-bind ordering, not the pre-activation controller
        // replacement covered by WarmResumeStateHolderTest. Setup eligibility is now an async read.
        awaitCondition(
            failureMessage = {
                "target account was not activated before exact preload: " +
                    routeState(appState, handled = handled)
            },
        ) {
            appState.activeAccountRef == TARGET_ACCOUNT
        }
        routeGate.releasePreload.countDown()
        awaitCondition(
            failureMessage = {
                "notification preload did not complete: ${routeState(appState, handled = handled)} " +
                    "preloadStarted=${routeGate.preloadStarted.count} " +
                    "preloadCompleted=${routeGate.preloadCompleted.count}"
            },
        ) {
            routeGate.preloadCompleted.count == 0L
        }
        if (routeGate.exactPreloadBeforeBroadBind) {
            awaitCondition(
                failureMessage = {
                    "notification target was not handled after exact preload: " +
                        routeState(appState, handled = handled)
                },
            ) {
                handled.get()
            }
        }
        routeGate.releaseBroadBind.countDown()
        awaitCondition(
            failureMessage = {
                "notification target was not handled after broad bind: " +
                    routeState(appState, handled = handled)
            },
        ) {
            handled.get()
        }
        awaitCondition(
            failureMessage = {
                val observedController = appState.attachedConversationControllersForTest().singleOrNull()
                "authoritative routed timeline did not mount: " +
                    "${routeState(appState, mountedController = observedController, handled = handled)} " +
                    "authoritative=${observedController?.hasPublishedAuthoritativeTimeline} " +
                    "loading=${observedController?.isLoading} loadFailure=${observedController?.error != null} " +
                    "expectedMessageCount=${expectedMessageIds.size}"
            },
        ) {
            appState.attachedConversationControllersForTest().singleOrNull()?.let { controller ->
                controller.hasPublishedAuthoritativeTimeline && timelineMessageIds(controller) == expectedMessageIds
            } == true
        }
        return appState.attachedConversationControllersForTest().single()
    }

    /** Forces the mounted notification conversation through a background reconnect. */
    internal fun reconnectWhileBackground(
        appState: WhiteNoiseAppState,
        firstSubscription: ScriptedConversationTimelineSubscription,
        mountedController: ConversationController,
        scriptedSubscriptions: ScriptedConversationLiveSubscriptions,
    ) {
        assertFalse(ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(mountedController))
        appState.setAppInForeground(false)
        awaitCondition(
            failureMessage = {
                "initial subscription did not enter nextWindow: ${routeState(appState, mountedController)} " +
                    "firstNext=${firstSubscription.nextWindowCallCount} " +
                    "firstClose=${firstSubscription.closeCallCount}"
            },
        ) {
            firstSubscription.nextWindowCallCount == 1
        }
        firstSubscription.endWindows()
        awaitCondition(
            failureMessage = {
                "initial subscription did not close after its window ended: " +
                    "${routeState(appState, mountedController)} " +
                    "firstNext=${firstSubscription.nextWindowCallCount} " +
                    "firstClose=${firstSubscription.closeCallCount}"
            },
        ) {
            firstSubscription.closeCallCount == 1
        }
        runBlocking { mountedController.retryLoadFailure() }
        awaitCondition(
            failureMessage = {
                "replacement subscription did not publish the recovered row: " +
                    "${routeState(appState, mountedController)} " +
                    "subscriptionOpens=${scriptedSubscriptions.timelineSubscriptionOpenCount}"
            },
        ) {
            ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(mountedController)
        }
    }

    protected fun resumeAfterForeground(
        appState: WhiteNoiseAppState,
        inboundRequestId: MutableState<Long>,
        mountedController: ConversationController,
    ) {
        appState.setAppInForeground(true)
        composeRule.runOnIdle {
            inboundRequestId.value += 1L
        }
        awaitCondition(
            failureMessage = {
                "foreground resume did not retain the recovered controller: " +
                    "${routeState(appState, mountedController)} requestAdvanced=${inboundRequestId.value > 0L}"
            },
        ) {
            appState.attachedConversationControllersForTest().singleOrNull() === mountedController &&
                ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(mountedController)
        }
    }

    /** Verifies reconnect reused the mounted controller and rendered the recovered row. */
    internal fun assertNotificationReconnectPresentation(
        mountedController: ConversationController,
        appState: WhiteNoiseAppState,
        scriptedSubscriptions: ScriptedConversationLiveSubscriptions,
        replacementSubscription: ScriptedConversationTimelineSubscription,
    ) {
        val attachedControllers = appState.attachedConversationControllersForTest()
        assertEquals(1, attachedControllers.size)
        assertSame(mountedController, attachedControllers.single())
        assertEquals(2, scriptedSubscriptions.timelineSubscriptionOpenCount)
        awaitCondition(
            failureMessage = {
                "replacement subscription never requested its next window: " +
                    "${routeState(appState, mountedController)} " +
                    "subscriptionOpens=${scriptedSubscriptions.timelineSubscriptionOpenCount} " +
                    "replacementNext=${replacementSubscription.nextWindowCallCount}"
            },
        ) {
            replacementSubscription.nextWindowCallCount >= 1
        }
        assertTimelineSubscriptionSnapshotBeforeFirstNextWindow(replacementSubscription)
        composeRule.onNodeWithText("notified body").assertIsDisplayed()
        assertFalse(attachedControllers.any { it !== mountedController })
    }

    internal class NotificationRouteGate(
        val exactPreloadBeforeBroadBind: Boolean,
        holdRoster: Boolean = false,
        rosterFails: Boolean = false,
    ) {
        val preloadStarted = CountDownLatch(1)
        val releasePreload = CountDownLatch(1)
        val preloadCompleted = CountDownLatch(1)
        val releaseBroadBind = CountDownLatch(if (exactPreloadBeforeBroadBind) 1 else 0)
        val rosterStarted = CountDownLatch(1)
        val releaseRoster = CountDownLatch(if (holdRoster) 1 else 0)
        val targetBroadBindStarted = CountDownLatch(1)
        val includeThirdMember = AtomicBoolean(false)
        val rosterFails = AtomicBoolean(rosterFails)
    }

    /** Provides separate account identities with no persisted target roster or network dependency. */
    internal fun notificationRouteAppState(
        scriptedSubscriptions: ScriptedConversationLiveSubscriptions,
        routeGate: NotificationRouteGate,
    ): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { accountRef ->
                when (accountRef) {
                    ConversationTimelineTestIds.ACCOUNT_REF -> ConversationTimelineTestIds.ACCOUNT_ID
                    TARGET_ACCOUNT -> TARGET_ACCOUNT_ID
                    else -> null
                }
            },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ConversationTimelineTestIds.ACCOUNT_REF,
                        accountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                    AccountSummaryFfi(
                        label = TARGET_ACCOUNT,
                        accountIdHex = TARGET_ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            profileDisplayNameReader = { id -> SENDER_NAME.takeIf { id == ConversationTimelineTestIds.SENDER_ID } },
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(
                    state,
                    AppMarmotRuntime(
                        rootPath = "test",
                        marmot = notificationRouteMarmot(routeGate),
                    ),
                )
            state.liveSubscriptionOverrides.conversation = scriptedSubscriptions.subscriptions
        }
    }

    /** Gates native projection, roster, and broad-list calls without replacing the production route logic. */
    @Suppress("CyclomaticComplexMethod")
    private fun notificationRouteMarmot(routeGate: NotificationRouteGate): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name.substringBefore('-')) {
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                // Preserve ordinary notification activation through the setup eligibility check.
                "onboardingRecoveryRequired" -> false
                "onboardingSnapshot" -> null
                "groupDetails" -> {
                    groupDetails()
                }
                "groupRoster" -> {
                    val accountRef = arguments?.firstOrNull() as? String
                    val groupIdHex = arguments?.getOrNull(1) as? String
                    check(accountRef == TARGET_ACCOUNT) { "roster read used an unknown account" }
                    check(groupIdHex == ConversationTimelineTestIds.GROUP_ID) { "roster read used the wrong group" }
                    routeGate.rosterStarted.countDown()
                    check(routeGate.releaseRoster.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        "target roster gate timed out"
                    }
                    if (routeGate.rosterFails.get()) error("target roster unavailable")
                    targetRoster(includeThirdMember = routeGate.includeThirdMember.get())
                }
                "groupRecoveryStatus" -> notificationRecoveryStatus(arguments)
                "chatListRow" -> {
                    val accountRef = arguments?.firstOrNull() as? String
                    val groupIdHex = arguments?.getOrNull(1) as? String
                    check(accountRef == TARGET_ACCOUNT) { "projection read used an unknown account" }
                    check(groupIdHex == ConversationTimelineTestIds.GROUP_ID) { "projection read used the wrong group" }
                    routeGate.preloadStarted.countDown()
                    check(routeGate.releasePreload.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        "notification preload gate timed out"
                    }
                    routeGate.preloadCompleted.countDown()
                    preGapChatListRow()
                }
                "openPresentedChatList" -> {
                    val accountRef = arguments?.firstOrNull() as? String
                    if (accountRef == TARGET_ACCOUNT) {
                        check(routeGate.releaseBroadBind.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                            "target broad-list bind gate timed out"
                        }
                        routeGate.targetBroadBindStarted.countDown()
                    }
                    error("Skip broad-list startup in the focused route test")
                }
                "toString" -> "NotificationRouteTimelineMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("Unexpected Marmot call: ${method.name}")
            }
        } as MarmotInterface

    /** Returns an empty recovery state after asserting the notification route's ownership arguments. */
    private fun notificationRecoveryStatus(arguments: Array<out Any?>?): GroupRecoveryStatusFfi {
        val accountRef = arguments?.firstOrNull() as? String
        val groupIdHex = arguments?.getOrNull(1) as? String
        check(accountRef == TARGET_ACCOUNT) { "recovery read used an unknown account" }
        check(groupIdHex == ConversationTimelineTestIds.GROUP_ID) { "recovery read used the wrong group" }
        return GroupRecoveryStatusFfi(
            groupIdHex = groupIdHex,
            automaticRecoveryFailed = false,
            pendingReinvites = 0u,
            failedReinvites = 0u,
            rejoinInvitations = emptyList(),
        )
    }

    private fun preGapChatListRow() =
        notificationChatListRow().let { row ->
            row.copy(
                lastMessage =
                    row.lastMessage?.copy(
                        messageIdHex = ConversationTimelineTestIds.MESSAGE_A,
                        plaintext = "older body",
                        timelineAt = 1uL,
                    ),
                unreadCount = 0uL,
                hasUnread = false,
                firstUnreadMessageIdHex = null,
                activitySortAt = 1uL,
                updatedAt = 1uL,
            )
        }

    protected fun routedTarget(accountRef: String): InboundIntentRouting {
        val target =
            NotificationTarget(
                accountRef = accountRef,
                groupIdHex = ConversationTimelineTestIds.GROUP_ID,
                messageIdHex = ConversationTimelineTestIds.MESSAGE_B,
                kind = NotificationTargetKind.MESSAGE,
            )
        val intent = Intent()
        val notificationKey = "timeline-route-card"
        NotificationNavigation.applyToIntent(intent, target, notificationKey, TAP_TOKEN)
        val parsed =
            NotificationNavigation.parse(intent) { parsedNotificationKey, tapToken ->
                parsedNotificationKey == notificationKey && tapToken == TAP_TOKEN
            }
        return routeInboundIntent(
            parsedTarget = parsed,
            shareRequest = null,
            dataString = null,
            current = InboundIntentRouting(notificationTarget = null, profilePayload = null),
        )
    }

    private fun groupDetails() =
        GroupDetailsFfi(
            group = conversationTimelineTestGroup(),
            members = emptyList(),
            mlsState =
                AppGroupMlsStateFfi(
                    groupIdHex = ConversationTimelineTestIds.GROUP_ID,
                    protocolProfile = AppProtocolProfileFfi.CURRENT,
                    lifecycleState = GroupLifecycleStateFfi.STABLE,
                    epoch = 0uL,
                    memberCount = 1u,
                    unrecoverable = false,
                    requiredAppComponents = emptyList(),
                    disbandingEnabled = false,
                    disbanding = false,
                    disbandingBlockers = emptyList(),
                    disbandRequest = null,
                ),
        )

    /** Returns authoritative two-/three-member target rosters without changing semantic group kind. */
    private fun targetRoster(includeThirdMember: Boolean) =
        GroupRosterFfi(
            groupIdHex = ConversationTimelineTestIds.GROUP_ID,
            members =
                buildList {
                    add(
                        rosterMember(
                            id = TARGET_ACCOUNT_ID,
                            accountRef = TARGET_ACCOUNT,
                            local = true,
                            isSelf = true,
                        ),
                    )
                    add(rosterMember(id = ConversationTimelineTestIds.SENDER_ID))
                    if (includeThirdMember) add(rosterMember(id = THIRD_MEMBER_ID))
                },
            epoch = 1uL,
            rosterRevision = 1uL,
            selfMembership = SelfMembershipFfi.MEMBER,
            memberCount = if (includeThirdMember) 3u else 2u,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
        )

    /** Models explicit self and peer membership as supplied by the native roster projection. */
    private fun rosterMember(
        id: String,
        accountRef: String? = null,
        local: Boolean = false,
        isSelf: Boolean = false,
    ) = GroupMemberDetailsFfi(
        memberIdHex = id,
        account = accountRef,
        local = local,
        isAdmin = isSelf,
        isSelf = isSelf,
        npub = "npub-$id",
        displayName = SENDER_NAME.takeIf { id == ConversationTimelineTestIds.SENDER_ID },
    )

    /** Deliberately disagrees with the target roster to detect cross-account presentation reuse. */
    protected fun sourceAccountThreeMemberSnapshot() =
        listOf(
            cachedMember(
                id = ConversationTimelineTestIds.ACCOUNT_ID,
                accountRef = ConversationTimelineTestIds.ACCOUNT_REF,
                local = true,
            ),
            cachedMember(id = ConversationTimelineTestIds.SENDER_ID),
            cachedMember(id = "ff".repeat(32)),
        )

    /** Creates only a source-account opening-snapshot row, never a target membership guess. */
    private fun cachedMember(
        id: String,
        accountRef: String? = null,
        local: Boolean = false,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = id,
        account = accountRef,
        local = local,
    )

    /** Pumps ready UI work and yields to real workers without advancing unrelated future main-loop deadlines. */
    protected fun awaitCondition(
        failureMessage: () -> String = { "Condition not met after route deadline" },
        condition: () -> Boolean,
    ) {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ROUTE_TIMEOUT_MILLIS)
        while (System.nanoTime() <= deadlineNanos) {
            composeRule.waitForIdle()
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError(failureMessage())
    }

    /** Summarizes synthetic route ownership without logging account or message identifiers. */
    protected fun routeState(
        appState: WhiteNoiseAppState,
        mountedController: ConversationController? = null,
        handled: AtomicBoolean? = null,
    ): String {
        val attachedControllers = appState.attachedConversationControllersForTest()
        val mountedMessageIds = mountedController?.let(::timelineMessageIds).orEmpty()
        return "targetAccountActive=${appState.activeAccountRef == TARGET_ACCOUNT} " +
            "runtimeGeneration=${appState.runtimeGeneration} " +
            "handled=${handled?.get()} " +
            "attachedControllerCount=${attachedControllers.size} " +
            "sameControllerMounted=${mountedController?.let { it in attachedControllers }} " +
            "mountedMessageCount=${mountedMessageIds.size} " +
            "mountedHasInitial=${ConversationTimelineTestIds.MESSAGE_A in mountedMessageIds} " +
            "mountedHasRecovered=${ConversationTimelineTestIds.MESSAGE_B in mountedMessageIds}"
    }

    /** Reads the test-only controller registry while holding its production synchronization lock. */
    @Suppress("UNCHECKED_CAST")
    protected fun WhiteNoiseAppState.attachedConversationControllersForTest(): List<ConversationController> {
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

    /** Evaluates a fresh target message against the AppState policy owned by the mounted route. */
    protected fun WhiteNoiseAppState.shouldPostIncomingTargetNotification(
        accountRef: String = TARGET_ACCOUNT,
        accountIdHex: String = TARGET_ACCOUNT_ID,
    ): Boolean {
        val method =
            WhiteNoiseAppState::class.java
                .getDeclaredMethod(
                    "shouldPostNotification",
                    NotificationUpdateFfi::class.java,
                    Boolean::class.javaPrimitiveType!!,
                ).apply { isAccessible = true }
        return method.invoke(this, targetNotificationUpdate(accountRef, accountIdHex), false) as Boolean
    }

    /** Creates one ordinary incoming update for the exact notification-routed account and group. */
    private fun targetNotificationUpdate(
        accountRef: String,
        accountIdHex: String,
    ) = NotificationUpdateFfi(
        notificationKey = "message:$accountRef:fresh-message",
        conversationKey = "conversation:$accountRef:${ConversationTimelineTestIds.GROUP_ID}",
        trigger = NotificationTriggerFfi.NEW_MESSAGE,
        trafficClass = NotificationTrafficClassFfi.STANDARD,
        accountRef = accountRef,
        accountIdHex = accountIdHex,
        groupIdHex = ConversationTimelineTestIds.GROUP_ID,
        groupName = "Fixture group",
        isDm = false,
        isMention = false,
        messageIdHex = "fresh-message",
        sender =
            NotificationUserFfi(
                accountIdHex = ConversationTimelineTestIds.SENDER_ID,
                displayName = SENDER_NAME,
                pictureUrl = null,
            ),
        receiver =
            NotificationUserFfi(
                accountIdHex = accountIdHex,
                displayName = "Fixture owner",
                pictureUrl = null,
            ),
        previewText = "Fresh incoming message",
        reactionEmoji = null,
        reactedToPreview = null,
        timestampMs = 1_982L,
        isFromSelf = false,
    )

    /** Adds a fixture controller once using ownership identity rather than value equality. */
    private fun MutableList<ConversationController>.addIfAbsentByIdentity(controller: ConversationController) {
        if (none { it === controller }) add(controller)
    }
}

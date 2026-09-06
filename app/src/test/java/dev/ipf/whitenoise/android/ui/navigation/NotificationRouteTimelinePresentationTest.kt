package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.notifications.InboundIntentRouting
import dev.ipf.whitenoise.android.notifications.NotificationNavigation
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.notifications.routeInboundIntent
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestDraftPersistence
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.assertTimelineSubscriptionSnapshotBeforeFirstNextWindow
import dev.ipf.whitenoise.android.state.awaitOpenedTimelineSubscriptionsClosed
import dev.ipf.whitenoise.android.state.conversationTimelineReconnectFixtures
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.notificationChatListRow
import dev.ipf.whitenoise.android.state.timelineMessageIds
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Regression for #2233: a notification-routed conversation must keep the same
 * mounted controller across background/foreground resume and surface a gap
 * message from the replacement subscription snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class NotificationRouteTimelinePresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Keeps one routed controller mounted while a foreground reconnect publishes the notified row. */
    @Test
    fun notificationRoutedReconnectShowsNotifiedMessageWithoutRecreatingController() {
        val fixtures = conversationTimelineReconnectFixtures()
        val routeGate = NotificationRouteGate(preloadFinishesFirst = true)
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
            routeGate.releaseActivation.countDown()
            try {
                disposeNotificationRoute(shellMounted, appState, mountedController)
            } finally {
                awaitOpenedTimelineSubscriptionsClosed(fixtures.scriptedSubscriptions)
            }
        }
    }

    /** Mounts the production shell with one authenticated synthetic notification route. */
    private fun mountNotificationRoute(
        appState: WhiteNoiseAppState,
        routed: InboundIntentRouting,
        handled: AtomicBoolean,
        inboundRequestId: MutableState<Long>,
        shellMounted: MutableState<Boolean>,
    ) {
        appState.setAppInForeground(true)
        composeRule.setContent {
            if (shellMounted.value) {
                var inboundTarget by remember { mutableStateOf(routed.notificationTarget) }
                WhiteNoiseTheme {
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

    /** Disposes the shell and clears every controller attached by this fixture's app state. */
    private fun disposeNotificationRoute(
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

    /** Releases activation after preload and returns the sole controller with its initial row. */
    private fun awaitMountedNotificationConversation(
        routeGate: NotificationRouteGate,
        handled: AtomicBoolean,
        appState: WhiteNoiseAppState,
    ): ConversationController {
        awaitCondition(
            failureMessage = {
                "notification preload did not complete: ${routeState(appState, handled = handled)} " +
                    "preloadStarted=${routeGate.preloadStarted.count} " +
                    "preloadCompleted=${routeGate.preloadCompleted.count}"
            },
        ) {
            routeGate.preloadCompleted.count == 0L
        }
        routeGate.releaseActivation.countDown()
        awaitCondition(
            failureMessage = {
                "notification target was not handled after activation: " +
                    routeState(appState, handled = handled)
            },
        ) {
            handled.get()
        }
        awaitCondition(
            failureMessage = {
                "initial routed timeline did not mount: ${routeState(appState, handled = handled)}"
            },
        ) {
            appState.attachedConversationControllersForTest().singleOrNull()?.let { controller ->
                timelineMessageIds(controller) == listOf(ConversationTimelineTestIds.MESSAGE_A)
            } == true
        }
        return appState.attachedConversationControllersForTest().single()
    }

    /** Forces the mounted notification conversation through a background reconnect. */
    private fun reconnectWhileBackground(
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

    /** Simulates foreground resume and verifies that route recomposition retains the controller. */
    private fun resumeAfterForeground(
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
    private fun assertNotificationReconnectPresentation(
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

    /** Coordinates background projection and account-activation boundaries without blocking the test owner. */
    private class NotificationRouteGate(
        preloadFinishesFirst: Boolean,
    ) {
        val preloadStarted = CountDownLatch(1)
        val releasePreload = CountDownLatch(if (preloadFinishesFirst) 0 else 1)
        val preloadCompleted = CountDownLatch(1)
        val releaseActivation = CountDownLatch(if (preloadFinishesFirst) 1 else 0)
    }

    /** Builds the two-account app state with an injected scripted conversation subscription owner. */
    private fun notificationRouteAppState(
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

    /** Supplies only the projection and activation calls required by the routed-open fixture. */
    private fun notificationRouteMarmot(routeGate: NotificationRouteGate): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "groupDetails" -> {
                    groupDetails()
                }
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
                "subscribeChatList" -> {
                    val accountRef = arguments?.firstOrNull() as? String
                    if (accountRef == TARGET_ACCOUNT) {
                        routeGate.releaseActivation.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    }
                    error("Skip broad-list startup in the focused route test")
                }
                "toString" -> "NotificationRouteTimelineMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("Unexpected Marmot call: ${method.name}")
            }
        } as MarmotInterface

    /** Returns the cached pre-gap row that the replacement timeline must advance. */
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

    /** Produces a notification target only after the normal tap-token validation path accepts it. */
    private fun routedTarget(accountRef: String): InboundIntentRouting {
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

    /** Provides stable group metadata for the synthetic target conversation. */
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

    /**
     * Pumps Compose, Robolectric main, and real worker time until one route phase completes.
     * The monotonic deadline keeps the original real-time bound under full-suite contention.
     */
    private fun awaitCondition(
        failureMessage: () -> String,
        condition: () -> Boolean,
    ) {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ROUTE_TIMEOUT_MILLIS)
        while (System.nanoTime() <= deadlineNanos) {
            composeRule.waitForIdle()
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
            ShadowLooper.idleMainLooper(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
        }
        throw AssertionError(failureMessage())
    }

    /** Summarizes synthetic route ownership without logging account or message identifiers. */
    private fun routeState(
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
    private fun WhiteNoiseAppState.attachedConversationControllersForTest(): List<ConversationController> {
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

    /** Adds a fixture controller once using ownership identity rather than value equality. */
    private fun MutableList<ConversationController>.addIfAbsentByIdentity(controller: ConversationController) {
        if (none { it === controller }) add(controller)
    }

    private companion object {
        const val TARGET_ACCOUNT = "bob"
        val TARGET_ACCOUNT_ID = "ee".repeat(32)
        const val TAP_TOKEN = "timeline-route-token"
        const val ROUTE_TIMEOUT_MILLIS = 10_000L
        const val POLL_INTERVAL_MILLIS = 10L
    }
}

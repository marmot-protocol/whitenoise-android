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
import dev.ipf.whitenoise.android.state.awaitConversationCondition
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

        var mountedController: ConversationController? = null
        try {
            mountNotificationRoute(appState, routed, handled, inboundRequestId)
            mountedController = awaitMountedNotificationConversation(routeGate, handled, appState)

            reconnectWhileBackground(
                appState = appState,
                firstSubscription = fixtures.firstSubscription,
                mountedController = mountedController,
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
            mountedController?.let { controller ->
                appState.detachConversationController(controller)
                controller.onCleared()
            }
            awaitOpenedTimelineSubscriptionsClosed(fixtures.scriptedSubscriptions)
        }
    }

    private fun mountNotificationRoute(
        appState: WhiteNoiseAppState,
        routed: InboundIntentRouting,
        handled: AtomicBoolean,
        inboundRequestId: MutableState<Long>,
    ) {
        appState.setAppInForeground(true)
        composeRule.setContent {
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

    private fun awaitMountedNotificationConversation(
        routeGate: NotificationRouteGate,
        handled: AtomicBoolean,
        appState: WhiteNoiseAppState,
    ): ConversationController {
        check(routeGate.preloadCompleted.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            "notification preload did not complete"
        }
        routeGate.releaseActivation.countDown()
        awaitCondition { handled.get() }
        awaitCondition {
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
    ) {
        assertFalse(ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(mountedController))
        appState.setAppInForeground(false)
        awaitCondition { firstSubscription.nextWindowCallCount == 1 }
        firstSubscription.endWindows()
        awaitCondition { firstSubscription.closeCallCount == 1 }
        runBlocking { mountedController.retryLoadFailure() }
        awaitCondition {
            ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(mountedController)
        }
    }

    private fun resumeAfterForeground(
        appState: WhiteNoiseAppState,
        inboundRequestId: MutableState<Long>,
        mountedController: ConversationController,
    ) {
        appState.setAppInForeground(true)
        composeRule.runOnIdle {
            inboundRequestId.value += 1L
        }
        awaitCondition {
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
        awaitConversationCondition { replacementSubscription.nextWindowCallCount >= 1 }
        assertTimelineSubscriptionSnapshotBeforeFirstNextWindow(replacementSubscription)
        composeRule.onNodeWithText("notified body").assertIsDisplayed()
        assertFalse(attachedControllers.any { it !== mountedController })
    }

    private class NotificationRouteGate(
        preloadFinishesFirst: Boolean,
    ) {
        val preloadStarted = CountDownLatch(1)
        val releasePreload = CountDownLatch(if (preloadFinishesFirst) 0 else 1)
        val preloadCompleted = CountDownLatch(1)
        val releaseActivation = CountDownLatch(if (preloadFinishesFirst) 1 else 0)
    }

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

    private fun awaitCondition(
        failureMessage: (() -> String)? = null,
        condition: () -> Boolean,
    ) {
        awaitConversationCondition(timeoutMs = ROUTE_TIMEOUT_MILLIS, condition = condition)
        composeRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        if (!condition()) {
            throw AssertionError(failureMessage?.invoke() ?: "Condition not met after idle")
        }
    }

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

    private companion object {
        const val TARGET_ACCOUNT = "bob"
        val TARGET_ACCOUNT_ID = "ee".repeat(32)
        const val TAP_TOKEN = "timeline-route-token"
        const val ROUTE_TIMEOUT_MILLIS = 10_000L
    }
}

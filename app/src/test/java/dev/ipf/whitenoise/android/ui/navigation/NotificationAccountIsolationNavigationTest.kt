package dev.ipf.whitenoise.android.ui.navigation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.NotificationCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import dev.ipf.whitenoise.android.notifications.NotificationMessageDirectLoadOutcome
import dev.ipf.whitenoise.android.notifications.NotificationReplyDraft
import dev.ipf.whitenoise.android.notifications.NotificationScenario
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.loadNotificationMessageDirectly
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Full Compose-route coverage for inactive-account notification navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
@Suppress("LargeClass") // The 0.10.0 fixtures add two engine cases; the scenarios themselves are unchanged.
class NotificationAccountIsolationNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        manager.cancelAll()
        manager.createNotificationChannel(
            NotificationChannel(TEST_CHANNEL, "Test", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    @After
    fun tearDown() {
        manager.cancelAll()
        manager.deleteNotificationChannel(TEST_CHANNEL)
    }

    @Test
    fun inactiveAccountTap_preloadOpensBeforeBroadActivationWork_preservesSourceAccountCards() {
        verifyInactiveAccountTapIsolation(preloadFinishesFirst = true)
    }

    /** A failed draft read still opens and consumes the route, and later navigation is not hijacked. */
    @Test
    fun failedNotificationDraftStillOpensAndConsumesItsRouteWithoutLaterNavigationHijack() {
        val gate = AccountRouteOrderGate(preloadFinishesFirst = true)
        gate.releaseActivation.countDown()
        val state = appState(fakeMarmot(gate))
        state.setAppInForeground(true)
        val holder = MainShellStateHolder(state, SavedStateHandle())
        val routed = routedTarget(SOURCE_ACCOUNT)
        val target =
            checkNotNull(routed.notificationTarget).copy(
                replyDraft = NotificationReplyDraft("failed-route", "partial reply"),
            )
        val inbound = mutableStateOf<NotificationTarget?>(target)
        val handled = AtomicInteger()
        composeRule.setContent {
            WhiteNoiseTheme {
                MainShell(
                    appState = state,
                    stateHolder = holder,
                    inboundNotificationTarget = inbound.value,
                    inboundNotificationRequestId = routed.notificationRequestId,
                    onNotificationTargetHandled = { _, _ ->
                        handled.incrementAndGet()
                        inbound.value = null
                    },
                )
            }
        }
        awaitCondition { handled.get() == 1 }
        assertEquals(SHARED_GROUP, holder.selectedChat.value?.id)
        awaitCondition { gate.draftReadCount.get() >= 6 }
        composeRule.runOnIdle {
            holder.selectedChat.value = null
            state.setAppInForeground(false)
            state.setAppInForeground(true)
        }
        composeRule.waitForIdle()
        assertEquals(1, handled.get())
        assertEquals(null, holder.selectedChat.value)
        composeRule.runOnIdle {
            state.mutationsScope.cancel()
            holder.release()
        }
        awaitCondition { state.mutationsScope.coroutineContext[Job]?.isCompleted != false }
    }

    @Test
    fun inactiveAccountTap_activationFinishesBeforePreload_preservesSourceAccountCards() {
        verifyInactiveAccountTapIsolation(preloadFinishesFirst = false)
    }

    /** The exact preload reads the target's production projection while the source account stays active. */
    @Test
    fun inactiveAccountPreload_readsExactProductionProjectionWhileSourceAccountIsActive() {
        val gate = AccountRouteOrderGate(preloadFinishesFirst = true)
        val appState = appState(fakeMarmot(gate))

        val item =
            runBlocking {
                appState.preloadNotificationChatListItem(TARGET_ACCOUNT, SHARED_GROUP)
            }

        assertEquals(SOURCE_ACCOUNT, appState.activeAccountRef)
        assertEquals(3uL, item.projection?.unreadCount)
        assertEquals(OLDEST_UNREAD_ID, item.projection?.firstUnreadMessageIdHex)
        assertEquals(1, gate.projectionReadCount.get())
    }

    /** The preload opens from its projection when roster enrichment is unavailable. */
    @Test
    fun inactiveAccountPreloadOpensFromProjectionWhenRosterEnrichmentIsUnavailable() {
        val gate =
            AccountRouteOrderGate(
                preloadFinishesFirst = true,
                rosterReadFails = true,
            )
        val appState = appState(fakeMarmot(gate))

        val item =
            runBlocking {
                appState.preloadNotificationChatListItem(TARGET_ACCOUNT, SHARED_GROUP)
            }

        assertEquals(SHARED_GROUP, item.id)
        assertEquals(3uL, item.projection?.unreadCount)
        assertEquals(0, gate.rosterReadCount.get())
    }

    /** A missing projection is inconclusive and waits for the broad chat list. */
    @Test
    fun inactiveAccountPreload_missingProjectionWaitsForBroadList() {
        val gate =
            AccountRouteOrderGate(
                preloadFinishesFirst = true,
                projectionAvailable = false,
            )
        val appState = appState(fakeMarmot(gate))

        val outcome =
            runBlocking {
                loadNotificationMessageDirectly {
                    appState.preloadNotificationChatListItem(TARGET_ACCOUNT, SHARED_GROUP)
                }
            }

        assertEquals(NotificationMessageDirectLoadOutcome.AwaitChatList, outcome)
        assertEquals(SOURCE_ACCOUNT, appState.activeAccountRef)
        assertEquals(1, gate.projectionReadCount.get())
    }

    /** A foreground resume does not dismiss the retained source conversation's cards. */
    @Test
    fun notificationForegroundResumeDoesNotDismissRetainedSourceConversationCards() {
        val gate = AccountRouteOrderGate(preloadFinishesFirst = true)
        val appState = appState(fakeMarmot(gate))
        appState.setAppInForeground(true)
        runBlocking { appState.setActiveConversation(SOURCE_ACCOUNT, SHARED_GROUP) }
        appState.setAppInForeground(false)
        val sourceKeys = postConversationCards(SOURCE_ACCOUNT, "source-invite")
        postConversationCards(TARGET_ACCOUNT, "target-invite")
        val routed = routedTarget(TARGET_ACCOUNT)
        val handled = AtomicBoolean(false)

        // MainActivity defers the retained A-conversation cleanup for a
        // notification-owned foreground entry. MainShell must then clear only
        // B's destination cards, even if its local preload wins activation.
        appState.setAppInForeground(
            foreground = true,
            dismissRetainedVisibleConversation = false,
        )
        composeRule.setContent {
            var inboundTarget by remember { mutableStateOf(routed.notificationTarget) }
            WhiteNoiseTheme {
                MainShell(
                    appState = appState,
                    inboundNotificationTarget = inboundTarget,
                    inboundNotificationRequestId = routed.notificationRequestId,
                    onNotificationTargetHandled = { _, _ ->
                        handled.set(true)
                        inboundTarget = null
                    },
                )
            }
        }

        awaitCondition { handled.get() }
        awaitCondition {
            activeUserCardKeys() == sourceKeys.toSet()
        }
        gate.releaseActivation.countDown()
    }

    @Test
    fun ordinaryConversation_accountSwitchInvalidatesOwnershipBeforeDestinationDismissal() {
        val renderedAccount = mutableStateOf<String?>(SOURCE_ACCOUNT)
        val navigationAccountStable = mutableStateOf(true)
        val observedOwnership = CopyOnWriteArrayList<Pair<String?, String?>>()

        composeRule.setContent {
            ConversationNotificationOwnershipEffect(
                selectedChatId = SHARED_GROUP,
                selectedGroupIdHex = SHARED_GROUP,
                renderedChatId = SHARED_GROUP,
                renderedAccountRef = renderedAccount.value,
                navigationAccountStable = navigationAccountStable.value,
                timelineVisible = true,
                onOwnershipChanged = { accountRef, groupIdHex ->
                    observedOwnership += accountRef to groupIdHex
                },
            )
        }

        awaitCondition { observedOwnership.lastOrNull() == (SOURCE_ACCOUNT to SHARED_GROUP) }
        composeRule.runOnIdle {
            // The active account has flipped, but MainShell has not yet cleared
            // the ordinary selection. Its new controller/account calculation
            // must not turn that stale selection into destination ownership.
            renderedAccount.value = TARGET_ACCOUNT
            navigationAccountStable.value = false
        }
        awaitCondition { observedOwnership.lastOrNull() == (null to null) }
        assertFalse(observedOwnership.contains(TARGET_ACCOUNT to SHARED_GROUP))
    }

    @Test
    fun hiddenTimeline_staysUnownedWhenNavigationBecomesStable() {
        val navigationAccountStable = mutableStateOf(false)
        val timelineVisible = mutableStateOf(false)
        val observedOwnership = CopyOnWriteArrayList<Pair<String?, String?>>()

        composeRule.setContent {
            ConversationNotificationOwnershipEffect(
                selectedChatId = SHARED_GROUP,
                selectedGroupIdHex = SHARED_GROUP,
                renderedChatId = SHARED_GROUP,
                renderedAccountRef = TARGET_ACCOUNT,
                navigationAccountStable = navigationAccountStable.value,
                timelineVisible = timelineVisible.value,
                onOwnershipChanged = { accountRef, groupIdHex ->
                    observedOwnership += accountRef to groupIdHex
                },
            )
        }

        awaitCondition { observedOwnership.lastOrNull() == (null to null) }
        composeRule.runOnIdle { navigationAccountStable.value = true }
        awaitCondition { observedOwnership.lastOrNull() == (null to null) }
        assertFalse(observedOwnership.contains(TARGET_ACCOUNT to SHARED_GROUP))

        composeRule.runOnIdle { timelineVisible.value = true }
        awaitCondition { observedOwnership.lastOrNull() == (TARGET_ACCOUNT to SHARED_GROUP) }
    }

    @Test
    fun backFromTargetConversationClearsOwnershipWithoutPublishingSourceAccount() {
        val selectedChatId = mutableStateOf<String?>(SHARED_GROUP)
        val observedOwnership = CopyOnWriteArrayList<Pair<String?, String?>>()

        composeRule.setContent {
            ConversationNotificationOwnershipEffect(
                selectedChatId = selectedChatId.value,
                selectedGroupIdHex = selectedChatId.value,
                renderedChatId = SHARED_GROUP,
                renderedAccountRef = TARGET_ACCOUNT,
                navigationAccountStable = true,
                timelineVisible = true,
                onOwnershipChanged = { accountRef, groupIdHex ->
                    observedOwnership += accountRef to groupIdHex
                },
            )
        }

        awaitCondition { observedOwnership.lastOrNull() == (TARGET_ACCOUNT to SHARED_GROUP) }

        composeRule.runOnIdle { selectedChatId.value = null }

        awaitCondition { observedOwnership.lastOrNull() == (null to null) }
        assertFalse(observedOwnership.contains(SOURCE_ACCOUNT to SHARED_GROUP))
    }

    /** An ordinary account switch dismisses source cards and keeps the destination's. */
    @Test
    fun mainShell_ordinaryConversationAccountSwitchPreservesDestinationCards() {
        val gate =
            AccountRouteOrderGate(
                preloadFinishesFirst = true,
                holdSourceBroadList = true,
            )
        // This case does not exercise inactive-account activation ordering.
        // Let the destination broad bind complete after the explicit switch;
        // the source broad bind stays held only until the direct route commits.
        gate.releaseActivation.countDown()
        val appState = appState(fakeMarmot(gate))
        val sourceKeys = postConversationCards(SOURCE_ACCOUNT, "source-invite")
        val targetKeys = postConversationCards(TARGET_ACCOUNT, "target-invite")
        val routed = routedTarget(SOURCE_ACCOUNT)
        val handled = AtomicBoolean(false)

        appState.setAppInForeground(true)
        composeRule.setContent {
            var inboundTarget by remember { mutableStateOf(routed.notificationTarget) }
            WhiteNoiseTheme {
                MainShell(
                    appState = appState,
                    inboundNotificationTarget = inboundTarget,
                    inboundNotificationRequestId = routed.notificationRequestId,
                    onNotificationTargetHandled = { _, _ ->
                        handled.set(true)
                        gate.releaseSourceBroadList.countDown()
                        inboundTarget = null
                    },
                )
            }
        }

        awaitCondition { handled.get() }
        awaitCondition(
            failureMessage = {
                val activeKeys = activeUserCardKeys()
                "source cards were not dismissed while destination cards remained: " +
                    "active=$activeKeys source=${sourceKeys.toSet()} target=${targetKeys.toSet()}"
            },
        ) {
            val activeKeys = activeUserCardKeys()
            sourceKeys.none { it in activeKeys } && targetKeys.all { it in activeKeys }
        }
        assertEquals(SOURCE_ACCOUNT, appState.activeAccountRef)

        composeRule.runOnIdle { setActiveAccountRefForTest(appState, TARGET_ACCOUNT) }
        awaitCondition { appState.activeAccountRef == TARGET_ACCOUNT }
        awaitCondition {
            val activeKeys = activeUserCardKeys()
            targetKeys.all { it in activeKeys }
        }
    }

    /** Cancelling cards for a visible conversation never waits on the listener dispatcher. */
    @Test
    fun visibleConversationCancellationDoesNotWaitForNotificationListenerDispatcher() {
        val listenerExecutor = Executors.newSingleThreadExecutor()
        val listenerDispatcher = listenerExecutor.asCoroutineDispatcher()
        val listenerStarted = CountDownLatch(1)
        val releaseListener = CountDownLatch(1)

        try {
            listenerExecutor.execute {
                listenerStarted.countDown()
                releaseListener.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
            check(listenerStarted.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "notification listener dispatcher did not start"
            }

            val sourceKeys = postConversationCards(SOURCE_ACCOUNT, "source-invite")
            val appState =
                appState(
                    marmot = fakeMarmot(AccountRouteOrderGate(preloadFinishesFirst = true)),
                    notificationDispatcher = listenerDispatcher,
                )

            appState.setActiveConversationFromUi(SOURCE_ACCOUNT, SHARED_GROUP)

            awaitCondition {
                val activeKeys = activeUserCardKeys()
                sourceKeys.none { it in activeKeys }
            }
        } finally {
            releaseListener.countDown()
            listenerDispatcher.close()
        }
    }

    /** Routes a tap for the inactive account and checks only its cards are dismissed in either activation order. */
    private fun verifyInactiveAccountTapIsolation(preloadFinishesFirst: Boolean) {
        val gate = AccountRouteOrderGate(preloadFinishesFirst)
        val appState = appState(fakeMarmot(gate))
        val sourceKeys = postConversationCards(SOURCE_ACCOUNT, "source-invite")
        val targetKeys = postConversationCards(TARGET_ACCOUNT, "target-invite")
        val lateSource = "source-during-route" to 52
        val routed = routedTarget(TARGET_ACCOUNT)
        val handled = AtomicBoolean(false)

        appState.setAppInForeground(true)
        composeRule.setContent {
            var inboundTarget by remember { mutableStateOf(routed.notificationTarget) }
            WhiteNoiseTheme {
                MainShell(
                    appState = appState,
                    inboundNotificationTarget = inboundTarget,
                    inboundNotificationRequestId = routed.notificationRequestId,
                    onNotificationTargetHandled = { _, _ ->
                        handled.set(true)
                        inboundTarget = null
                    },
                )
            }
        }

        awaitCondition { gate.preloadStarted.count == 0L }
        manager.notify(lateSource.first, lateSource.second, notification(SOURCE_ACCOUNT))

        if (preloadFinishesFirst) {
            awaitCondition(
                failureMessage = {
                    "destination cards were not dismissed before activation: " +
                        "active=${activeUserCardKeys()} " +
                        "expected=${(sourceKeys + lateSource).toSet()}"
                },
            ) {
                activeUserCardKeys() ==
                    (sourceKeys + lateSource).toSet()
            }
            // setActiveAccount publishes the target ref before the gated broad
            // chat-list subscription finishes. The direct conversation can
            // already own and dismiss only its target cards in that window, and
            // the commit may land a frame before or after the ref flips, so wait
            // for the ref while the activation gate still holds the broad bind.
            awaitCondition(
                failureMessage = {
                    "target account was not published before the gated broad bind: " +
                        "active=${appState.activeAccountRef} broadBindStarted=${gate.broadBindStarted.count == 0L}"
                },
            ) {
                appState.activeAccountRef == TARGET_ACCOUNT && gate.broadBindStarted.count == 0L
            }
            gate.releaseActivation.countDown()
        } else {
            awaitCondition {
                appState.activeAccountRef == TARGET_ACCOUNT
            }
            assertEquals(1L, gate.broadBindStarted.count)
            assertEquals(
                (sourceKeys + targetKeys + lateSource).toSet(),
                activeUserCardKeys(),
            )
            gate.releasePreload.countDown()
        }

        verifyRouteCompletion(appState, gate, handled, (sourceKeys + lateSource).toSet())
    }

    /** The app summary has no account ownership; every actual child key still participates. */
    private fun activeUserCardKeys(): Set<Pair<String?, Int>> =
        manager.activeNotifications
            .filterNot {
                it.tag == dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup.SUMMARY_TAG &&
                    it.id == dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup.SUMMARY_ID
            }.map { it.tag to it.id }
            .toSet()

    /** Asserts the route preloaded, was handled, activated the target and dismissed only the destination cards. */
    private fun verifyRouteCompletion(
        appState: WhiteNoiseAppState,
        gate: AccountRouteOrderGate,
        handled: AtomicBoolean,
        expectedNotificationKeys: Set<Pair<String?, Int>>,
    ) {
        check(gate.preloadCompleted.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            "notification preload did not complete"
        }
        awaitCondition { handled.get() }
        awaitCondition { appState.activeAccountRef == TARGET_ACCOUNT }
        awaitCondition(
            failureMessage = {
                "destination cards were not dismissed after the routed open: " +
                    "active=${activeUserCardKeys()} " +
                    "expected=$expectedNotificationKeys runtimeGeneration=${appState.runtimeGeneration} " +
                    "projectionReads=${gate.projectionReadCount.get()} handled=${handled.get()}"
            },
        ) {
            activeUserCardKeys() == expectedNotificationKeys
        }
        assertEquals(TARGET_ACCOUNT, appState.activeAccountRef)
    }

    /** One parsed tap for [accountRef], built by the shared route harness. */
    private fun routedTarget(accountRef: String) = NotificationRouteHarness.routedTarget(accountRef)

    private fun setActiveAccountRefForTest(
        appState: WhiteNoiseAppState,
        accountRef: String,
    ) {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setActiveAccountRef", String::class.java)
            .apply { isAccessible = true }
            .invoke(appState, accountRef)
    }

    /** The two-account app state around [marmot], built by the shared route harness. */
    private fun appState(
        marmot: MarmotInterface,
        notificationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): WhiteNoiseAppState = NotificationRouteHarness.appState(context, marmot, notificationDispatcher)

    /** The account-isolated engine fake, with the focused-route broad-bind shortcut this suite relies on. */
    private fun fakeMarmot(gate: AccountRouteOrderGate): MarmotInterface = NotificationRouteHarness.fakeMarmot(gate)

    private fun postConversationCards(
        accountRef: String,
        inviteTag: String,
    ): List<Pair<String?, Int>> {
        val keys =
            listOf(
                LocalNotificationFormatter.conversationDismissalKey(accountRef, SHARED_GROUP),
                LocalNotificationFormatter.reactionDismissalKey(accountRef, SHARED_GROUP),
                LocalNotificationFormatter.mentionDismissalKey(accountRef, SHARED_GROUP),
                LocalNotificationFormatter.agentActivityDismissalKey(accountRef, SHARED_GROUP),
            )
        keys.forEach { key -> manager.notify(key.tag, key.id, notification(accountRef)) }
        manager.notify(inviteTag, 51, notification(accountRef))
        return keys.map { it.tag to it.id } + (inviteTag to 51)
    }

    private fun notification(accountRef: String) =
        NotificationCompat
            .Builder(context, TEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Test")
            .addExtras(
                Bundle().apply {
                    putString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF, accountRef)
                    putString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID, SHARED_GROUP)
                },
            ).build()

    private fun awaitCondition(
        failureMessage: (() -> String)? = null,
        condition: () -> Boolean,
    ) {
        val deadlineNanos =
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ROUTE_TIMEOUT_MILLIS)
        while (System.nanoTime() <= deadlineNanos) {
            composeRule.waitForIdle()
            ShadowLooper.idleMainLooper()
            if (condition()) return
            // The route also uses real Dispatchers.IO/Default workers. Give
            // those threads real wall-clock time rather than exhausting a
            // synthetic timeout by advancing only Robolectric's main looper.
            Thread.sleep(POLL_INTERVAL_MILLIS)
            ShadowLooper.idleMainLooper(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
        }
        throw AssertionError(
            failureMessage?.invoke() ?: "Condition not met within ${ROUTE_TIMEOUT_MILLIS}ms",
        )
    }

    private companion object {
        const val SOURCE_ACCOUNT = NotificationRouteHarness.SOURCE_ACCOUNT
        const val TARGET_ACCOUNT = NotificationRouteHarness.TARGET_ACCOUNT
        val SHARED_GROUP = NotificationRouteHarness.SHARED_GROUP
        val OLDEST_UNREAD_ID = NotificationScenario.OLDEST_UNREAD_ID
        const val TEST_CHANNEL = "notification-account-isolation-test"
        const val ROUTE_TIMEOUT_MILLIS = NotificationRouteHarness.ROUTE_TIMEOUT_MILLIS
        const val POLL_INTERVAL_MILLIS = 20L
    }
}

package dev.ipf.whitenoise.android.ui.navigation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.notifications.InboundIntentRouting
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import dev.ipf.whitenoise.android.notifications.NotificationMessageDirectLoadOutcome
import dev.ipf.whitenoise.android.notifications.NotificationNavigation
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.notifications.loadNotificationMessageDirectly
import dev.ipf.whitenoise.android.notifications.routeInboundIntent
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Full Compose-route coverage for inactive-account notification navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
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

    @Test
    fun inactiveAccountTap_activationFinishesBeforePreload_preservesSourceAccountCards() {
        verifyInactiveAccountTapIsolation(preloadFinishesFirst = false)
    }

    @Test
    fun inactiveAccountPreload_readsExactProductionProjectionWhileSourceAccountIsActive() {
        val gate = RouteOrderGate(preloadFinishesFirst = true)
        val appState = appState(fakeMarmot(gate))

        val item =
            runBlocking {
                appState.preloadNotificationChatListItem(TARGET_ACCOUNT, SHARED_GROUP)
            }

        assertEquals(SOURCE_ACCOUNT, appState.activeAccountRef)
        assertEquals(3uL, item.projection?.unreadCount)
        assertEquals(MESSAGE_ID, item.projection?.firstUnreadMessageIdHex)
        assertEquals(1, gate.projectionReadCount.get())
    }

    @Test
    fun inactiveAccountPreloadOpensFromProjectionWhenRosterEnrichmentIsUnavailable() {
        val gate =
            RouteOrderGate(
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

    @Test
    fun inactiveAccountPreload_missingProjectionWaitsForBroadList() {
        val gate =
            RouteOrderGate(
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

    @Test
    fun notificationForegroundResumeDoesNotDismissRetainedSourceConversationCards() {
        val gate = RouteOrderGate(preloadFinishesFirst = true)
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
            manager.activeNotifications.map { it.tag to it.id }.toSet() == sourceKeys.toSet()
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

    @Test
    fun mainShell_ordinaryConversationAccountSwitchPreservesDestinationCards() {
        val gate =
            RouteOrderGate(
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
                val activeKeys = manager.activeNotifications.map { it.tag to it.id }.toSet()
                "source cards were not dismissed while destination cards remained: " +
                    "active=$activeKeys source=${sourceKeys.toSet()} target=${targetKeys.toSet()}"
            },
        ) {
            val activeKeys = manager.activeNotifications.map { it.tag to it.id }.toSet()
            sourceKeys.none { it in activeKeys } && targetKeys.all { it in activeKeys }
        }
        assertEquals(SOURCE_ACCOUNT, appState.activeAccountRef)

        composeRule.runOnIdle { setActiveAccountRefForTest(appState, TARGET_ACCOUNT) }
        awaitCondition { appState.activeAccountRef == TARGET_ACCOUNT }
        awaitCondition {
            val activeKeys = manager.activeNotifications.map { it.tag to it.id }.toSet()
            targetKeys.all { it in activeKeys }
        }
    }

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
                    marmot = fakeMarmot(RouteOrderGate(preloadFinishesFirst = true)),
                    notificationDispatcher = listenerDispatcher,
                )

            appState.setActiveConversationFromUi(SOURCE_ACCOUNT, SHARED_GROUP)

            awaitCondition {
                val activeKeys = manager.activeNotifications.map { it.tag to it.id }.toSet()
                sourceKeys.none { it in activeKeys }
            }
        } finally {
            releaseListener.countDown()
            listenerDispatcher.close()
        }
    }

    private fun verifyInactiveAccountTapIsolation(preloadFinishesFirst: Boolean) {
        val gate = RouteOrderGate(preloadFinishesFirst)
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
                        "active=${manager.activeNotifications.map { it.tag to it.id }.toSet()} " +
                        "expected=${(sourceKeys + lateSource).toSet()}"
                },
            ) {
                manager.activeNotifications.map { it.tag to it.id }.toSet() ==
                    (sourceKeys + lateSource).toSet()
            }
            // setActiveAccount publishes the target ref before the gated broad
            // chat-list subscription finishes. The direct conversation can
            // already own and dismiss only its target cards in that window.
            assertEquals(TARGET_ACCOUNT, appState.activeAccountRef)
            gate.releaseActivation.countDown()
        } else {
            awaitCondition {
                appState.activeAccountRef == TARGET_ACCOUNT
            }
            assertEquals(1L, gate.broadBindStarted.count)
            assertEquals(
                (sourceKeys + targetKeys + lateSource).toSet(),
                manager.activeNotifications.map { it.tag to it.id }.toSet(),
            )
            gate.releasePreload.countDown()
        }

        verifyRouteCompletion(appState, gate, handled, (sourceKeys + lateSource).toSet())
    }

    private fun verifyRouteCompletion(
        appState: WhiteNoiseAppState,
        gate: RouteOrderGate,
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
                    "active=${manager.activeNotifications.map { it.tag to it.id }.toSet()} " +
                    "expected=$expectedNotificationKeys runtimeGeneration=${appState.runtimeGeneration} " +
                    "projectionReads=${gate.projectionReadCount.get()} handled=${handled.get()}"
            },
        ) {
            manager.activeNotifications.map { it.tag to it.id }.toSet() == expectedNotificationKeys
        }
        assertEquals(TARGET_ACCOUNT, appState.activeAccountRef)
    }

    private fun routedTarget(accountRef: String): InboundIntentRouting {
        val target =
            NotificationTarget(
                accountRef = accountRef,
                groupIdHex = SHARED_GROUP,
                messageIdHex = MESSAGE_ID,
                kind = NotificationTargetKind.MESSAGE,
            )
        val intent = Intent()
        val notificationKey = "$accountRef-card"
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

    private fun setActiveAccountRefForTest(
        appState: WhiteNoiseAppState,
        accountRef: String,
    ) {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setActiveAccountRef", String::class.java)
            .apply { isAccessible = true }
            .invoke(appState, accountRef)
    }

    private fun appState(
        marmot: MarmotInterface,
        notificationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(NoopDraftPersistence),
            accountIdHexResolver = { null },
            accounts = listOf(account(SOURCE_ACCOUNT, SOURCE_ID), account(TARGET_ACCOUNT, TARGET_ID)),
            activeAccountRef = SOURCE_ACCOUNT,
            notificationDispatcher = notificationDispatcher,
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }

    private fun fakeMarmot(gate: RouteOrderGate): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "groupDetails" -> {
                    gate.rosterReadCount.incrementAndGet()
                    check(!gate.rosterReadFails) { "roster enrichment is unavailable" }
                    groupDetails()
                }
                "chatListRow" -> {
                    val accountRef = arguments?.firstOrNull() as? String
                    val groupIdHex = arguments?.getOrNull(1) as? String
                    check(accountRef == SOURCE_ACCOUNT || accountRef == TARGET_ACCOUNT) {
                        "projection read used an unknown account"
                    }
                    check(groupIdHex == SHARED_GROUP) { "projection read used the wrong group" }
                    if (accountRef == TARGET_ACCOUNT) {
                        gate.preloadStarted.countDown()
                        check(gate.releasePreload.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                            "preload gate timed out"
                        }
                        gate.projectionReadCount.incrementAndGet()
                        if (!gate.projectionAvailable) {
                            throw NoSuchElementException("notification chat-list projection unavailable")
                        }
                        gate.preloadCompleted.countDown()
                    }
                    chatListRow(requireNotNull(groupIdHex))
                }
                "subscribeChatList" -> {
                    val accountRef = arguments?.firstOrNull() as? String
                    if (accountRef == SOURCE_ACCOUNT) {
                        check(gate.releaseSourceBroadList.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                            "source broad-list gate timed out"
                        }
                    }
                    if (accountRef == TARGET_ACCOUNT) {
                        gate.broadBindStarted.countDown()
                        check(gate.releaseActivation.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                            "activation gate timed out"
                        }
                    }
                    error("Skip broad-list startup in the focused route test")
                }
                "toString" -> "NotificationAccountIsolationMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("Unexpected Marmot call: ${method.name}")
            }
        } as MarmotInterface

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

    private fun groupDetails() =
        GroupDetailsFfi(
            group = group(),
            members = emptyList(),
            mlsState =
                AppGroupMlsStateFfi(
                    groupIdHex = SHARED_GROUP,
                    protocolProfile = AppProtocolProfileFfi.CURRENT,
                    lifecycleState = GroupLifecycleStateFfi.STABLE,
                    epoch = 0uL,
                    memberCount = 0u,
                    unrecoverable = false,
                    requiredAppComponents = emptyList(),
                    disbandingEnabled = false,
                    disbanding = false,
                    disbandingBlockers = emptyList(),
                    disbandRequest = null,
                ),
        )

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = SHARED_GROUP,
            protocolProfile = AppProtocolProfileFfi.CURRENT,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Shared group",
            description = "",
            admins = emptyList(),
            relays = listOf("wss://relay.example"),
            nostrGroupIdHex = "e4".repeat(32),
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints =
                        listOf(
                            AppBlobEndpointFfi(
                                locatorKind = "blossom-v1",
                                baseUrl = "https://blossom.example",
                            ),
                        ),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private fun chatListRow(groupIdHex: String) =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = groupIdHex,
            archived = false,
            pendingConfirmation = false,
            title = "Shared group",
            groupName = "Shared group",
            avatarUrl = null,
            avatar = null,
            lastMessage = null,
            unreadCount = 3uL,
            hasUnread = true,
            firstUnreadMessageIdHex = MESSAGE_ID,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 0uL,
            activitySortAt = 0uL,
            updatedAt = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            manuallyMarkedUnread = false,
            conversationKind = ChatConversationKindFfi.GROUP,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )

    private fun account(
        ref: String,
        id: String,
    ) = AccountSummaryFfi(
        label = ref,
        accountIdHex = id,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    private class RouteOrderGate(
        preloadFinishesFirst: Boolean,
        val projectionAvailable: Boolean = true,
        holdSourceBroadList: Boolean = false,
        val rosterReadFails: Boolean = false,
    ) {
        val preloadStarted = CountDownLatch(1)
        val preloadCompleted = CountDownLatch(1)
        val broadBindStarted = CountDownLatch(1)
        val releasePreload = CountDownLatch(if (preloadFinishesFirst) 0 else 1)
        val releaseActivation = CountDownLatch(if (preloadFinishesFirst) 1 else 0)
        val releaseSourceBroadList = CountDownLatch(if (holdSourceBroadList) 1 else 0)
        val projectionReadCount = AtomicInteger()
        val rosterReadCount = AtomicInteger()
    }

    private object NoopDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val SOURCE_ACCOUNT = "account-a"
        const val TARGET_ACCOUNT = "account-b"
        val SOURCE_ID = "a1".repeat(32)
        val TARGET_ID = "b2".repeat(32)
        val SHARED_GROUP = "c3".repeat(32)
        val MESSAGE_ID = "d4".repeat(32)
        const val TAP_TOKEN = "trusted-test-token"
        const val TEST_CHANNEL = "notification-account-isolation-test"

        // CI runs the entire Robolectric/Compose corpus in the same worker;
        // individual route cases have reached eight seconds under contention.
        // Keep a bounded margin without slowing successful polling paths.
        const val ROUTE_TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 20L
    }
}

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
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.notifications.InboundIntentRouting
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import dev.ipf.whitenoise.android.notifications.NotificationNavigation
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.notifications.routeInboundIntent
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Full Compose-route regression coverage for the inactive-account race in #2191. */
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
    fun inactiveAccountTap_preloadOpensBeforeActivation_preservesSourceAccountCards() {
        verifyInactiveAccountTapIsolation(preloadFinishesFirst = true)
    }

    @Test
    fun inactiveAccountTap_activationFinishesBeforePreload_preservesSourceAccountCards() {
        verifyInactiveAccountTapIsolation(preloadFinishesFirst = false)
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
    fun mainShell_ordinaryConversationAccountSwitchPreservesDestinationCards() {
        val gate = RouteOrderGate(preloadFinishesFirst = true)
        val appState = appState(fakeMarmot(gate))
        postConversationCards(SOURCE_ACCOUNT, "source-invite")
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
                        inboundTarget = null
                    },
                )
            }
        }

        awaitCondition { handled.get() }
        awaitCondition {
            manager.activeNotifications.map { it.tag to it.id }.toSet() == targetKeys.toSet()
        }
        assertEquals(SOURCE_ACCOUNT, appState.activeAccountRef)

        composeRule.runOnIdle { setActiveAccountRefForTest(appState, TARGET_ACCOUNT) }
        awaitCondition { appState.activeAccountRef == TARGET_ACCOUNT }
        awaitCondition {
            manager.activeNotifications.map { it.tag to it.id }.toSet() == targetKeys.toSet()
        }
        gate.releaseActivation.countDown()
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

        awaitCondition {
            gate.preloadStarted.count == 0L && gate.activationStarted.count == 0L
        }
        manager.notify(lateSource.first, lateSource.second, notification(SOURCE_ACCOUNT))

        if (preloadFinishesFirst) {
            awaitCondition {
                manager.activeNotifications.map { it.tag to it.id }.toSet() ==
                    (sourceKeys + lateSource).toSet()
            }
            assertEquals(SOURCE_ACCOUNT, appState.activeAccountRef)
            gate.releaseActivation.countDown()
        } else {
            awaitCondition {
                appState.activeAccountRef == TARGET_ACCOUNT
            }
            assertEquals(
                (sourceKeys + targetKeys + lateSource).toSet(),
                manager.activeNotifications.map { it.tag to it.id }.toSet(),
            )
            gate.releasePreload.countDown()
        }

        check(gate.preloadCompleted.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            "notification preload did not complete"
        }
        awaitCondition { handled.get() }
        awaitCondition {
            appState.activeAccountRef == TARGET_ACCOUNT
        }
        awaitCondition {
            manager.activeNotifications.map { it.tag to it.id }.toSet() ==
                (sourceKeys + lateSource).toSet()
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

    private fun appState(marmot: MarmotInterface): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(NoopDraftPersistence),
            accountIdHexResolver = { null },
            accounts = listOf(account(SOURCE_ACCOUNT, SOURCE_ID), account(TARGET_ACCOUNT, TARGET_ID)),
            activeAccountRef = SOURCE_ACCOUNT,
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
                    gate.preloadStarted.countDown()
                    check(gate.releasePreload.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        "preload gate timed out"
                    }
                    gate.preloadCompleted.countDown()
                    groupDetails()
                }
                "subscribeChatList" -> {
                    val accountRef = arguments?.firstOrNull() as? String
                    if (accountRef == TARGET_ACCOUNT) {
                        gate.activationStarted.countDown()
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

    private fun awaitCondition(condition: () -> Boolean) {
        var elapsedMillis = 0L
        while (elapsedMillis <= ROUTE_TIMEOUT_MILLIS) {
            composeRule.waitForIdle()
            ShadowLooper.idleMainLooper()
            if (condition()) return
            ShadowLooper.idleMainLooper(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            elapsedMillis += POLL_INTERVAL_MILLIS
        }
        throw AssertionError("Condition not met within ${ROUTE_TIMEOUT_MILLIS}ms")
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
    ) {
        val preloadStarted = CountDownLatch(1)
        val preloadCompleted = CountDownLatch(1)
        val activationStarted = CountDownLatch(1)
        val releasePreload = CountDownLatch(if (preloadFinishesFirst) 0 else 1)
        val releaseActivation = CountDownLatch(if (preloadFinishesFirst) 1 else 0)
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
        const val ROUTE_TIMEOUT_MILLIS = 10_000L
        const val POLL_INTERVAL_MILLIS = 20L
    }
}

package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.content.Intent
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.notifications.InboundIntentRouting
import dev.ipf.whitenoise.android.notifications.NotificationNavigation
import dev.ipf.whitenoise.android.notifications.NotificationScenario
import dev.ipf.whitenoise.android.notifications.routeInboundIntent
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MarmotWindowTestFakes
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orders the native reads of one inactive-account route: whether the exact preload or the broad
 * activation work finishes first, and which reads fail. It carries every observation a test asserts.
 */
internal class AccountRouteOrderGate(
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
    val draftReadCount = AtomicInteger()
}

/** In-memory drafts, because no draft persistence participates in notification routing. */
internal object NoopRouteDraftPersistence : DraftPersistence {
    /** No persisted drafts exist. */
    override fun read(): Map<String, String> = emptyMap()

    /** Discards the write. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}

/**
 * The two-account notification route shared by the shell-level and whole-app route tests: one engine
 * fake, one app state around it and one parsed tap. Extracted so a test that mounts the whole app does
 * not hand-build a second engine, and so neither stubs the broad chat-list bind as a hidden shortcut
 * unless it asks for that explicitly.
 */
internal object NotificationRouteHarness {
    const val SOURCE_ACCOUNT = NotificationScenario.SOURCE_ACCOUNT_REF
    const val TARGET_ACCOUNT = NotificationScenario.TARGET_ACCOUNT_REF
    val SOURCE_ID = NotificationScenario.SOURCE_ACCOUNT_ID
    val TARGET_ID = NotificationScenario.TARGET_ACCOUNT_ID
    val SHARED_GROUP = NotificationScenario.GROUP_ID
    val MESSAGE_ID = NotificationScenario.NOTIFIED_ID
    const val TAP_TOKEN = "trusted-test-token"

    /** The peer whose messages the shared conversation shows. */
    val PEER_ID = "cc".repeat(32)

    // CI runs the entire Robolectric/Compose corpus in the same worker; individual route cases have
    // reached eight seconds under contention. Keep a bounded margin without slowing successful polls.
    const val ROUTE_TIMEOUT_MILLIS = 30_000L

    /** An app state with both accounts, the source account active and [marmot] as its runtime. */
    fun appState(
        context: Context,
        marmot: MarmotInterface,
        notificationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(NoopRouteDraftPersistence),
            accountIdHexResolver = { null },
            accounts = NotificationScenario.accounts(),
            activeAccountRef = SOURCE_ACCOUNT,
            notificationDispatcher = notificationDispatcher,
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }

    /**
     * Models the account-isolated native reads used while activating a notification route. With
     * [failBroadBind] the broad chat-list window throws once its gate opens, which is the focused-route
     * shortcut the shell-level tests use. A test that mounts the whole app leaves it false and supplies
     * a real window through the chat-list seam instead.
     */
    @Suppress("CyclomaticComplexMethod")
    fun fakeMarmot(
        gate: AccountRouteOrderGate,
        failBroadBind: Boolean = true,
    ): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name.substringBefore('-')) {
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                // These existing signed-in accounts have no interactive setup checkpoint.
                "onboardingRecoveryRequired" -> false
                "onboardingSnapshot" -> null
                "groupDetails" -> {
                    gate.rosterReadCount.incrementAndGet()
                    check(!gate.rosterReadFails) { "roster enrichment is unavailable" }
                    groupDetails()
                }
                "chatListRow" -> projectionRead(gate, arguments)
                "groupRoster" -> groupRoster()
                "groupRecoveryStatus" -> noRecoveryNeeded()
                "openChatListWindow" -> {
                    broadBindRead(gate, arguments)
                    check(!failBroadBind) { "Skip broad-list startup in the focused route test" }
                    MarmotWindowTestFakes.chatListWindow(
                        view =
                            arguments?.getOrNull(1) as? dev.ipf.marmotkit.ChatListViewFfi
                                ?: dev.ipf.marmotkit.ChatListViewFfi.CHATS,
                        rows = emptyList(),
                    )
                }
                "subscribeAccountAttention" -> MarmotWindowTestFakes.accountAttention()
                "subscribeBlockedUsers" -> MarmotWindowTestFakes.blockList()
                "messageDraft" -> {
                    gate.draftReadCount.incrementAndGet()
                    error("Forced draft storage failure")
                }
                "toString" -> "NotificationAccountIsolationMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("Unexpected Marmot call: ${method.name}")
            }
        } as MarmotInterface

    /** The exact per-group projection read, which the target account's preload gates and counts. */
    private fun projectionRead(
        gate: AccountRouteOrderGate,
        arguments: Array<out Any?>?,
    ): Any {
        val accountRef = arguments?.firstOrNull() as? String
        val groupIdHex = arguments?.getOrNull(1) as? String
        check(accountRef in listOf(SOURCE_ACCOUNT, TARGET_ACCOUNT)) { "projection read used an unknown account" }
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
        return NotificationScenario.chatListRow(requireNotNull(groupIdHex))
    }

    /** Holds each account's broad chat-list open on its own gate, as the real bind waits on the engine. */
    private fun broadBindRead(
        gate: AccountRouteOrderGate,
        arguments: Array<out Any?>?,
    ) {
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
    }

    /**
     * One parsed, trusted tap for [accountRef]'s notified message, through the production intent route.
     * [current] is the routing a later tap replaces, so a second tap advances the request generation.
     */
    fun routedTarget(
        accountRef: String,
        current: InboundIntentRouting = InboundIntentRouting(notificationTarget = null, profilePayload = null),
    ): InboundIntentRouting {
        val target = NotificationScenario.target(accountRef = accountRef, groupIdHex = SHARED_GROUP)
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
            current = current,
        )
    }

    /** The two-member roster of the shared group: the target account itself and one peer who sent the messages. */
    private fun groupRoster() =
        GroupRosterFfi(
            groupIdHex = SHARED_GROUP,
            members =
                listOf(
                    rosterMember(TARGET_ID, accountRef = TARGET_ACCOUNT, isSelf = true),
                    rosterMember(PEER_ID),
                ),
            epoch = 1uL,
            rosterRevision = 1uL,
            selfMembership = SelfMembershipFfi.MEMBER,
            memberCount = 2u,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
        )

    /** One roster entry as the native projection reports it, with explicit self and peer membership. */
    private fun rosterMember(
        id: String,
        accountRef: String? = null,
        isSelf: Boolean = false,
    ) = GroupMemberDetailsFfi(
        memberIdHex = id,
        account = accountRef,
        local = isSelf,
        isAdmin = isSelf,
        isSelf = isSelf,
        npub = "npub-$id",
        displayName = null,
    )

    /** A recovery status with nothing to repair, so no recovery card renders. */
    private fun noRecoveryNeeded() =
        GroupRecoveryStatusFfi(
            groupIdHex = SHARED_GROUP,
            automaticRecoveryFailed = false,
            pendingReinvites = 0u,
            failedReinvites = 0u,
            rejoinInvitations = emptyList(),
        )

    /** The group details a roster-enrichment read answers with. */
    fun groupDetails() =
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

    /** The shared group's record, a member group both local accounts belong to. */
    fun group() =
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
}

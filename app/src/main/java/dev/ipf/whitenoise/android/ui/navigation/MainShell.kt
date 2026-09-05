@file:Suppress("ReturnCount") // Foreground routes must stop lower surfaces from composing over them.

package dev.ipf.whitenoise.android.ui.navigation

import android.animation.ValueAnimator
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.LayoutDirection.Ltr
import androidx.compose.ui.unit.LayoutDirection.Rtl
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.SavedStateHandle
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.notifications.NotificationInviteAuthoritativeOutcome
import dev.ipf.whitenoise.android.notifications.NotificationMessageDirectLoadOutcome
import dev.ipf.whitenoise.android.notifications.NotificationMessagePreload
import dev.ipf.whitenoise.android.notifications.NotificationMessagePreloadState
import dev.ipf.whitenoise.android.notifications.NotificationNavStep
import dev.ipf.whitenoise.android.notifications.NotificationRouteFirstFrameGate
import dev.ipf.whitenoise.android.notifications.NotificationRouteTrace
import dev.ipf.whitenoise.android.notifications.NotificationRouteTraceSection
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.notifications.awaitNotificationAccountActivationBoundary
import dev.ipf.whitenoise.android.notifications.inviteAuthoritativeGroupAvailable
import dev.ipf.whitenoise.android.notifications.loadNotificationMessageDirectly
import dev.ipf.whitenoise.android.notifications.notificationMessagePreloadKey
import dev.ipf.whitenoise.android.notifications.notificationMessageRouteChatListReady
import dev.ipf.whitenoise.android.notifications.notificationPreloadStuckLoading
import dev.ipf.whitenoise.android.notifications.resolveNotificationNav
import dev.ipf.whitenoise.android.notifications.retryInviteAuthoritativeLoad
import dev.ipf.whitenoise.android.notifications.runInactiveNotificationRouteStage
import dev.ipf.whitenoise.android.notifications.shouldDeferNotificationChatListBind
import dev.ipf.whitenoise.android.notifications.shouldRetryNotificationMessageLoadAfterActivation
import dev.ipf.whitenoise.android.notifications.stateFor
import dev.ipf.whitenoise.android.share.SerializedPendingShareRequestStore
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.share.createPendingShareRequestStore
import dev.ipf.whitenoise.android.share.shouldPresentInboundShare
import dev.ipf.whitenoise.android.state.AccountSwitchPreloadPolicy
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.AttachmentOpenDestination
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.attachmentOpenChatSelectionMatches
import dev.ipf.whitenoise.android.state.currentTtsConversationDestination
import dev.ipf.whitenoise.android.state.isSignedInSigningAccount
import dev.ipf.whitenoise.android.state.newAttachmentOpenNavigationGeneration
import dev.ipf.whitenoise.android.state.nextNavAccountRef
import dev.ipf.whitenoise.android.state.observeTtsConversationDestination
import dev.ipf.whitenoise.android.state.reconcileProvisionalOpenChat
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.state.shouldResetNavOnAccountChange
import dev.ipf.whitenoise.android.ui.chats.ChatsScreen
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupFlow
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.common.rememberConversationControllerCopy
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.conversation.conversationScrollKey
import dev.ipf.whitenoise.android.ui.conversation.media.attachmentInstallerHandoffEffect
import dev.ipf.whitenoise.android.ui.profile.ProfileSheet
import dev.ipf.whitenoise.android.ui.settings.DiagnosticsScreen
import dev.ipf.whitenoise.android.ui.settings.SettingsScreen
import dev.ipf.whitenoise.android.ui.share.ShareChatPickerFullScreen
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal data class ConversationOpenContext(
    val focusMessageId: String? = null,
    val focusMessageRequestId: Long = 0L,
    val ttsFocusSessionId: Long? = null,
    val notificationOpenRequestId: Long = 0L,
    val notificationRouteTraceRequestId: Long? = null,
    // Message notification taps advance this cursor only after the destination
    // freezes its pre-read unread projection. Back commits it immediately when
    // the user leaves before that boundary becomes available (#1016/#2191).
    val notificationReadThroughMessageId: String? = null,
    // Non-null only when a notification-routed conversation opened before its
    // account switch landed (#586). The conversation controller and scroll key
    // bind to this account — never the still-switching active account — so the
    // open renders the target account's local history immediately and survives
    // the active-ref flip without recreation.
    val pinnedAccountRef: String? = null,
)

internal data class PendingConversationOpen(
    val requestId: Long,
    val accountRef: String?,
    val item: ChatListItem,
    val focusMessageId: String?,
    val justCreated: Boolean,
    val visibleActiveListHeadId: String?,
)

internal fun pendingConversationOpenBelongsToAccount(
    requestAccountRef: String?,
    activeAccountRef: String?,
): Boolean = requestAccountRef != null && requestAccountRef == activeAccountRef

internal fun mainShellAccountContentOwned(
    previousAccountRef: String?,
    activeAccountRef: String?,
): Boolean = previousAccountRef == activeAccountRef

internal data class ConversationTransitionContent(
    val chat: ChatListItem,
    val controller: ConversationController,
    val accountRef: String,
    val openContext: ConversationOpenContext,
    val justCreated: Boolean,
    val openedAsDmHint: Boolean,
)

private data class ConversationTimelineVisibility(
    val controller: ConversationController,
    val visible: Boolean,
)

internal fun conversationControllerAccountRef(
    selectedPinnedAccountRef: String?,
    pendingAccountRef: String?,
    exitingAccountRef: String?,
    activeAccountRef: String?,
): String? = selectedPinnedAccountRef ?: pendingAccountRef ?: exitingAccountRef ?: activeAccountRef

internal fun retainedConversationContentBelongsToRoute(
    contentAccountRef: String,
    activeAccountRef: String?,
    pinnedAccountRef: String?,
    notificationRouteTraceRequestId: Long?,
    notificationEarlyOpenRequestId: Long,
): Boolean =
    contentAccountRef == activeAccountRef ||
        (
            pinnedAccountRef == contentAccountRef &&
                notificationEarlyOpenRequestId != 0L &&
                notificationRouteTraceRequestId == notificationEarlyOpenRequestId
        )

internal fun preparedConversationCanOpen(
    hasPublishedAuthoritativeTimeline: Boolean,
    hasPreparedInitialPresentation: Boolean,
    hasLoadError: Boolean,
    terminalConversationUnavailable: Boolean,
): Boolean =
    (hasPublishedAuthoritativeTimeline && hasPreparedInitialPresentation) ||
        hasLoadError ||
        terminalConversationUnavailable

internal fun conversationRouteForwardDirection(layoutDirection: LayoutDirection): Int =
    when (layoutDirection) {
        Ltr -> 1
        Rtl -> -1
    }

internal data class PendingStagedShareOpen(
    val accountRef: String,
    val groupIdHex: String,
    val otherChatCount: Int,
)

internal sealed interface ProfileForegroundRoute {
    data object None : ProfileForegroundRoute

    data class ShellProfile(
        val npub: String,
    ) : ProfileForegroundRoute

    data class ConversationProfile(
        val npub: String,
    ) : ProfileForegroundRoute

    data class NewGroup(
        val initialMember: RecipientSearch.Candidate,
    ) : ProfileForegroundRoute
}

internal class ProfileGroupForegroundState {
    var initialMember by mutableStateOf<RecipientSearch.Candidate?>(null)
        private set

    fun open(member: RecipientSearch.Candidate) {
        initialMember = member
    }

    fun close() {
        initialMember = null
    }
}

internal fun profileForegroundRoute(
    pendingProfileNpub: String?,
    startGroupMember: RecipientSearch.Candidate?,
    conversationOpen: Boolean,
): ProfileForegroundRoute =
    when {
        startGroupMember != null -> ProfileForegroundRoute.NewGroup(startGroupMember)
        pendingProfileNpub == null -> ProfileForegroundRoute.None
        conversationOpen -> ProfileForegroundRoute.ConversationProfile(pendingProfileNpub)
        else -> ProfileForegroundRoute.ShellProfile(pendingProfileNpub)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
internal fun ProfileGroupForegroundCoordinator(
    appState: WhiteNoiseAppState,
    conversationController: ConversationController?,
    profileGroupForegroundState: ProfileGroupForegroundState,
    secureWindowEnabled: Boolean?,
    profileSecurePolicy: SecureFlagPolicy,
    onOpenConversation: (ChatListItem, Boolean) -> Unit,
    onGroupCreateSubmitted: () -> Long = { 0L },
    onGroupCreateCompletedOpen: (ChatListItem, Long) -> Unit = { item, _ -> onOpenConversation(item, false) },
    onGroupCreateFlowSuperseded: () -> Unit = {},
    onDismissProfile: () -> Unit,
    onClosePicker: () -> Unit,
    content: @Composable () -> Unit,
) {
    val foregroundState = profileGroupForegroundState
    val route =
        profileForegroundRoute(
            pendingProfileNpub = appState.pendingProfileNpub,
            startGroupMember = foregroundState.initialMember,
            conversationOpen = conversationController != null,
        )
    when (route) {
        is ProfileForegroundRoute.NewGroup -> {
            secureWindowEnabled?.let { WindowSecureFlag(enabled = it) }
            NewGroupFlow(
                appState = appState,
                initialMembers = listOf(route.initialMember),
                onCreateCompletedOpen = { item, requestToken ->
                    foregroundState.close()
                    onGroupCreateCompletedOpen(item, requestToken)
                },
                onCreateSubmitted = onGroupCreateSubmitted,
                onCreateFlowSuperseded = onGroupCreateFlowSuperseded,
                onClose = {
                    onClosePicker()
                    foregroundState.close()
                    onGroupCreateFlowSuperseded()
                },
            )
            return
        }
        else -> content()
    }
    val profileNpub =
        when (route) {
            is ProfileForegroundRoute.ShellProfile -> route.npub
            is ProfileForegroundRoute.ConversationProfile -> route.npub
            else -> null
        }
    profileNpub?.let {
        ProfileSheet(
            appState = appState,
            npub = it,
            onOpenGroup = onOpenConversation,
            onStartGroup = { candidate ->
                appState.clearPresentedProfile()
                foregroundState.open(candidate)
            },
            onDismiss = onDismissProfile,
            adminController = conversationController,
            securePolicy = profileSecurePolicy,
        )
    }
}

internal fun nextNotificationConversationOpenContext(
    current: ConversationOpenContext,
    notificationRouteTraceRequestId: Long? = null,
    pinnedAccountRef: String? = null,
    notificationReadThroughMessageId: String? = null,
): ConversationOpenContext =
    ConversationOpenContext(
        notificationOpenRequestId = current.notificationOpenRequestId + 1L,
        notificationRouteTraceRequestId = notificationRouteTraceRequestId,
        notificationReadThroughMessageId = notificationReadThroughMessageId,
        pinnedAccountRef = pinnedAccountRef,
    )

internal enum class MainShellContentRoute {
    Conversation,
    NotificationLoading,
    TtsReturnTransition,
    Main,
}

/** Selects the single shell surface that owns the current navigation frame. */
internal fun resolveMainShellContentRoute(
    conversationOpen: Boolean,
    routingNotification: Boolean,
    routingTtsReturn: Boolean,
): MainShellContentRoute =
    when {
        routingNotification -> MainShellContentRoute.NotificationLoading
        routingTtsReturn -> MainShellContentRoute.TtsReturnTransition
        conversationOpen -> MainShellContentRoute.Conversation
        else -> MainShellContentRoute.Main
    }

/** Coordinates account-owned chat-list and conversation routes for the main app surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainShell(
    appState: WhiteNoiseAppState,
    stateHolder: MainShellStateHolder? = null,
    inboundNotificationTarget: NotificationTarget? = null,
    inboundNotificationRequestId: Long = 0L,
    onNotificationTargetHandled: (NotificationTarget, Long) -> Unit = { _, _ -> },
    inboundShareRequest: ShareRequest? = null,
    onShareRequestHandled: (ShareRequest) -> Unit = {},
    inboundAppUpdateTap: Int = 0,
    onAppUpdateTapHandled: (Int) -> Unit = {},
) {
    attachmentInstallerHandoffEffect(appState)
    val shellStateHolder =
        remember(appState, stateHolder) {
            stateHolder ?: MainShellStateHolder(appState, SavedStateHandle())
        }
    DisposableEffect(shellStateHolder, stateHolder) {
        onDispose {
            if (stateHolder == null) shellStateHolder.release()
        }
    }
    val chatsController =
        shellStateHolder.chatsController(
            accountRef = appState.activeAccountRef,
            runtimeGeneration = appState.runtimeGeneration,
        )
    var quickAccountSwitchTransition by remember {
        mutableStateOf<QuickAccountSwitchTransition?>(null)
    }
    var nextQuickAccountSwitchRequestId by remember { mutableLongStateOf(0L) }

    /** Invalidates an in-flight quick switch and restores the account the user chose most recently. */
    fun cancelPendingQuickAccountSwitchTo(targetAccountRef: String) {
        // A→B→A can arrive while A is still the published active ref.
        // Invalidate B's shouldActivate token now, then queue a guarded
        // restoration in case B crossed its activation boundary first.
        nextQuickAccountSwitchRequestId += 1L
        val cancellationRequestId = nextQuickAccountSwitchRequestId
        quickAccountSwitchTransition = null
        appState.launchMutation {
            if (appState.activeAccountRef != targetAccountRef) {
                appState.setActiveAccount(
                    label = targetAccountRef,
                    shouldActivate = {
                        nextQuickAccountSwitchRequestId == cancellationRequestId &&
                            quickAccountSwitchTransition == null
                    },
                )
            }
        }
    }

    /** Starts a generation-owned home-screen quick switch without delaying account activation. */
    fun requestQuickAccountSwitch(targetAccountRef: String) {
        val sourceAccountRef = appState.activeAccountRef ?: return
        when (
            quickAccountSwitchRequestDisposition(
                activeAccountRef = sourceAccountRef,
                pending = quickAccountSwitchTransition,
                targetAccountRef = targetAccountRef,
            )
        ) {
            QuickAccountSwitchRequestDisposition.Ignore -> return
            QuickAccountSwitchRequestDisposition.CancelPendingToCurrent -> {
                cancelPendingQuickAccountSwitchTo(targetAccountRef)
                return
            }
            QuickAccountSwitchRequestDisposition.Start -> Unit
        }
        val target = appState.accounts.firstOrNull { it.label == targetAccountRef } ?: return
        nextQuickAccountSwitchRequestId += 1L
        val requestId = nextQuickAccountSwitchRequestId
        quickAccountSwitchTransition =
            QuickAccountSwitchTransition(
                requestId = requestId,
                sourceAccountRef = sourceAccountRef,
                targetAccountRef = targetAccountRef,
                targetTitle = appState.accountDisplayNameCached(target.accountIdHex),
                targetSeed = target.accountIdHex,
                targetPictureUrl = appState.avatarUrl(target.accountIdHex),
                motion =
                    if (ValueAnimator.areAnimatorsEnabled()) {
                        QuickAccountSwitchMotion.Animated
                    } else {
                        QuickAccountSwitchMotion.Reduced
                    },
            )
        appState.launchMutation {
            val activated =
                appState.setActiveAccount(
                    label = targetAccountRef,
                    preloadPolicy = AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS,
                    shouldActivate = {
                        quickAccountSwitchRequestIsCurrent(
                            transition = quickAccountSwitchTransition,
                            requestId = requestId,
                            targetAccountRef = targetAccountRef,
                        )
                    },
                )
            if (!activated && quickAccountSwitchTransition?.requestId == requestId) {
                quickAccountSwitchTransition = null
            }
        }
    }
    shellStateHolder.restoreConversationIfReady(chatsController, appState.activeAccountRef)
    var sectionName by rememberSaveable { mutableStateOf(MainSection.Chats.name) }
    var settingsDetailName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedChat by shellStateHolder.selectedChat
    // Retain the complete outgoing route for the short back-slide. Account pin,
    // controller and decrypted seed are one ownership unit; retaining only the
    // chat would rebuild it against the active account during an early Back.
    var exitingConversationContent by remember { mutableStateOf<ConversationTransitionContent?>(null) }
    // A normal chat-list tap keeps the already-rendered list on screen while
    // the destination controller reads its first local authoritative page.
    // Once ready, the same controller is promoted into the conversation route,
    // so that route's first composition already owns real data and final index
    // math instead of painting an empty destination and filling it afterward.
    var pendingConversationOpen by remember { mutableStateOf<PendingConversationOpen?>(null) }
    var nextPendingConversationOpenRequestId by remember { mutableLongStateOf(0L) }
    // Open-time conversation state. Search hits carry a focus id; every
    // notification tap advances the request id so an already-mounted
    // ConversationScreen re-runs its first-unread anchor.
    var selectedChatOpenContext by shellStateHolder.selectedChatOpenContext
    // True only when `selectedChat` was opened straight off a just-completed
    // New Chat / Create Group flow (issue #321), so ConversationScreen raises
    // the composer + keyboard once on entry. Plain `remember` (not
    // rememberSaveable) so it never survives process death. Reset on every
    // other open path and on back.
    var selectedChatJustCreated by shellStateHolder.selectedChatJustCreated
    // True only for the route that has just created a 1:1 DM and is opening it
    // before the live roster has necessarily settled. Suppresses the group-style
    // member-count subtitle during that transient 0/1-member window (#998).
    var selectedChatOpenedAsDmHint by shellStateHolder.selectedChatOpenedAsDmHint
    // Per-conversation scroll anchors for back-to-list re-entry (issue #1107).
    // Keyed by account + group id; dropped when the reader leaves near-bottom so
    // the normal unread/newest anchor still runs for chats left at the tail.
    val conversationScrollSnapshots = shellStateHolder.conversationScrollSnapshots
    // Visible active-list head provenance while a conversation opened from
    // ChatsScreen is foregrounded. Published back to the list only after
    // setChatListVisible(true) flushes pending recompute (issue #1313).
    var chatListReturnHeadSnap by remember { mutableStateOf<ChatListReturnHeadSnapState>(ChatListReturnHeadSnapState.Unarmed) }
    // Active chat-list folder filter (null = All). Owned here so opening a
    // conversation does not dispose the selection when ChatsScreen leaves
    // composition (issue #1897).
    var selectedChatListFolderId by remember { mutableStateOf<String?>(null) }
    // Global chat-list search survives conversation navigation and rotation
    // (issue #1941). Saveable codec only — no protocol or preference storage.
    val globalSearch =
        rememberMainShellGlobalSearchState(
            accountRef = appState.activeAccountRef,
            runtimeGeneration = appState.runtimeGeneration,
        )
    val scopedGlobalSearchState = globalSearch.scopedState
    // True while a tapped notification for a non-active account is mid-resolution
    // (switching account / awaiting its chat list). Holds a single stable loading
    // state over the multi-step route so the chat list never paints as an
    // intermediate stop between the account switch and the opened conversation.
    var routingNotification by remember(inboundNotificationRequestId) {
        mutableStateOf(inboundNotificationTarget != null)
    }
    var routingShare by remember(inboundShareRequest?.requestId) {
        mutableStateOf(inboundShareRequest != null)
    }
    var routingAppUpdate by remember(inboundAppUpdateTap) {
        mutableStateOf(inboundAppUpdateTap != 0)
    }
    // Tracks whether an in-flight group-create completion may still open its
    // conversation. Explicit shell navigation advances [navigationGeneration]
    // and invalidates a captured pending generation (issue #1953).
    var shellNavState by rememberSaveable(stateSaver = ShellNavigationStateSaver) {
        mutableStateOf(ShellNavigationState())
    }
    var attachmentOpenNavigationGeneration by rememberSaveable {
        mutableLongStateOf(newAttachmentOpenNavigationGeneration())
    }
    var previousAttachmentOpenDestinationKey by rememberSaveable {
        mutableStateOf(ATTACHMENT_OPEN_DESTINATION_UNINITIALIZED)
    }
    var pendingTtsDestinationNavigation by remember {
        mutableStateOf<TtsDestinationNavigationRequest?>(null)
    }
    var pendingTtsAccountSwitchOwnership by remember {
        mutableStateOf<TtsDestinationAccountSwitchOwnership?>(null)
    }
    val observedTtsDestination = appState.observeTtsConversationDestination()
    var nextTtsDestinationRequestId by remember { mutableLongStateOf(0L) }
    val supersedePendingTtsDestinationNavigation: () -> Unit = {
        pendingTtsDestinationNavigation = null
        pendingTtsAccountSwitchOwnership = null
    }
    val requestTtsDestinationOpen: () -> Unit = {
        val destination = appState.currentTtsConversationDestination()
        if (destination == null) {
            appState.present(R.string.tts_source_unavailable)
        } else {
            nextTtsDestinationRequestId += 1L
            pendingTtsAccountSwitchOwnership = null
            pendingTtsDestinationNavigation =
                TtsDestinationNavigationRequest(
                    requestId = nextTtsDestinationRequestId,
                    accountRef = destination.accountRef,
                    groupIdHex = destination.groupIdHex,
                    sessionId = destination.sessionId,
                )
        }
    }
    val profileGroupForegroundState =
        remember(appState.activeAccountRef) { ProfileGroupForegroundState() }
    var armedNotificationRequestId by remember { mutableLongStateOf(0L) }
    var notificationAccountSwitchRequestId by remember(appState.runtimeGeneration) {
        mutableStateOf<Long?>(null)
    }
    // Request id whose conversation opened before its account switch landed
    // (#586). Consuming the tap clears the inbound target, so the switch's
    // currency checks accept this id in place of a still-armed target.
    var notificationEarlyOpenRequestId by remember(appState.runtimeGeneration) {
        mutableLongStateOf(0L)
    }
    var notificationFirstFrameGate by remember(appState.runtimeGeneration) {
        mutableStateOf<NotificationRouteFirstFrameGate?>(null)
    }
    var notificationActiveRetryRequestId by remember(appState.runtimeGeneration) {
        mutableStateOf<Long?>(null)
    }

    fun releaseNotificationFirstFrameGate(requestId: Long? = null) {
        val gate = notificationFirstFrameGate ?: return
        if (requestId != null && gate.requestId != requestId) return
        gate.release()
        notificationFirstFrameGate = null
    }

    DisposableEffect(appState.runtimeGeneration, notificationFirstFrameGate) {
        val ownedGate = notificationFirstFrameGate
        onDispose { ownedGate?.release() }
    }
    var previousPendingProfileNpub by remember { mutableStateOf<String?>(null) }
    val supersedePendingGroupCreateOpen: () -> Unit = {
        shellNavState =
            reduceShellNavigation(shellNavState, ShellNavigationEvent.CreateFlowSuperseded).state
    }
    val onGroupCreateSubmitted: () -> Long = {
        val transition = reduceShellNavigation(shellNavState, ShellNavigationEvent.CreateSubmitted)
        shellNavState = transition.state
        transition.createRequestTokenMinted ?: 0L
    }

    fun commitExplicitConversationOpen(chatId: String) {
        supersedePendingTtsDestinationNavigation()
        shellNavState =
            reduceShellNavigation(
                shellNavState,
                ShellNavigationEvent.ExplicitConversationOpened(chatId),
            ).state
    }

    fun commitGroupCreateCompletionOpen(
        chatId: String,
        requestToken: Long,
    ): Boolean {
        val transition =
            reduceShellNavigation(
                shellNavState,
                ShellNavigationEvent.CreateCompleted(chatId, requestToken),
            )
        shellNavState = transition.state
        return transition.createOpenAccepted
    }
    var notificationInviteAuthoritativelyUnavailable by remember(
        inboundNotificationRequestId,
        inboundNotificationTarget?.accountRef,
        inboundNotificationTarget?.groupIdHex,
        inboundNotificationTarget?.kind,
    ) {
        mutableStateOf(false)
    }
    var notificationInviteAuthoritativeProbeAttempts by remember(
        inboundNotificationRequestId,
        inboundNotificationTarget?.accountRef,
        inboundNotificationTarget?.groupIdHex,
        inboundNotificationTarget?.kind,
    ) {
        mutableIntStateOf(0)
    }
    val notificationMessagePreloadKey =
        notificationMessagePreloadKey(
            target = inboundNotificationTarget,
            requestId = inboundNotificationRequestId,
        )
    var notificationMessagePreload by remember(
        notificationMessagePreloadKey,
        appState.runtimeGeneration,
    ) {
        mutableStateOf<NotificationMessagePreload<ChatListItem>?>(null)
    }
    val context = LocalContext.current
    val currentInboundNotificationTarget by rememberUpdatedState(inboundNotificationTarget)
    val currentInboundNotificationRequestId by rememberUpdatedState(inboundNotificationRequestId)
    val currentRuntimeGeneration by rememberUpdatedState(appState.runtimeGeneration)
    var pendingShareRequestStore by remember(context) { mutableStateOf<SerializedPendingShareRequestStore?>(null) }
    LaunchedEffect(context) {
        pendingShareRequestStore = SerializedPendingShareRequestStore(createPendingShareRequestStore(context))
    }
    val inboundDirectGroupId =
        inboundShareRequest?.let {
            shellStateHolder.visibleShareDirectGroupId(
                activeAccountRef = appState.activeAccountRef,
                runtimeGeneration = appState.runtimeGeneration,
            )
        }
    val visibleShareRequest = inboundShareRequest ?: shellStateHolder.pendingShareRequest.value
    val visiblePickerRequest = visibleShareRequest.takeUnless { inboundDirectGroupId != null }
    var pendingStagedShareOpen by remember { mutableStateOf<PendingStagedShareOpen?>(null) }
    val clearSharePickerRequest: () -> Unit = {
        val request = visibleShareRequest
        val requestId = request?.requestId ?: shellStateHolder.pendingShareRequestId
        shellStateHolder.clearPendingShareRequest(requestId)
        if (request != null && inboundShareRequest?.requestId == request.requestId) {
            onShareRequestHandled(request)
        }
        requestId?.let { pendingRequestId ->
            pendingShareRequestStore?.let { store ->
                appState.launchMutation {
                    store.remove(pendingRequestId)
                }
            }
        }
    }
    val deferNotificationChatListBind =
        shouldDeferNotificationChatListBind(notificationFirstFrameGate, appState.activeAccountRef)
    val section = runCatching { MainSection.valueOf(sectionName) }.getOrDefault(MainSection.Chats)
    val settingsDetail = settingsDetailName?.let { runCatching { SettingsDetail.valueOf(it) }.getOrNull() }

    LaunchedEffect(
        chatsController,
        appState.activeAccountRef,
        appState.runtimeGeneration,
        chatsController.retryGeneration,
        deferNotificationChatListBind,
    ) {
        if (deferNotificationChatListBind) return@LaunchedEffect
        chatsController.bind(
            accountRef = appState.activeAccountRef,
            preserveLoadedContent = chatsController.retryGeneration > 0L || chatsController.hasLoadedLocalSnapshot,
        )
    }

    LaunchedEffect(
        pendingShareRequestStore,
        shellStateHolder.pendingShareRequestId,
        inboundShareRequest?.requestId,
    ) {
        val store = pendingShareRequestStore ?: return@LaunchedEffect
        if (inboundShareRequest != null) return@LaunchedEffect
        val requestId = shellStateHolder.pendingShareRequestId ?: return@LaunchedEffect
        if (shellStateHolder.pendingShareRequest.value?.requestId == requestId) return@LaunchedEffect
        val restored = store.load(requestId)
        shellStateHolder.restorePendingShareRequest(requestId, restored)
    }

    LaunchedEffect(
        pendingShareRequestStore,
        shellStateHolder.pendingShareRequestId,
        inboundShareRequest,
        shellStateHolder.pendingShareRequest.value,
    ) {
        val store = pendingShareRequestStore ?: return@LaunchedEffect
        if (
            shellStateHolder.pendingShareRequestId == null &&
            inboundShareRequest == null &&
            shellStateHolder.pendingShareRequest.value == null
        ) {
            store.clear()
        }
    }

    // Freshness model for #6: the chat-list subscription stays bound while a
    // conversation is foregrounded (returning shows the current list instantly,
    // no reload), but its recompute is paused so account-wide list projection
    // doesn't contend with the conversation's own streams on the heaviest nav
    // path. The subscription keeps draining updates into the controller's maps
    // throughout; one recompute flushes on return.
    LaunchedEffect(chatsController, selectedChat == null) {
        val listVisible = selectedChat == null
        chatsController.setChatListVisible(listVisible)
        if (listVisible) {
            chatListReturnHeadSnap = onChatListBecameVisible(chatListReturnHeadSnap)
        }
    }

    // Notification tap routing: switch to the target account if needed, wait
    // read a message conversation directly (invites still await their row),
    // then open it — or fall back to the chat
    // list with a toast for a stale/removed target. Pure logic in
    // [resolveNotificationNav]; this effect just acts on each step and re-fires
    // as account/chat-list state changes.
    LaunchedEffect(appState.pendingProfileNpub) {
        val current = appState.pendingProfileNpub
        if (current != null && current != previousPendingProfileNpub) {
            supersedePendingTtsDestinationNavigation()
            shellNavState = armShellProfileForeground(shellNavState, profileGroupForegroundState)
        }
        previousPendingProfileNpub = current
    }

    LaunchedEffect(
        inboundNotificationTarget,
        inboundNotificationRequestId,
        appState.activeAccountRef,
        appState.runtimeGeneration,
        appState.accounts,
        chatsController,
        chatsController.boundAccountRef,
        chatsController.isLoading,
        chatsController.items,
        chatsController.materializedGroupsRevision,
        notificationInviteAuthoritativelyUnavailable,
        notificationMessagePreload,
    ) {
        val routingRequestId = inboundNotificationRequestId
        val target =
            inboundNotificationTarget ?: run {
                // The pending target was cancelled or replaced mid-route (e.g. a
                // White Noise deep link cleared it via routeInboundIntent) while
                // we were still in SwitchAccount/AwaitChatList. Nothing left to
                // resolve, so release the routing loading overlay; otherwise the
                // render gate below would keep MainShell on a permanent
                // LoadingScreen with no target to ever clear it (issue #585).
                routingNotification = false
                if (
                    armedNotificationRequestId != 0L &&
                    selectedChatOpenContext.notificationRouteTraceRequestId != armedNotificationRequestId
                ) {
                    releaseNotificationFirstFrameGate(armedNotificationRequestId)
                    NotificationRouteTrace.finishRequest(armedNotificationRequestId)
                }
                return@LaunchedEffect
            }
        if (routingRequestId != armedNotificationRequestId) {
            releaseNotificationFirstFrameGate(armedNotificationRequestId)
            notificationActiveRetryRequestId = null
            supersedePendingTtsDestinationNavigation()
            armedNotificationRequestId = routingRequestId
            shellNavState = armShellNotificationRequest(shellNavState, profileGroupForegroundState)
        }
        if (appState.accounts.isEmpty()) {
            // Accounts are not loaded yet; release any routing overlay so we do
            // not stick on NotificationLoading. Do not touch chat-list await
            // semantics — the effect re-runs when accounts arrive.
            routingNotification = false
            return@LaunchedEffect
        }
        val broadChatListReady =
            chatsController.boundAccountRef == target.accountRef &&
                !chatsController.isLoading
        // Archived conversations still exist — include them so an archived
        // group isn't treated as a missing conversation.
        val allChats = chatsController.items + chatsController.archivedItems
        val availableGroupIds = allChats.mapTo(mutableSetOf()) { it.group.groupIdHex }
        val exactPreloadState = notificationMessagePreload.stateFor(notificationMessagePreloadKey)
        val chatListReady =
            if (target.kind == NotificationTargetKind.MESSAGE) {
                notificationMessageRouteChatListReady(
                    chatListReady = broadChatListReady,
                    targetPresent = target.groupIdHex in availableGroupIds,
                    preloadState = exactPreloadState,
                )
            } else {
                broadChatListReady
            }

        fun notificationChatItem(groupIdHex: String): ChatListItem? =
            allChats.firstOrNull { it.group.groupIdHex == groupIdHex }
                ?: chatsController.chatItemForGroup(groupIdHex)

        val inviteRowMaterialized = chatsController.containsGroup(target.groupIdHex)
        val inviteListItem =
            if (target.kind == NotificationTargetKind.INVITE) {
                notificationChatItem(target.groupIdHex)
            } else {
                null
            }
        val inviteRowMembershipOpenable =
            inviteListItem?.let { item ->
                inviteAuthoritativeGroupAvailable(
                    pendingConfirmation = item.group.pendingConfirmation,
                    selfMembership = item.group.selfMembership,
                )
            } ?: true
        val step =
            resolveNotificationNav(
                target = target,
                knownAccountRefs = appState.accounts.mapTo(mutableSetOf()) { it.label },
                activeAccountRef = appState.activeAccountRef,
                chatListReady = chatListReady,
                availableGroupIds = availableGroupIds,
                inviteRowMaterialized = inviteRowMaterialized,
                inviteRowMembershipOpenable = inviteRowMembershipOpenable,
                inviteAuthoritativelyUnavailable = notificationInviteAuthoritativelyUnavailable,
                exactPreloadReady = exactPreloadState is NotificationMessagePreloadState.Ready,
            )

        fun commitNotificationConversationOpen(chatItem: ChatListItem) {
            sectionName = MainSection.Chats.name
            settingsDetailName = null
            shellNavState =
                reduceShellNavigation(
                    shellNavState,
                    ShellNavigationEvent.NotificationRoutedConversationOpened(
                        chatItem.group.groupIdHex,
                    ),
                ).state
            // Keep ownership pinned for every open produced by this account-
            // switch request. The active ref can land a frame before this
            // commit while the shell still remembers the source account; if we
            // drop the pin in that window, the account-change reset closes the
            // routed conversation before it can claim notification ownership.
            val ownsNotificationAccountSwitch = notificationAccountSwitchRequestId == routingRequestId
            val pinnedAccountRef =
                target.accountRef.takeIf {
                    ownsNotificationAccountSwitch || it != appState.activeAccountRef
                }
            if (ownsNotificationAccountSwitch) {
                notificationEarlyOpenRequestId = routingRequestId
            }
            selectedChatOpenContext =
                nextNotificationConversationOpenContext(
                    current = selectedChatOpenContext,
                    notificationRouteTraceRequestId = routingRequestId,
                    pinnedAccountRef = pinnedAccountRef,
                    notificationReadThroughMessageId = target.messageIdHex,
                )
            selectedChatJustCreated = false
            selectedChatOpenedAsDmHint = false
            chatListReturnHeadSnap = resetChatListReturnHeadSnap()
            NotificationRouteTrace.beginPhase(
                requestId = routingRequestId,
                sectionName = NotificationRouteTraceSection.CONTROLLER_BIND,
            )
            NotificationRouteTrace.beginPhase(
                requestId = routingRequestId,
                sectionName = NotificationRouteTraceSection.TARGET_TIMELINE,
            )
            NotificationRouteTrace.beginPhase(
                requestId = routingRequestId,
                sectionName = NotificationRouteTraceSection.FIRST_CONVERSATION_FRAME,
            )
            selectedChat = chatItem
            routingNotification = false
            onNotificationTargetHandled(target, routingRequestId)
        }

        fun fallBackToChatList() {
            sectionName = MainSection.Chats.name
            settingsDetailName = null
            chatListReturnHeadSnap = resetChatListReturnHeadSnap()
            supersedePendingGroupCreateOpen()
            selectedChat = null
            // Notification routing never opens a just-created conversation, so
            // clear any leftover open-time state from a prior New Chat / Create
            // Group flow; otherwise a stale justCreated flag would auto-raise
            // the IME on the next opened conversation (issue #321 guard).
            selectedChatOpenContext = ConversationOpenContext()
            selectedChatJustCreated = false
            selectedChatOpenedAsDmHint = false
        }
        when (step) {
            is NotificationNavStep.SwitchAccount -> {
                // Hold a single loading state over the whole switch→open route so
                // the chat list never paints between them.
                routingNotification = true
                // Close any conversation open under the previous account before
                // switching. Otherwise the destination conversation is built
                // mid-switch against a not-yet-settled chat-list projection and
                // anchors to a stale unread count / old messages. Clearing it
                // here makes tapping from inside a chat take the same clean path
                // as tapping after returning to the chat list.
                chatListReturnHeadSnap = resetChatListReturnHeadSnap()
                supersedePendingGroupCreateOpen()
                selectedChat = null
                selectedChatOpenContext = ConversationOpenContext()
                selectedChatJustCreated = false
                selectedChatOpenedAsDmHint = false
                if (notificationAccountSwitchRequestId == routingRequestId) {
                    return@LaunchedEffect
                }
                notificationAccountSwitchRequestId = routingRequestId
                val routeRuntimeGeneration = appState.runtimeGeneration

                // An early-opened conversation consumes the tap (clearing the
                // inbound target) while its switch is still landing, so the
                // switch stays current either while the target is still armed
                // or after this exact request committed an early open (#586).
                fun switchStillCurrent(): Boolean =
                    currentInboundNotificationRequestId == routingRequestId &&
                        currentRuntimeGeneration == routeRuntimeGeneration &&
                        (
                            currentInboundNotificationTarget == target ||
                                notificationEarlyOpenRequestId == routingRequestId
                        )
                val preloadKey = notificationMessagePreloadKey
                val canUseTargetFirst =
                    preloadKey != null &&
                        appState.accounts.any { it.label == step.accountRef }
                val canPreload =
                    canUseTargetFirst &&
                        appState.accounts.any {
                            it.label == step.accountRef && it.isSignedInSigningAccount()
                        }
                if (canPreload) {
                    notificationMessagePreload =
                        NotificationMessagePreload(
                            key = requireNotNull(preloadKey),
                            state = NotificationMessagePreloadState.Loading,
                        )
                }
                val firstFrameGate =
                    if (canUseTargetFirst) {
                        NotificationRouteFirstFrameGate(
                            requestId = routingRequestId,
                            accountRef = step.accountRef,
                        ).also { notificationFirstFrameGate = it }
                    } else {
                        null
                    }

                val activateAccount: suspend () -> Unit = {
                    NotificationRouteTrace.beginPhase(
                        requestId = routingRequestId,
                        sectionName = NotificationRouteTraceSection.ACCOUNT_ACTIVATION,
                    )
                    // Signal the local-ready boundary from setActiveAccount's
                    // callback. Its profile/privacy/push follow-up work keeps
                    // running in the process scope without holding the route.
                    awaitNotificationAccountActivationBoundary { onLocalReady ->
                        appState.launchMutation {
                            try {
                                appState.setActiveAccount(
                                    label = step.accountRef,
                                    shouldActivate = { switchStillCurrent() },
                                    preloadPolicy =
                                        if (firstFrameGate != null) {
                                            AccountSwitchPreloadPolicy.TARGET_CONVERSATION_FIRST
                                        } else {
                                            AccountSwitchPreloadPolicy.FULL_LOCAL_SNAPSHOT
                                        },
                                    awaitPostActivationWork = { firstFrameGate?.awaitRelease() },
                                    onActivated = {
                                        NotificationRouteTrace.endPhase(
                                            requestId = routingRequestId,
                                            sectionName = NotificationRouteTraceSection.ACCOUNT_ACTIVATION,
                                        )
                                        onLocalReady()
                                    },
                                )
                            } finally {
                                NotificationRouteTrace.endPhase(
                                    requestId = routingRequestId,
                                    sectionName = NotificationRouteTraceSection.ACCOUNT_ACTIVATION,
                                )
                                onLocalReady()
                            }
                        }
                    }
                }

                // This effect is keyed on activeAccountRef, so an inline suspend
                // switch would cancel itself the moment the ref flips.
                suspend fun runNotificationAccountSwitchRoute() {
                    if (canPreload) {
                        runInactiveNotificationRouteStage(
                            key = requireNotNull(preloadKey),
                            loadTarget = {
                                NotificationRouteTrace.tracePhase(
                                    requestId = routingRequestId,
                                    sectionName = NotificationRouteTraceSection.TARGET_PROJECTION,
                                ) {
                                    appState.preloadNotificationChatListItem(
                                        accountRef = preloadKey.accountRef,
                                        groupIdHex = preloadKey.groupIdHex,
                                    )
                                }
                            },
                            activateAccount = activateAccount,
                            isCurrent = {
                                currentInboundNotificationRequestId == routingRequestId &&
                                    currentInboundNotificationTarget == target &&
                                    currentRuntimeGeneration == routeRuntimeGeneration
                            },
                            // Published before activation completes: a ready
                            // local read re-fires the routing effect, which
                            // commits the conversation open immediately while
                            // the switch keeps settling behind it.
                            onPreload = {
                                notificationMessagePreload = it
                            },
                        )
                    } else {
                        activateAccount()
                    }
                    if (switchStillCurrent() && appState.activeAccountRef != step.accountRef) {
                        // The switch never landed. A route that never opened a
                        // conversation falls back as before; one that opened
                        // early closes it only while the user is still inside
                        // it — navigation they performed since then must not
                        // be undone by a now-stale failure.
                        val earlyOpened = notificationEarlyOpenRequestId == routingRequestId
                        val stillInsideEarlyOpen =
                            selectedChatOpenContext.notificationRouteTraceRequestId == routingRequestId
                        notificationMessagePreload = null
                        notificationEarlyOpenRequestId = 0L
                        releaseNotificationFirstFrameGate(routingRequestId)
                        routingNotification = false
                        if (!earlyOpened || stillInsideEarlyOpen) {
                            fallBackToChatList()
                        }
                        onNotificationTargetHandled(target, routingRequestId)
                        NotificationRouteTrace.finishRequest(routingRequestId)
                    }
                }
                appState.launchMutation {
                    try {
                        runNotificationAccountSwitchRoute()
                    } finally {
                        // A route that died before publishing leaves this
                        // request's Loading preload in place; the direct-load
                        // branch maps Loading to "keep waiting", so it would
                        // pin the loading overlay forever. Release both.
                        if (notificationPreloadStuckLoading(notificationMessagePreload, preloadKey)) {
                            notificationMessagePreload = null
                            releaseNotificationFirstFrameGate(routingRequestId)
                            if (currentInboundNotificationRequestId == routingRequestId) {
                                routingNotification = false
                            }
                        }
                    }
                }
            }
            NotificationNavStep.LoadMessageDirectly -> {
                routingNotification = true
                when (val preloadState = notificationMessagePreload.stateFor(notificationMessagePreloadKey)) {
                    NotificationMessagePreloadState.Loading -> Unit
                    is NotificationMessagePreloadState.Ready -> {
                        commitNotificationConversationOpen(preloadState.item)
                    }
                    NotificationMessagePreloadState.Failed -> {
                        // The inactive-account read may fail while account
                        // activation is still taking ownership of the native
                        // runtime. Retry the exact group once now that the target
                        // account is active. Keep the stable routing surface up;
                        // if this retry also fails, the broad list remains the
                        // authoritative fallback without flashing it first.
                        if (
                            shouldRetryNotificationMessageLoadAfterActivation(
                                preloadState = preloadState,
                                routingRequestId = routingRequestId,
                                retriedRequestId = notificationActiveRetryRequestId,
                            )
                        ) {
                            notificationActiveRetryRequestId = routingRequestId
                            val retryKey = requireNotNull(notificationMessagePreloadKey)
                            val retryRuntimeGeneration = appState.runtimeGeneration
                            // Account activation can publish an empty broad
                            // list while this exact retry is suspended. Keep
                            // that absence non-authoritative until the retry
                            // itself returns. Run it in the process mutation
                            // scope because publishing Loading re-composes and
                            // cancels this keyed routing effect.
                            notificationMessagePreload =
                                NotificationMessagePreload(
                                    key = retryKey,
                                    state = NotificationMessagePreloadState.Loading,
                                )
                            appState.launchMutation {
                                val outcome =
                                    loadNotificationMessageDirectly {
                                        NotificationRouteTrace.tracePhase(
                                            requestId = routingRequestId,
                                            sectionName = NotificationRouteTraceSection.TARGET_PROJECTION,
                                        ) {
                                            appState.loadNotificationChatListItem(
                                                accountRef = target.accountRef,
                                                groupIdHex = target.groupIdHex,
                                            )
                                        }
                                    }
                                if (
                                    currentInboundNotificationRequestId == routingRequestId &&
                                    currentInboundNotificationTarget == target &&
                                    currentRuntimeGeneration == retryRuntimeGeneration
                                ) {
                                    when (outcome) {
                                        is NotificationMessageDirectLoadOutcome.OpenConversation -> {
                                            notificationMessagePreload =
                                                NotificationMessagePreload(
                                                    key = retryKey,
                                                    state = NotificationMessagePreloadState.Ready(outcome.item),
                                                )
                                        }
                                        NotificationMessageDirectLoadOutcome.AwaitChatList -> {
                                            notificationMessagePreload =
                                                NotificationMessagePreload(
                                                    key = retryKey,
                                                    state = NotificationMessagePreloadState.Failed,
                                                )
                                            releaseNotificationFirstFrameGate(routingRequestId)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    null -> {
                        when (
                            val outcome =
                                loadNotificationMessageDirectly {
                                    NotificationRouteTrace.tracePhase(
                                        requestId = routingRequestId,
                                        sectionName = NotificationRouteTraceSection.TARGET_PROJECTION,
                                    ) {
                                        appState.loadNotificationChatListItem(
                                            accountRef = target.accountRef,
                                            groupIdHex = target.groupIdHex,
                                        )
                                    }
                                }
                        ) {
                            is NotificationMessageDirectLoadOutcome.OpenConversation -> {
                                commitNotificationConversationOpen(outcome.item)
                            }
                            NotificationMessageDirectLoadOutcome.AwaitChatList -> {
                                // A transient local-read failure does not consume the tap;
                                // the existing chat-list state will re-fire this route.
                                releaseNotificationFirstFrameGate(routingRequestId)
                                routingNotification = false
                            }
                        }
                    }
                }
            }
            NotificationNavStep.AwaitChatList -> Unit // invite route re-fires when list state settles
            NotificationNavStep.OpenChatList -> {
                releaseNotificationFirstFrameGate(routingRequestId)
                routingNotification = false
                fallBackToChatList()
                onNotificationTargetHandled(target, routingRequestId)
                NotificationRouteTrace.finishRequest(routingRequestId)
            }
            NotificationNavStep.AwaitInviteRow -> {
                routingNotification = true
                var authoritativeItem: ChatListItem? = null
                when (
                    retryInviteAuthoritativeLoad(
                        probeAttempts = notificationInviteAuthoritativeProbeAttempts,
                        onProbeAttempt = {
                            notificationInviteAuthoritativeProbeAttempts = it
                        },
                        load = {
                            runCatchingCancellable {
                                appState.loadCreatedChatListItem(target.groupIdHex)
                            }.onSuccess { authoritativeItem = it }
                                .map {
                                    inviteAuthoritativeGroupAvailable(
                                        pendingConfirmation = it.group.pendingConfirmation,
                                        selfMembership = it.group.selfMembership,
                                    )
                                }
                        },
                    )
                ) {
                    NotificationInviteAuthoritativeOutcome.OpenConversation ->
                        commitNotificationConversationOpen(requireNotNull(authoritativeItem))
                    NotificationInviteAuthoritativeOutcome.Unavailable ->
                        notificationInviteAuthoritativelyUnavailable = true
                    NotificationInviteAuthoritativeOutcome.Inconclusive -> {
                        // Release the overlay without consuming the target. A live
                        // row update can still re-run this route; only a proven
                        // unavailable invite may be consumed with an error.
                        routingNotification = false
                    }
                }
            }
            is NotificationNavStep.OpenConversation -> {
                notificationChatItem(step.groupIdHex)
                    ?.let { item ->
                        commitNotificationConversationOpen(item)
                    }
                    ?: run {
                        routingNotification = false
                        onNotificationTargetHandled(target, routingRequestId)
                    }
            }
            NotificationNavStep.MissingAccount -> {
                releaseNotificationFirstFrameGate(routingRequestId)
                routingNotification = false
                fallBackToChatList()
                appState.present(R.string.toast_notification_account_unavailable)
                onNotificationTargetHandled(target, routingRequestId)
                NotificationRouteTrace.finishRequest(routingRequestId)
            }
            NotificationNavStep.MissingConversation -> {
                releaseNotificationFirstFrameGate(routingRequestId)
                routingNotification = false
                fallBackToChatList()
                appState.present(R.string.toast_notification_conversation_unavailable)
                onNotificationTargetHandled(target, routingRequestId)
                NotificationRouteTrace.finishRequest(routingRequestId)
            }
        }
    }

    // A tapped app-update notification lands here: drop any open conversation /
    // deep settings nav, return to the chat list, and surface the update banner.
    LaunchedEffect(inboundAppUpdateTap, appState.phase) {
        val tap = inboundAppUpdateTap
        if (tap == 0 || appState.phase != AppPhase.Ready) return@LaunchedEffect
        supersedePendingTtsDestinationNavigation()
        sectionName = MainSection.Chats.name
        settingsDetailName = null
        supersedePendingGroupCreateOpen()
        selectedChat = null
        selectedChatOpenContext = ConversationOpenContext()
        selectedChatJustCreated = false
        routingNotification = false
        routingAppUpdate = false
        appState.showAppUpdateBannerFromNotification()
        onAppUpdateTapHandled(tap)
    }

    val openChatForShare: (List<ChatListItem>, String) -> Boolean = { allChats, groupIdHex ->
        val item = allChats.firstOrNull { it.group.groupIdHex.equals(groupIdHex, ignoreCase = true) }
        if (item == null) {
            false
        } else {
            sectionName = MainSection.Chats.name
            settingsDetailName = null
            chatListReturnHeadSnap = resetChatListReturnHeadSnap()
            commitExplicitConversationOpen(item.group.groupIdHex)
            selectedChatOpenContext = ConversationOpenContext()
            selectedChatJustCreated = false
            selectedChatOpenedAsDmHint = false
            selectedChat = item
            true
        }
    }

    val openAfterStagedShare: (String, List<String>) -> Unit = openStagedShare@{ accountRef, groupIds ->
        if (groupIds.isEmpty()) return@openStagedShare
        val otherCount = groupIds.size - 1
        if (accountRef == appState.activeAccountRef) {
            val allChats = chatsController.items + chatsController.archivedItems
            if (!openChatForShare(allChats, groupIds.first())) {
                appState.present(R.string.toast_notification_conversation_unavailable)
                return@openStagedShare
            }
            if (otherCount > 0) {
                appState.presentTransient(
                    AppText.Resource(R.string.toast_share_staged_other_chats, listOf(otherCount)),
                )
            }
        } else {
            val pending =
                PendingStagedShareOpen(
                    accountRef = accountRef,
                    groupIdHex = groupIds.first(),
                    otherChatCount = otherCount,
                )
            pendingStagedShareOpen = pending
            appState.launchMutation {
                appState.setActiveAccount(accountRef)
                if (
                    appState.activeAccountRef != accountRef &&
                    pendingStagedShareOpen === pending
                ) {
                    pendingStagedShareOpen = null
                }
            }
        }
    }
    val stageShareToChats:
        (ShareRequest, String, List<String>) -> Boolean =
        stageShare@{ request, accountRef, groupIds ->
            if (groupIds.isEmpty()) return@stageShare false
            if (!appState.stageInboundShare(accountRef, groupIds, request.payload)) {
                appState.present(R.string.toast_notification_account_unavailable)
                return@stageShare false
            }
            openAfterStagedShare(accountRef, groupIds)
            true
        }

    LaunchedEffect(
        pendingStagedShareOpen,
        appState.activeAccountRef,
        appState.accounts,
        chatsController,
        chatsController.boundAccountRef,
        chatsController.isLoading,
        chatsController.items,
        chatsController.archivedItems,
    ) {
        val pending = pendingStagedShareOpen ?: return@LaunchedEffect
        if (appState.accounts.none { it.label == pending.accountRef && it.isSignedInSigningAccount() }) {
            pendingStagedShareOpen = null
            appState.present(R.string.toast_notification_account_unavailable)
            return@LaunchedEffect
        }
        if (appState.activeAccountRef != pending.accountRef) return@LaunchedEffect
        val chatListReady =
            chatsController.boundAccountRef == pending.accountRef &&
                !chatsController.isLoading
        if (!chatListReady) return@LaunchedEffect
        val allChats = chatsController.items + chatsController.archivedItems
        pendingStagedShareOpen = null
        if (!openChatForShare(allChats, pending.groupIdHex)) {
            appState.present(R.string.toast_notification_conversation_unavailable)
            return@LaunchedEffect
        }
        if (pending.otherChatCount > 0) {
            appState.presentTransient(
                AppText.Resource(
                    R.string.toast_share_staged_other_chats,
                    listOf(pending.otherChatCount),
                ),
            )
        }
    }

    LaunchedEffect(
        chatsController,
        chatsController.boundAccountRef,
        chatsController.isLoading,
        chatsController.items,
        appState.activeAccountRef,
    ) {
        val chatListReady =
            chatsController.boundAccountRef == appState.activeAccountRef &&
                !chatsController.isLoading
        if (!chatListReady) return@LaunchedEffect
        appState.publishShareShortcuts(chatsController.forwardTargets())
    }

    LaunchedEffect(appState.appLockScreenVisible) {
        if (appState.appLockScreenVisible) {
            supersedePendingTtsDestinationNavigation()
            routingShare = false
        }
    }

    LaunchedEffect(
        pendingShareRequestStore,
        inboundShareRequest,
        appState.phase,
        appState.appLockScreenVisible,
        appState.activeAccountRef,
        appState.accounts,
        chatsController,
        chatsController.boundAccountRef,
        chatsController.isLoading,
        chatsController.hasLoadedLocalSnapshot,
        chatsController.items,
        chatsController.archivedItems,
        inboundDirectGroupId,
        shellStateHolder.pendingShareRequestId,
    ) {
        val request =
            inboundShareRequest ?: run {
                routingShare = false
                return@LaunchedEffect
            }
        routingShare = true
        supersedePendingTtsDestinationNavigation()
        val store = pendingShareRequestStore ?: return@LaunchedEffect
        var persisted = shellStateHolder.pendingShareRequestId == request.requestId
        if (!persisted) {
            persisted = store.save(request)
            if (persisted && !shellStateHolder.markInboundSharePersisted(request.requestId)) {
                store.remove(request.requestId)
                return@LaunchedEffect
            }
        }
        if (!shouldPresentInboundShare(appState.phase, appState.appLockScreenVisible)) return@LaunchedEffect
        if (request.shortcutId.isNullOrBlank()) {
            val promoted = shellStateHolder.promoteInboundShareRequest(request, persisted)
            if (promoted) {
                routingShare = false
                onShareRequestHandled(request)
            }
            return@LaunchedEffect
        }
        if (appState.accounts.isEmpty()) return@LaunchedEffect
        val accountRef = appState.activeAccountRef ?: return@LaunchedEffect
        val chatListReady =
            chatsController.boundAccountRef == accountRef &&
                !chatsController.isLoading
        if (!chatListReady) return@LaunchedEffect
        val directGroupId = inboundDirectGroupId
        if (directGroupId != null) {
            val staged =
                appState.stageInboundShareForFirstFrame(
                    accountRef = accountRef,
                    targetGroupIds = listOf(directGroupId),
                    payload = request.payload,
                )
            clearSharePickerRequest()
            if (staged) {
                openAfterStagedShare(accountRef, listOf(directGroupId))
            } else {
                appState.present(R.string.toast_notification_account_unavailable)
            }
            routingShare = false
        } else {
            val promoted = shellStateHolder.promoteInboundShareRequest(request, persisted)
            if (!promoted) {
                if (persisted) store.remove(request.requestId)
                return@LaunchedEffect
            }
            routingShare = false
            onShareRequestHandled(request)
        }
    }

    // Upgrade a provisional open (targeted groupDetails read) to the
    // authoritative chat-list row when it arrives — once, without re-navigation.
    LaunchedEffect(
        chatsController.materializedGroupsRevision,
        selectedChat?.group?.groupIdHex,
        selectedChat?.projection,
    ) {
        val open = selectedChat ?: return@LaunchedEffect
        reconcileProvisionalOpenChat(open, chatsController)?.let { selectedChat = it }
    }

    DisposableEffect(Unit) {
        onDispose { appState.clearActiveConversation() }
    }

    // Pop in-shell navigation back to the chat-list root when the active
    // account changes while the shell stays mounted (AppPhase.Ready preserved).
    // Without this, Sign Out & Wipe of the active account while another remains
    // leaves the shell painted on the now-deleted account's Identity & Keys
    // screen (issue #547), since the deep Settings/conversation nav state lives
    // in this shell's rememberSaveable and survives the account switch. The
    // no-accounts case drops to AppPhase.Onboarding and tears the shell down at
    // the top-level router, so it isn't handled here. Plain `remember` (not
    // Saveable) for the previous-ref tracker: a fresh composition after process
    // death must report `previous == null` so the saved screen/conversation is
    // restored, not popped (issue #386 guard, encoded in
    // shouldResetNavOnAccountChange). The tracker is advanced via
    // nextNavAccountRef so the transient null the destructive wipe sets while
    // draining the wiped account's streams (#610) doesn't poison the comparison
    // and swallow the pop onto the next account (regression of #547).
    var previousActiveAccountRef by remember { mutableStateOf(appState.activeAccountRef) }
    LaunchedEffect(appState.activeAccountRef) {
        val current = appState.activeAccountRef
        // A notification-routed early open renders its conversation before the
        // switch lands (#586); when this transition is exactly that pinned
        // account arriving while the user is still inside that conversation,
        // resetting here would immediately close what the route just opened.
        // Any other account change — including one while the user has since
        // navigated elsewhere — keeps the full reset.
        val earlyOpenLandsPinnedAccount =
            current != null &&
                notificationEarlyOpenRequestId != 0L &&
                selectedChatOpenContext.pinnedAccountRef == current &&
                selectedChatOpenContext.notificationRouteTraceRequestId == notificationEarlyOpenRequestId
        if (shouldResetNavOnAccountChange(previousActiveAccountRef, current) && !earlyOpenLandsPinnedAccount) {
            val ttsOwnsAccountChange =
                pendingTtsAccountSwitchOwnership.ownsAccountChange(
                    previousAccountRef = previousActiveAccountRef,
                    currentAccountRef = current,
                    request = pendingTtsDestinationNavigation,
                )
            if (!ttsOwnsAccountChange) {
                supersedePendingTtsDestinationNavigation()
            } else {
                pendingTtsAccountSwitchOwnership = null
            }
            clearSharePickerRequest()
            shellNavState =
                reduceShellNavigation(shellNavState, ShellNavigationEvent.AccountSwitched).state
            pendingConversationOpen = null
            exitingConversationContent = null
            selectedChat = null
            selectedChatOpenContext = ConversationOpenContext()
            selectedChatJustCreated = false
            selectedChatOpenedAsDmHint = false
            chatListReturnHeadSnap = resetChatListReturnHeadSnap()
            selectedChatListFolderId = null
            sectionName = MainSection.Chats.name
            settingsDetailName = null
        }
        previousActiveAccountRef = nextNavAccountRef(previousActiveAccountRef, current)
    }

    // Return the UI to the active spoken passage without touching playback.
    // Re-resolve the passage at every routing boundary so a sentence/message
    // advance during an account switch or local read cannot open a stale row.
    LaunchedEffect(
        pendingTtsDestinationNavigation,
        observedTtsDestination,
        appState.activeAccountRef,
        appState.accounts,
        chatsController,
        chatsController.boundAccountRef,
        chatsController.isLoading,
        chatsController.items,
        chatsController.archivedItems,
        chatsController.materializedGroupsRevision,
    ) {
        val request = pendingTtsDestinationNavigation ?: return@LaunchedEffect
        val currentDestination = observedTtsDestination
        val allChats = chatsController.items + chatsController.archivedItems
        val chatListReady =
            chatsController.boundAccountRef == request.accountRef &&
                !chatsController.isLoading
        val step =
            resolveTtsDestinationNavigation(
                request = request,
                currentDestination = currentDestination,
                knownAccountRefs = appState.accounts.mapTo(mutableSetOf()) { it.label },
                activeAccountRef = appState.activeAccountRef,
                availableGroupIds = allChats.mapTo(mutableSetOf()) { it.group.groupIdHex },
            )

        fun clearPendingRequest() {
            if (!pendingTtsDestinationNavigation.ownsCompletion(request.requestId)) return
            pendingTtsDestinationNavigation = null
            if (pendingTtsAccountSwitchOwnership?.requestId == request.requestId) {
                pendingTtsAccountSwitchOwnership = null
            }
        }

        fun failUnavailable() {
            if (!pendingTtsDestinationNavigation.ownsCompletion(request.requestId)) return
            clearPendingRequest()
            appState.present(R.string.tts_source_unavailable)
        }

        fun openDestination(item: ChatListItem) {
            if (!pendingTtsDestinationNavigation.ownsCompletion(request.requestId)) return
            val latest = appState.currentTtsConversationDestination()
            val valid =
                latest?.takeIf {
                    it.sessionId == request.sessionId &&
                        it.accountRef == request.accountRef &&
                        it.groupIdHex.equals(request.groupIdHex, ignoreCase = true)
                } ?: run {
                    failUnavailable()
                    return
                }
            sectionName = MainSection.Chats.name
            settingsDetailName = null
            supersedePendingGroupCreateOpen()
            chatListReturnHeadSnap = resetChatListReturnHeadSnap()
            selectedChatOpenContext =
                ConversationOpenContext(
                    focusMessageId = valid.passage.messageIdHex,
                    focusMessageRequestId = request.requestId,
                    ttsFocusSessionId = valid.sessionId,
                )
            selectedChatJustCreated = false
            selectedChatOpenedAsDmHint = false
            selectedChat = item
            clearPendingRequest()
        }

        when (step) {
            TtsDestinationNavigationStep.Cancelled -> clearPendingRequest()

            TtsDestinationNavigationStep.MissingAccount -> failUnavailable()

            TtsDestinationNavigationStep.AwaitAccountSwitch -> Unit

            is TtsDestinationNavigationStep.SwitchAccount -> {
                pendingTtsDestinationNavigation = request.copy(accountSwitchRequested = true)
                pendingTtsAccountSwitchOwnership =
                    TtsDestinationAccountSwitchOwnership(
                        requestId = request.requestId,
                        sourceAccountRef = appState.activeAccountRef,
                        targetAccountRef = step.accountRef,
                    )
                selectedChat = null
                selectedChatOpenContext = ConversationOpenContext()
                selectedChatJustCreated = false
                selectedChatOpenedAsDmHint = false
                chatListReturnHeadSnap = resetChatListReturnHeadSnap()
                appState.launchMutation {
                    appState.setActiveAccount(
                        label = step.accountRef,
                        shouldActivate = {
                            pendingTtsDestinationNavigation.ownsCompletion(request.requestId)
                        },
                    )
                    // setActiveAccount already presents its specific failure;
                    // do not stack a second generic unavailable message.
                    if (appState.activeAccountRef != step.accountRef) clearPendingRequest()
                }
            }

            is TtsDestinationNavigationStep.OpenConversation -> {
                allChats
                    .firstOrNull { it.group.groupIdHex.equals(step.groupIdHex, ignoreCase = true) }
                    ?.let(::openDestination)
                    ?: chatsController.chatItemForGroup(step.groupIdHex)?.let(::openDestination)
                    ?: failUnavailable()
            }

            is TtsDestinationNavigationStep.LoadConversationDirectly -> {
                runCatchingCancellable {
                    appState.loadNotificationChatListItem(
                        accountRef = step.accountRef,
                        groupIdHex = step.groupIdHex,
                    )
                }.onSuccess(::openDestination)
                    .onFailure {
                        // A transient targeted-read failure can race the new
                        // account's list bind. Keep the request until that local
                        // readiness boundary; once ready, failure is authoritative.
                        if (chatListReady) failUnavailable()
                    }
            }
        }
    }

    // Navigate the shell to a (possibly different) group when a profile sheet's
    // shared-group / Message action fires. The shell-owned profile coordinator
    // uses this for both shell and in-conversation sheets (#635).
    val openGroupFromProfile: (ChatListItem, Boolean) -> Unit = { item, justCreated ->
        chatListReturnHeadSnap = openGroupFromProfileSheet(chatListReturnHeadSnap)
        commitExplicitConversationOpen(item.group.groupIdHex)
        selectedChatOpenContext = ConversationOpenContext()
        selectedChatJustCreated = justCreated
        // `justCreated` is true only for freshly-created DMs; group creation and
        // existing-DM opens pass false. Reuse that DM-only invariant for the
        // open-time subtitle hint (#998).
        selectedChatOpenedAsDmHint = justCreated
        selectedChat = item
        appState.clearPresentedProfile()
    }
    val openGroupFromGroupCreateCompletion: (ChatListItem, Long) -> Unit = { item, requestToken ->
        if (commitGroupCreateCompletionOpen(item.group.groupIdHex, requestToken)) {
            chatListReturnHeadSnap = openGroupFromProfileSheet(chatListReturnHeadSnap)
            selectedChatOpenContext = ConversationOpenContext()
            selectedChatJustCreated = false
            selectedChatOpenedAsDmHint = false
            selectedChat = item
            appState.clearPresentedProfile()
        }
    }

    val conversationControllerCopy = rememberConversationControllerCopy()
    // A notification-routed early open pins the conversation to the target
    // account while its switch is still landing (#586). Keying and constructing
    // on the pinned ref keeps the controller correct before the flip and stops
    // it from being torn down and rebuilt when the active ref catches up.
    val accountOwnedPendingConversationOpen =
        pendingConversationOpen?.takeIf { request ->
            pendingConversationOpenBelongsToAccount(request.accountRef, appState.activeAccountRef)
        }
    val accountOwnedExitingConversationContent =
        exitingConversationContent?.takeIf { content ->
            retainedConversationContentBelongsToRoute(
                contentAccountRef = content.accountRef,
                activeAccountRef = appState.activeAccountRef,
                pinnedAccountRef = content.openContext.pinnedAccountRef,
                notificationRouteTraceRequestId = content.openContext.notificationRouteTraceRequestId,
                notificationEarlyOpenRequestId = notificationEarlyOpenRequestId,
            )
        }
    val conversationAccountRef =
        conversationControllerAccountRef(
            selectedPinnedAccountRef = selectedChatOpenContext.pinnedAccountRef,
            pendingAccountRef = accountOwnedPendingConversationOpen?.accountRef,
            exitingAccountRef = accountOwnedExitingConversationContent?.accountRef,
            activeAccountRef = appState.activeAccountRef,
        )
    // The pending and retained-exit legs are already account-gated; selected is not,
    // and the account-change nav reset clears them only a frame later — so during an
    // account switch or the wipe transient-null they could otherwise build a
    // controller seeded with the previous account's decrypted preview. Drop it
    // until the shell's remembered account matches the live one. A notification-
    // routed early open is the deliberate exception — its content is pinned to
    // the very account that is arriving, so the flip that lands the pin must not
    // blank or rebuild the conversation the route just opened.
    val earlyOpenLandsPinnedAccount =
        appState.activeAccountRef != null &&
            notificationEarlyOpenRequestId != 0L &&
            selectedChatOpenContext.pinnedAccountRef == appState.activeAccountRef &&
            selectedChatOpenContext.notificationRouteTraceRequestId == notificationEarlyOpenRequestId
    val navAccountStable =
        mainShellAccountContentOwned(previousActiveAccountRef, appState.activeAccountRef) ||
            earlyOpenLandsPinnedAccount
    // A completed local snapshot is immediately useful even while the
    // controller is refreshing or retaining a non-fatal refresh error. Only a
    // genuinely absent snapshot falls through to the truthful loading/error
    // surface below.
    val quickSwitchTargetLocallyReady =
        quickAccountSwitchTargetLocallyReady(
            controllerAccountRef = chatsController.boundAccountRef,
            activeAccountRef = appState.activeAccountRef,
            hasLoadedLocalSnapshot = chatsController.hasLoadedLocalSnapshot,
        )
    val quickSwitchTargetHasAnyChats =
        chatsController.items.isNotEmpty() || chatsController.archivedItems.isNotEmpty()
    val quickSwitchOwnsTargetFrame =
        quickAccountSwitchOwnsTargetFrame(
            transition = quickAccountSwitchTransition,
            activeAccountRef = appState.activeAccountRef,
            targetLocallyReady = quickSwitchTargetLocallyReady,
        )
    LaunchedEffect(
        quickAccountSwitchTransition?.requestId,
        appState.activeAccountRef,
        quickSwitchTargetLocallyReady,
        quickSwitchTargetHasAnyChats,
        navAccountStable,
    ) {
        val request = quickAccountSwitchTransition ?: return@LaunchedEffect
        when {
            appState.activeAccountRef == request.targetAccountRef && !quickSwitchTargetLocallyReady ->
                // A missing local snapshot owns the existing truthful
                // loading/error surface, never the decorative cue.
                quickAccountSwitchTransition = null
            appState.activeAccountRef == request.targetAccountRef && !quickSwitchTargetHasAnyChats ->
                // The authoritative destination-owned empty state is already
                // the useful first frame; do not insert or later resurrect an
                // account-identity interstitial over it.
                quickAccountSwitchTransition = null
            appState.activeAccountRef == request.targetAccountRef &&
                request.motion == QuickAccountSwitchMotion.Animated &&
                request.phase == QuickAccountSwitchPhase.AwaitingTarget -> {
                // Commit one complete target-identity frame, then reveal the
                // already-composed local target beneath the bounded fade.
                withFrameNanos { }
                if (quickAccountSwitchTransition?.requestId == request.requestId) {
                    quickAccountSwitchTransition = request.copy(phase = QuickAccountSwitchPhase.RevealingTarget)
                }
            }
            appState.activeAccountRef == request.targetAccountRef &&
                request.motion == QuickAccountSwitchMotion.Reduced &&
                navAccountStable ->
                quickAccountSwitchTransition = null
            appState.activeAccountRef == request.targetAccountRef &&
                request.phase == QuickAccountSwitchPhase.RevealComplete &&
                navAccountStable ->
                quickAccountSwitchTransition = null
            appState.activeAccountRef != request.sourceAccountRef &&
                appState.activeAccountRef != request.targetAccountRef ->
                quickAccountSwitchTransition = null
        }
    }
    // Activity recreation reads the live selection synchronously from the
    // holder. Process restoration keeps only lightweight route keys. Persist
    // only after account ownership is stable so an account-switch frame cannot
    // save the previous account's group under the destination account.
    LaunchedEffect(
        selectedChat?.group?.groupIdHex,
        conversationAccountRef,
        navAccountStable,
    ) {
        if (selectedChat == null || navAccountStable) {
            shellStateHolder.persistConversationRoute(conversationAccountRef)
        }
    }
    val controllerChat =
        selectedChat?.takeIf { navAccountStable && conversationAccountRef != null }
            ?: accountOwnedPendingConversationOpen?.item
    val controllerChatId = controllerChat?.id
    val selectedOrPendingConversationController =
        controllerChat?.let { openChat ->
            conversationAccountRef?.let { accountRef ->
                shellStateHolder.conversationController(
                    chatId = openChat.id,
                    accountRef = accountRef,
                    runtimeGeneration = appState.runtimeGeneration,
                    presentationKey = conversationControllerCopy.hashCode(),
                ) {
                    ConversationController(
                        appState = appState,
                        initialGroup = openChat.group,
                        initialMemberSnapshot =
                            openChat.memberSnapshot
                                ?: appState.cachedGroupMemberSnapshot(
                                    accountRef,
                                    openChat.group.groupIdHex,
                                ),
                        initialChatListRow = openChat.projection,
                        initialIsDm = openChat.isDm(),
                        initialTimelinePreview = openChat.projection?.lastMessage,
                        accountRefOverride = accountRef.takeIf { it != appState.activeAccountRef },
                        startOnConstruction = true,
                        copy = conversationControllerCopy,
                    )
                }
            }
        }
    val conversationController =
        selectedOrPendingConversationController
            ?: accountOwnedExitingConversationContent?.controller
    var conversationTimelineVisibility by remember {
        mutableStateOf<ConversationTimelineVisibility?>(null)
    }
    val selectedConversationTimelineVisible =
        conversationTimelineVisibility
            ?.takeIf { it.controller === selectedOrPendingConversationController }
            ?.visible
            ?: true
    val attachmentOpenSelectedChat = selectedChat
    val attachmentOpenDestinationAccountRef =
        selectedOrPendingConversationController
            ?.boundAccountRef
            ?.takeIf {
                navAccountStable &&
                    selectedConversationTimelineVisible &&
                    attachmentOpenChatSelectionMatches(attachmentOpenSelectedChat?.id, controllerChatId) &&
                    appState.pendingProfileNpub == null &&
                    profileGroupForegroundState.initialMember == null &&
                    !routingNotification &&
                    !routingShare &&
                    !routingAppUpdate &&
                    pendingTtsDestinationNavigation == null
            }
    val attachmentOpenDestinationGroupId =
        attachmentOpenSelectedChat?.group?.groupIdHex.takeIf { attachmentOpenDestinationAccountRef != null }
    val attachmentOpenDestinationKey =
        if (attachmentOpenDestinationAccountRef != null && attachmentOpenDestinationGroupId != null) {
            "$attachmentOpenDestinationAccountRef\u0000${attachmentOpenDestinationGroupId.lowercase()}"
        } else {
            ATTACHMENT_OPEN_DESTINATION_NONE
        }
    val attachmentOpenDestinationGeneration =
        if (previousAttachmentOpenDestinationKey == attachmentOpenDestinationKey) {
            attachmentOpenNavigationGeneration
        } else {
            attachmentOpenNavigationGeneration + 1L
        }
    SideEffect {
        appState.attachmentOpens.setDestination(
            attachmentOpenDestinationAccountRef?.let { accountRef ->
                AttachmentOpenDestination(
                    accountRef = accountRef,
                    groupIdHex = requireNotNull(attachmentOpenDestinationGroupId),
                    navigationGeneration = attachmentOpenDestinationGeneration,
                )
            },
        )
        if (previousAttachmentOpenDestinationKey != attachmentOpenDestinationKey) {
            previousAttachmentOpenDestinationKey = attachmentOpenDestinationKey
            attachmentOpenNavigationGeneration = attachmentOpenDestinationGeneration
        }
    }
    // Follow the selected controller directly so a chat-to-chat switch never
    // hops through null. The account-stability guard is essential: during an
    // ordinary account switch the old selection survives for one composition,
    // but it is no longer rendered. Never rebind that stale group to the new
    // account and dismiss the destination account's notifications. A routed
    // early open remains stable through its pinned-account exception above.
    ConversationNotificationOwnershipEffect(
        selectedChatId = selectedChat?.id,
        selectedGroupIdHex = selectedChat?.group?.groupIdHex,
        renderedChatId = controllerChatId,
        renderedAccountRef = selectedOrPendingConversationController?.boundAccountRef,
        navigationAccountStable = navAccountStable,
        timelineVisible = selectedConversationTimelineVisible,
    ) { accountRef, groupIdHex ->
        appState.setActiveConversationFromUi(accountRef, groupIdHex)
    }
    // Preloading, selected, and outgoing routes can briefly own different
    // controllers during a rapid Back -> open gesture. Keep each instance alive
    // for as long as any route slot references it; a single "current" effect
    // would clear the outgoing controller while AnimatedContent still composes it.
    val ownedConversationControllers =
        remember(selectedOrPendingConversationController, accountOwnedExitingConversationContent?.controller) {
            listOfNotNull(
                selectedOrPendingConversationController,
                accountOwnedExitingConversationContent?.controller,
            ).distinct()
        }
    ownedConversationControllers.forEach { ownedController ->
        key(ownedController) {
            DisposableEffect(ownedController) {
                appState.attachConversationController(ownedController)
                onDispose {
                    appState.detachConversationController(ownedController)
                }
            }
            LaunchedEffect(ownedController, ownedController.retryGeneration) {
                ownedController.start()
            }
        }
    }
    LaunchedEffect(ownedConversationControllers) {
        shellStateHolder.retainConversationControllers(ownedConversationControllers)
    }
    LaunchedEffect(
        conversationController,
        conversationController?.isLoading,
        conversationController?.hasPublishedAuthoritativeTimeline,
        conversationController?.error,
        conversationController?.terminalConversationUnavailable,
        selectedChatOpenContext.notificationRouteTraceRequestId,
    ) {
        val requestId = selectedChatOpenContext.notificationRouteTraceRequestId ?: return@LaunchedEffect
        if (conversationController?.error != null || conversationController?.terminalConversationUnavailable == true) {
            releaseNotificationFirstFrameGate(requestId)
            NotificationRouteTrace.finishRequest(requestId)
            return@LaunchedEffect
        }
        if (conversationController?.hasPublishedAuthoritativeTimeline == true) {
            NotificationRouteTrace.endPhase(
                requestId = requestId,
                sectionName = NotificationRouteTraceSection.TARGET_TIMELINE,
            )
            NotificationRouteTrace.beginPhase(
                requestId = requestId,
                sectionName = NotificationRouteTraceSection.INITIAL_ANCHOR,
            )
        }
        if (conversationController != null && !conversationController.isLoading) {
            NotificationRouteTrace.endPhase(
                requestId = requestId,
                sectionName = NotificationRouteTraceSection.CONTROLLER_BIND,
            )
        }
    }
    val pendingOpen = accountOwnedPendingConversationOpen
    LaunchedEffect(
        pendingOpen?.requestId,
        conversationController,
    ) {
        val request = pendingOpen ?: return@LaunchedEffect
        val controller = conversationController ?: return@LaunchedEffect
        if (!controller.group.groupIdHex.equals(request.item.group.groupIdHex, ignoreCase = true)) {
            return@LaunchedEffect
        }
        withTimeoutOrNull(CONVERSATION_PENDING_OPEN_TIMEOUT_MILLIS) {
            snapshotFlow {
                preparedConversationCanOpen(
                    hasPublishedAuthoritativeTimeline = controller.hasPublishedAuthoritativeTimeline,
                    hasPreparedInitialPresentation = controller.hasPreparedInitialPresentation,
                    hasLoadError = controller.error != null,
                    terminalConversationUnavailable = controller.terminalConversationUnavailable,
                )
            }.filter { it }
                .first()
        }
        // A normal cached open reaches the ready state before this deadline and
        // keeps its spinner-free transition. A stuck local read must not make a
        // tap look ignored indefinitely; after the bound, the destination owns
        // the existing loading/error surfaces while the same controller continues.
        selectedChatOpenContext = ConversationOpenContext(focusMessageId = request.focusMessageId)
        selectedChatJustCreated = request.justCreated
        selectedChatOpenedAsDmHint = request.justCreated
        chatListReturnHeadSnap =
            openGroupFromChatList(
                chatListReturnHeadSnap,
                visibleActiveListHeadId = request.visibleActiveListHeadId,
            )
        selectedChat = request.item
        pendingConversationOpen = null
    }
    LaunchedEffect(
        selectedChat?.id,
        section,
        appState.pendingProfileNpub,
        routingNotification,
        routingShare,
        routingAppUpdate,
    ) {
        val supersededByOtherNavigation =
            selectedChat != null ||
                section != MainSection.Chats ||
                appState.pendingProfileNpub != null ||
                routingNotification ||
                routingShare ||
                routingAppUpdate
        if (pendingConversationOpen != null && supersededByOtherNavigation) {
            pendingConversationOpen = null
        }
    }
    if (!navAccountStable && !quickSwitchOwnsTargetFrame) {
        // Account invalidation is a privacy boundary, not an ordinary Back
        // navigation. Remove the AnimatedContent subtree immediately so its
        // outgoing slot cannot retain a decrypted route for the exit tween.
        LoadingScreen()
        return
    }
    ProfileGroupForegroundCoordinator(
        appState = appState,
        conversationController = selectedOrPendingConversationController.takeIf { selectedChat != null },
        profileGroupForegroundState = profileGroupForegroundState,
        secureWindowEnabled =
            if (selectedChat != null || section == MainSection.Chats) {
                !appState.allowChatScreenshotsInChats
            } else {
                null
            },
        profileSecurePolicy =
            when {
                conversationController != null && appState.allowChatScreenshotsInChats -> SecureFlagPolicy.SecureOff
                conversationController != null -> SecureFlagPolicy.SecureOn
                section != MainSection.Chats -> SecureFlagPolicy.Inherit
                appState.allowChatScreenshotsInChats -> SecureFlagPolicy.SecureOff
                else -> SecureFlagPolicy.SecureOn
            },
        onOpenConversation = openGroupFromProfile,
        onGroupCreateSubmitted = onGroupCreateSubmitted,
        onGroupCreateCompletedOpen = openGroupFromGroupCreateCompletion,
        onGroupCreateFlowSuperseded = supersedePendingGroupCreateOpen,
        onDismissProfile = {
            chatListReturnHeadSnap = dismissChatListProfile(chatListReturnHeadSnap)
            appState.clearPresentedProfile()
        },
        onClosePicker = { chatListReturnHeadSnap = dismissChatListProfile(chatListReturnHeadSnap) },
    ) {
        val routeForwardDirection = conversationRouteForwardDirection(LocalLayoutDirection.current)
        val transitionContent =
            selectedChat?.let { chat ->
                selectedOrPendingConversationController?.let { controller ->
                    conversationAccountRef?.let { accountRef ->
                        ConversationTransitionContent(
                            chat = chat,
                            controller = controller,
                            accountRef = accountRef,
                            openContext = selectedChatOpenContext,
                            justCreated = selectedChatJustCreated,
                            openedAsDmHint = selectedChatOpenedAsDmHint,
                        )
                    }
                }
            }
        val routeTransition = updateTransition(targetState = transitionContent, label = "conversation route")
        LaunchedEffect(exitingConversationContent?.chat?.id, selectedChat?.id, routeTransition) {
            val exiting = exitingConversationContent ?: return@LaunchedEffect
            if (selectedChat?.id == exiting.chat.id) {
                exitingConversationContent = null
                return@LaunchedEffect
            }
            snapshotFlow {
                conversationRouteTransitionComplete(
                    currentStateMatchesTarget = routeTransition.currentState == routeTransition.targetState,
                    transitionRunning = routeTransition.isRunning,
                )
            }.filter { it }
                .first()
            // AnimatedContent removes its outgoing slot at completion. Give that
            // disposal one committed frame before releasing the controller that
            // the outgoing ConversationScreen may still reference.
            withFrameNanos { }
            if (selectedChat?.id != exiting.chat.id) exitingConversationContent = null
        }
        ConversationRouteAnimatedContent(
            transition = routeTransition,
            routeForwardDirection = routeForwardDirection,
            suppressMotion =
                routingNotification ||
                    routingShare ||
                    routingAppUpdate ||
                    pendingTtsDestinationNavigation != null,
            contentKey = { content -> content?.chat?.id ?: MAIN_SHELL_ROUTE_KEY },
        ) { animatedConversation ->
            when (
                resolveMainShellContentRoute(
                    conversationOpen = animatedConversation != null,
                    routingNotification = routingNotification || routingShare || routingAppUpdate,
                    routingTtsReturn = pendingTtsDestinationNavigation != null,
                )
            ) {
                MainShellContentRoute.Conversation -> {
                    val content = requireNotNull(animatedConversation)
                    val chat = content.chat
                    val scrollKey = conversationScrollKey(content.accountRef, chat.group.groupIdHex)
                    val notificationReadThroughCommitter =
                        remember(
                            content.controller,
                            content.openContext.notificationOpenRequestId,
                            content.openContext.notificationRouteTraceRequestId,
                            content.openContext.notificationReadThroughMessageId,
                        ) {
                            NotificationReadThroughCommitter(
                                content.openContext.notificationReadThroughMessageId
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { messageIdHex ->
                                        NotificationReadThroughTarget(
                                            accountRef = content.accountRef,
                                            groupIdHex = chat.group.groupIdHex,
                                            messageIdHex = messageIdHex,
                                        )
                                    },
                            )
                        }
                    val commitNotificationReadThrough: (NotificationReadThroughTarget) -> Unit = { target ->
                        appState.launchMutation {
                            appState.markNotificationMessageRead(
                                accountRef = target.accountRef,
                                groupIdHex = target.groupIdHex,
                                messageIdHex = target.messageIdHex,
                            )
                        }
                    }
                    NotificationReadThroughCommitOnDispose(
                        committer = notificationReadThroughCommitter,
                        onCommit = commitNotificationReadThrough,
                    )
                    ConversationScreen(
                        appState = appState,
                        chat = chat,
                        controller = content.controller,
                        focusMessageId = content.openContext.focusMessageId,
                        focusMessageRequestId = content.openContext.focusMessageRequestId,
                        ttsFocusSessionId = content.openContext.ttsFocusSessionId,
                        notificationOpenRequestId = content.openContext.notificationOpenRequestId,
                        notificationReadThroughMessageId = content.openContext.notificationReadThroughMessageId,
                        onNotificationUnreadBoundaryCaptured = {
                            notificationReadThroughCommitter.commit(commitNotificationReadThrough)
                        },
                        onNotificationTimelineVisibilityChanged = { visible ->
                            conversationTimelineVisibility =
                                ConversationTimelineVisibility(
                                    controller = content.controller,
                                    visible = visible,
                                )
                        },
                        onFirstFrameCommitted = {
                            content.openContext.notificationRouteTraceRequestId?.let { requestId ->
                                releaseNotificationFirstFrameGate(requestId)
                                NotificationRouteTrace.endPhase(
                                    requestId = requestId,
                                    sectionName = NotificationRouteTraceSection.INITIAL_ANCHOR,
                                )
                                NotificationRouteTrace.endPhase(
                                    requestId = requestId,
                                    sectionName = NotificationRouteTraceSection.FIRST_CONVERSATION_FRAME,
                                )
                                NotificationRouteTrace.finishRequest(requestId)
                            }
                        },
                        justCreated = content.justCreated,
                        openedAsDmHint = content.openedAsDmHint,
                        routeTransitionInProgress =
                            !conversationRouteTransitionComplete(
                                currentStateMatchesTarget =
                                    routeTransition.currentState == routeTransition.targetState,
                                transitionRunning = routeTransition.isRunning,
                            ),
                        restoredScrollSnapshot = conversationScrollSnapshots[scrollKey],
                        onOpenConversation = openGroupFromProfile,
                        onGroupCreateSubmitted = onGroupCreateSubmitted,
                        onGroupCreateCompletedOpen = openGroupFromGroupCreateCompletion,
                        onGroupCreateFlowSuperseded = supersedePendingGroupCreateOpen,
                        onTtsTransportBodyClick = requestTtsDestinationOpen,
                        onSaveScrollSnapshot = { snapshot ->
                            if (snapshot == null) {
                                conversationScrollSnapshots.remove(scrollKey)
                            } else {
                                conversationScrollSnapshots[scrollKey] = snapshot
                            }
                        },
                        onBack = {
                            // A very quick Back can leave before the provisional
                            // open receives its chat-list projection. The entry
                            // divider no longer matters once the route is closed,
                            // so durably commit the tap's read cursor here (#1016).
                            notificationReadThroughCommitter.commit(commitNotificationReadThrough)
                            // Invalidate notification ownership before retaining
                            // the outgoing screen for its Back animation. That
                            // retained screen must never republish its account.
                            conversationTimelineVisibility =
                                ConversationTimelineVisibility(
                                    controller = content.controller,
                                    visible = false,
                                )
                            appState.clearActiveConversation()
                            // Flush the hidden list before exposing it, so the first
                            // drawn return frame already has the optimistic preview
                            // in its final recency slot (#900).
                            chatsController.setChatListVisible(true)
                            shellNavState =
                                reduceShellNavigation(
                                    shellNavState,
                                    ShellNavigationEvent.ConversationBackedOut,
                                ).state
                            // Backing out before the first frame committed abandons
                            // the trace's owner; finish it here or TOTAL stays open
                            // until the next notification. finishRequest is a no-op
                            // for an already-finished request.
                            content.openContext.notificationRouteTraceRequestId?.let {
                                releaseNotificationFirstFrameGate(it)
                                NotificationRouteTrace.finishRequest(it)
                            }
                            exitingConversationContent = content
                            selectedChat = null
                            selectedChatOpenContext = ConversationOpenContext()
                            selectedChatJustCreated = false
                            selectedChatOpenedAsDmHint = false
                        },
                    )
                }
                MainShellContentRoute.NotificationLoading -> {
                    // A notification tap on a non-active account resolves in steps
                    // (switch account → await its chat list → open conversation). Keep
                    // one loading surface over that whole route.
                    LoadingScreen()
                }
                MainShellContentRoute.TtsReturnTransition -> {
                    BackHandler { supersedePendingTtsDestinationNavigation() }
                    TtsReturnTransitionScreen(
                        requestId = requireNotNull(pendingTtsDestinationNavigation).requestId,
                    )
                }
                MainShellContentRoute.Main ->
                    when (section) {
                        MainSection.Chats -> {
                            WindowSecureFlag(enabled = !appState.allowChatScreenshotsInChats)
                            ChatsScreen(
                                appState = appState,
                                controller = chatsController,
                                globalSearchState = scopedGlobalSearchState,
                                onGlobalSearchStateChange = globalSearch.update,
                                selectedFolderId = selectedChatListFolderId,
                                onSelectFolder = { selectedChatListFolderId = it },
                                onTtsTransportBodyClick = requestTtsDestinationOpen,
                                onQuickSwitchAccount = ::requestQuickAccountSwitch,
                                onGroupCreateSubmitted = onGroupCreateSubmitted,
                                onGroupCreateCompletedOpen = openGroupFromGroupCreateCompletion,
                                onGroupCreateFlowSuperseded = supersedePendingGroupCreateOpen,
                                conversationReturnHeadId = publishedConversationReturnHead(chatListReturnHeadSnap),
                                onConversationReturnHeadHandled = {
                                    chatListReturnHeadSnap = onConversationReturnHeadHandled(chatListReturnHeadSnap)
                                },
                                onOpenSettings = {
                                    pendingConversationOpen = null
                                    supersedePendingTtsDestinationNavigation()
                                    chatListReturnHeadSnap = resetChatListReturnHeadSnap()
                                    supersedePendingGroupCreateOpen()
                                    sectionName = MainSection.Settings.name
                                    settingsDetailName = null
                                },
                                onOpenGroup = { item, focusMessageId, justCreated, visibleHeadId ->
                                    commitExplicitConversationOpen(item.group.groupIdHex)
                                    nextPendingConversationOpenRequestId += 1L
                                    pendingConversationOpen =
                                        PendingConversationOpen(
                                            requestId = nextPendingConversationOpenRequestId,
                                            accountRef = appState.activeAccountRef,
                                            item = item,
                                            focusMessageId = focusMessageId,
                                            justCreated = justCreated,
                                            visibleActiveListHeadId = visibleHeadId,
                                        )
                                },
                                onPresentProfile = { npub, visibleHeadId ->
                                    pendingConversationOpen = null
                                    chatListReturnHeadSnap =
                                        presentProfileFromChatList(chatListReturnHeadSnap, visibleHeadId)
                                    shellNavState =
                                        armShellProfileForeground(shellNavState, profileGroupForegroundState)
                                    previousPendingProfileNpub = npub
                                    appState.presentProfile(npub)
                                },
                            )
                        }
                        MainSection.Settings ->
                            SettingsScreen(
                                appState = appState,
                                onBackToChats = {
                                    sectionName = MainSection.Chats.name
                                    settingsDetailName = null
                                },
                                onOpenDiagnostics = {
                                    // Preserve `settingsDetailName` so backing out of
                                    // Diagnostics returns to Developer (its only entry point)
                                    // rather than the Settings home, restoring the breadcrumb
                                    // the user walked in on (#412).
                                    sectionName = MainSection.Diagnostics.name
                                },
                                onOpenSupportChat = { item ->
                                    // Land in the conversation itself, not the chat list; no
                                    // list scroll state exists to snapshot from Settings.
                                    commitExplicitConversationOpen(item.group.groupIdHex)
                                    selectedChatOpenedAsDmHint = false
                                    selectedChat = item
                                    sectionName = MainSection.Chats.name
                                    settingsDetailName = null
                                },
                                detail = settingsDetail,
                                onDetailChange = { settingsDetailName = it?.name },
                            )
                        MainSection.Diagnostics ->
                            DiagnosticsScreen(
                                appState = appState,
                                onBack = {
                                    // Leave `settingsDetailName` alone — it still holds the
                                    // detail (Developer) the user opened Diagnostics from, so
                                    // Settings re-enters that screen directly (#412).
                                    sectionName = MainSection.Settings.name
                                },
                            )
                    }
            }
        }
        ConversationRouteSettledPerformanceMarker(
            conversationId = transitionContent?.chat?.id,
            routeTransition = routeTransition,
            destinationContentReady =
                transitionContent?.controller?.let { controller ->
                    preparedConversationCanOpen(
                        hasPublishedAuthoritativeTimeline = controller.hasPublishedAuthoritativeTimeline,
                        hasPreparedInitialPresentation = controller.hasPreparedInitialPresentation,
                        hasLoadError = controller.error != null,
                        terminalConversationUnavailable = controller.terminalConversationUnavailable,
                    )
                } ?: true,
        )
        ConversationControllerReleasedPerformanceMarker(
            controllerReleased =
                conversationControllerReleased(
                    conversationOpen = transitionContent != null,
                    exitingContentRetained = exitingConversationContent != null,
                    controllerPresent = conversationController != null,
                ),
        )
    }
    val showQuickAccountSwitchCue =
        quickAccountSwitchShouldShowCue(
            transition = quickAccountSwitchTransition,
            activeAccountRef = appState.activeAccountRef,
            targetLocallyReady = quickSwitchTargetLocallyReady,
            targetHasAnyChats = quickSwitchTargetHasAnyChats,
        )
    QuickAccountSwitchTransitionOverlay(
        transition =
            quickAccountSwitchTransition.takeIf {
                quickSwitchOwnsTargetFrame && quickSwitchTargetHasAnyChats
            },
        visible = showQuickAccountSwitchCue,
        onFinished = { requestId ->
            quickAccountSwitchTransition?.takeIf { it.requestId == requestId }?.let { request ->
                quickAccountSwitchTransition =
                    if (navAccountStable) {
                        null
                    } else {
                        request.copy(phase = QuickAccountSwitchPhase.RevealComplete)
                    }
            }
        },
    )

    // Compose after every shell/profile/new-group surface. The full-screen
    // Dialog owns pointer and accessibility focus while preserving the route
    // underneath for an exact return after cancellation (issue #1721).
    if (shouldPresentInboundShare(appState.phase, appState.appLockScreenVisible)) {
        visiblePickerRequest?.let { request ->
            ShareChatPickerFullScreen(
                appState = appState,
                requestId = request.requestId,
                payload = request.payload,
                onDismiss = clearSharePickerRequest,
                onStage = { accountRef, groupIds ->
                    stageShareToChats(request, accountRef, groupIds)
                },
            )
        }
    }
}

private const val ATTACHMENT_OPEN_DESTINATION_UNINITIALIZED = "attachment-open-uninitialized"
private const val ATTACHMENT_OPEN_DESTINATION_NONE = "attachment-open-none"

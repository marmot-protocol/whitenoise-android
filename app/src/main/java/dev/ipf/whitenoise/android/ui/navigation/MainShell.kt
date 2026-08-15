@file:Suppress("ReturnCount") // Foreground routes must stop lower surfaces from composing over them.

package dev.ipf.whitenoise.android.ui.navigation

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.notifications.NotificationInviteAuthoritativeOutcome
import dev.ipf.whitenoise.android.notifications.NotificationMessageDirectLoadOutcome
import dev.ipf.whitenoise.android.notifications.NotificationNavStep
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.notifications.inviteAuthoritativeGroupAvailable
import dev.ipf.whitenoise.android.notifications.loadNotificationMessageDirectly
import dev.ipf.whitenoise.android.notifications.resolveNotificationNav
import dev.ipf.whitenoise.android.notifications.retryInviteAuthoritativeLoad
import dev.ipf.whitenoise.android.share.EncryptedPendingShareRequestStore
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.share.resolveShareDirectGroupId
import dev.ipf.whitenoise.android.share.shouldPresentInboundShare
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.currentTtsConversationDestination
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
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollSnapshot
import dev.ipf.whitenoise.android.ui.conversation.conversationScrollKey
import dev.ipf.whitenoise.android.ui.profile.ProfileSheet
import dev.ipf.whitenoise.android.ui.settings.DiagnosticsScreen
import dev.ipf.whitenoise.android.ui.settings.SettingsScreen
import dev.ipf.whitenoise.android.ui.share.ShareChatPickerFullScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ConversationOpenContext(
    val focusMessageId: String? = null,
    val focusMessageRequestId: Long = 0L,
    val ttsFocusSessionId: Long? = null,
    val notificationOpenRequestId: Long = 0L,
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

internal fun nextNotificationConversationOpenContext(current: ConversationOpenContext): ConversationOpenContext =
    ConversationOpenContext(notificationOpenRequestId = current.notificationOpenRequestId + 1L)

internal enum class MainShellContentRoute {
    Conversation,
    NotificationLoading,
    TtsReturnTransition,
    Main,
}

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainShell(
    appState: WhiteNoiseAppState,
    inboundNotificationTarget: NotificationTarget? = null,
    inboundNotificationRequestId: Long = 0L,
    onNotificationTargetHandled: (NotificationTarget, Long) -> Unit = { _, _ -> },
    inboundShareRequest: ShareRequest? = null,
    onShareRequestHandled: (ShareRequest) -> Unit = {},
    inboundAppUpdateTap: Int = 0,
    onAppUpdateTapHandled: (Int) -> Unit = {},
) {
    var sectionName by rememberSaveable { mutableStateOf(MainSection.Chats.name) }
    var settingsDetailName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedChat by remember { mutableStateOf<ChatListItem?>(null) }
    // The open conversation must survive Activity recreation / process death
    // (issue #386): the in-app camera foregrounds an external activity that can
    // get the host process killed on low-memory devices, and on return a null
    // selection drops the user on the chat list and discards the staged capture
    // owned by ConversationScreen. Per AGENTS.md we must NOT serialize the
    // ChatListItem (it wraps FFI records) into the saved bundle — only the
    // lightweight group id hex is persisted, and selectedChat is re-resolved
    // from the live ChatsController once its list loads.
    var savedSelectedGroupIdHex by rememberSaveable { mutableStateOf<String?>(null) }
    // Reset on each new composition (plain remember, not Saveable): a fresh
    // composition after process death must be allowed exactly one restore
    // attempt. Once that attempt runs — or the user explicitly navigates —
    // the sync effect below owns the saved id and this stays true.
    var hasRestoredSelectedChat by remember { mutableStateOf(false) }
    // Open-time conversation state. Search hits carry a focus id; every
    // notification tap advances the request id so an already-mounted
    // ConversationScreen re-runs its first-unread anchor.
    var selectedChatOpenContext by remember { mutableStateOf(ConversationOpenContext()) }
    // True only when `selectedChat` was opened straight off a just-completed
    // New Chat / Create Group flow (issue #321), so ConversationScreen raises
    // the composer + keyboard once on entry. Plain `remember` (not
    // rememberSaveable) so it never survives process death. Reset on every
    // other open path and on back.
    var selectedChatJustCreated by remember { mutableStateOf(false) }
    // True only for the route that has just created a 1:1 DM and is opening it
    // before the live roster has necessarily settled. Suppresses the group-style
    // member-count subtitle during that transient 0/1-member window (#998).
    var selectedChatOpenedAsDmHint by remember { mutableStateOf(false) }
    // Per-conversation scroll anchors for back-to-list re-entry (issue #1107).
    // Keyed by account + group id; dropped when the reader leaves near-bottom so
    // the normal unread/newest anchor still runs for chats left at the tail.
    val conversationScrollSnapshots = remember { mutableStateMapOf<String, ConversationScrollSnapshot>() }
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
    var routingNotification by remember { mutableStateOf(false) }
    // Tracks whether an in-flight group-create completion may still open its
    // conversation. Explicit shell navigation advances [navigationGeneration]
    // and invalidates a captured pending generation (issue #1953).
    var shellNavState by rememberSaveable(stateSaver = ShellNavigationStateSaver) {
        mutableStateOf(ShellNavigationState())
    }
    var pendingTtsDestinationNavigation by remember {
        mutableStateOf<TtsDestinationNavigationRequest?>(null)
    }
    val observedTtsDestination = appState.observeTtsConversationDestination()
    var nextTtsDestinationRequestId by remember { mutableLongStateOf(0L) }
    val supersedePendingTtsDestinationNavigation: () -> Unit = {
        pendingTtsDestinationNavigation = null
    }
    val requestTtsDestinationOpen: () -> Unit = {
        val destination = appState.currentTtsConversationDestination()
        if (destination == null) {
            appState.present(R.string.tts_source_unavailable)
        } else {
            nextTtsDestinationRequestId += 1L
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
    val context = LocalContext.current
    val pendingShareRequestStore = remember(context) { EncryptedPendingShareRequestStore.create(context) }
    var sharePickerRequest by remember { mutableStateOf<ShareRequest?>(null) }
    var savedSharePickerRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val clearSharePickerRequest: () -> Unit = {
        val requestId = savedSharePickerRequestId ?: sharePickerRequest?.requestId
        sharePickerRequest = null
        savedSharePickerRequestId = null
        requestId?.let { pendingRequestId ->
            appState.launchMutation {
                withContext(Dispatchers.IO) { pendingShareRequestStore.remove(pendingRequestId) }
            }
        }
    }
    val chatsController = remember(appState.activeAccountRef, appState.runtimeGeneration) { ChatsController(appState) }
    val section = runCatching { MainSection.valueOf(sectionName) }.getOrDefault(MainSection.Chats)
    val settingsDetail = settingsDetailName?.let { runCatching { SettingsDetail.valueOf(it) }.getOrNull() }

    DisposableEffect(chatsController) {
        appState.attachChatsController(chatsController)
        onDispose {
            appState.attachChatsController(null)
            chatsController.onCleared()
        }
    }

    LaunchedEffect(
        chatsController,
        appState.activeAccountRef,
        appState.runtimeGeneration,
        chatsController.retryGeneration,
    ) {
        chatsController.bind(
            accountRef = appState.activeAccountRef,
            preserveLoadedContent = chatsController.retryGeneration > 0L,
        )
    }

    LaunchedEffect(pendingShareRequestStore, savedSharePickerRequestId) {
        val requestId = savedSharePickerRequestId ?: return@LaunchedEffect
        if (sharePickerRequest?.requestId == requestId) return@LaunchedEffect
        val restored = withContext(Dispatchers.IO) { pendingShareRequestStore.load(requestId) }
        if (savedSharePickerRequestId != requestId) return@LaunchedEffect
        if (restored == null) {
            savedSharePickerRequestId = null
        } else {
            sharePickerRequest = restored
        }
    }

    LaunchedEffect(
        pendingShareRequestStore,
        savedSharePickerRequestId,
        inboundShareRequest,
        sharePickerRequest,
    ) {
        if (
            savedSharePickerRequestId == null &&
            inboundShareRequest == null &&
            sharePickerRequest == null
        ) {
            withContext(Dispatchers.IO) { pendingShareRequestStore.clear() }
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
                return@LaunchedEffect
            }
        if (routingRequestId != armedNotificationRequestId) {
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
        val chatListReady =
            chatsController.boundAccountRef == target.accountRef &&
                !chatsController.isLoading
        // Archived conversations still exist — include them so an archived
        // group isn't treated as a missing conversation.
        val allChats = chatsController.items + chatsController.archivedItems

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
                availableGroupIds = allChats.mapTo(mutableSetOf()) { it.group.groupIdHex },
                inviteRowMaterialized = inviteRowMaterialized,
                inviteRowMembershipOpenable = inviteRowMembershipOpenable,
                inviteAuthoritativelyUnavailable = notificationInviteAuthoritativelyUnavailable,
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
            selectedChatOpenContext =
                nextNotificationConversationOpenContext(selectedChatOpenContext)
            selectedChatJustCreated = false
            selectedChatOpenedAsDmHint = false
            chatListReturnHeadSnap = resetChatListReturnHeadSnap()
            selectedChat = chatItem
            routingNotification = false
            onNotificationTargetHandled(target, routingRequestId)
        }

        fun markNotificationTargetRead() {
            target.messageIdHex?.let { messageIdHex ->
                appState.launchMutation {
                    appState.markNotificationMessageRead(
                        accountRef = target.accountRef,
                        groupIdHex = target.groupIdHex,
                        messageIdHex = messageIdHex,
                    )
                }
            }
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
                // This effect is keyed on activeAccountRef, so an inline suspend
                // switch would cancel itself the moment the ref flips.
                appState.launchMutation { appState.setActiveAccount(step.accountRef) }
            }
            NotificationNavStep.LoadMessageDirectly -> {
                routingNotification = true
                when (
                    val outcome =
                        loadNotificationMessageDirectly {
                            appState.loadNotificationChatListItem(
                                accountRef = target.accountRef,
                                groupIdHex = target.groupIdHex,
                            )
                        }
                ) {
                    is NotificationMessageDirectLoadOutcome.OpenConversation -> {
                        markNotificationTargetRead()
                        commitNotificationConversationOpen(outcome.item)
                    }
                    NotificationMessageDirectLoadOutcome.AwaitChatList -> {
                        // A transient local-read failure does not consume the tap;
                        // the existing chat-list state will re-fire this route.
                        routingNotification = false
                    }
                }
            }
            NotificationNavStep.AwaitChatList -> Unit // invite route re-fires when list state settles
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
                        // Opening from a message notification explicitly reads
                        // up to the notified message. Persist that cursor outside
                        // the conversation composition so a quick back press
                        // cannot cancel the scroll-driven mark-read before it
                        // reaches the store (#1016).
                        if (step.readThroughMessageIdHex != null) markNotificationTargetRead()
                        commitNotificationConversationOpen(item)
                    }
                    ?: run {
                        routingNotification = false
                        onNotificationTargetHandled(target, routingRequestId)
                    }
            }
            NotificationNavStep.MissingAccount -> {
                routingNotification = false
                fallBackToChatList()
                appState.present(R.string.toast_notification_account_unavailable)
                onNotificationTargetHandled(target, routingRequestId)
            }
            NotificationNavStep.MissingConversation -> {
                routingNotification = false
                fallBackToChatList()
                appState.present(R.string.toast_notification_conversation_unavailable)
                onNotificationTargetHandled(target, routingRequestId)
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
        appState.showAppUpdateBannerFromNotification()
        onAppUpdateTapHandled(tap)
    }

    val openChatForShare: (List<ChatListItem>, String) -> Unit = { allChats, groupIdHex ->
        allChats
            .firstOrNull { it.group.groupIdHex.equals(groupIdHex, ignoreCase = true) }
            ?.let { item ->
                sectionName = MainSection.Chats.name
                settingsDetailName = null
                chatListReturnHeadSnap = resetChatListReturnHeadSnap()
                commitExplicitConversationOpen(item.group.groupIdHex)
                selectedChatOpenContext = ConversationOpenContext()
                selectedChatJustCreated = false
                selectedChatOpenedAsDmHint = false
                selectedChat = item
            }
    }

    val stageShareToChats:
        (ShareRequest, List<String>, List<ChatListItem>) -> Unit =
        stageShare@{ request, groupIds, allChats ->
            if (groupIds.isEmpty()) return@stageShare
            appState.stageInboundShare(groupIds, request.payload)
            openChatForShare(allChats, groupIds.first())
            val otherCount = groupIds.size - 1
            if (otherCount > 0) {
                appState.presentTransient(AppText.Resource(R.string.toast_share_staged_other_chats, listOf(otherCount)))
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
            clearSharePickerRequest()
        }
    }

    LaunchedEffect(
        inboundShareRequest,
        appState.phase,
        appState.appLockScreenVisible,
        appState.activeAccountRef,
        appState.accounts,
        chatsController,
        chatsController.boundAccountRef,
        chatsController.isLoading,
        chatsController.items,
    ) {
        val request = inboundShareRequest ?: return@LaunchedEffect
        supersedePendingTtsDestinationNavigation()
        if (!shouldPresentInboundShare(appState.phase, appState.appLockScreenVisible)) return@LaunchedEffect
        if (appState.accounts.isEmpty()) return@LaunchedEffect
        val accountRef = appState.activeAccountRef ?: return@LaunchedEffect
        val chatListReady =
            chatsController.boundAccountRef == accountRef &&
                !chatsController.isLoading
        if (!chatListReady) return@LaunchedEffect
        val allChats = chatsController.items + chatsController.archivedItems
        val activeGroupIds = allChats.mapTo(mutableSetOf()) { it.group.groupIdHex }
        val directGroupId = resolveShareDirectGroupId(request, accountRef, activeGroupIds)
        if (directGroupId != null) {
            clearSharePickerRequest()
            onShareRequestHandled(request)
            stageShareToChats(request, listOf(directGroupId), allChats)
        } else {
            val persisted = withContext(Dispatchers.IO) { pendingShareRequestStore.save(request) }
            onShareRequestHandled(request)
            sharePickerRequest = request
            savedSharePickerRequestId = request.requestId.takeIf { persisted }
        }
    }

    // One-shot restore after process death: once the chat list for the active
    // account is loaded, re-resolve the saved group id back to a live
    // ChatListItem (issue #386). Runs before the sync effect can clobber the
    // saved id, and closes the restore window (hasRestoredSelectedChat) as soon
    // as the list is ready — whether or not a saved selection was present — so
    // it never overrides a later explicit user navigation.
    LaunchedEffect(
        chatsController,
        chatsController.boundAccountRef,
        chatsController.isLoading,
        chatsController.items,
    ) {
        if (hasRestoredSelectedChat) return@LaunchedEffect
        val chatListReady =
            chatsController.boundAccountRef == appState.activeAccountRef &&
                !chatsController.isLoading
        if (!chatListReady) return@LaunchedEffect
        val savedGroupId = savedSelectedGroupIdHex
        if (savedGroupId != null && selectedChat == null) {
            (chatsController.items + chatsController.archivedItems)
                .firstOrNull { it.group.groupIdHex == savedGroupId }
                ?.let {
                    chatListReturnHeadSnap = resetChatListReturnHeadSnap()
                    selectedChatOpenContext = ConversationOpenContext()
                    selectedChatOpenedAsDmHint = false
                    selectedChat = it
                }
        }
        hasRestoredSelectedChat = true
    }

    // Keep the saved (Saveable) group id in step with the live selection so a
    // recreation always restores the right conversation — and so returning to
    // the chat list (selectedChat == null) clears it, preventing a stale
    // restore. Gated until the one-shot restore window has closed so it can't
    // null out the saved id before restore reads it. See issue #386.
    LaunchedEffect(selectedChat?.group?.groupIdHex, hasRestoredSelectedChat) {
        if (hasRestoredSelectedChat) {
            savedSelectedGroupIdHex = selectedChat?.group?.groupIdHex
        }
    }

    // Follow the selection directly so a chat-to-chat switch never hops
    // through null — a notification update landing in that hop would beat
    // suppression and post for the conversation being opened.
    LaunchedEffect(selectedChat?.id) {
        appState.setActiveConversation(selectedChat?.group?.groupIdHex)
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
        if (shouldResetNavOnAccountChange(previousActiveAccountRef, current)) {
            clearSharePickerRequest()
            shellNavState =
                reduceShellNavigation(shellNavState, ShellNavigationEvent.AccountSwitched).state
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
    val conversationController =
        selectedChat?.let { openChat ->
            remember(openChat.id, appState.activeAccountRef, appState.runtimeGeneration) {
                ConversationController(
                    appState = appState,
                    initialGroup = openChat.group,
                    initialMemberSnapshot =
                        openChat.memberSnapshot
                            ?: appState.cachedGroupMemberSnapshot(appState.activeAccountRef, openChat.group.groupIdHex),
                    initialLastReadMessageId = openChat.projection?.lastReadMessageIdHex,
                    initialLastReadTimelineAt = openChat.projection?.lastReadTimelineAt,
                    copy = conversationControllerCopy,
                )
            }
        }
    // The controller is owned by the selected conversation route, not the
    // ConversationScreen composition. The profile-to-group picker temporarily
    // replaces that screen, so disposing the controller with the screen would
    // retain and then reuse an already-cleared controller when Back restores it.
    DisposableEffect(conversationController) {
        conversationController?.let(appState::attachConversationController)
        onDispose {
            conversationController?.let {
                appState.detachConversationController(it)
                it.onCleared()
            }
        }
    }
    LaunchedEffect(conversationController, conversationController?.retryGeneration) {
        conversationController?.start()
    }
    if (
        shouldPresentInboundShare(appState.phase, appState.appLockScreenVisible) &&
        savedSharePickerRequestId != null &&
        sharePickerRequest == null
    ) {
        LoadingScreen()
        return
    }
    ProfileGroupForegroundCoordinator(
        appState = appState,
        conversationController = conversationController,
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
        val openChat = selectedChat
        when (
            resolveMainShellContentRoute(
                conversationOpen = openChat != null,
                routingNotification = routingNotification,
                routingTtsReturn = pendingTtsDestinationNavigation != null,
            )
        ) {
            MainShellContentRoute.Conversation -> {
                val chat = requireNotNull(openChat)
                val scrollKey = conversationScrollKey(appState.activeAccountRef, chat.group.groupIdHex)
                ConversationScreen(
                    appState = appState,
                    chat = chat,
                    controller = requireNotNull(conversationController),
                    focusMessageId = selectedChatOpenContext.focusMessageId,
                    focusMessageRequestId = selectedChatOpenContext.focusMessageRequestId,
                    ttsFocusSessionId = selectedChatOpenContext.ttsFocusSessionId,
                    notificationOpenRequestId = selectedChatOpenContext.notificationOpenRequestId,
                    justCreated = selectedChatJustCreated,
                    openedAsDmHint = selectedChatOpenedAsDmHint,
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
                        // Flush the hidden list before exposing it, so the first
                        // drawn return frame already has the optimistic preview
                        // in its final recency slot (#900).
                        chatsController.setChatListVisible(true)
                        shellNavState =
                            reduceShellNavigation(
                                shellNavState,
                                ShellNavigationEvent.ConversationBackedOut,
                            ).state
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
                            onGroupCreateSubmitted = onGroupCreateSubmitted,
                            onGroupCreateCompletedOpen = openGroupFromGroupCreateCompletion,
                            onGroupCreateFlowSuperseded = supersedePendingGroupCreateOpen,
                            conversationReturnHeadId = publishedConversationReturnHead(chatListReturnHeadSnap),
                            onConversationReturnHeadHandled = {
                                chatListReturnHeadSnap = onConversationReturnHeadHandled(chatListReturnHeadSnap)
                            },
                            onOpenSettings = {
                                supersedePendingTtsDestinationNavigation()
                                chatListReturnHeadSnap = resetChatListReturnHeadSnap()
                                supersedePendingGroupCreateOpen()
                                sectionName = MainSection.Settings.name
                                settingsDetailName = null
                            },
                            onOpenGroup = { item, focusMessageId, justCreated, visibleHeadId ->
                                commitExplicitConversationOpen(item.group.groupIdHex)
                                selectedChatOpenContext = ConversationOpenContext(focusMessageId = focusMessageId)
                                selectedChatJustCreated = justCreated
                                // `justCreated` is true only for freshly-created DMs; group
                                // creation and existing-DM opens pass false. Reuse that DM-only
                                // invariant for the open-time subtitle hint (#998).
                                selectedChatOpenedAsDmHint = justCreated
                                chatListReturnHeadSnap = openGroupFromChatList(chatListReturnHeadSnap, visibleHeadId)
                                selectedChat = item
                            },
                            onPresentProfile = { npub, visibleHeadId ->
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

    // Compose after every shell/profile/new-group surface. The full-screen
    // Dialog owns pointer and accessibility focus while preserving the route
    // underneath for an exact return after cancellation (issue #1721).
    if (shouldPresentInboundShare(appState.phase, appState.appLockScreenVisible)) {
        sharePickerRequest?.let { request ->
            ShareChatPickerFullScreen(
                appState = appState,
                requestId = request.requestId,
                payload = request.payload,
                onDismiss = clearSharePickerRequest,
                onStage = { groupIds ->
                    val allChats = chatsController.items + chatsController.archivedItems
                    stageShareToChats(request, groupIds, allChats)
                },
            )
        }
    }
}

@file:Suppress("ReturnCount") // Foreground routes must stop lower surfaces from composing over them.

package dev.ipf.whitenoise.android.ui.navigation

import android.provider.Settings
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.notifications.NotificationInviteAuthoritativeOutcome
import dev.ipf.whitenoise.android.notifications.NotificationNavStep
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.notifications.inviteAuthoritativeGroupAvailable
import dev.ipf.whitenoise.android.notifications.resolveNotificationNav
import dev.ipf.whitenoise.android.notifications.retryInviteAuthoritativeLoad
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.share.resolveShareDirectGroupId
import dev.ipf.whitenoise.android.share.shouldPresentInboundShare
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.nextNavAccountRef
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
import dev.ipf.whitenoise.android.ui.share.ShareChatPickerSheet

internal data class ConversationOpenContext(
    val focusMessageId: String? = null,
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
    secureWindowEnabled: Boolean?,
    profileSecurePolicy: SecureFlagPolicy,
    onOpenConversation: (ChatListItem, Boolean) -> Unit,
    onDismissProfile: () -> Unit,
    onClosePicker: () -> Unit,
    content: @Composable () -> Unit,
) {
    val foregroundState = remember(appState.activeAccountRef) { ProfileGroupForegroundState() }
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
                onOpenConversation = { item, justCreated ->
                    foregroundState.close()
                    onOpenConversation(item, justCreated)
                },
                onClose = {
                    onClosePicker()
                    foregroundState.close()
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
    Main,
}

internal fun resolveMainShellContentRoute(
    conversationOpen: Boolean,
    routingNotification: Boolean,
): MainShellContentRoute =
    when {
        routingNotification -> MainShellContentRoute.NotificationLoading
        conversationOpen -> MainShellContentRoute.Conversation
        else -> MainShellContentRoute.Main
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainShell(
    appState: WhiteNoiseAppState,
    inboundNotificationTarget: NotificationTarget? = null,
    onNotificationTargetHandled: (NotificationTarget) -> Unit = {},
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
    // True while a tapped notification for a non-active account is mid-resolution
    // (switching account / awaiting its chat list). Holds a single stable loading
    // state over the multi-step route so the chat list never paints as an
    // intermediate stop between the account switch and the opened conversation.
    var routingNotification by remember { mutableStateOf(false) }
    var notificationInviteAuthoritativelyUnavailable by remember(
        inboundNotificationTarget?.accountRef,
        inboundNotificationTarget?.groupIdHex,
        inboundNotificationTarget?.kind,
    ) {
        mutableStateOf(false)
    }
    var notificationInviteAuthoritativeProbeAttempts by remember(
        inboundNotificationTarget?.accountRef,
        inboundNotificationTarget?.groupIdHex,
        inboundNotificationTarget?.kind,
    ) {
        mutableIntStateOf(0)
    }
    var sharePickerRequest by remember { mutableStateOf<ShareRequest?>(null) }
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

    LaunchedEffect(chatsController, appState.activeAccountRef, appState.runtimeGeneration) {
        chatsController.bind(appState.activeAccountRef)
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
    // for its chat list, then open the conversation — or fall back to the chat
    // list with a toast for a stale/removed target. Pure logic in
    // [resolveNotificationNav]; this effect just acts on each step and re-fires
    // as account/chat-list state changes.
    LaunchedEffect(
        inboundNotificationTarget,
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
            selectedChatOpenContext =
                nextNotificationConversationOpenContext(selectedChatOpenContext)
            selectedChatJustCreated = false
            selectedChatOpenedAsDmHint = false
            chatListReturnHeadSnap = resetChatListReturnHeadSnap()
            selectedChat = chatItem
            routingNotification = false
            onNotificationTargetHandled(target)
        }

        fun fallBackToChatList() {
            sectionName = MainSection.Chats.name
            settingsDetailName = null
            chatListReturnHeadSnap = resetChatListReturnHeadSnap()
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
                selectedChat = null
                selectedChatOpenContext = ConversationOpenContext()
                selectedChatJustCreated = false
                selectedChatOpenedAsDmHint = false
                // This effect is keyed on activeAccountRef, so an inline suspend
                // switch would cancel itself the moment the ref flips.
                appState.launchMutation { appState.setActiveAccount(step.accountRef) }
            }
            NotificationNavStep.AwaitChatList -> Unit // re-fires when list state settles
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
                        step.readThroughMessageIdHex?.let { messageIdHex ->
                            appState.launchMutation {
                                appState.markNotificationMessageRead(
                                    accountRef = target.accountRef,
                                    groupIdHex = target.groupIdHex,
                                    messageIdHex = messageIdHex,
                                )
                            }
                        }
                        commitNotificationConversationOpen(item)
                    }
                    ?: run {
                        routingNotification = false
                        onNotificationTargetHandled(target)
                    }
            }
            NotificationNavStep.MissingAccount -> {
                routingNotification = false
                fallBackToChatList()
                appState.present(R.string.toast_notification_account_unavailable)
                onNotificationTargetHandled(target)
            }
            NotificationNavStep.MissingConversation -> {
                routingNotification = false
                fallBackToChatList()
                appState.present(R.string.toast_notification_conversation_unavailable)
                onNotificationTargetHandled(target)
            }
        }
    }

    // A tapped app-update notification lands here: drop any open conversation /
    // deep settings nav, return to the chat list, and surface the update banner.
    LaunchedEffect(inboundAppUpdateTap, appState.phase) {
        val tap = inboundAppUpdateTap
        if (tap == 0 || appState.phase != AppPhase.Ready) return@LaunchedEffect
        sectionName = MainSection.Chats.name
        settingsDetailName = null
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
                appState.presentText(AppText.Resource(R.string.toast_share_staged_other_chats, listOf(otherCount)))
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
            sharePickerRequest = null
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
        onShareRequestHandled(request)
        if (directGroupId != null) {
            stageShareToChats(request, listOf(directGroupId), allChats)
        } else {
            sharePickerRequest = request
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
            sharePickerRequest = null
            selectedChat = null
            selectedChatOpenContext = ConversationOpenContext()
            selectedChatJustCreated = false
            selectedChatOpenedAsDmHint = false
            chatListReturnHeadSnap = resetChatListReturnHeadSnap()
            sectionName = MainSection.Chats.name
            settingsDetailName = null
        }
        previousActiveAccountRef = nextNavAccountRef(previousActiveAccountRef, current)
    }

    // Navigate the shell to a (possibly different) group when a profile sheet's
    // shared-group / Message action fires. The shell-owned profile coordinator
    // uses this for both shell and in-conversation sheets (#635).
    val openGroupFromProfile: (ChatListItem, Boolean) -> Unit = { item, justCreated ->
        chatListReturnHeadSnap = openGroupFromProfileSheet(chatListReturnHeadSnap)
        selectedChatOpenContext = ConversationOpenContext()
        selectedChatJustCreated = justCreated
        // `justCreated` is true only for freshly-created DMs; group creation and
        // existing-DM opens pass false. Reuse that DM-only invariant for the
        // open-time subtitle hint (#998).
        selectedChatOpenedAsDmHint = justCreated
        selectedChat = item
        appState.clearPresentedProfile()
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
    LaunchedEffect(conversationController) {
        conversationController?.start()
    }
    ProfileGroupForegroundCoordinator(
        appState = appState,
        conversationController = conversationController,
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
                    notificationOpenRequestId = selectedChatOpenContext.notificationOpenRequestId,
                    justCreated = selectedChatJustCreated,
                    openedAsDmHint = selectedChatOpenedAsDmHint,
                    restoredScrollSnapshot = conversationScrollSnapshots[scrollKey],
                    onOpenConversation = openGroupFromProfile,
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
            MainShellContentRoute.Main ->
                when (section) {
                    MainSection.Chats -> {
                        WindowSecureFlag(enabled = !appState.allowChatScreenshotsInChats)
                        ChatsScreen(
                            appState = appState,
                            controller = chatsController,
                            conversationReturnHeadId = publishedConversationReturnHead(chatListReturnHeadSnap),
                            onConversationReturnHeadHandled = {
                                chatListReturnHeadSnap = onConversationReturnHeadHandled(chatListReturnHeadSnap)
                            },
                            onOpenSettings = {
                                chatListReturnHeadSnap = resetChatListReturnHeadSnap()
                                sectionName = MainSection.Settings.name
                                settingsDetailName = null
                            },
                            onOpenGroup = { item, focusMessageId, justCreated, visibleHeadId ->
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

        // Compose the inbound share picker after the foreground route so its Back
        // handler wins over conversation/chat-list handlers (issue #1721).
        if (shouldPresentInboundShare(appState.phase, appState.appLockScreenVisible)) {
            sharePickerRequest?.let { request ->
                ShareChatPickerSheet(
                    appState = appState,
                    payload = request.payload,
                    onDismiss = { sharePickerRequest = null },
                    onStage = { groupIds ->
                        val allChats = chatsController.items + chatsController.archivedItems
                        stageShareToChats(request, groupIds, allChats)
                        sharePickerRequest = null
                    },
                )
            }
        }
    }
}

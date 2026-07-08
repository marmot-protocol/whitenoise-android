package dev.ipf.whitenoise.android.ui

import android.Manifest
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NotificationNavStep
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.resolveNotificationNav
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.nextNavAccountRef
import dev.ipf.whitenoise.android.state.shouldResetNavOnAccountChange
import dev.ipf.whitenoise.android.ui.chats.ChatsScreen
import dev.ipf.whitenoise.android.ui.common.AppLockScreen
import dev.ipf.whitenoise.android.ui.common.FailureScreen
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarBottomInset
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSnackbarHost
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollSnapshot
import dev.ipf.whitenoise.android.ui.conversation.conversationScrollKey
import dev.ipf.whitenoise.android.ui.conversation.media.SHARED_MEDIA_MAX_AGE_MS
import dev.ipf.whitenoise.android.ui.conversation.media.sweepStaleSharedMedia
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingScreen
import dev.ipf.whitenoise.android.ui.profile.ProfileSheet
import dev.ipf.whitenoise.android.ui.settings.DiagnosticsScreen
import dev.ipf.whitenoise.android.ui.settings.SettingsScreen
import dev.ipf.whitenoise.android.ui.settings.WipeOutcomeSheet
import dev.ipf.whitenoise.android.ui.settings.WipeProgressSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class MainSection {
    Chats,
    Settings,
    Diagnostics,
}

internal enum class SettingsDetail {
    Appearance,
    FontSize,
    Data,
    Profile,
    Identity,
    Relays,
    KeyPackages,
    Notifications,
    SecurityPrivacy,
    Donate,
}

@Composable
fun WhiteNoiseApp(
    appState: WhiteNoiseAppState,
    inboundProfilePayload: String? = null,
    onProfilePayloadHandled: (String) -> Unit = {},
    inboundNotificationTarget: NotificationTarget? = null,
    onNotificationTargetHandled: (NotificationTarget) -> Unit = {},
    onRequestAppUnlock: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Mutable bottom-chrome inset so screens further down the tree
    // (e.g. ConversationScreen) can push the snackbar above their
    // composer. Owned here so the host — which lives at this level —
    // can read it; child screens mutate via [LocalSnackbarBottomInset].
    val snackbarBottomInset = remember { mutableStateOf(0.dp) }
    val toast = appState.toast
    val context = LocalContext.current
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appState.refreshLocalNotificationPermission()
            if (granted) {
                appState.launchMutation { appState.enableDefaultNotificationsIfReady() }
            } else {
                appState.markDefaultNotificationsEnableAttempted()
            }
        }

    LaunchedEffect(Unit) {
        appState.bootstrap()
        // Stale share-temp janitor. Runs once per process start, off the
        // main thread because directory walks on cold cache can take a
        // moment. Files in `shared_media` from earlier sessions that
        // any external reader has long since finished with are deleted.
        withContext(Dispatchers.IO) {
            sweepStaleSharedMedia(context, SHARED_MEDIA_MAX_AGE_MS)
        }
    }
    LaunchedEffect(
        appState.phase,
        appState.activeAccountRef,
        appState.localNotificationPermissionGranted,
        appState.backgroundConnectionEnabled,
        appState.localNotificationSettings?.localNotificationsEnabled,
        appState.runtimeGeneration,
        appState.appLockScreenVisible,
    ) {
        if (appState.phase != AppPhase.Ready || appState.appLockScreenVisible) return@LaunchedEffect
        appState.refreshLocalNotificationPermission()
        appState.refreshLocalNotificationSettings()
        if (appState.shouldRequestDefaultNotificationPermission()) {
            appState.markDefaultNotificationPermissionPromptLaunched()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            appState.enableDefaultNotificationsIfReady()
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            snackbarHostState.showSnackbar(
                ToastSnackbarVisuals(
                    message = listOfNotNull(toast.title.resolve(context), toast.detail?.resolve(context)).joinToString("\n"),
                    copyable = toast.copyable,
                ),
            )
            appState.clearToast()
        }
    }
    LaunchedEffect(inboundProfilePayload, appState.phase) {
        val payload = inboundProfilePayload ?: return@LaunchedEffect
        if (appState.phase == AppPhase.Ready && appState.presentProfilePayload(payload)) {
            onProfilePayloadHandled(payload)
        }
    }
    LaunchedEffect(appState.appLockScreenVisible, appState.appUnlockPromptRequestId) {
        if (appState.appLockScreenVisible) onRequestAppUnlock()
    }

    // Privacy hardening (#405): when "Force incognito keyboard" is on, wrap the
    // whole app UI so every descendant text field requests incognito mode from
    // the IME (no learning / suggestion history / cloud sync of typed content).
    IncognitoKeyboardScope(enabled = appState.forceIncognitoKeyboard) {
        CompositionLocalProvider(LocalSnackbarBottomInset provides snackbarBottomInset) {
            Scaffold(
                contentWindowInsets = WindowInsets(0.dp),
                snackbarHost = { WhiteNoiseSnackbarHost(snackbarHostState) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (val phase = appState.phase) {
                        AppPhase.Bootstrapping -> LoadingScreen()
                        AppPhase.Onboarding -> OnboardingScreen(appState)
                        AppPhase.Ready ->
                            MainShell(
                                appState = appState,
                                inboundNotificationTarget = inboundNotificationTarget,
                                onNotificationTargetHandled = onNotificationTargetHandled,
                            )
                        is AppPhase.Failed ->
                            FailureScreen(
                                message = phase.message,
                                onRetry = { appState.present(R.string.toast_restarting) },
                                onRetryAction = { appState.bootstrap() },
                            )
                    }
                    if (appState.appLockScreenVisible) {
                        AppLockScreen(
                            error = appState.appUnlockError,
                            onRetry = { appState.requestAppUnlock() },
                        )
                    }
                    // Sign Out & Wipe chrome (#350) is hosted here, above the
                    // phase router: the wipe flips the active account (or drops
                    // to Onboarding) mid-flight, popping whatever screen
                    // started it, so neither the progress sheet nor the
                    // partial-failure outcome sheet can live in that screen.
                    if (appState.wipeInProgress) {
                        WipeProgressSheet()
                    }
                    appState.pendingWipeReport?.let { report ->
                        WipeOutcomeSheet(
                            report = report,
                            onDismiss = { appState.pendingWipeReport = null },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainShell(
    appState: WhiteNoiseAppState,
    inboundNotificationTarget: NotificationTarget? = null,
    onNotificationTargetHandled: (NotificationTarget) -> Unit = {},
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
    // When a conversation is opened from a chat-list message-body search hit
    // (issue #290), this carries the matched message id so ConversationScreen
    // can scroll to and briefly highlight it on open. Null for every other
    // open path (row tap, notification, new-chat), which lands at the normal
    // unread/newest anchor.
    var selectedChatFocusMessageId by remember { mutableStateOf<String?>(null) }
    // Whether the focused message also gets the brief highlight flash. Search
    // hits highlight; a notification tap scrolls to it without the color flash.
    var selectedChatFocusHighlight by remember { mutableStateOf(true) }
    // True only when `selectedChat` was opened by tapping a message notification.
    // A message notification implies current group membership, so the composer
    // shows immediately instead of a placeholder while membership verification
    // catches up after the account switch.
    var selectedChatOpenedFromNotification by remember { mutableStateOf(false) }
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
    // True while a tapped notification for a non-active account is mid-resolution
    // (switching account / awaiting its chat list). Holds a single stable loading
    // state over the multi-step route so the chat list never paints as an
    // intermediate stop between the account switch and the opened conversation.
    var routingNotification by remember { mutableStateOf(false) }
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
        chatsController.setChatListVisible(selectedChat == null)
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
        if (appState.accounts.isEmpty()) return@LaunchedEffect // accounts not loaded yet
        val chatListReady =
            chatsController.boundAccountRef == target.accountRef &&
                !chatsController.isLoading
        // Archived conversations still exist — include them so an archived
        // group isn't treated as a missing conversation.
        val allChats = chatsController.items + chatsController.archivedItems
        val step =
            resolveNotificationNav(
                target = target,
                knownAccountRefs = appState.accounts.mapTo(mutableSetOf()) { it.label },
                activeAccountRef = appState.activeAccountRef,
                chatListReady = chatListReady,
                availableGroupIds = allChats.mapTo(mutableSetOf()) { it.group.groupIdHex },
            )

        fun fallBackToChatList() {
            sectionName = MainSection.Chats.name
            settingsDetailName = null
            selectedChat = null
            // Notification routing never opens a just-created conversation, so
            // clear any leftover open-time state from a prior New Chat / Create
            // Group flow; otherwise a stale justCreated flag would auto-raise
            // the IME on the next opened conversation (issue #321 guard).
            selectedChatFocusMessageId = null
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
                selectedChat = null
                selectedChatFocusMessageId = null
                selectedChatJustCreated = false
                selectedChatOpenedAsDmHint = false
                appState.setActiveAccount(step.accountRef)
            }
            NotificationNavStep.AwaitChatList -> Unit // re-fires when list state settles
            is NotificationNavStep.OpenConversation -> {
                // Ensure we're on the Chats section so back-from-conversation
                // lands on the chat list, not whatever section was open.
                sectionName = MainSection.Chats.name
                settingsDetailName = null
                allChats
                    .firstOrNull { it.group.groupIdHex == step.groupIdHex }
                    ?.let {
                        // Opening from a message notification explicitly reads
                        // up to the notified message. Persist that cursor outside
                        // the conversation composition so a quick back press
                        // cannot cancel the scroll-driven mark-read before it
                        // reaches the store (#1016).
                        step.focusMessageIdHex?.let { messageIdHex ->
                            appState.launchMutation {
                                appState.markNotificationMessageRead(
                                    accountRef = target.accountRef,
                                    groupIdHex = target.groupIdHex,
                                    messageIdHex = messageIdHex,
                                )
                            }
                        }
                        // Scroll to the notified message, reusing the search-hit
                        // focus path. The id is resolved (and MESSAGE-gated) in
                        // the nav FSM. No highlight flash on a notification tap.
                        selectedChatFocusMessageId = step.focusMessageIdHex
                        selectedChatFocusHighlight = false
                        selectedChatOpenedFromNotification = true
                        // Notification routing is never a just-created open, so
                        // clear any stale justCreated flag carried over from a
                        // prior New Chat / Create Group flow before showing the
                        // target conversation (issue #321 guard).
                        selectedChatJustCreated = false
                        selectedChatOpenedAsDmHint = false
                        selectedChat = it
                    }
                routingNotification = false
                onNotificationTargetHandled(target)
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
                    selectedChatFocusMessageId = null
                    selectedChatOpenedFromNotification = false
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

    DisposableEffect(selectedChat?.id) {
        appState.setActiveConversation(selectedChat?.group?.groupIdHex)
        onDispose {
            if (selectedChat != null) appState.setActiveConversation(null)
        }
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
            selectedChat = null
            selectedChatFocusMessageId = null
            selectedChatJustCreated = false
            selectedChatOpenedAsDmHint = false
            sectionName = MainSection.Chats.name
            settingsDetailName = null
        }
        previousActiveAccountRef = nextNavAccountRef(previousActiveAccountRef, current)
    }

    // Navigate the shell to a (possibly different) group when a profile sheet's
    // shared-group / Message action fires. Shared by the shell-level sheet and,
    // threaded through ConversationScreen, by the in-conversation sheet (#635) so
    // both surfaces behave identically.
    val openGroupFromProfile: (ChatListItem, Boolean) -> Unit = { item, justCreated ->
        selectedChatFocusMessageId = null
        selectedChatOpenedFromNotification = false
        selectedChatJustCreated = justCreated
        // `justCreated` is true only for freshly-created DMs; group creation and
        // existing-DM opens pass false. Reuse that DM-only invariant for the
        // open-time subtitle hint (#998).
        selectedChatOpenedAsDmHint = justCreated
        selectedChat = item
        appState.clearPresentedProfile()
    }

    // The shell-level profile sheet covers every non-conversation entry point
    // (chat list, search, settings, QR, reaction list). While a conversation is
    // active the in-conversation copy inside ConversationScreen renders it
    // instead — with group-admin context (#635) — so gate this one off to avoid
    // double-rendering the same sheet.
    if (selectedChat == null) {
        appState.pendingProfileNpub?.let { npub ->
            ProfileSheet(
                appState = appState,
                npub = npub,
                onOpenGroup = openGroupFromProfile,
                onDismiss = { appState.clearPresentedProfile() },
                securePolicy =
                    when {
                        section != MainSection.Chats -> SecureFlagPolicy.Inherit
                        appState.allowChatScreenshotsInChats -> SecureFlagPolicy.SecureOff
                        else -> SecureFlagPolicy.SecureOn
                    },
            )
        }
    }

    if (selectedChat != null) {
        val openChat = selectedChat!!
        val scrollKey = conversationScrollKey(appState.activeAccountRef, openChat.group.groupIdHex)
        ConversationScreen(
            appState = appState,
            chat = openChat,
            focusMessageId = selectedChatFocusMessageId,
            highlightFocusMessage = selectedChatFocusHighlight,
            openedFromNotification = selectedChatOpenedFromNotification,
            justCreated = selectedChatJustCreated,
            openedAsDmHint = selectedChatOpenedAsDmHint,
            restoredScrollSnapshot = conversationScrollSnapshots[scrollKey],
            onSaveScrollSnapshot = { snapshot ->
                if (snapshot == null) {
                    conversationScrollSnapshots.remove(scrollKey)
                } else {
                    conversationScrollSnapshots[scrollKey] = snapshot
                }
            },
            onBack = {
                selectedChat = null
                selectedChatFocusMessageId = null
                selectedChatJustCreated = false
                selectedChatOpenedAsDmHint = false
            },
            onOpenProfileGroup = openGroupFromProfile,
        )
        return
    }

    // A notification tap on a non-active account resolves in steps (switch
    // account → await its chat list → open conversation). Render one stable
    // loading state for that whole window so the chat list doesn't flash as an
    // intermediate stop before the conversation appears.
    if (routingNotification) {
        LoadingScreen()
        return
    }

    when (section) {
        MainSection.Chats -> {
            WindowSecureFlag(enabled = !appState.allowChatScreenshotsInChats)
            ChatsScreen(
                appState = appState,
                controller = chatsController,
                onOpenSettings = {
                    sectionName = MainSection.Settings.name
                    settingsDetailName = null
                },
                onOpenGroup = { item, focusMessageId, justCreated ->
                    selectedChatFocusMessageId = focusMessageId
                    selectedChatFocusHighlight = true
                    selectedChatOpenedFromNotification = false
                    selectedChatJustCreated = justCreated
                    // `justCreated` is true only for freshly-created DMs; group
                    // creation and existing-DM opens pass false. Reuse that DM-only
                    // invariant for the open-time subtitle hint (#998).
                    selectedChatOpenedAsDmHint = justCreated
                    selectedChat = item
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
                    // Diagnostics returns to Security & Privacy (its only
                    // entry point) rather than the Settings home, restoring
                    // the breadcrumb the user walked in on (#412).
                    sectionName = MainSection.Diagnostics.name
                },
                detail = settingsDetail,
                onDetailChange = { settingsDetailName = it?.name },
            )
        MainSection.Diagnostics ->
            DiagnosticsScreen(
                appState = appState,
                onBack = {
                    // Leave `settingsDetailName` alone — it still holds the
                    // detail (Security & Privacy) the user opened Diagnostics
                    // from, so Settings re-enters that screen directly (#412).
                    sectionName = MainSection.Settings.name
                },
            )
    }
}

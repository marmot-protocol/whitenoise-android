package dev.ipf.whitenoise.android.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.core.ConversationTranscriptExport
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.MediaReferenceParser
import dev.ipf.whitenoise.android.media.Thumbhash
import dev.ipf.whitenoise.android.notifications.NotificationNavStep
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.resolveNotificationNav
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.TimelineMessage
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
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingScreen
import dev.ipf.whitenoise.android.ui.profile.ProfileSheet
import dev.ipf.whitenoise.android.ui.settings.DiagnosticsScreen
import dev.ipf.whitenoise.android.ui.settings.SettingsScreen
import dev.ipf.whitenoise.android.ui.settings.WipeOutcomeSheet
import dev.ipf.whitenoise.android.ui.settings.WipeProgressSheet
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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

/** Fixed height of an in-timeline image bubble — constant across load states
 *  so async decode never reflows the list (would break the open-time anchor). */
private val MediaBubbleHeight = 240.dp

/** Hard cap on the height a `dim`-shaped image bubble can claim, so a tall
 *  portrait can't dominate the chat viewport. Width fills the bubble; this
 *  bounds the height so the aspect-ratio sizing degrades to a cropped
 *  preview at the extremes. */
private val MediaBubbleMaxHeight = 340.dp

/** Fixed card width used for portrait image bubbles, so every portrait
 *  reads as a consistently-sized card rather than a width-varying strip.
 *  Landscape bubbles still fill the parent. */
private val MediaBubbleCardWidth = 280.dp

/** Sizing modifier for both the optimistic and the confirmed single-image
 *  bubble. Portrait images become uniform-width cards with a height cap;
 *  landscape images fill the bubble width and derive their natural height
 *  (which can't exceed the width for ratio ≥ 1). Falls back to the legacy
 *  fixed-height slab when the aspect ratio is unknown. */
@Composable
private fun imageBubbleSizing(ratio: Float?): Modifier =
    when {
        ratio == null -> Modifier.fillMaxWidth().height(MediaBubbleHeight)
        ratio >= 1f -> Modifier.fillMaxWidth().aspectRatio(ratio)
        else -> {
            val natural = (MediaBubbleCardWidth.value / ratio).dp
            val height = if (natural > MediaBubbleMaxHeight) MediaBubbleMaxHeight else natural
            Modifier.width(MediaBubbleCardWidth).height(height)
        }
    }

/**
 * Decode an imeta `thumbhash` field into a tiny ARGB ImageBitmap, cached
 * for the lifetime of the composition. Returns null when the field is
 * absent or doesn't decode. Callers render the bitmap with
 * [ContentScale.Crop] under the loading state so the bubble shows a
 * blurred preview before the real bytes arrive.
 */
@Composable
internal fun rememberThumbhashImage(thumbhash: String?): ImageBitmap? {
    if (thumbhash.isNullOrBlank()) return null
    // The decode is a few hundred μs to a couple ms (cosine-basis sum
    // across a 32×32 grid). Doing it inside `remember { ... }` runs it on
    // the Compose / Main thread during the initial composition pass, which
    // multiplied across the bubbles entering composition during scroll adds
    // up to a measurable Input+Anim+Layout cost. `produceState` defers the
    // decode to Dispatchers.Default and emits the result when ready —
    // initial composition returns instantly with `null` and the bubble
    // shows the underlying surface tint until the blurred placeholder
    // arrives.
    val state =
        produceState<ImageBitmap?>(initialValue = null, key1 = thumbhash) {
            value =
                withContext(Dispatchers.Default) {
                    Thumbhash.decodeToBitmap(thumbhash)?.asImageBitmap()
                }
        }
    return state.value
}

/**
 * Parse the imeta `dim` field ("WxH") into a width/height aspect ratio.
 * Returns null when [dim] is null, blank, malformed, or non-positive on
 * either axis. Caller falls back to [MediaBubbleHeight] in that case.
 */
private fun aspectRatioFromDim(dim: String?): Float? {
    if (dim.isNullOrBlank()) return null
    val parts = dim.split('x', 'X', ignoreCase = true)
    if (parts.size != 2) return null
    val w = parts[0].trim().toIntOrNull() ?: return null
    val h = parts[1].trim().toIntOrNull() ?: return null
    if (w <= 0 || h <= 0) return null
    // Clamp wide panoramas so the bubble doesn't squeeze to a sliver.
    // Tall portraits are bounded by [MediaBubbleMaxHeight] at the layout
    // site instead — keeping the aspect ratio uncramped lets the placeholder
    // still convey "this is a tall image" before the bytes arrive.
    return (w.toFloat() / h.toFloat()).coerceIn(0.4f, 2.5f)
}

/** Saves a nullable Uri across process death (camera capture round-trip). */
internal val NullableUriSaver: Saver<android.net.Uri?, String> =
    Saver(
        save = { it?.toString() ?: "" },
        restore = { s -> s.takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse) },
    )

/**
 * Saves a nullable [java.io.File] across process death by its absolute path.
 * Used so the camera capture's temp-file handle survives the round-trip and a
 * capture cancelled after process death can still delete the empty temp
 * instead of leaking it (issue #531).
 */
internal val NullableFileSaver: Saver<java.io.File?, String> =
    Saver(
        save = { it?.absolutePath ?: "" },
        restore = { s -> s.takeIf { it.isNotEmpty() }?.let { path -> java.io.File(path) } },
    )

// Persist a multi-pick selection across rotation / process death. Empty list
// encodes "no preview shown" so the parent re-render skips the sheet on
// restore. Uses '\n' as the separator — content URIs don't contain newlines.
internal val UriListSaver: Saver<List<android.net.Uri>, String> =
    Saver(
        save = { encodeUriListTokens(it.map { uri -> uri.toString() }) },
        restore = { s -> decodeUriListTokens(s).map(android.net.Uri::parse) },
    )

/**
 * Pure string codec backing [UriListSaver], split out from the [android.net.Uri]
 * conversion so the separator and empty-list contract is unit-testable on the
 * JVM (the Android `Uri` stubs are non-functional in local unit tests). Joins
 * tokens with '\n'; an empty list encodes to "".
 */
internal fun encodeUriListTokens(tokens: List<String>): String = tokens.joinToString("\n")

/**
 * Inverse of [encodeUriListTokens]. An empty input decodes to an empty list
 * (the "no preview shown" sentinel); blank tokens are dropped so a trailing or
 * doubled separator can't yield empty URI strings.
 */
internal fun decodeUriListTokens(encoded: String): List<String> =
    if (encoded.isEmpty()) {
        emptyList()
    } else {
        encoded.split('\n').filter { it.isNotEmpty() }
    }

@Composable
internal fun MediaImageBubble(
    item: TimelineMessage,
    reference: MediaAttachmentReferenceFfi,
    attachmentIndex: Int,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onLongPress: () -> Unit = {},
    uploading: Boolean = false,
) {
    val record = item.record
    val key = record.messageIdHex
    // Decode-state keys split into two buckets:
    //   - Bytes-level state (bitmap, failed, reloadToken): keyed on
    //     `sourceEpoch` so a typed-reference upgrade from imeta-fallback
    //     (epoch = 0) to the real listMedia value clears a stuck failure.
    //   - User-interaction state (viewerOpen, startDownload): NOT keyed on
    //     epoch, because we never want a background typed-ref upgrade to
    //     close a viewer the user just opened, or re-gate a download the
    //     user just consented to.
    val epoch = reference.sourceEpoch
    // Seed from the decoded-thumbnail cache so an already-fetched or just-sent
    // image paints on the first frame — no decode spinner, no visible "reload".
    // Animated GIF/WebP and byte-sniffed unknowns skip the static thumbnail
    // cache so they always decode through the ImageDecoder path.
    var presentation by remember(key, attachmentIndex, epoch) {
        val cached =
            if (MediaPipeline.canSeedStaticThumbnailFromMediaType(reference.mediaType)) {
                controller.thumbnailFor(key, attachmentIndex)
            } else {
                null
            }
        mutableStateOf<DecodedAttachmentPresentation?>(
            cached?.let { DecodedAttachmentPresentation.Static(it) },
        )
    }
    var failed by remember(key, attachmentIndex, epoch) { mutableStateOf(false) }
    var viewerOpen by remember(key, attachmentIndex) { mutableStateOf(false) }
    var reloadToken by remember(key, attachmentIndex, epoch) { mutableStateOf(0) }
    // Auto-download gating (#10): own messages always render (bytes are cached
    // from the send), incoming honor the policy. Keyed on the policy so
    // flipping the setting re-gates undownloaded bubbles.
    var startDownload by remember(key, attachmentIndex, appState.mediaAutoDownloadMatrix) {
        mutableStateOf(mine || appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image))
    }

    LaunchedEffect(key, attachmentIndex, epoch, startDownload, reloadToken) {
        if (presentation != null) return@LaunchedEffect // already have decoded pixels
        if (!startDownload) return@LaunchedEffect
        // Own optimistic sends still have their bytes only in the pending list
        // (the projection hasn't reconciled them into the L1 cache yet). Use those
        // directly so the bubble paints during the upload window instead of hanging
        // on a missing-epoch FFI.
        val pendingBytes =
            if (mine) {
                controller.pendingAttachmentsList(key).getOrNull(attachmentIndex)?.plaintextBytes
            } else {
                null
            }
        // The imeta-tag parser falls back to sourceEpoch=0 (the wire format
        // doesn't carry it). Calling downloadMedia with epoch=0 errors with
        // "missing encrypted media secret for epoch 0". Wait for the typed
        // reference upgrade via `refreshMediaReferences` — once it lands,
        // `epoch` re-keys this effect with the real value. The spinner stays
        // visible during the wait (bitmap=null, failed=false, startDownload).
        // Skip the wait when we already hold the pending bytes (own upload window).
        if (pendingBytes == null && epoch == 0uL) return@LaunchedEffect
        failed = false
        try {
            val data = pendingBytes ?: controller.downloadAttachment(key, attachmentIndex, reference)
            val decoded =
                decodeMessageAttachmentImage(
                    bytes = data,
                    mediaType = reference.mediaType,
                    staticMaxEdgePx = MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
                )
            if (decoded != null) {
                if (decoded is DecodedAttachmentPresentation.Static) {
                    controller.cacheThumbnail(key, attachmentIndex, decoded.bitmap)
                }
                presentation = decoded
            } else {
                failed = true
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // Composable left composition or key changed — propagate. A
            // cancelled effect isn't a download failure; the bubble is gone.
            throw cancel
        } catch (t: Throwable) {
            android.util.Log.w(
                "MediaImageBubble",
                "auto-download failed for msg=${key.take(8)} idx=$attachmentIndex",
                t,
            )
            failed = true
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        // Single source of truth for image-bubble shape: portraits become
        // uniform-width cards (capped height), landscapes fill the bubble
        // width. Used by both the confirmed bubble and the optimistic
        // upload-phase bubble so the optimistic → confirmed swap is a
        // visual no-op.
        modifier = imageBubbleSizing(aspectRatioFromDim(reference.dim)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val current = presentation
            val placeholder = rememberThumbhashImage(reference.thumbhash)
            // Paint the blurred placeholder behind whatever loading-state is
            // shown so the bubble has a perceptual preview before the real
            // bytes arrive. The real image (when `current != null`) covers it.
            if (current == null && placeholder != null) {
                Image(
                    bitmap = placeholder,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            when (current) {
                is DecodedAttachmentPresentation.Static ->
                    Image(
                        bitmap = current.toImageBitmap(),
                        contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .combinedClickable(
                                    onLongClick = onLongPress,
                                    onClick = { viewerOpen = true },
                                ),
                    )
                is DecodedAttachmentPresentation.Animated ->
                    AnimatedDrawableAttachmentImage(
                        drawable = current.drawable,
                        contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .combinedClickable(
                                    onLongClick = onLongPress,
                                    onClick = { viewerOpen = true },
                                ),
                    )
                null ->
                    when {
                        failed ->
                            MediaCircleAction(
                                icon = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.media_tap_to_retry),
                                onClick = {
                                    failed = false
                                    reloadToken++
                                },
                            )
                        !startDownload ->
                            MediaCircleAction(
                                icon = Icons.Default.ArrowDownward,
                                contentDescription = stringResource(R.string.media_tap_to_download),
                                onClick = { startDownload = true },
                            )
                        else ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
            }
            if (uploading) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }

    if (viewerOpen) {
        FullScreenImageViewer(
            controller = controller,
            appState = appState,
            messageIdHex = key,
            attachments = listOf(IndexedValue(attachmentIndex, reference)),
            startIndex = 0,
            onDismiss = { viewerOpen = false },
            sender = record.sender,
            recordedAt = record.recordedAt,
            mine = mine,
        )
    }
}

/**
 * Count-specific masonry scaffolding for a 2-4 image album. Lays out the
 * tiles so a 3-image set is tall-left + two-stacked-right (no empty cell),
 * and 4+ is a 2×2 grid where the fourth tile carries the "+N" overflow chip
 * (#527). Caller provides the per-tile composable through [tile]; the helper
 * supplies each tile its size modifier so the layout shape stays one source
 * of truth across the confirmed bubble and the optimistic upload-phase
 * placeholder.
 */
@Composable
private fun MasonryImageLayout(
    visibleCount: Int,
    onLongPress: () -> Unit = {},
    tile: @Composable (index: Int, tileModifier: Modifier) -> Unit,
) {
    when (visibleCount) {
        2 ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(2.dp),
            ) {
                tile(0, Modifier.weight(1f).aspectRatio(1f))
                tile(1, Modifier.weight(1f).aspectRatio(1f))
            }
        3 ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(2.dp).aspectRatio(1f),
            ) {
                tile(0, Modifier.weight(1f).fillMaxHeight())
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    tile(1, Modifier.weight(1f).fillMaxWidth())
                    tile(2, Modifier.weight(1f).fillMaxWidth())
                }
            }
        else ->
            // 4 tiles in a 2×2 grid; any attachments beyond the fourth collapse
            // into the "+N" overflow chip the caller draws on the fourth tile
            // (index 3, the last visible tile) (#527).
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tile(0, Modifier.weight(1f).aspectRatio(1f))
                    tile(1, Modifier.weight(1f).aspectRatio(1f))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tile(2, Modifier.weight(1f).aspectRatio(1f))
                    tile(3, Modifier.weight(1f).aspectRatio(1f))
                }
            }
    }
}

@Composable
private fun MediaImageGridBubble(
    item: TimelineMessage,
    attachments: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onLongPress: () -> Unit = {},
) {
    val record = item.record
    // Show up to four tiles before collapsing the remainder into a "+N"
    // overlay on the fourth tile. Higher counts trip the overflow chip in
    // the 2×2 layout below (#527).
    val visible = attachments.take(4)
    val overflow = (attachments.size - visible.size).coerceAtLeast(0)
    var viewerOpenAt by remember(record.messageIdHex) { mutableStateOf<Int?>(null) }

    val tileAt: @Composable (Int, Modifier) -> Unit = { tileIndex, tileModifier ->
        val entry = visible[tileIndex]
        val showOverflow = tileIndex == visible.lastIndex && overflow > 0
        MediaImageGridTile(
            messageIdHex = record.messageIdHex,
            attachmentIndex = entry.index,
            reference = entry.value,
            controller = controller,
            appState = appState,
            mine = mine,
            onTap = { viewerOpenAt = tileIndex },
            overflowCount = if (showOverflow) overflow else 0,
            modifier = tileModifier,
            onLongPress = onLongPress,
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        MasonryImageLayout(visibleCount = visible.size, onLongPress = onLongPress, tile = tileAt)
    }

    viewerOpenAt?.let { index ->
        FullScreenImageViewer(
            controller = controller,
            appState = appState,
            messageIdHex = record.messageIdHex,
            attachments = attachments,
            startIndex = index,
            onDismiss = { viewerOpenAt = null },
            sender = record.sender,
            recordedAt = record.recordedAt,
            mine = mine,
        )
    }
}

/**
 * Mixed image + video album bubble. Each tile picks its renderer based on
 * MIME — image tiles open the image viewer, video tiles tap-to-play in the
 * fullscreen ExoPlayer. Layout is the same masonry as MediaImageGridBubble.
 */
@Composable
internal fun MediaVisualGridBubble(
    item: TimelineMessage,
    attachments: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onLongPress: () -> Unit = {},
    uploading: Boolean = false,
) {
    val record = item.record
    // Show up to four tiles before collapsing the remainder into a "+N"
    // overlay on the fourth tile, matching the image grid (#527).
    val visible = attachments.take(4)
    val overflow = (attachments.size - visible.size).coerceAtLeast(0)
    var viewerOpenAt by remember(record.messageIdHex) { mutableStateOf<Int?>(null) }

    val tileAt: @Composable (Int, Modifier) -> Unit = { tileIndex, tileModifier ->
        val entry = visible[tileIndex]
        val showOverflow = tileIndex == visible.lastIndex && overflow > 0
        if (MediaReferenceParser.isVideoMedia(entry.value)) {
            MediaVideoGridTile(
                messageIdHex = record.messageIdHex,
                attachmentIndex = entry.index,
                reference = entry.value,
                controller = controller,
                appState = appState,
                mine = mine,
                onTap = { _ -> viewerOpenAt = tileIndex },
                overflowCount = if (showOverflow) overflow else 0,
                modifier = tileModifier,
                onLongPress = onLongPress,
                uploading = uploading,
            )
        } else {
            MediaImageGridTile(
                messageIdHex = record.messageIdHex,
                attachmentIndex = entry.index,
                reference = entry.value,
                controller = controller,
                appState = appState,
                mine = mine,
                onTap = { viewerOpenAt = tileIndex },
                overflowCount = if (showOverflow) overflow else 0,
                modifier = tileModifier,
                onLongPress = onLongPress,
                uploading = uploading,
            )
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        MasonryImageLayout(visibleCount = visible.size, onLongPress = onLongPress, tile = tileAt)
    }

    viewerOpenAt?.let { tileIndex ->
        // Unified viewer walks the full attachments list — each page picks
        // its renderer (image vs video) by MIME, swipes between siblings
        // regardless of type. mine threads through so an own optimistic
        // overflow video (>4 tiles) materialises from retained bytes
        // instead of trying an FFI download at epoch=0.
        FullScreenImageViewer(
            controller = controller,
            appState = appState,
            messageIdHex = record.messageIdHex,
            attachments = attachments,
            startIndex = tileIndex,
            onDismiss = { viewerOpenAt = null },
            sender = record.sender,
            recordedAt = record.recordedAt,
            mine = mine,
        )
    }
}

/**
 * Single video tile in an album grid. Auto-materialises on first
 * composition (mine + cached short-circuit; otherwise FFI download honoring
 * the auto-download policy), decodes a scaled poster, overlays a centered
 * play affordance. Tap delivers the materialised file to the parent so
 * the bubble can open the fullscreen player.
 */
@Composable
internal fun MediaVideoGridTile(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onTap: (java.io.File) -> Unit,
    overflowCount: Int,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    uploading: Boolean = false,
) {
    val context = LocalContext.current
    val epoch = reference.sourceEpoch
    val cachedFileOnEntry =
        remember(messageIdHex, attachmentIndex, reference.mediaType) {
            cachedVideoAttachmentFile(
                context = context,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
            )
        }
    val cachedPlaintextOnEntry =
        remember(messageIdHex, attachmentIndex) {
            controller.hasCachedAttachment(messageIdHex, attachmentIndex)
        }
    var localFile by remember(messageIdHex, attachmentIndex, epoch, reference.mediaType) {
        mutableStateOf(cachedFileOnEntry)
    }
    // Seed the poster from the epoch-independent thumbnail cache (mirrors
    // MediaImageGridTile). A sourceEpoch upgrade re-keys this state, so without
    // the cache seed the poster would reset to null and flash back to the
    // thumbhash before the frame is re-extracted, even though the video is
    // already downloaded.
    var posterBitmap by remember(messageIdHex, attachmentIndex, epoch) {
        mutableStateOf(controller.thumbnailFor(messageIdHex, attachmentIndex)?.asImageBitmap())
    }
    var failed by remember(messageIdHex, attachmentIndex, epoch) { mutableStateOf(false) }
    val thumbhashImage = rememberThumbhashImage(reference.thumbhash)
    var startDownload by remember(messageIdHex, attachmentIndex, appState.mediaAutoDownloadMatrix) {
        mutableStateOf(
            shouldStartVideoAttachmentDownload(
                mine = mine,
                videoAutoDownload = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Video),
                hasCachedAttachment = cachedPlaintextOnEntry,
                hasCachedFile = cachedFileOnEntry != null,
            ),
        )
    }
    var reloadToken by remember(messageIdHex, attachmentIndex, epoch) { mutableStateOf(0) }

    LaunchedEffect(messageIdHex, attachmentIndex, epoch, startDownload, reloadToken, cachedPlaintextOnEntry) {
        if (localFile != null) return@LaunchedEffect
        if (!startDownload) return@LaunchedEffect
        // Re-probe the controller cache right before using the epoch-0 bypass;
        // the remembered entry snapshot only decides initial UI/download policy.
        if (
            !mine &&
            epoch == 0uL &&
            !controller.hasCachedAttachment(messageIdHex, attachmentIndex)
        ) {
            return@LaunchedEffect
        }
        runCatching {
            materializeVideoAttachment(
                context = context,
                controller = controller,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
                mine = mine,
            )
        }.onSuccess { f ->
            localFile = f
            failed = false
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            failed = true
        }
    }

    LaunchedEffect(localFile) {
        val f = localFile ?: return@LaunchedEffect
        if (posterBitmap != null) return@LaunchedEffect
        val frame =
            withContext(Dispatchers.IO) {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(f.absolutePath)
                    val edge = MediaPipeline.THUMBNAIL_MAX_EDGE_PX
                    mmr.getScaledFrameAtTime(
                        0L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        edge,
                        edge,
                    )
                } catch (t: Throwable) {
                    null
                } finally {
                    runCatching { mmr.release() }
                }
            }
        if (frame != null) {
            // Cache under the epoch-independent slot so a later sourceEpoch
            // upgrade re-seeds the poster instead of flashing the thumbhash.
            controller.cacheThumbnail(messageIdHex, attachmentIndex, frame)
            posterBitmap = frame.asImageBitmap()
        }
    }

    Box(
        modifier =
            modifier.combinedClickable(
                onLongClick = onLongPress,
                onClick = {
                    val f = localFile
                    when {
                        f != null -> onTap(f)
                        failed -> {
                            failed = false
                            reloadToken++
                        }
                        else -> startDownload = true
                    }
                },
            ),
    ) {
        val poster = posterBitmap
        when {
            poster != null ->
                Image(
                    bitmap = poster,
                    contentDescription = stringResource(R.string.reply_media_video),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            thumbhashImage != null ->
                Image(
                    bitmap = thumbhashImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            else ->
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        }
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = CircleShape,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    failed ->
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.voice_message_failed),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    !startDownload && localFile == null ->
                        Icon(
                            Icons.Default.Download,
                            contentDescription = stringResource(R.string.media_open),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    localFile == null ->
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    else ->
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.reply_media_video),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                }
            }
        }
        if (overflowCount > 0) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+$overflowCount",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        if (uploading) {
            Box(
                modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * One tile of the album grid: square thumbnail + per-tile download state.
 * The thumbnail-cache lookup is keyed on `(messageId, attachmentIndex)` so
 * tiles never clobber each other. Tap fires [onTap] (the parent opens the
 * full-screen viewer at this attachment's index).
 */
@Composable
internal fun MediaImageGridTile(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onTap: () -> Unit,
    overflowCount: Int,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    uploading: Boolean = false,
) {
    // Two-bucket key model (mirrors `MediaImageBubble`):
    //   - `decodeKey` includes `sourceEpoch`, scoped to bytes-level state
    //     so a typed-reference upgrade clears a failed-at-epoch-0 tile.
    //   - `tileSlot` omits the epoch, scoped to user-choice state
    //     (startDownload) so a background ref upgrade can't re-gate a tile
    //     the user already consented to fetch.
    val decodeKey = "$messageIdHex#$attachmentIndex#${reference.sourceEpoch}"
    val tileSlot = "$messageIdHex#$attachmentIndex"
    var presentation by remember(decodeKey) {
        val cached =
            if (MediaPipeline.canSeedStaticThumbnailFromMediaType(reference.mediaType)) {
                controller.thumbnailFor(messageIdHex, attachmentIndex)
            } else {
                null
            }
        mutableStateOf<DecodedAttachmentPresentation?>(
            cached?.let { DecodedAttachmentPresentation.Static(it) },
        )
    }
    var failed by remember(decodeKey) { mutableStateOf(false) }
    var reloadToken by remember(decodeKey) { mutableStateOf(0) }
    // Mirror the single-image bubble's auto-download gate (#10) so the
    // policy applies to album tiles too. Outgoing tiles (`mine`) always
    // download because the bytes are seeded from the send. Re-keyed on
    // the policy so flipping the setting re-gates undownloaded tiles.
    var startDownload by remember(tileSlot, appState.mediaAutoDownloadMatrix) {
        mutableStateOf(mine || appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image))
    }

    LaunchedEffect(decodeKey, startDownload, reloadToken) {
        if (presentation != null) return@LaunchedEffect
        if (!startDownload) return@LaunchedEffect
        val pendingBytes =
            if (mine) {
                controller.pendingAttachmentsList(messageIdHex).getOrNull(attachmentIndex)?.plaintextBytes
            } else {
                null
            }
        // Pre-confirm own albums: bytes live in pendingAttachmentsList and the
        // FFI imeta isn't ready yet, so skip the sourceEpoch guard for that
        // path. After reconcile, downloadAttachment hits the cache instead.
        if (pendingBytes == null && reference.sourceEpoch == 0uL) return@LaunchedEffect
        failed = false
        try {
            val data = pendingBytes ?: controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
            val decoded =
                decodeMessageAttachmentImage(
                    bytes = data,
                    mediaType = reference.mediaType,
                    staticMaxEdgePx = MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
                )
            if (decoded != null) {
                if (decoded is DecodedAttachmentPresentation.Static) {
                    controller.cacheThumbnail(messageIdHex, attachmentIndex, decoded.bitmap)
                }
                presentation = decoded
            } else {
                failed = true
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            android.util.Log.w(
                "MediaImageGridTile",
                "tile auto-download failed for msg=${messageIdHex.take(8)} idx=$attachmentIndex",
                t,
            )
            failed = true
        }
    }

    Box(
        modifier =
            modifier.combinedClickable(
                onLongClick = onLongPress,
                // Two modes:
                //   - Bytes ready (`bitmap != null`): tap opens the viewer.
                //   - Auto-download gated: tap flips startDownload, so the
                //     first tap fetches and the next tap (once decoded)
                //     opens the viewer. Same UX as the single-image bubble.
                onClick = {
                    if (presentation != null) {
                        onTap()
                    } else if (!startDownload) {
                        startDownload = true
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val current = presentation
        val placeholder = rememberThumbhashImage(reference.thumbhash)
        if (current == null && placeholder != null) {
            Image(
                bitmap = placeholder,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        when (current) {
            is DecodedAttachmentPresentation.Static ->
                Image(
                    bitmap = current.toImageBitmap(),
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            is DecodedAttachmentPresentation.Animated ->
                AnimatedDrawableAttachmentImage(
                    drawable = current.drawable,
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            null ->
                when {
                    failed ->
                        MediaCircleAction(
                            icon = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.media_tap_to_retry),
                            onClick = {
                                failed = false
                                reloadToken++
                            },
                        )
                    !startDownload ->
                        MediaCircleAction(
                            icon = Icons.Default.ArrowDownward,
                            contentDescription = stringResource(R.string.media_tap_to_download),
                            onClick = { startDownload = true },
                        )
                    else ->
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                }
        }
        if (overflowCount > 0 && current != null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflowCount",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (uploading) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * Receive-side bubble for any attachment whose MIME isn't an image. Renders
 * as a tappable pill: icon (chosen by MIME family), filename, size + status.
 * Tapping fetches the bytes (cached after first tap), writes a temp file
 * routed through the app's FileProvider, and fires `ACTION_VIEW` so the
 * system picks an external app (PDF reader, etc.) to open it.
 */
@Composable
internal fun MediaFileBubble(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onLongPress: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pillKey = "$messageIdHex#$attachmentIndex"
    var inFlight by remember(pillKey) { mutableStateOf(false) }
    var failed by remember(pillKey) { mutableStateOf(false) }
    val noOpenAppMessage = stringResource(R.string.media_no_app_to_open)
    val couldntOpenMessage = stringResource(R.string.media_couldnt_open)
    // Cached bytes (own send, or downloaded earlier) mean the chevron is
    // misleading — there's nothing to fetch. Probe on first composition,
    // then flip after a successful in-bubble download. Outgoing sends are
    // implicitly cached, so `mine` short-circuits to true.
    var cached by remember(pillKey) {
        mutableStateOf(mine || controller.hasCachedAttachment(messageIdHex, attachmentIndex))
    }
    // Auto-download gate (#407): own sends are already cached; incoming
    // documents honor the Documents matrix row for the active connection.
    // Re-keyed on the matrix so flipping a toggle re-gates an un-fetched
    // file. A tap flips this to true so manual fetch/open is always
    // available regardless of the policy.
    var startDownload by remember(pillKey, appState.mediaAutoDownloadMatrix) {
        mutableStateOf(mine || appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Document))
    }

    // When the Documents policy allows auto-download, prefetch the bytes into
    // the attachment cache so the file is ready to open without a tap. We
    // only materialize (warm the L1/L2 cache); opening still happens on tap
    // via openAttachmentExternally below. Mirrors the audio/video bubbles.
    LaunchedEffect(pillKey, reference.sourceEpoch, startDownload) {
        if (cached) return@LaunchedEffect
        if (!startDownload) return@LaunchedEffect
        // Receive-side imeta-parsed refs start with sourceEpoch=0 until the
        // controller's listMedia FFI lands the real epoch; the FFI download
        // path errors with "missing encrypted media secret for epoch 0".
        // Skip + retry once the projection rebinds the bubble with a real
        // epoch. Own sends keep epoch 0 valid (retained bytes short-circuit).
        if (!mine && reference.sourceEpoch == 0uL) return@LaunchedEffect
        inFlight = true
        runCatching {
            controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
        }.onSuccess {
            cached = true
            failed = false
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w("MediaFileBubble", "auto-download failed for msg=${messageIdHex.take(8)}#$attachmentIndex", it)
        }
        inFlight = false
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier =
            Modifier
                .widthIn(max = 360.dp)
                .combinedClickable(
                    enabled = !inFlight,
                    onLongClick = onLongPress,
                    onClick = {
                        failed = false
                        // Tap is an explicit opt-in: ensure the gate is open so a
                        // policy-gated file still fetches on demand.
                        startDownload = true
                        inFlight = true
                        scope.launch {
                            val outcome =
                                runCatching {
                                    // For own sends, the retained-uploads LRU still
                                    // holds the source plaintext during the upload
                                    // window. Prefer those bytes — the FFI download
                                    // path is mid-flight (the blob may not have
                                    // fully propagated through the Blossom server
                                    // yet) and would otherwise return invalid bytes
                                    // that the system reader rejects.
                                    val retained =
                                        if (mine) {
                                            controller
                                                .pendingAttachmentsList(messageIdHex)
                                                .getOrNull(attachmentIndex)
                                                ?.plaintextBytes
                                        } else {
                                            null
                                        }
                                    val data =
                                        retained
                                            ?: controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
                                    cached = true
                                    openAttachmentExternally(context, data, reference.fileName, reference.mediaType)
                                }.onFailure {
                                    // Swipe-up / screen-dispose cancels this
                                    // coroutine. The download itself continues on
                                    // `mutationsScope` and lands in the cache —
                                    // rethrow so the launch dies quietly instead
                                    // of misreporting cancellation as a generic
                                    // "couldn't open file" toast.
                                    if (it is kotlinx.coroutines.CancellationException) throw it
                                }.getOrDefault(OpenAttachmentResult.Error)
                            when (outcome) {
                                OpenAttachmentResult.Opened -> Unit
                                OpenAttachmentResult.NoHandler -> {
                                    failed = true
                                    appState.present(noOpenAppMessage)
                                }
                                OpenAttachmentResult.Error -> {
                                    failed = true
                                    appState.present(couldntOpenMessage, copyable = true)
                                }
                            }
                            inFlight = false
                        }
                    },
                ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = fileIconFor(reference.mediaType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    MediaPipeline.safeDisplayName(reference.fileName),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    shortMediaTypeLabel(reference.mediaType),
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (inFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (failed) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.media_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            } else if (!cached) {
                // Bytes aren't local yet — show the chevron so the user
                // knows the tap will fetch. Once cached (own send, or after
                // first tap-and-download) the chevron disappears: nothing
                // to fetch, and the row is just "tap to open".
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringResource(R.string.media_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun MediaVideoBubble(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onLongPress: () -> Unit = {},
    uploading: Boolean = false,
    uploadFailed: Boolean = false,
    onRetryUpload: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pillKey = "$messageIdHex#$attachmentIndex"
    val epoch = reference.sourceEpoch
    val cachedFileOnEntry =
        remember(pillKey, reference.mediaType) {
            cachedVideoAttachmentFile(
                context = context,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
            )
        }
    val cachedPlaintextOnEntry =
        remember(pillKey) {
            controller.hasCachedAttachment(messageIdHex, attachmentIndex)
        }
    var localFile by remember(pillKey, epoch, reference.mediaType) {
        mutableStateOf(cachedFileOnEntry)
    }
    var loading by remember(pillKey, epoch) { mutableStateOf(false) }
    var failed by remember(pillKey, epoch) { mutableStateOf(false) }
    // Seed the poster from the epoch-independent thumbnail cache (mirrors
    // MediaImageBubble). A sourceEpoch upgrade re-keys this state, so without
    // the cache seed the poster would reset to null and flash back to the
    // thumbhash before the frame is re-extracted, even though the video is
    // already downloaded.
    var posterBitmap by remember(pillKey, epoch) {
        mutableStateOf(controller.thumbnailFor(messageIdHex, attachmentIndex)?.asImageBitmap())
    }
    var durationMs by remember(pillKey, epoch) { mutableStateOf(0L) }
    var playerOpen by remember(pillKey) { mutableStateOf(false) }
    val thumbhashImage = rememberThumbhashImage(reference.thumbhash)
    // Mirrors the image bubble's auto-download gate, but already-local bytes
    // bypass the network-spend policy so chat re-entry starts at Play instead
    // of showing a fake Download affordance. When the policy says no for an
    // uncached video (e.g. Wi-Fi-only on cellular), a tap flips
    // startDownload=true so the user always has a path to fetch — never
    // "looks present but can't be opened". See PR #191 reviewer feedback.
    var startDownload by remember(pillKey, appState.mediaAutoDownloadMatrix) {
        mutableStateOf(
            shouldStartVideoAttachmentDownload(
                mine = mine,
                videoAutoDownload = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Video),
                hasCachedAttachment = cachedPlaintextOnEntry,
                hasCachedFile = cachedFileOnEntry != null,
            ),
        )
    }

    LaunchedEffect(pillKey, epoch, startDownload, cachedPlaintextOnEntry) {
        if (localFile != null) return@LaunchedEffect
        if (!startDownload) return@LaunchedEffect
        // Re-probe the controller cache right before using the epoch-0 bypass;
        // the remembered entry snapshot only decides initial UI/download policy.
        if (
            !mine &&
            epoch == 0uL &&
            !controller.hasCachedAttachment(messageIdHex, attachmentIndex)
        ) {
            return@LaunchedEffect
        }
        loading = true
        runCatching {
            materializeVideoAttachment(
                context = context,
                controller = controller,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
                mine = mine,
            )
        }.onSuccess { f ->
            localFile = f
            failed = false
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w("MediaVideoBubble", "auto-materialize failed for msg=${messageIdHex.take(8)}#$attachmentIndex", it)
            failed = true
        }
        loading = false
    }

    LaunchedEffect(localFile) {
        val f = localFile ?: return@LaunchedEffect
        // Poster may already be seeded from cache after a sourceEpoch upgrade;
        // still recompute when the duration is missing so the label survives.
        if (posterBitmap != null && durationMs > 0L) return@LaunchedEffect
        val (bm, dur) =
            withContext(Dispatchers.IO) {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(f.absolutePath)
                    // Scale down to bubble preview size so a 4K source doesn't
                    // hold a ~33 MB ARGB bitmap per visible video bubble.
                    val edge = MediaPipeline.THUMBNAIL_MAX_EDGE_PX
                    val frame =
                        mmr.getScaledFrameAtTime(
                            0L,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            edge,
                            edge,
                        )
                    val d =
                        mmr
                            .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                    frame to d
                } catch (t: Throwable) {
                    Log.w("MediaVideoBubble", "poster extract failed", t)
                    null to 0L
                } finally {
                    runCatching { mmr.release() }
                }
            }
        if (dur > 0L) durationMs = dur
        if (bm != null && posterBitmap == null) {
            // Cache under the epoch-independent slot so a later sourceEpoch
            // upgrade re-seeds the poster instead of flashing the thumbhash.
            controller.cacheThumbnail(messageIdHex, attachmentIndex, bm)
            posterBitmap = bm.asImageBitmap()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = imageBubbleSizing(aspectRatioFromDim(reference.dim)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val poster = posterBitmap
            when {
                poster != null ->
                    Image(
                        bitmap = poster,
                        contentDescription = stringResource(R.string.reply_media_video),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                thumbhashImage != null ->
                    Image(
                        bitmap = thumbhashImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                else ->
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
            }

            // Centered play overlay — semi-transparent dark circle with white
            // triangle. While uploading we replace the triangle with a spinner
            // so the user sees the send is in flight (matches the image bubble).
            // When startDownload is gated off (policy says no auto-fetch), the
            // triangle becomes a download icon and tap consents to the fetch.
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = CircleShape,
                modifier =
                    Modifier
                        .size(56.dp)
                        .combinedClickable(
                            onLongClick = onLongPress,
                            onClick = {
                                when {
                                    uploadFailed -> onRetryUpload?.invoke()
                                    else -> {
                                        val f = localFile
                                        if (f != null) {
                                            playerOpen = true
                                        } else {
                                            startDownload = true
                                        }
                                    }
                                }
                            },
                        ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        uploadFailed ->
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.voice_message_failed),
                                tint = Color.White,
                                modifier =
                                    Modifier
                                        .size(28.dp)
                                        .clickable { onRetryUpload?.invoke() },
                            )
                        uploading ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp,
                                color = Color.White,
                            )
                        !startDownload && localFile == null ->
                            Icon(
                                Icons.Default.Download,
                                contentDescription = stringResource(R.string.media_open),
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        loading && posterBitmap == null ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        failed ->
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.voice_message_failed),
                                tint = Color.White,
                                modifier =
                                    Modifier
                                        .size(28.dp)
                                        .clickable {
                                            failed = false
                                            scope.launch {
                                                runCatching {
                                                    materializeVideoAttachment(
                                                        context = context,
                                                        controller = controller,
                                                        messageIdHex = messageIdHex,
                                                        attachmentIndex = attachmentIndex,
                                                        reference = reference,
                                                        mine = mine,
                                                    )
                                                }.onSuccess { localFile = it }
                                                    .onFailure { failed = true }
                                            }
                                        },
                            )
                        else ->
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.reply_media_video),
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                    }
                }
            }

            // Duration pill bottom-start. Only shown once duration is known.
            if (durationMs > 0L) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(6.dp),
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                ) {
                    Text(
                        formatVoiceTime(durationMs.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
    if (playerOpen) {
        val file = localFile
        if (file != null) {
            FullscreenVideoPlayer(file = file, onDismiss = { playerOpen = false })
        }
    }
}

/** Decrypted video on disk under cacheDir/video_attachments; reuses the
 *  age-based janitor that already sweeps shared_media / voice_attachments. */
private suspend fun materializeVideoAttachment(
    context: android.content.Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
): java.io.File {
    cachedVideoAttachmentFile(
        context = context,
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = reference,
    )?.let { return it }

    val file =
        videoAttachmentCacheFile(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        )
    val retained =
        if (mine) {
            controller
                .pendingAttachmentsList(messageIdHex)
                .getOrNull(attachmentIndex)
                ?.plaintextBytes
        } else {
            null
        }
    val bytes = retained ?: controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
    withContext(Dispatchers.IO) { file.writeBytes(bytes) }
    return file
}

@VisibleForTesting
internal fun cachedVideoAttachmentFile(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): java.io.File? =
    videoAttachmentCacheFile(
        context = context,
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = reference,
    ).takeIf { it.isFile && it.length() > 0L }

@VisibleForTesting
internal fun shouldStartVideoAttachmentDownload(
    mine: Boolean,
    videoAutoDownload: Boolean,
    hasCachedAttachment: Boolean,
    hasCachedFile: Boolean,
): Boolean = mine || videoAutoDownload || hasCachedAttachment || hasCachedFile

private fun videoAttachmentCacheFile(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): java.io.File {
    val dir = java.io.File(context.cacheDir, MediaCacheDirs.VIDEO).apply { mkdirs() }
    return java.io.File(dir, "$messageIdHex-$attachmentIndex.${videoAttachmentExtension(reference)}")
}

private fun videoAttachmentExtension(reference: MediaAttachmentReferenceFfi): String =
    when {
        reference.mediaType.contains("quicktime", ignoreCase = true) -> "mov"
        reference.mediaType.contains("webm", ignoreCase = true) -> "webm"
        else -> "mp4"
    }

/**
 * Fullscreen player backed by Media3 ExoPlayer + PlayerView — the same
 * controller the platform media apps ship. Tap toggles the transport bar;
 * play/pause/seek work reliably without VideoView's MediaController quirks.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun FullscreenVideoPlayer(
    file: java.io.File,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val exo =
        remember(file) {
            androidx.media3.exoplayer.ExoPlayer
                .Builder(context)
                .build()
                .apply {
                    setMediaItem(
                        androidx.media3.common.MediaItem
                            .fromUri(android.net.Uri.fromFile(file)),
                    )
                    prepare()
                    playWhenReady = true
                }
        }
    DisposableEffect(exo) { onDispose { exo.release() } }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties =
            androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        player = exo
                        useController = true
                        setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        controllerShowTimeoutMs = 2500
                    }
                },
            )
            IconButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
internal fun MediaVoiceBubble(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onLongPress: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pillKey = "$messageIdHex#$attachmentIndex"

    val cachedFileOnEntry =
        remember(pillKey, reference.mediaType) {
            cachedVoiceAttachmentFile(
                context = context,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
            )
        }
    val cachedPlaintextOnEntry =
        remember(pillKey, reference.mediaType) {
            controller.hasCachedAttachment(messageIdHex, attachmentIndex)
        }
    var localFile by remember(pillKey, reference.mediaType) {
        mutableStateOf(cachedFileOnEntry)
    }
    var totalDurationMs by remember(pillKey) { mutableStateOf(0) }
    var loading by remember(pillKey) { mutableStateOf(false) }
    var failed by remember(pillKey) { mutableStateOf(false) }
    // Auto-download gate (#407): own clips always materialize (bytes are
    // cached from the send), incoming honor the Audio matrix row unless the
    // attachment is already local. A cached voice file or controller plaintext
    // cache means re-entering the chat should start at Play instead of showing
    // a fake Download affordance. Re-keyed on the matrix so flipping a toggle
    // re-gates an un-fetched clip. A tap on the bubble flips this to true so
    // manual fetch/playback is always available even when auto-download is off.
    var startDownload by remember(pillKey, appState.mediaAutoDownloadMatrix) {
        mutableStateOf(
            shouldStartVoiceAttachmentDownload(
                mine = mine,
                audioAutoDownload = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Audio),
                hasCachedAttachment = cachedPlaintextOnEntry,
                hasCachedFile = cachedFileOnEntry != null,
            ),
        )
    }

    val playback by remember(pillKey) {
        dev.ipf.whitenoise.android.audio.VoicePlaybackController.state
            .map { state -> state.takeIf { it.key == pillKey } }
            .distinctUntilChanged()
    }.collectAsState(null)
    val isThis = playback != null
    val isPlayingThis = playback?.isPlaying == true
    val isPausedThis = playback?.let { !it.isPlaying && it.positionMs > 0 } == true
    val activeDurationMs =
        playback?.durationMs?.takeIf { it > 0 } ?: totalDurationMs
    val activePositionMs = playback?.positionMs ?: 0
    val progressFraction =
        if (activeDurationMs > 0) {
            (activePositionMs.toFloat() / activeDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val pseudoWaveform: FloatArray =
        remember(pillKey) {
            val bytes =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(pillKey.toByteArray())
            FloatArray(dev.ipf.whitenoise.android.audio.AudioWaveformExtractor.BARS) { i ->
                val byte = bytes[i % bytes.size].toInt() and 0xFF
                0.3f + (byte / 255f) * 0.7f
            }
        }
    var realWaveform by remember(pillKey) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(localFile, pillKey) {
        val file = localFile ?: return@LaunchedEffect
        if (realWaveform != null) return@LaunchedEffect
        realWaveform =
            dev.ipf.whitenoise.android.audio.AudioWaveformExtractor
                .decode(file)
    }
    val waveform: FloatArray = realWaveform ?: pseudoWaveform

    suspend fun clearBadVoiceCache(reason: String) {
        Log.w(
            "MediaVoiceBubble",
            "$reason for cached voice msg=${messageIdHex.take(8)}#$attachmentIndex; clearing cache",
        )
        clearVoiceAttachmentCacheAfterPlaybackFailure(
            context = context,
            controller = controller,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        )
        localFile = null
        realWaveform = null
        totalDurationMs = 0
        failed = true
        startDownload = mine || appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Audio)
    }

    LaunchedEffect(pillKey, reference.mediaType) {
        VoicePlaybackController.failures.collect { failure ->
            if (failure.key == pillKey && failure.invalidatesCache) {
                clearBadVoiceCache("playback error")
            }
        }
    }

    LaunchedEffect(pillKey, reference.sourceEpoch, startDownload) {
        if (localFile != null) return@LaunchedEffect
        // Honor the auto-download gate: when Audio is off for the active
        // connection the clip waits behind a Download affordance until the
        // user opts in (tap flips startDownload=true). Manual playback below
        // stays available regardless.
        if (!startDownload) return@LaunchedEffect
        // Receive-side imeta-parsed refs start with sourceEpoch=0 until the
        // controller's listMedia FFI lands the real epoch; the FFI download
        // path errors with "missing encrypted media secret for epoch 0".
        // Skip + retry once the projection rebinds the bubble with a real
        // epoch. Own sends keep epoch 0 valid (retained bytes short-circuit).
        if (!mine && reference.sourceEpoch == 0uL) return@LaunchedEffect
        val instant = mine || controller.hasCachedAttachment(messageIdHex, attachmentIndex)
        if (!instant) loading = true
        runCatching {
            materializeVoiceAttachment(
                context = context,
                controller = controller,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
                mine = mine,
            )
        }.onSuccess { file ->
            localFile = file
            failed = false
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w("MediaVoiceBubble", "auto-materialize failed for msg=${messageIdHex.take(8)}#$attachmentIndex", it)
            failed = true
        }
        loading = false
    }

    // Surface a cached duration as soon as the file is materialized so the
    // bubble shows "0:12" instead of "0:00" before the user taps Play.
    LaunchedEffect(pillKey, localFile) {
        val file = localFile ?: return@LaunchedEffect
        if (totalDurationMs == 0) {
            val probed =
                dev.ipf.whitenoise.android.audio.VoicePlaybackController
                    .probeDuration(file)
            if (probed > 0) totalDurationMs = probed
        }
    }

    val onSurfaceMuted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            // Circular play/pause button. Anchors the bubble and is the
            // primary tap target — sized generously (48dp) so it reads as
            // the focal control.
            Surface(
                color = accent,
                shape = androidx.compose.foundation.shape.CircleShape,
                modifier =
                    Modifier
                        .size(48.dp)
                        .combinedClickable(
                            enabled = !loading,
                            onLongClick = onLongPress,
                            onClick = {
                                failed = false
                                // First tap on an un-fetched, auto-download-off clip
                                // is a Download affordance: opt in and let the
                                // gated effect fetch it, rather than fetch+play in
                                // one tap. Mirrors the video bubble's tap-to-fetch.
                                if (!startDownload && localFile == null) {
                                    startDownload = true
                                    return@combinedClickable
                                }
                                if (isPlayingThis) {
                                    dev.ipf.whitenoise.android.audio.VoicePlaybackController
                                        .pause()
                                    return@combinedClickable
                                }
                                scope.launch {
                                    val file =
                                        localFile ?: runCatching {
                                            loading = true
                                            materializeVoiceAttachment(
                                                context = context,
                                                controller = controller,
                                                messageIdHex = messageIdHex,
                                                attachmentIndex = attachmentIndex,
                                                reference = reference,
                                                mine = mine,
                                            )
                                        }.onFailure {
                                            if (it is kotlinx.coroutines.CancellationException) throw it
                                            Log.w("MediaVoiceBubble", "materialize failed for msg=${messageIdHex.take(8)}#$attachmentIndex", it)
                                            failed = true
                                        }.also { loading = false }
                                            .getOrNull()

                                    if (file == null) return@launch
                                    localFile = file
                                    val playbackResult =
                                        dev.ipf.whitenoise.android.audio.VoicePlaybackController
                                            .play(pillKey, file, ownerKey = controller.group.groupIdHex)
                                    if (shouldInvalidateVoiceAttachmentCache(playbackResult)) {
                                        clearBadVoiceCache("playback start failed")
                                    }
                                }
                            },
                        ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        loading ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = onAccent,
                            )
                        failed ->
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.voice_message_failed),
                                tint = onAccent,
                                modifier = Modifier.size(26.dp),
                            )
                        !startDownload && localFile == null ->
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = stringResource(R.string.media_tap_to_download),
                                tint = onAccent,
                                modifier = Modifier.size(26.dp),
                            )
                        isPlayingThis ->
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = stringResource(R.string.voice_message_pause),
                                tint = onAccent,
                                modifier = Modifier.size(28.dp),
                            )
                        else ->
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.voice_message_play),
                                tint = onAccent,
                                modifier = Modifier.size(28.dp),
                            )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                VoiceWaveform(
                    bars = waveform,
                    progress = progressFraction,
                    playedColor = accent,
                    remainingColor = onSurfaceMuted,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(28.dp),
                    onSeek =
                        if (isThis && activeDurationMs > 0) {
                            { fraction ->
                                dev.ipf.whitenoise.android.audio.VoicePlaybackController
                                    .seekTo(pillKey, (fraction * activeDurationMs).toInt())
                            }
                        } else {
                            null
                        },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val timeText =
                        when {
                            isPlayingThis || isPausedThis ->
                                "${formatVoiceTime(activePositionMs)} / ${formatVoiceTime(activeDurationMs)}"
                            totalDurationMs > 0 -> formatVoiceTime(totalDurationMs)
                            else -> "0:00"
                        }
                    Text(
                        timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // Speed pill: only shown once playback has been engaged
                    // for this clip, so an unplayed bubble stays uncluttered.
                    playback?.let { activePlayback ->
                        VoiceSpeedPill(currentSpeed = activePlayback.speed)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceSpeedPill(currentSpeed: Float) {
    val label =
        when {
            currentSpeed >= 1.95f -> "2×"
            currentSpeed >= 1.45f -> "1.5×"
            else -> "1×"
        }
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        shape = RoundedCornerShape(10.dp),
        modifier =
            Modifier.clickable {
                dev.ipf.whitenoise.android.audio.VoicePlaybackController
                    .cycleSpeed()
            },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * Voice attachments need a file on disk for MediaPlayer; reuse the
 * downloaded plaintext to populate a stable per-message cache file so
 * subsequent plays are instant. Own outgoing sends short-circuit through
 * the still-retained source bytes from the pending-attachments list while
 * the Blossom upload is in flight.
 */
internal suspend fun materializeVoiceAttachment(
    context: android.content.Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
): java.io.File {
    cachedVoiceAttachmentFile(
        context = context,
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = reference,
    )?.let { return it }

    val cacheFile =
        voiceAttachmentCacheFile(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        )
    val retained =
        if (mine) {
            controller
                .pendingAttachmentsList(messageIdHex)
                .getOrNull(attachmentIndex)
                ?.plaintextBytes
        } else {
            null
        }
    val bytes =
        retained
            ?: controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
    withContext(Dispatchers.IO) { cacheFile.writeBytes(bytes) }
    return cacheFile
}

internal fun cachedVoiceAttachmentFile(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): java.io.File? =
    voiceAttachmentCacheFile(
        context = context,
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = reference,
    ).takeIf { it.isFile && it.length() > 0L }

internal fun shouldStartVoiceAttachmentDownload(
    mine: Boolean,
    audioAutoDownload: Boolean,
    hasCachedAttachment: Boolean,
    hasCachedFile: Boolean,
): Boolean = mine || audioAutoDownload || hasCachedAttachment || hasCachedFile

internal fun shouldInvalidateVoiceAttachmentCache(playbackResult: VoicePlaybackController.PlaybackStartResult): Boolean =
    playbackResult == VoicePlaybackController.PlaybackStartResult.PrepareFailed ||
        playbackResult == VoicePlaybackController.PlaybackStartResult.StartFailed

internal suspend fun clearVoiceAttachmentCacheAfterPlaybackFailure(
    context: android.content.Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
) {
    withContext(Dispatchers.IO) {
        voiceAttachmentCacheFile(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        ).delete()
    }
    controller.evictCachedAttachment(messageIdHex, attachmentIndex)
}

private fun voiceAttachmentCacheFile(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): java.io.File {
    val cacheDir = java.io.File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() }
    return java.io.File(cacheDir, "$messageIdHex-$attachmentIndex.${voiceAttachmentExtension(reference)}")
}

private fun voiceAttachmentExtension(reference: MediaAttachmentReferenceFfi): String =
    when {
        reference.mediaType.contains("mp4", ignoreCase = true) -> "m4a"
        reference.mediaType.contains("aac", ignoreCase = true) -> "aac"
        reference.mediaType.contains("ogg", ignoreCase = true) -> "ogg"
        reference.mediaType.contains("wav", ignoreCase = true) -> "wav"
        else -> "bin"
    }

/** mm:ss formatter; durations cap below an hour for voice notes. */
private fun formatVoiceTime(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
}

@Composable
internal fun VoiceWaveform(
    bars: FloatArray,
    progress: Float,
    playedColor: Color,
    remainingColor: Color,
    modifier: Modifier = Modifier,
    onSeek: ((fraction: Float) -> Unit)? = null,
) {
    var widthPx by remember { mutableStateOf(0f) }
    val seekModifier =
        if (onSeek != null) {
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Consume so the bubble's parent swipe-to-reply gesture
                    // doesn't snatch a rightward drag mid-scrub.
                    down.consume()
                    // Before the first onSizeChanged, widthPx is 0 → x/0 = NaN → a
                    // stray seek-to-zero. Skip the gesture until the size is known.
                    if (widthPx <= 0f) return@awaitEachGesture
                    onSeek((down.position.x / widthPx).coerceIn(0f, 1f))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        onSeek((change.position.x / widthPx).coerceIn(0f, 1f))
                        if (change.changedToUp() || !change.pressed) break
                    }
                }
            }
        } else {
            Modifier
        }
    Canvas(
        modifier =
            modifier
                .then(seekModifier)
                .onSizeChanged { widthPx = it.width.toFloat() },
    ) {
        val barCount = bars.size
        if (barCount == 0) return@Canvas
        val totalWidth = size.width
        val totalHeight = size.height
        val barSlot = totalWidth / barCount
        val barWidth = barSlot * 0.55f
        val cornerRadius =
            androidx.compose.ui.geometry
                .CornerRadius(barWidth / 2f, barWidth / 2f)
        val playedBars = (progress * barCount).toInt()
        for (i in 0 until barCount) {
            val barHeight = totalHeight * bars[i]
            val x = i * barSlot + (barSlot - barWidth) / 2f
            val y = (totalHeight - barHeight) / 2f
            val color = if (i < playedBars) playedColor else remainingColor
            drawRoundRect(
                color = color,
                topLeft =
                    androidx.compose.ui.geometry
                        .Offset(x, y),
                size =
                    androidx.compose.ui.geometry
                        .Size(barWidth, barHeight),
                cornerRadius = cornerRadius,
            )
        }
    }
}

/**
 * Compact uppercase label for the file-bubble's MIME line: `application/pdf`
 * becomes "PDF", `image/jpeg` becomes "JPG", `application/vnd.…` falls back
 * to the lowercase MIME so the bubble never goes blank.
 */
internal fun shortMediaTypeLabel(mediaType: String): String {
    val trimmed = mediaType.trim()
    if (trimmed.isEmpty()) return ""
    val tail = trimmed.substringAfterLast('/', missingDelimiterValue = trimmed)
    return when (val canonical = tail.substringBefore('+').substringBefore(';').lowercase()) {
        "jpeg" -> "JPG"
        "vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX"
        "vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "XLSX"
        "vnd.openxmlformats-officedocument.presentationml.presentation" -> "PPTX"
        "msword" -> "DOC"
        "vnd.ms-excel" -> "XLS"
        "vnd.ms-powerpoint" -> "PPT"
        "" -> trimmed
        else -> canonical.uppercase()
    }
}

internal fun fileIconFor(mediaType: String): androidx.compose.ui.graphics.vector.ImageVector =
    when {
        mediaType.startsWith("audio/", ignoreCase = true) -> Icons.Default.Audiotrack
        mediaType.startsWith("video/", ignoreCase = true) -> Icons.Default.Movie
        mediaType.startsWith("image/", ignoreCase = true) -> Icons.Default.Image
        else -> Icons.Default.Description
    }

private fun formatFileSize(bytes: Long): String {
    if (bytes < 0L) return ""
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.US, "%.1f GB", gb)
}

internal enum class OpenAttachmentResult { Opened, NoHandler, Error }

/**
 * Write [bytes] to a temp file in the cache directory and fire `ACTION_VIEW`
 * for it via the app's FileProvider so an external app (PDF reader, etc.)
 * can open it.
 *
 * Distinguishes "no app claims this MIME" ([OpenAttachmentResult.NoHandler])
 * from "we couldn't even try" ([OpenAttachmentResult.Error]) so the caller
 * can surface the right toast.
 *
 * `resolveActivity`/`queryIntentActivities` are intentionally NOT used to
 * pre-flight the launch: under Android 11+ package visibility they return
 * null for any handler whose package isn't declared in `<queries>`, even
 * when the activity exists and `startActivity` would launch it. Catching
 * `ActivityNotFoundException` from `startActivity` is the authoritative
 * "nothing handles this MIME" signal.
 *
 * Suspends because the temp-file write can be a multi-megabyte hop —
 * documents and videos picked from the document bubble are read whole
 * into a `ByteArray` and need to land on disk before the intent fires.
 * Doing that on the main dispatcher would jank the UI for the whole
 * write; the `Dispatchers.IO` jump moves it off the main thread.
 *
 * The temp file is owned by the cache cleanup pass triggered on screen
 * exit; we don't track it per-call because the handing-off intent may
 * need it alive for an unbounded duration after this function returns.
 */
internal suspend fun openAttachmentExternally(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): OpenAttachmentResult {
    val uri =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
                val name = MediaPipeline.safeDisplayName(fileName)
                val file = java.io.File.createTempFile("open_", "_$name", dir)
                file.writeBytes(bytes)
                fileProviderUri(context, file)
            }.getOrNull()
        } ?: return OpenAttachmentResult.Error
    val mime = mediaType.ifBlank { "application/octet-stream" }
    val intent =
        android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    return try {
        context.startActivity(intent)
        OpenAttachmentResult.Opened
    } catch (_: android.content.ActivityNotFoundException) {
        OpenAttachmentResult.NoHandler
    } catch (_: SecurityException) {
        // FileProvider grant rejected, or target activity has no permission
        // to access this URI for some reason. Surfacing this as a generic
        // error is more useful than crashing.
        OpenAttachmentResult.Error
    }
}

/**
 * Circular tap target overlaid on a media bubble. Used for both the
 * "tap to download" affordance (download arrow) and the "tap to retry"
 * affordance (refresh arrow) so the receiver-side bubble feels like a
 * polished media-message card instead of a flat icon-label stack.
 *
 * Renders as a ~52dp opaque scrim circle with a centered icon — works
 * over a blurred thumbhash placeholder or a plain surface tint without
 * fighting the background.
 */
@Composable
private fun MediaCircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        modifier = modifier.size(52.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun MediaBubbleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .fillMaxSize()
                .clickable(onClick = onClick)
                .wrapContentSize(Alignment.Center)
                .padding(16.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun MediaPendingPlaceholder(
    pendingAttachments: List<PendingAttachment>,
    failed: Boolean,
    onRetry: (() -> Unit)? = null,
) {
    val statusLabel = stringResource(if (failed) R.string.media_upload_failed else R.string.media_uploading)
    val statusColor = if (failed) MaterialTheme.colorScheme.error else Color.White

    // Image-only sends keep the fixed-height image bubble. The moment a
    // non-image attachment is part of the album the bubble shape switches to
    // a stack of file-pill placeholders so the optimistic → confirmed swap
    // matches the post-upload layout (image grid above, file pills below).
    val allImages = pendingAttachments.isNotEmpty() && pendingAttachments.all { isImagePendingAttachment(it) }
    if (!allImages) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            pendingAttachments.forEach { attachment ->
                PendingFilePill(
                    fileName = attachment.fileName,
                    mediaType = attachment.mediaType,
                    sizeBytes = attachment.plaintextBytes.size.toLong(),
                    failed = failed,
                    statusLabel = statusLabel,
                    onRetry = onRetry,
                )
            }
        }
        return
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (pendingAttachments.size <= 1) {
                // Single-image optimistic: same sizing as the confirmed
                // bubble so the optimistic→confirmed swap doesn't reflow
                // the timeline. Source aspect ratio comes from the
                // attachment's own `dim` (set at pick time).
                val attachment = pendingAttachments.firstOrNull()
                val preview = rememberSampledBitmap(attachment?.plaintextBytes)
                val ratio = aspectRatioFromDim(attachment?.dim)
                Box(
                    imageBubbleSizing(ratio),
                    contentAlignment = Alignment.Center,
                ) {
                    preview?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))
                    }
                    PendingStatusOverlay(
                        failed = failed,
                        hasPreview = preview != null,
                        statusLabel = statusLabel,
                        statusColor = statusColor,
                        onRetry = onRetry,
                    )
                }
            } else {
                // Album: route through the same count-specific masonry
                // layout the confirmed bubble uses so the optimistic →
                // confirmed transition is a visual no-op even on the
                // 3-image case. Each tile decodes from local bytes (no
                // network), and a single status overlay sits across the
                // whole bubble. Cap at four tiles so the surplus collapses
                // into the "+N" chip on the fourth tile, matching the
                // confirmed grid bubbles (MasonryImageLayout renders four
                // tiles max) (#527).
                val visible = pendingAttachments.take(4)
                val overflow = (pendingAttachments.size - visible.size).coerceAtLeast(0)
                Box(Modifier.fillMaxWidth()) {
                    MasonryImageLayout(visibleCount = visible.size) { index, tileModifier ->
                        val attachment = visible[index]
                        val showOverflow = index == visible.lastIndex && overflow > 0
                        PendingGridTile(
                            bytes = attachment.plaintextBytes,
                            overflowCount = if (showOverflow) overflow else 0,
                            modifier = tileModifier,
                        )
                    }
                    Box(
                        Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)),
                    )
                    PendingStatusOverlay(
                        failed = failed,
                        hasPreview = true,
                        statusLabel = statusLabel,
                        statusColor = statusColor,
                        onRetry = onRetry,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

private fun isImagePendingAttachment(attachment: PendingAttachment): Boolean = attachment.mediaType.startsWith("image/", ignoreCase = true)

@Composable
internal fun PendingFilePill(
    fileName: String,
    mediaType: String,
    sizeBytes: Long,
    failed: Boolean,
    statusLabel: String,
    onRetry: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (failed && onRetry != null) {
                        Modifier.clickable(onClick = onRetry)
                    } else {
                        Modifier
                    },
                ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = fileIconFor(mediaType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    MediaPipeline.safeDisplayName(fileName),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatFileSize(sizeBytes)} · $statusLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (failed) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.retry),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PendingStatusOverlay(
    failed: Boolean,
    hasPreview: Boolean,
    statusLabel: String,
    statusColor: Color,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (failed) {
            // Tap target for retry. Without this the user only has the
            // small refresh icon down in the status row, which is easy to
            // miss on a media bubble dominated by a blurred preview.
            if (onRetry != null) {
                MediaCircleAction(
                    icon = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.retry),
                    onClick = onRetry,
                )
            } else {
                Icon(
                    Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(28.dp),
                )
            }
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
                color = if (hasPreview) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            statusLabel,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (hasPreview) {
                    statusColor
                } else {
                    if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Composable
private fun PendingGridTile(
    bytes: ByteArray,
    overflowCount: Int,
    modifier: Modifier = Modifier,
) {
    val preview = rememberSampledBitmap(bytes)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        preview?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (overflowCount > 0 && preview != null) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+$overflowCount",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Decode [bytes] to a sampled [ImageBitmap] off the main thread; null while
 *  decoding or when [bytes] is null/undecodable. */
@Composable
private fun rememberSampledBitmap(bytes: ByteArray?): ImageBitmap? {
    var bitmap by remember(bytes) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(bytes) {
        bitmap =
            if (bytes == null) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    MediaPipeline.decodeSampledBitmap(bytes, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
                }
            }
    }
    // Recycle the multi-MB ARGB buffer on key change and dispose instead of
    // leaving it to the GC, mirroring ViewerPage. Capture the instance so a
    // key change recycles the previous bitmap, not the replacement.
    DisposableEffect(bitmap) {
        val decoded = bitmap
        onDispose { decoded?.recycle() }
    }
    return remember(bitmap) { bitmap?.asImageBitmap() }
}

/**
 * Resolve the decrypted bytes for an attachment, preferring the retained
 * plaintext in `pendingAttachmentsList` for own optimistic sends so the
 * viewer / save / share paths don't spin while waiting for the projection
 * to reconcile. Falls back to the standard FFI download for everything else.
 */
private suspend fun attachmentBytes(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
): ByteArray {
    if (mine) {
        controller
            .pendingAttachmentsList(messageIdHex)
            .getOrNull(attachmentIndex)
            ?.plaintextBytes
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
    }
    return controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
}

// One page of the full-screen media viewer. Unlike the original single-album
// viewer (one fixed messageIdHex + mine for the whole pager), each page now
// carries its own message context so the pager can span attachments from
// different messages — the cross-message gallery the shared-media grids open.
// The save/share/decrypt paths read the CURRENT page's descriptor.
internal data class MediaViewerPage(
    val messageIdHex: String,
    val attachmentIndex: Int,
    val reference: MediaAttachmentReferenceFfi,
    val mine: Boolean,
    val sender: String,
    val recordedAt: ULong,
)

// Album wrapper preserving the original call shape: a single message's
// attachments, one `mine` flag. The three conversation bubble callsites use
// this; it just projects the album onto per-page descriptors.
@Composable
private fun FullScreenImageViewer(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    messageIdHex: String,
    attachments: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    startIndex: Int,
    onDismiss: () -> Unit,
    sender: String,
    recordedAt: ULong,
    mine: Boolean = false,
) {
    val pages =
        remember(messageIdHex, attachments, mine, sender, recordedAt) {
            attachments.map { entry ->
                MediaViewerPage(messageIdHex, entry.index, entry.value, mine, sender, recordedAt)
            }
        }
    FullScreenMediaViewer(
        controller = controller,
        appState = appState,
        pages = pages,
        startIndex = startIndex,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun FullScreenMediaViewer(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    pages: List<MediaViewerPage>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    if (pages.isEmpty()) {
        // Defensive — callers shouldn't open an empty viewer, but guard so the
        // pager doesn't NPE on a vanished album.
        onDismiss()
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.media_saved)
    val saveFailedMessage = stringResource(R.string.media_save_failed)
    val pagerState =
        rememberPagerState(
            initialPage = startIndex.coerceIn(0, pages.lastIndex),
            pageCount = { pages.size },
        )
    // pagerState outlives a shrinking pages list (album reconcile): currentPage
    // isn't re-clamped to the new lastIndex for a frame, so clamp at the read.
    val currentPage = pages[pagerState.currentPage.coerceIn(0, pages.lastIndex)]
    val currentReference = currentPage.reference
    val currentAttachmentIndex = currentPage.attachmentIndex
    val currentMessageIdHex = currentPage.messageIdHex
    val currentMine = currentPage.mine
    // Zoom state is hoisted to the viewer scope (not per-page) so the pager
    // can read it to gate horizontal swipe. Without this gate, the page's
    // `detectTransformGestures` claims every horizontal drag and the pager
    // never moves. Page change resets to identity below.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // Disable pager swipe while the visible page is zoomed in —
                // otherwise the pan gesture and the pager's swipe both want
                // the horizontal drag. At scale 1× the pager wins.
                userScrollEnabled = scale <= 1f,
            ) { page ->
                val pageDescriptor = pages[page.coerceIn(0, pages.lastIndex)]
                if (MediaReferenceParser.isVideoMedia(pageDescriptor.reference)) {
                    VideoViewerPage(
                        controller = controller,
                        messageIdHex = pageDescriptor.messageIdHex,
                        attachmentIndex = pageDescriptor.attachmentIndex,
                        reference = pageDescriptor.reference,
                        isCurrent = page == pagerState.currentPage,
                        mine = pageDescriptor.mine,
                    )
                } else {
                    ViewerPage(
                        controller = controller,
                        messageIdHex = pageDescriptor.messageIdHex,
                        attachmentIndex = pageDescriptor.attachmentIndex,
                        reference = pageDescriptor.reference,
                        scale = if (page == pagerState.currentPage) scale else 1f,
                        offset = if (page == pagerState.currentPage) offset else Offset.Zero,
                        onScaleChange = { if (page == pagerState.currentPage) scale = it },
                        onOffsetChange = { if (page == pagerState.currentPage) offset = it },
                        mine = pageDescriptor.mine,
                    )
                }
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                }
                if (pages.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${pages.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Row {
                    IconButton(
                        onClick = {
                            val ref = currentReference
                            val attachmentIndex = currentAttachmentIndex
                            val msgId = currentMessageIdHex
                            val owned = currentMine
                            scope.launch {
                                val data =
                                    runCatching {
                                        attachmentBytes(controller, msgId, attachmentIndex, ref, owned)
                                    }.getOrNull()
                                val ok =
                                    data != null &&
                                        withContext(Dispatchers.IO) {
                                            if (MediaReferenceParser.isVideoMedia(ref)) {
                                                saveVideoToGallery(context, data, ref.fileName, ref.mediaType)
                                            } else {
                                                saveImageToGallery(context, data, ref.fileName, ref.mediaType)
                                            }
                                        }
                                snackbarHostState.showSnackbar(if (ok) savedMessage else saveFailedMessage)
                            }
                        },
                    ) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.media_save), tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            val ref = currentReference
                            val attachmentIndex = currentAttachmentIndex
                            val msgId = currentMessageIdHex
                            val owned = currentMine
                            scope.launch {
                                runCatching {
                                    attachmentBytes(controller, msgId, attachmentIndex, ref, owned)
                                }.getOrNull()?.let { shareImage(context, it, ref.fileName, ref.mediaType) }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share), tint = Color.White)
                    }
                }
            }
            // Sender + send-time caption for the visible page, over a bottom
            // scrim so it stays readable on bright photos. Reads the current
            // page so it tracks swipes.
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                            ),
                        ).navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = appState.displayName(currentPage.sender),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        DateUtils.formatDateTime(
                            context,
                            currentPage.recordedAt.toLong() * 1000L,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
                        ),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
            )
        }
    }
}

/**
 * One page of the full-screen pager. Owns its own download + decode + pan/zoom
 * state so swiping to a sibling page doesn't carry zoom across, and disposing
 * the page recycles the multi-MB native bitmap instead of leaning on GC. The
 * pager prefetches one page either side by default, which is why
 * `LaunchedEffect` doesn't need to wait for "page becomes visible" — it
 * downloads as soon as the page composes.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoViewerPage(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    isCurrent: Boolean,
    mine: Boolean,
) {
    val context = LocalContext.current
    val cachedFileOnEntry =
        remember(messageIdHex, attachmentIndex, reference.mediaType) {
            cachedVideoAttachmentFile(
                context = context,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
            )
        }
    var localFile by remember(
        messageIdHex,
        attachmentIndex,
        reference.sourceEpoch,
        reference.mediaType,
    ) {
        mutableStateOf(cachedFileOnEntry)
    }
    LaunchedEffect(messageIdHex, attachmentIndex, reference.sourceEpoch) {
        if (localFile != null) return@LaunchedEffect
        // Receive-side: skip epoch=0 (FFI download would error). Own
        // optimistic sends still have their bytes in pendingAttachmentsList
        // even at epoch=0, so we let materializeVideoAttachment short-
        // circuit through the retained-bytes path with mine=true.
        if (!mine && reference.sourceEpoch == 0uL) return@LaunchedEffect
        runCatching {
            materializeVideoAttachment(
                context = context,
                controller = controller,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
                mine = mine,
            )
        }.onSuccess { localFile = it }
    }
    val file = localFile
    if (file == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        }
        return
    }
    val exo =
        remember(file) {
            androidx.media3.exoplayer.ExoPlayer
                .Builder(context)
                .build()
                .apply {
                    setMediaItem(
                        androidx.media3.common.MediaItem
                            .fromUri(android.net.Uri.fromFile(file)),
                    )
                    prepare()
                }
        }
    DisposableEffect(exo) { onDispose { exo.release() } }
    // Pre-composed neighbour pages must NOT play audio — only the visible
    // one autoplays. Pause when the page scrolls off-screen.
    LaunchedEffect(isCurrent, exo) {
        if (isCurrent) exo.playWhenReady = true else exo.pause()
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                player = exo
                useController = true
                setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                controllerShowTimeoutMs = 2500
            }
        },
    )
}

@Composable
private fun ViewerPage(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    scale: Float,
    offset: Offset,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    mine: Boolean,
) {
    // `pointerInput(pageKey)` only restarts when the key changes — its
    // coroutine outlives any single gesture. Function parameters
    // (`scale`, `offset`, the callbacks) captured directly inside that
    // coroutine would stay at their initial values for the lifetime of
    // the gesture, causing jumpy zoom/pan and stale callback dispatch.
    // `rememberUpdatedState` snapshots each parameter into a stable
    // State<T> whose `.value` reads inside the coroutine always reflect
    // the most recent recomposition's value.
    val latestScale by rememberUpdatedState(scale)
    val latestOffset by rememberUpdatedState(offset)
    val latestOnScaleChange by rememberUpdatedState(onScaleChange)
    val latestOnOffsetChange by rememberUpdatedState(onOffsetChange)
    // `sourceEpoch` is folded into the page key so a viewer that failed
    // its first decrypt at epoch 0 (typed reference not yet loaded) re-keys
    // and retries when the real reference arrives.
    val pageKey = "$messageIdHex#$attachmentIndex#${reference.sourceEpoch}"
    var presentation by remember(pageKey) { mutableStateOf<DecodedAttachmentPresentation?>(null) }
    var viewerFailed by remember(pageKey) { mutableStateOf(false) }
    var viewerReloadToken by remember(pageKey) { mutableStateOf(0) }
    val imageWidth =
        when (val current = presentation) {
            is DecodedAttachmentPresentation.Static -> current.bitmap.width
            is DecodedAttachmentPresentation.Animated -> current.drawable.intrinsicWidth
            null -> 0
        }
    val imageHeight =
        when (val current = presentation) {
            is DecodedAttachmentPresentation.Static -> current.bitmap.height
            is DecodedAttachmentPresentation.Animated -> current.drawable.intrinsicHeight
            null -> 0
        }
    LaunchedEffect(pageKey, viewerReloadToken) {
        viewerFailed = false
        try {
            val data = attachmentBytes(controller, messageIdHex, attachmentIndex, reference, mine)
            val decoded =
                decodeMessageAttachmentImage(
                    bytes = data,
                    mediaType = reference.mediaType,
                    staticMaxEdgePx = MediaPipeline.VIEWER_MAX_EDGE_PX,
                )
            if (decoded != null) {
                presentation = decoded
            } else {
                viewerFailed = true
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            viewerFailed = true
        }
    }
    DisposableEffect(pageKey, presentation) {
        val owned = presentation
        onDispose {
            when (owned) {
                is DecodedAttachmentPresentation.Static -> owned.bitmap.recycle()
                is DecodedAttachmentPresentation.Animated ->
                    (owned.drawable as? android.graphics.drawable.AnimatedImageDrawable)?.stop()
                null -> Unit
            }
        }
    }

    val viewerGestureModifier =
        Modifier
            .fillMaxSize()
            .pointerInput(pageKey) {
                detectTapGestures(onDoubleTap = {
                    latestOnScaleChange(1f)
                    latestOnOffsetChange(Offset.Zero)
                })
            }.pointerInput(pageKey) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount =
                            event.changes.count { it.pressed }
                        if (pressedCount == 0) break
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val currentScale = latestScale
                        val currentOffset = latestOffset
                        val handleAsTransform =
                            pressedCount >= 2 || currentScale > 1f
                        if (!handleAsTransform) {
                            continue
                        }
                        val nextScale = (currentScale * zoom).coerceIn(1f, 5f)
                        if (nextScale != currentScale) latestOnScaleChange(nextScale)
                        if (nextScale > 1f) {
                            val viewportW = size.width.toFloat()
                            val viewportH = size.height.toFloat()
                            val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
                            val viewportAspect = viewportW / viewportH
                            val baseWidth: Float
                            val baseHeight: Float
                            if (imageAspect > viewportAspect) {
                                baseWidth = viewportW
                                baseHeight = viewportW / imageAspect
                            } else {
                                baseHeight = viewportH
                                baseWidth = viewportH * imageAspect
                            }
                            val maxX = ((baseWidth * nextScale) - viewportW).coerceAtLeast(0f) / 2f
                            val maxY = ((baseHeight * nextScale) - viewportH).coerceAtLeast(0f) / 2f
                            latestOnOffsetChange(
                                Offset(
                                    (currentOffset.x + pan.x).coerceIn(-maxX, maxX),
                                    (currentOffset.y + pan.y).coerceIn(-maxY, maxY),
                                ),
                            )
                        } else if (currentOffset != Offset.Zero) {
                            latestOnOffsetChange(Offset.Zero)
                        }
                        event.changes.forEach { it.consume() }
                    } while (true)
                }
            }.graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            )

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = presentation) {
            is DecodedAttachmentPresentation.Static ->
                Image(
                    bitmap = current.toImageBitmap(),
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.Fit,
                    modifier = viewerGestureModifier,
                )
            is DecodedAttachmentPresentation.Animated ->
                AnimatedDrawableAttachmentImage(
                    drawable = current.drawable,
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.Fit,
                    modifier = viewerGestureModifier,
                )
            null ->
                when {
                    viewerFailed ->
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.BrokenImage,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                stringResource(R.string.media_save_failed),
                                color = Color.White,
                            )
                            TextButton(onClick = { viewerReloadToken += 1 }) {
                                Text(stringResource(R.string.media_tap_to_retry), color = Color.White)
                            }
                        }
                    else ->
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Color.White,
                        )
                }
        }
    }
}

/**
 * Persist [bytes] to the device gallery (Pictures/White Noise). Returns success.
 * Uses the IS_PENDING dance so other apps never see a half-written entry, and
 * sanitizes the remote-supplied [fileName] to a basename.
 */
internal fun saveImageToGallery(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): Boolean {
    val resolver = context.contentResolver
    val values =
        android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, MediaPipeline.safeDisplayName(fileName))
            // Preserve the attachment's real MIME (a peer may send PNG/WebP/HEIC),
            // so gallery indexing matches the actual bytes.
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, mediaType.ifBlank { MediaPipeline.RECOMPRESSED_MIME })
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/White Noise")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
    val uri =
        resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
    return try {
        resolver.openOutputStream(uri).use { out ->
            if (out == null) throw java.io.IOException("null output stream")
            out.write(bytes)
        }
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (_: Throwable) {
        resolver.delete(uri, null, null) // don't leave a pending orphan
        false
    }
}

/** Persist a decrypted video to the public Movies/White Noise folder via the
 *  Video MediaStore so it shows up in the system gallery. Mirrors the image
 *  save flow's IS_PENDING dance. */
internal fun saveVideoToGallery(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): Boolean {
    val resolver = context.contentResolver
    val values =
        android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, MediaPipeline.safeDisplayName(fileName))
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, mediaType.ifBlank { "video/mp4" })
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/White Noise")
            put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
        }
    val uri =
        resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
    return try {
        resolver.openOutputStream(uri).use { out ->
            if (out == null) throw java.io.IOException("null output stream")
            out.write(bytes)
        }
        values.clear()
        values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (_: Throwable) {
        resolver.delete(uri, null, null)
        false
    }
}

/**
 * Share [bytes] via a FileProvider Uri using the system share sheet.
 *
 * Suspends because the temp-file write is multi-megabyte for any non-trivial
 * attachment; doing it on the main dispatcher would stall the UI for the
 * write. The `startActivity` call has to run on Main, so the I/O is hopped
 * to `Dispatchers.IO` and the chooser is fired back on Main.
 */
internal suspend fun shareImage(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
) {
    val uri =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
                // Unique temp keyed off a sanitized basename — avoids
                // collisions and path traversal from a remote-supplied
                // filename.
                val file = java.io.File.createTempFile("share_", "_" + MediaPipeline.safeDisplayName(fileName), dir)
                file.outputStream().use { it.write(bytes) }
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }.getOrNull()
        } ?: return
    runCatching {
        val intent =
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mediaType.ifBlank { MediaPipeline.RECOMPRESSED_MIME }
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(
            android.content.Intent.createChooser(intent, null).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

/** Create a cache file for a camera capture. Returns null if it can't be made. */
internal fun createImageCaptureFile(context: android.content.Context): java.io.File? =
    try {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        java.io.File.createTempFile("capture_", ".jpg", dir)
    } catch (_: Throwable) {
        null
    }

internal fun fileProviderUri(
    context: android.content.Context,
    file: java.io.File,
): android.net.Uri =
    androidx.core.content.FileProvider
        .getUriForFile(context, "${context.packageName}.fileprovider", file)

/**
 * Best-effort wipe of decrypted camera-capture temp files from cache.
 *
 * Intentionally does NOT touch `shared_media`. Those entries back live
 * FileProvider URIs the system may still be reading after the user backs
 * out of a chat (an external PDF reader holding the granted URI, the
 * system share-sheet target, etc.). Yanking the file out from under
 * those readers caused the "opened PDF goes blank when I leave the chat"
 * class of bug — the [sweepStaleSharedMedia] janitor cleans those on a
 * stale-age basis at app start instead.
 */
internal fun clearMediaTempFiles(context: android.content.Context) {
    runCatching { java.io.File(context.cacheDir, "camera").deleteRecursively() }
}

/**
 * Delete `shared_media` files older than [maxAgeMillis]. Called once at
 * app start so transient FileProvider temps for opened/shared
 * attachments don't accumulate across sessions, without racing the
 * external readers that may still be using them in the current session.
 */
internal fun sweepStaleSharedMedia(
    context: android.content.Context,
    maxAgeMillis: Long,
) {
    runCatching {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        // Same age-based reaper covers the decrypted voice cache too —
        // those bytes are plaintext E2EE-decrypted audio and shouldn't
        // linger past the last MediaPlayer that opened them.
        listOf(MediaCacheDirs.SHARED, ConversationTranscriptExport.CacheDirName, MediaCacheDirs.VOICE, MediaCacheDirs.VIDEO).forEach { name ->
            val dir = java.io.File(context.cacheDir, name)
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { entry ->
                if (entry.isFile && entry.lastModified() < cutoff) {
                    runCatching { entry.delete() }
                }
            }
        }
    }
}

/** Files in `shared_media` older than this are considered safe to delete —
 *  any external reader has had ample time to finish loading the bytes. */
private const val SHARED_MEDIA_MAX_AGE_MS: Long = 10L * 60L * 1000L

/** Decode a downscaled preview bitmap for a local content Uri, off-thread. */
@Composable
private fun rememberLocalPreviewBitmap(uri: android.net.Uri): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap =
            withContext(Dispatchers.Default) {
                val mime = safeGetType(context.contentResolver, uri)
                if (mime.startsWith("video/", ignoreCase = true)) {
                    // Video URI: extract the first frame as the staging thumbnail
                    // instead of trying to decode the bytes as JPEG (which spins
                    // forever on a video and leaves the sheet stuck). Scaled to
                    // the staging tile size — full-res posters from a 4K clip
                    // would be a ~33 MB ARGB bitmap per tile.
                    runCatching {
                        val mmr = android.media.MediaMetadataRetriever()
                        try {
                            mmr.setDataSource(context, uri)
                            val edge = MediaPipeline.THUMBNAIL_MAX_EDGE_PX
                            mmr
                                .getScaledFrameAtTime(
                                    0L,
                                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    edge,
                                    edge,
                                )?.asImageBitmap()
                        } finally {
                            runCatching { mmr.release() }
                        }
                    }.getOrNull()
                } else {
                    // Decode the picked image straight to a sampled bitmap,
                    // preserving its native format and alpha. Earlier this
                    // round-tripped through MediaPipeline.readDownscaledJpeg
                    // (recompress to JPEG) and then re-decoded those bytes at
                    // full resolution — that flattened transparent PNGs onto
                    // white and, on large lossless sources (e.g. PNG
                    // screenshots), the recompress or the un-sampled re-decode
                    // could silently OOM/fail, leaving the staging tile stuck
                    // on a spinner that never resolved (#387). Mirrors the
                    // in-bubble thumbnail path (decodeSampledBitmap).
                    runCatching {
                        MediaPipeline
                            .decodeSampledFromUri(
                                context.contentResolver,
                                uri,
                                MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
                            )?.asImageBitmap()
                    }.getOrNull()
                }
            }
    }
    return bitmap
}

@Composable
private fun LocalImagePreview(
    uri: android.net.Uri,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberLocalPreviewBitmap(uri)
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StagingTile(
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .aspectRatio(1f),
    ) {
        content()
        FilledIconButton(
            onClick = onRemove,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(40.dp),
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                ),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.media_attachment_remove),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun StagingDocumentTile(uri: android.net.Uri) {
    val context = LocalContext.current
    val displayName =
        remember(uri) { queryDisplayName(context.contentResolver, uri) ?: "file" }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaPreviewSheet(
    uris: List<android.net.Uri>,
    documentUris: List<android.net.Uri>,
    onDismiss: () -> Unit,
    onSend: (caption: String) -> Unit,
    onRemoveAt: (Int) -> Unit,
    onRemoveDocumentAt: (Int) -> Unit,
    onAddPhotos: () -> Unit,
    onAddDocuments: () -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    // Local guard against a rapid double-tap firing onSend twice before the
    // parent clears pendingMediaUris and the sheet leaves composition.
    var sending by remember { mutableStateOf(false) }
    var addMoreMenuOpen by remember { mutableStateOf(false) }
    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Horizontally-scrollable shelf of square tiles, one per staged
            // attachment plus a trailing "Add more" tile. Each tile carries a
            // small `✕` overlay that removes only that item from the queue.
            LazyRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 220.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(uris, key = { _, uri -> "image:$uri" }) { index, uri ->
                    StagingTile(
                        onRemove = { if (!sending) onRemoveAt(index) },
                    ) {
                        LocalImagePreview(
                            uri = uri,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
                itemsIndexed(documentUris, key = { _, uri -> "doc:$uri" }) { index, uri ->
                    StagingTile(
                        onRemove = { if (!sending) onRemoveDocumentAt(index) },
                    ) {
                        StagingDocumentTile(uri = uri)
                    }
                }
                item(key = "media_preview_add_more_tile") {
                    // Anchor a DropdownMenu to the tile so the user can add
                    // either kind to a mixed shelf — the tile alone can't
                    // know which (images vs files) the user wants to append.
                    Box {
                        OutlinedButton(
                            onClick = { if (!sending) addMoreMenuOpen = true },
                            modifier =
                                Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(0.dp),
                            enabled = !sending,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.media_attachment_add_more),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = addMoreMenuOpen,
                            onDismissRequest = { addMoreMenuOpen = false },
                            shape = MenuDefaults.shape,
                            border = amoledSurfaceBorderStroke(),
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_photo_library)) },
                                onClick = {
                                    addMoreMenuOpen = false
                                    onAddPhotos()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_document)) },
                                onClick = {
                                    addMoreMenuOpen = false
                                    onAddDocuments()
                                },
                            )
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.add_caption)) },
                    maxLines = 4,
                    enabled = !sending,
                )
                FilledIconButton(
                    onClick = {
                        if (sending) return@FilledIconButton
                        sending = true
                        onSend(caption)
                    },
                    enabled = !sending,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.send),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Read the user-visible filename a content Uri exposes via OpenableColumns,
 *  falling back to the Uri's path segment. Null when neither is available.
 *
 *  Guarded against a revoked grant: a Photo Picker / SAF Uri staged before
 *  process death (issue #531) comes back as a ghost whose session-scoped read
 *  permission is gone, so `query()` throws `SecurityException` (or the backing
 *  provider may be dead — `IllegalArgumentException` / `NullPointerException`).
 *  We swallow it and fall through to the path-segment fallback so the staging
 *  preview renders a placeholder name instead of crashing; the actual decode
 *  still fails gracefully into the existing toast path. */
internal fun queryDisplayName(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri,
): String? {
    runCatching {
        contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
    }
    return uri.lastPathSegment
}

/**
 * Best-effort byte size of a content Uri, queried via `OpenableColumns.SIZE`.
 * Returns -1 when the provider doesn't report a size (some virtual / streamed
 * providers omit it); callers must then enforce a cap via the bounded read.
 *
 * Also returns -1 when the Uri's grant has been revoked (a ghost Uri restored
 * after process death — see [queryDisplayName] / issue #531): the bounded read
 * downstream is itself `SecurityException`-guarded and will reject the file, so
 * treating a revoked grant as "size unknown" routes it into the same graceful
 * rejection rather than crashing the send coroutine.
 */
internal fun queryContentSize(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri,
): Long {
    runCatching {
        contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getLong(0)
                }
            }
    }
    return -1L
}

/** `ContentResolver.getType` for a content Uri whose read grant may have been
 *  revoked (a ghost staging Uri restored after process death — issue #531).
 *  The platform docs say `getType` can throw `SecurityException` for a Uri the
 *  caller can no longer access; an unguarded call on a ghost Uri crashes the
 *  preview composition or the send coroutine before the already-guarded decode
 *  gets a chance to degrade. Returns "" on any failure so callers treat the
 *  ghost as an unknown / non-video type and let the guarded decode reject it
 *  into the existing decode-failure toast. */
internal fun safeGetType(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri,
): String = coerceResolvedMime { contentResolver.getType(uri) }

/** Pure swallow-and-default kernel behind [safeGetType], split out so the
 *  ghost-Uri contract (issue #531) — a throwing or null resolver lookup must
 *  collapse to "" rather than propagate — is unit-testable on the JVM without
 *  Robolectric, mirroring the `UriListSaver` codec split. */
internal inline fun coerceResolvedMime(getType: () -> String?): String = runCatching(getType).getOrNull().orEmpty()

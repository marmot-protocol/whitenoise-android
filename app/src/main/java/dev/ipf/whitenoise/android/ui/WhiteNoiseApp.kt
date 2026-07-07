package dev.ipf.whitenoise.android.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.core.ConversationTranscriptExport
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import dev.ipf.whitenoise.android.core.LeaveAction
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.MessageDebugCategory
import dev.ipf.whitenoise.android.core.MessageDebugClassifier
import dev.ipf.whitenoise.android.core.MessageDebugStyle
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageSearch
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.core.RecentEmojiList
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.core.ReplySwipe
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.core.TimelineRowKind
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.core.replyMediaKindFromMime
import dev.ipf.whitenoise.android.core.timelineRowKind
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
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.MessageStatusLabels
import dev.ipf.whitenoise.android.state.OutgoingMessageIndicator
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.ReactionParticipant
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.countUnreadIncoming
import dev.ipf.whitenoise.android.state.formatExactTimestamp
import dev.ipf.whitenoise.android.state.labelFor
import dev.ipf.whitenoise.android.state.nextNavAccountRef
import dev.ipf.whitenoise.android.state.nextReadAnchor
import dev.ipf.whitenoise.android.state.outgoingIndicator
import dev.ipf.whitenoise.android.state.shortHex
import dev.ipf.whitenoise.android.state.shouldResetNavOnAccountChange
import dev.ipf.whitenoise.android.state.shouldShowOriginalTimestamp
import dev.ipf.whitenoise.android.state.unreadReceivedMentionIds
import dev.ipf.whitenoise.android.ui.chats.ChatsScreen
import dev.ipf.whitenoise.android.ui.chats.ConversationSearchNavBar
import dev.ipf.whitenoise.android.ui.chats.ConversationSearchTopBar
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.chats.newchat.FlowSearchField
import dev.ipf.whitenoise.android.ui.chats.newchat.SectionHeader
import dev.ipf.whitenoise.android.ui.chats.newchat.SelectionIndicator
import dev.ipf.whitenoise.android.ui.chats.newchat.canInviteFromEmptyGroup
import dev.ipf.whitenoise.android.ui.common.AppLockScreen
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.ErrorContent
import dev.ipf.whitenoise.android.ui.common.FailureScreen
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarBottomInset
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSnackbarHost
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.common.lifecycleOwner
import dev.ipf.whitenoise.android.ui.common.rememberConversationControllerCopy
import dev.ipf.whitenoise.android.ui.common.rememberGroupSystemCopy
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.common.rememberMessageTextCopy
import dev.ipf.whitenoise.android.ui.common.rememberedClockTime
import dev.ipf.whitenoise.android.ui.common.rememberedRelativeTime
import dev.ipf.whitenoise.android.ui.design.KeyboardPreservingDropdownMenu
import dev.ipf.whitenoise.android.ui.design.conversationMenuItemPadding
import dev.ipf.whitenoise.android.ui.group.GroupDetailsScreen
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingScreen
import dev.ipf.whitenoise.android.ui.profile.ProfileSheet
import dev.ipf.whitenoise.android.ui.settings.DiagnosticsScreen
import dev.ipf.whitenoise.android.ui.settings.SettingsScreen
import dev.ipf.whitenoise.android.ui.settings.WipeOutcomeSheet
import dev.ipf.whitenoise.android.ui.settings.WipeProgressSheet
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

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

/**
 * Whether the conversation top bar should render a members-count subtitle.
 *
 * The top bar must not show group copy until the initial roster has loaded, and
 * a just-created DM gets one extra grace state: while its nameless roster is
 * still 0/1 members, keep the DM presentation instead of flashing "Just you" or
 * "1 member" before the peer row hydrates. If that one-member DM state stalls,
 * keep it quiet for this open session and let a later reopen re-evaluate from
 * live roster state (#998).
 */
internal fun shouldShowConversationMembersSubtitle(
    membersLoaded: Boolean,
    openedAsDmHint: Boolean,
    groupName: String,
    memberCount: Int,
): Boolean =
    membersLoaded &&
        !GroupProjector.isDm(memberCount, groupName) &&
        !(openedAsDmHint && GroupProjector.isUnnamed(groupName) && memberCount < 2)

/**
 * The four things the conversation bottom bar can render for the membership
 * gate. Pulled out of Compose as a pure decision so [conversationComposerGate]
 * can be unit-tested and pinned against regression (issues #545, #623, and
 * #802).
 */
internal enum class ComposerGate {
    /** Active composer — self is (believed to be) a member. */
    COMPOSER,

    /** "You are no longer a member of this group" notice. */
    NOTICE,

    /** Pending invite preview — read-only transcript with explicit Join/Decline. */
    INVITE,

    /**
     * Membership is not yet known locally: render NOTHING this frame and wait
     * for `refreshMembers()` rather than flash a wrong state. See [PENDING] use
     * in [conversationComposerGate].
     */
    PENDING,
}

/**
 * Decide what the conversation bottom bar renders for the membership gate,
 * given only what is known synchronously at (and shortly after) first paint.
 *
 * Pending invites are a separate, explicit state: opening the conversation must
 * not auto-accept the MLS welcome and must not expose the live composer until
 * the user taps Join group (#802). For already-joined or left groups, the older
 * membership flash rules still apply:
 *
 * - Confirmed member (`isSelfMember`) → [ComposerGate.COMPOSER].
 * - Confirmed not-member (`membersLoaded && !isSelfMember`) → [ComposerGate.NOTICE].
 * - Still loading (`!membersLoaded`):
 *   - The seeding snapshot was present (`seededMembershipKnown`) → it is an
 *     authoritative local signal: self is in it (`seededSelfMember`) →
 *     [ComposerGate.COMPOSER] (warm member, no blank-bar flash, preserving the
 *     #264 intent); self was removed from it (the left group) →
 *     [ComposerGate.NOTICE] immediately (the #545 fix).
 *   - No seeding snapshot at all (genuinely cold open) → membership is unknown,
 *     so [ComposerGate.PENDING]: render neither the composer nor the notice
 *     until `refreshMembers()` confirms. This removes the #623 notice-flash for
 *     a member opening cold without reintroducing the #545 composer-flash for a
 *     left group.
 *
 * This drives only the INITIAL VISUAL state; it does not affect send-gating,
 * which stays guarded by `canSendMessages` / [canAcceptTextSend] (issue #264).
 */
internal fun conversationComposerGate(
    pendingInvite: Boolean,
    membersVerified: Boolean,
    isSelfMember: Boolean,
    seededSelfMember: Boolean,
    seededMembershipKnown: Boolean,
    assumeMemberUntilVerified: Boolean,
): ComposerGate =
    when {
        pendingInvite -> ComposerGate.INVITE
        isSelfMember -> ComposerGate.COMPOSER
        // Removed-member notice only once refreshMembers() has VERIFIED the
        // roster. An unverified roster that merely omits self — e.g. a stale or
        // cross-account snapshot right after tapping another account's
        // notification — must not flash the notice; wait instead.
        membersVerified -> ComposerGate.NOTICE
        seededMembershipKnown && seededSelfMember -> ComposerGate.COMPOSER
        // Opened from a message notification: receiving a message for a group
        // implies current membership, so show the composer immediately rather
        // than a placeholder while verification catches up. A genuine removal
        // still wins above via membersVerified once refreshMembers() confirms.
        assumeMemberUntilVerified -> ComposerGate.COMPOSER
        else -> ComposerGate.PENDING
    }

/**
 * Decide whether to restore composer focus (and thus re-raise the soft
 * keyboard) when the conversation returns to the foreground (issue #589).
 *
 * Case B of #589: switching away with the keyboard CLOSED and then returning
 * must NOT pop the keyboard open — Android/Compose otherwise restores the
 * `BasicTextField` focus and IME visibility on its own. We only re-request
 * focus on resume when the composer actually held focus when we were paused,
 * so the post-resume keyboard state matches the pre-switch state exactly.
 *
 * An active edit or reply session is treated as focus-owning even if the raw
 * focus flag briefly lagged behind on pause: those sessions deliberately raise
 * the keyboard (see the edit/reply focus effects), so returning to them with
 * the keyboard down would be just as surprising as Case A. The caller still
 * gates the actual `requestFocus()` on this predicate so the decision stays in
 * one pure, unit-tested place.
 */
internal fun shouldRestoreComposerFocusOnResume(
    wasComposerFocusedOnPause: Boolean,
    hasActiveEditOrReplySession: Boolean = false,
): Boolean = wasComposerFocusedOnPause || hasActiveEditOrReplySession

/**
 * Whether the resume observer should actively clear focus and hide the keyboard
 * (issue #589, Case B "keyboard was closed on leave").
 *
 * This is NOT the inverse of [shouldRestoreComposerFocusOnResume]. The clear/hide
 * branch uses the screen-wide [androidx.compose.ui.focus.FocusManager], so it must
 * not fire whenever *some other* text field legitimately owns focus and the
 * keyboard. In-chat search (#292) is exactly that case: while the search bar is
 * open the composer is not focused (so [shouldRestoreComposerFocusOnResume] is
 * false), but the search field holds focus and the IME is up on purpose. Clearing
 * focus here would drop the search field's focus and hide its keyboard, and
 * `LaunchedEffect(searchOpen)` would not re-fire on resume to restore it —
 * regressing the search UX after an app-switch.
 *
 * So: clear focus only when we are not restoring composer focus AND no other
 * text field (currently just in-chat search) owns the focus/IME.
 */
internal fun shouldClearFocusOnResume(
    restoringComposerFocus: Boolean,
    searchOpen: Boolean,
): Boolean = !restoringComposerFocus && !searchOpen

/** UI-only scroll anchor for a conversation the user left while reading history. */
internal data class ConversationScrollSnapshot(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val anchorItemId: String? = null,
    val anchorMessageIdHex: String? = null,
)

internal fun conversationScrollKey(
    accountRef: String?,
    groupIdHex: String,
): String = "${accountRef.orEmpty()}\u0000$groupIdHex"

/**
 * Snapshot to persist when leaving a conversation. Returns null when the reader
 * was at/near the bottom so the normal unread/newest anchor runs on re-entry.
 */
internal fun conversationScrollSnapshotOnLeave(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    nearBottom: Boolean,
    anchorItemId: String? = null,
    anchorMessageIdHex: String? = null,
): ConversationScrollSnapshot? =
    if (nearBottom) {
        null
    } else {
        ConversationScrollSnapshot(
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
            anchorItemId = anchorItemId,
            anchorMessageIdHex = anchorMessageIdHex,
        )
    }

internal fun conversationScrollRestoreListIndex(
    snapshot: ConversationScrollSnapshot,
    renderedItemIds: List<String>,
    renderedMessageIds: List<String> = emptyList(),
    olderHeaderCount: Int,
): Int {
    val anchorIndex =
        snapshot.anchorMessageIdHex
            ?.takeIf { it.isNotBlank() }
            ?.let(renderedMessageIds::indexOf)
            ?.takeIf { it >= 0 }
            ?: snapshot.anchorItemId
                ?.let(renderedItemIds::indexOf)
                ?.takeIf { it >= 0 }
            ?: -1
    return if (anchorIndex >= 0) {
        1 + olderHeaderCount + anchorIndex
    } else {
        snapshot.firstVisibleItemIndex
    }
}

/** Within this many items of the trailing edge counts as "at bottom". */
private const val ConversationNearBottomItemSlack = 3

// Maximum images per multi-pick. The Android Photo Picker enforces this
// cap on the system dialog side; 10 keeps the album payload bounded
// (10 * 1920px JPEG ≈ a few MB encrypted) without feeling artificially low.
// Approximate clearance the conversation composer occupies above the
// navigation bar. Used by ConversationScreen to push the global
// snackbar host above the composer so toasts don't intercept touches
// on the message input — see [LocalSnackbarBottomInset] + issue #122.
// 72.dp covers the single-line composer plus its vertical padding.
// Only a seed for the first frames: the bottom bar's measured height
// replaces it as soon as layout runs (#796), so multi-line composers,
// reply/edit banners, and the invite bar stay cleared exactly.
private val COMPOSER_SNACKBAR_INSET = 72.dp

private const val MEDIA_PICKER_MAX_ITEMS = 10

// Per-file ceiling for a document attachment. Matches the retained-uploads
// LRU cap so a single oversize pick can't OOM the picker pass before the
// retained store gets a chance to evict. Anything larger is dropped with a
// toast — the user can re-pick a smaller file or split the upload.
private const val MEDIA_ATTACHMENT_MAX_BYTES = ConversationController.MEDIA_RETAINED_MAX_BYTES

// Total bytes cap across one album send. Bound to the retained-uploads LRU
// cap (NOT independently doubled): exceeding that cap on insert would cause
// `ByteSizeLruCache` to evict the just-inserted RetainedMediaUpload during
// its own `put()` pass, breaking retry. Keep the picker ceiling honest with
// the actual heap budget rather than letting the user pick more than the
// controller can ever hold.
private const val MEDIA_ALBUM_MAX_TOTAL_BYTES = ConversationController.MEDIA_RETAINED_MAX_BYTES

private data class ImageAttachmentReadOutcome(
    val attachment: PendingAttachment?,
    val overflowed: Boolean = false,
)

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
private val NullableUriSaver: Saver<android.net.Uri?, String> =
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
private val NullableFileSaver: Saver<java.io.File?, String> =
    Saver(
        save = { it?.absolutePath ?: "" },
        restore = { s -> s.takeIf { it.isNotEmpty() }?.let { path -> java.io.File(path) } },
    )

// Persist a multi-pick selection across rotation / process death. Empty list
// encodes "no preview shown" so the parent re-render skips the sheet on
// restore. Uses '\n' as the separator — content URIs don't contain newlines.
private val UriListSaver: Saver<List<android.net.Uri>, String> =
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

private fun senderTitleForReply(
    senderPubkey: String,
    appState: WhiteNoiseAppState,
): String = appState.displayName(senderPubkey)

private fun isOwnReplySender(
    senderPubkey: String,
    appState: WhiteNoiseAppState,
): Boolean {
    val active = appState.activeAccount?.accountIdHex ?: return false
    return senderPubkey.equals(active, ignoreCase = true)
}

@Composable
internal fun ReplyPreviewCard(
    senderTitle: String,
    isOwn: Boolean,
    body: String,
    mediaKind: dev.ipf.whitenoise.android.core.ReplyMediaKind,
    onClick: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
    // The composer banner spans the input row, so it fills its width. The
    // in-bubble quote (#208) must instead hug its content: forcing
    // fillMaxWidth there expands the enclosing bubble Column to its max
    // width even when the quote and reply text are both short.
    fillWidth: Boolean = true,
    mentionDisplayName: ((String) -> String?)? = null,
) {
    val title = if (isOwn) stringResource(R.string.reply_you) else senderTitle
    val mediaLabel =
        when (mediaKind) {
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Photo -> stringResource(R.string.reply_media_photo)
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Video -> stringResource(R.string.reply_media_video)
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Voice -> stringResource(R.string.reply_media_voice)
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Document -> stringResource(R.string.reply_media_document)
            dev.ipf.whitenoise.android.core.ReplyMediaKind.None -> null
        }
    val mediaIcon =
        when (mediaKind) {
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Photo -> Icons.Default.Image
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Video -> Icons.Default.Movie
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Voice -> Icons.Default.Mic
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Document -> Icons.Default.Description
            dev.ipf.whitenoise.android.core.ReplyMediaKind.None -> null
        }
    // Media path shows a label; only the plaintext body carries raw profile
    // mention runs, so resolve them to match the bubble's rendering (#615/#1090).
    val bodyText =
        remember(body, mediaLabel, mentionDisplayName) {
            mediaLabel ?: resolveMentionsInPlaintext(body, mentionDisplayName)
        }
    val accent =
        if (isOwn) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.tertiary
        }
    val container = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f)
    Surface(
        color = container,
        shape = RoundedCornerShape(10.dp),
        border = amoledSurfaceBorderStroke(),
        modifier =
            Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(accent),
            )
            Row(
                // weight(1f) forces this Row to fill the parent's width, which
                // re-expands the bubble (#208). Only claim it when the card is
                // meant to span its container (composer banner). When hugging,
                // wrap to content so a short quote keeps the bubble narrow.
                modifier =
                    (if (fillWidth) Modifier.weight(1f) else Modifier)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = if (fillWidth) Modifier.weight(1f) else Modifier) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (mediaIcon != null) {
                            Icon(
                                mediaIcon,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            bodyText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel_reply),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaImageBubble(
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
private fun MediaVisualGridBubble(
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
private fun MediaFileBubble(
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
private fun MediaVideoBubble(
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
private fun MediaVoiceBubble(
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
private fun MediaPendingPlaceholder(
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
private fun createImageCaptureFile(context: android.content.Context): java.io.File? =
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
private fun clearMediaTempFiles(context: android.content.Context) {
    runCatching { java.io.File(context.cacheDir, "camera").deleteRecursively() }
}

/**
 * Delete `shared_media` files older than [maxAgeMillis]. Called once at
 * app start so transient FileProvider temps for opened/shared
 * attachments don't accumulate across sessions, without racing the
 * external readers that may still be using them in the current session.
 */
private fun sweepStaleSharedMedia(
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
private fun MediaPreviewSheet(
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

@Composable
private fun UnreadMessagesDivider(count: Int) {
    val text = pluralStringResource(R.plurals.unread_messages_count, count, count)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .padding(horizontal = 12.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

private fun differentDay(
    a: ULong,
    b: ULong,
): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochSecond(a.toLong()).atZone(zone).toLocalDate() !=
        Instant.ofEpochSecond(b.toLong()).atZone(zone).toLocalDate()
}

// Today/Yesterday, then weekday within a week, then a locale-medium date —
// all sourced from the platform so the ribbon needs no new translation keys.
private fun messageDayLabel(
    epochSeconds: ULong,
    locale: Locale,
): String {
    if (epochSeconds == 0uL) return ""
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochSecond(epochSeconds.toLong()).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(date, LocalDate.now(zone))
    return when {
        days <= 0L || days == 1L ->
            DateUtils
                .getRelativeTimeSpanString(
                    epochSeconds.toLong() * 1000L,
                    System.currentTimeMillis(),
                    DateUtils.DAY_IN_MILLIS,
                ).toString()
        days in 2L..6L -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale)
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
}

@Composable
private fun DaySeparator(label: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/**
 * Centered one-line row for a kind-1210 group system event ("%s changed the
 * group avatar", membership changes, renames). Rendered from `system_type` +
 * `data` with display names resolved live — [WhiteNoiseAppState.displayName]
 * reads the profile revision, so the row re-renders when a name loads. An
 * unparseable payload renders the generic fallback, never the raw content.
 */
@Composable
private fun GroupSystemRow(
    record: AppMessageRecordFfi,
    appState: WhiteNoiseAppState,
    groupSystem: GroupSystemEventFfi? = null,
) {
    val copy = rememberGroupSystemCopy()
    val event =
        remember(record.plaintext, groupSystem) {
            GroupSystemEvents.resolve(record.plaintext, groupSystem)
        }
    // Localized new-window label for the disappearing-timer "set to …" rows; null
    // when the event isn't a timer-on change (off/other rows need no duration).
    val retentionLabel = event?.newRetentionSeconds?.takeIf { it > 0uL }?.let { disappearingMessagesLabel(it.toLong()) }
    val summary =
        if (event != null) {
            run {
                val selfHex = appState.activeAccount?.accountIdHex
                val actorHex = GroupSystemEvents.actorHex(event, record.sender)
                GroupSystemEvents.summary(
                    event = event,
                    actorName = actorHex?.let { appState.displayName(it) },
                    subjectName = event.subject?.let { appState.displayName(it) },
                    actorIsSelf = GroupSystemEvents.isSelf(selfHex, actorHex),
                    subjectIsSelf = GroupSystemEvents.isSelf(selfHex, event.subject),
                    retentionLabel = retentionLabel,
                    copy = copy,
                )
            }
        } else {
            copy.fallback
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        // Developer-mode only: keep the one-line summary as the default and tuck
        // the MLS commit dump behind a per-row tap (#857). Saveable row-keyed UI
        // state lets an expanded row survive lazy-list disposal without leaking to others.
        if (appState.streamingDebugEnabled) {
            var detailsExpanded by rememberSaveable(record.messageIdHex) { mutableStateOf(false) }
            val debugStyle = remember(record) { MessageDebugClassifier.debugStyle(record) }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { detailsExpanded = !detailsExpanded }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        stringResource(
                            if (detailsExpanded) {
                                R.string.group_system_hide_details
                            } else {
                                R.string.group_system_show_details
                            },
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (detailsExpanded) {
                Spacer(Modifier.height(4.dp))
                MessageDebugRow(style = debugStyle, record = record)
            }
        }
    }
}

// Category -> accent color for the streaming-debug row. Kept out of the
// Compose-free MessageDebugStyle classifier and resolved here so the
// pure-Kotlin classifier stays JVM-testable. Literal hues stay legible across
// the light/dark/amoled themes the app ships.
private fun MessageDebugCategory.accentColor(): Color =
    when (this) {
        MessageDebugCategory.UserVisible -> Color(0xFF2E7D32)
        MessageDebugCategory.StreamSignaling -> Color(0xFFEF6C00)
        MessageDebugCategory.AgentChrome -> Color(0xFF7B1FA2)
        MessageDebugCategory.GroupSystem -> Color(0xFF607D8B)
        MessageDebugCategory.Control -> Color(0xFFB28900)
        MessageDebugCategory.Unknown -> Color(0xFFC62828)
    }

/**
 * Inline streaming-debug row. Renders a non-user-visible signaling record
 * (agent-stream-start, reaction, delete, group-system, unknown) with debug
 * chrome: a category-accented header (category label + kind label), the
 * kind-specific detail, and a multi-line tag summary, all in a small monospace
 * caption. Display-only — wires NO reply/long-press gestures and does no
 * read-marking. Only rendered when [WhiteNoiseAppState.streamingDebugEnabled]
 * is true.
 */
@Composable
private fun MessageDebugRow(
    style: MessageDebugStyle,
    record: AppMessageRecordFfi,
) {
    val accent = style.category.accentColor()
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        color = accent.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                    ).border(
                        width = 1.dp,
                        color = accent.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                    ).padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            val idSuffix =
                record.messageIdHex
                    .takeIf { it.isNotBlank() }
                    ?.take(8)
                    ?.let { " · $it" } ?: ""
            Text(
                text = "${style.category.label} · ${style.kindLabel}$idSuffix",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = accent,
            )
            if (style.detailText.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = style.detailText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = style.tagsSummary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Inline row for one live QUIC agent-stream update shown during streaming
 * debug. Surfaces the chunk / status / progress / record / finished / failed
 * events the conversation otherwise drops. Display-only: wires no gestures and
 * does no read-marking. Only rendered for synthetic `dbg:stream:` timeline
 * rows, which exist only while [WhiteNoiseAppState.streamingDebugEnabled] is
 * true.
 */
@Composable
private fun StreamDebugEventRow(record: AppMessageRecordFfi) {
    val accent = MessageDebugCategory.StreamSignaling.accentColor()
    val eventKind =
        record.tags
            .firstOrNull { it.values.firstOrNull() == "dbg" }
            ?.values
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: "event"
    val streamId = MessageProjector.streamId(record).orEmpty()
    val detail = record.plaintext
    val tagsSummary =
        record.tags
            .joinToString(" · ") { tag -> tag.values.joinToString(" ") }
            .ifBlank { "tags: (none)" }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        color = accent.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                    ).border(
                        width = 1.dp,
                        color = accent.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                    ).padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = "QUIC · $eventKind · ${MessageDebugCategory.StreamSignaling.label}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = accent,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "stream ${shortStreamId(streamId)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = tagsSummary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Abbreviate a long stream id to head…tail, leaving short ids untouched so
// they stay copy-comparable.
private fun shortStreamId(streamId: String): String {
    if (streamId.length <= 16) return streamId.ifBlank { "(none)" }
    return "${streamId.take(8)}…${streamId.takeLast(8)}"
}

// How many rows from the top to begin prefetching the next older page.
private const val OLDER_PAGE_PREFETCH_ROWS = 4

/**
 * Shared definition of "user is at (or near) the newest message". Used both
 * by the auto-scroll LaunchedEffect (issue #59) and the jump-to-newest FAB
 * so they can't disagree on the threshold.
 */
private fun isNearBottom(
    listState: androidx.compose.foundation.lazy.LazyListState,
    timelineSize: Int,
    hasOlderHeader: Boolean,
): Boolean {
    if (!listState.canScrollForward) return true
    val olderHeaderCount = if (hasOlderHeader) 1 else 0
    val bottomTimelineIndex = timelineSize + 1 + olderHeaderCount
    // Check the LAST visible item, not the first — keeps "near bottom"
    // truthful when the viewport shrinks (e.g. keyboard open) and fewer
    // items fit, which pushes firstVisibleItemIndex earlier even though
    // the bottom is still on-screen.
    val lastVisible =
        listState.layoutInfo.visibleItemsInfo
            .lastOrNull()
            ?.index ?: return false
    return lastVisible >= bottomTimelineIndex - 1
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
private fun queryDisplayName(
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
private fun queryContentSize(
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
private fun safeGetType(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri,
): String = coerceResolvedMime { contentResolver.getType(uri) }

/** Pure swallow-and-default kernel behind [safeGetType], split out so the
 *  ghost-Uri contract (issue #531) — a throwing or null resolver lookup must
 *  collapse to "" rather than propagate — is unit-testable on the JVM without
 *  Robolectric, mirroring the `UriListSaver` codec split. */
internal inline fun coerceResolvedMime(getType: () -> String?): String = runCatching(getType).getOrNull().orEmpty()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationScreen(
    appState: WhiteNoiseAppState,
    chat: ChatListItem,
    onBack: () -> Unit,
    // When opened from a chat-list message-body search hit (issue #290), the
    // matched message id to scroll to and briefly highlight once the timeline
    // has paged it in. Null for every normal open path.
    focusMessageId: String? = null,
    // Whether [focusMessageId] also gets the brief highlight flash. Search hits
    // do; a notification tap scrolls without the color flash.
    highlightFocusMessage: Boolean = true,
    // True when opened by tapping a message notification: receiving a message
    // implies current membership, so the composer shows immediately rather than
    // a placeholder while membership verification catches up after the switch.
    openedFromNotification: Boolean = false,
    // True only when this conversation was just created in the same navigation
    // step (issue #321) — drives a one-shot composer focus + keyboard raise so
    // the user can type the first message without an extra tap. False for row
    // taps, notification routing, and search hits.
    justCreated: Boolean = false,
    // True only when the opener knows this conversation is a newly-created DM.
    // The live roster can briefly report 0/1 members before the peer arrives;
    // keep that transient state in the DM presentation instead of falling into
    // the group subtitle branch (#998).
    openedAsDmHint: Boolean = false,
    // (chat, justCreated): navigate to another shared group when the user taps a
    // shared group / Message in the in-conversation profile sheet (issue #635).
    // Reuses the shell's existing open-group lambda so this path matches the
    // shell-level sheet's onOpenGroup exactly.
    onOpenProfileGroup: (ChatListItem, Boolean) -> Unit = { _, _ -> },
    // Scroll position captured when the user last left this chat while reading
    // history (issue #1107). Null when none was saved or they left near-bottom.
    restoredScrollSnapshot: ConversationScrollSnapshot? = null,
    onSaveScrollSnapshot: (ConversationScrollSnapshot?) -> Unit = {},
) {
    WindowSecureFlag(enabled = !appState.allowChatScreenshotsInChats)
    // Push the global snackbar host above the conversation composer so
    // a toast (e.g. the post-invite-accept confirmation) doesn't
    // overlap and intercept touches on the message input. Resets to
    // zero on dispose so other surfaces aren't affected. Issue #122.
    val snackbarBottomInset = LocalSnackbarBottomInset.current
    // Keyed on chat.id so that a back-to-back conversation push (Compose
    // reusing the same node across nav) re-runs the effect: the
    // previous chat's onDispose may not have fired before the next
    // enters, leaving the inset at zero on a stale snackbar host.
    // Seeds the resting-composer estimate; the bottom bar's measured
    // height takes over on first layout (#796).
    DisposableEffect(chat.id) {
        snackbarBottomInset.value = COMPOSER_SNACKBAR_INSET
        onDispose { snackbarBottomInset.value = 0.dp }
    }
    val controllerCopy = rememberConversationControllerCopy()
    val controller =
        // Key on the active account too: chat.id is the groupIdHex, which is
        // shared across local accounts that belong to the same group. Without
        // the account in the key, switching accounts into the same conversation
        // (e.g. tapping another account's notification) reuses a controller
        // still bound to the previous account's timeline and read state.
        remember(chat.id, appState.activeAccountRef, appState.runtimeGeneration) {
            ConversationController(
                appState = appState,
                initialGroup = chat.group,
                initialMemberSnapshot =
                    chat.memberSnapshot
                        ?: appState.cachedGroupMemberSnapshot(appState.activeAccountRef, chat.group.groupIdHex),
                initialLastReadMessageId = chat.projection?.lastReadMessageIdHex,
                initialLastReadTimelineAt = chat.projection?.lastReadTimelineAt,
                copy = controllerCopy,
            )
        }
    // When the developer streaming-debug toggle flips, re-publish the timeline.
    // Turning it off drops the transient QUIC debug rows so they don't linger.
    LaunchedEffect(controller, appState.streamingDebugEnabled) {
        controller.refreshStreamingDebugPresentation()
    }
    var menuOpen by remember { mutableStateOf(false) }
    // Keyed on chat.id so opening a different conversation (e.g. via Message or
    // a shared-group tap from a profile opened on this group's details page)
    // lands on the conversation, not the new chat's details page.
    var showDetails by remember(chat.id) { mutableStateOf(false) }
    // Notification suppression must follow the visible *timeline*, not merely an
    // open chat. While group details/settings (and its sub-screens) are up, the
    // user can't see incoming messages, so those must notify — lift the
    // active-conversation suppression for the group while details are showing
    // and restore it on return to the timeline.
    LaunchedEffect(chat.group.groupIdHex, showDetails) {
        appState.setActiveConversation(if (showDetails) null else chat.group.groupIdHex)
    }
    var pendingTopBarLeaveAction by remember { mutableStateOf<LeaveAction?>(null) }
    // Sole-admin Leave gate: a sole admin with other members can't leave until
    // they hand admin to someone else. Instead of the old toast-only dead end,
    // the Leave action surfaces a "Transfer admin first" dialog that routes
    // into the group details transfer picker (#417, adversarial review).
    var showTransferAdminFirst by remember { mutableStateOf(false) }
    // Set when the user opts to transfer from that dialog: opens details with
    // the transfer picker auto-expanded. Keyed on chat.id so it doesn't leak
    // across a conversation switch.
    var openTransferOnDetails by remember(chat.id) { mutableStateOf(false) }
    // Empty newly-created groups should route users into the existing member
    // invite flow instead of carrying a duplicate picker in the create sheet.
    var openAddMemberOnDetails by remember(chat.id) { mutableStateOf(false) }
    // Re-open after back-to-list should land where the reader left off, unless
    // this open path owns the anchor (search hit, just-created) or they left
    // at/near the bottom — then the existing unread/newest anchor runs.
    val scrollRestore =
        restoredScrollSnapshot?.takeIf {
            focusMessageId == null &&
                !justCreated
        }
    val positionalScrollRestore =
        scrollRestore?.takeIf {
            it.anchorItemId.isNullOrBlank() && it.anchorMessageIdHex.isNullOrBlank()
        }
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = positionalScrollRestore?.firstVisibleItemIndex ?: 0,
            initialFirstVisibleItemScrollOffset = positionalScrollRestore?.firstVisibleItemScrollOffset ?: 0,
        )
    // Single conversation-level owner of which message's action menu is open, so
    // only one popover can be open at a time. With the keyboard up the menu is
    // non-focusable (#284), so long-pressing several bubbles would otherwise
    // stack several popovers; deriving each bubble's open state from this one id
    // makes opening one close any other.
    var openActionMenuId by remember(chat.id) { mutableStateOf<String?>(null) }
    var initialTimelineAnchored by remember(chat.id) { mutableStateOf(false) }
    // Id of the newest row the bottom-follow has reacted to. A real append
    // gives a new last id while the previous one stays in the list; an
    // older-page load trims the newest rows, so the previous id is gone and
    // no follow fires. Keyed on id (not recordedAt) to survive same-second tails.
    var lastFollowedLatestId by remember(chat.id) { mutableStateOf<String?>(null) }
    var initialTimelineLoadStarted by remember(chat.id) { mutableStateOf(false) }
    var highlightedMessageId by remember(chat.id) { mutableStateOf<String?>(null) }
    var navigateReplyJob by remember(chat.id) { mutableStateOf<Job?>(null) }
    // UI-only row-height cache for exact centered scrolls. LazyColumn can only
    // measure a target after it has been composed; keeping the measured height
    // by message id lets future off-screen jumps animate straight to the exact
    // centered offset, while never becoming protocol/data source-of-truth state.
    val timelineItemHeightsPx = remember(chat.id) { mutableStateMapOf<String, Int>() }
    // In-chat search (#292). Opening from the overflow menu swaps the top
    // bar into an inline search field; closing it restores the normal bar.
    // `searchPinnedMatchId` keeps the active match anchored to a concrete
    // message id so the N/M cursor follows that message as older pages load
    // and the match set grows. `searchJob` serializes scroll-jump coroutines
    // the same way `navigateReplyJob` does for reply navigation.
    var searchOpen by remember(chat.id) { mutableStateOf(false) }
    var searchQuery by remember(chat.id) { mutableStateOf("") }
    var searchPinnedMatchId by remember(chat.id) { mutableStateOf<String?>(null) }
    var searchJob by remember(chat.id) { mutableStateOf<Job?>(null) }
    // Scroll anchor captured the moment search opens, restored verbatim on
    // close so leaving search returns the list to where the reader was —
    // #292 requires "Closing search restores the normal top bar and scroll
    // position." Pair = (firstVisibleItemIndex, firstVisibleItemScrollOffset).
    var preSearchScrollAnchor by remember(chat.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    val searchFocusRequester = remember { FocusRequester() }
    // Jump-to-newest plumbing.
    //
    // Badge = incoming messages newer than the highest-index timeline row the
    // user has ever had on screen during this composition. The high-water
    // mark only INCREASES, so scrolling back up past read messages doesn't
    // resurrect the badge.
    //
    //   HWM advances when the viewport reaches a new highest-visible row.
    //   New incoming arrivals (which extend the timeline beyond HWM) bump
    //   the badge. On chat re-entry, the auto-scroll's snap to the bottom
    //   immediately advances HWM to the last timeline index, so the badge
    //   shows 0 — matching the convention that an "open chat" is read up to
    //   the visible row, not the last delivered row.
    // Size of the rendered (edit-filtered) list. Derived once per timeline
    // change and shared by every position calculation, so the filter never
    // re-runs on the scroll path. Read as a State, so the derived blocks below
    // stay reactive to it.
    val renderedSize by remember {
        derivedStateOf { controller.timeline.count { !MessageProjector.isEdit(it.record) } }
    }
    val nearBottom by remember {
        derivedStateOf {
            // Match the rendered list size, otherwise bottomTimelineIndex
            // overshoots and nearBottom stays false at the physical bottom.
            isNearBottom(listState, renderedSize, controller.hasMoreBefore || controller.isLoadingOlder)
        }
    }
    // Read anchor stored as the message id of the deepest row the user has
    // settled on. Looked up live each time so load-older prepends shift both
    // the candidate and the anchor by the same offset — position comparisons
    // stay valid. Anchored on id (not recordedAt) to survive same-second
    // collisions: send() stamps with nowSeconds(), so multiple messages can
    // share a recordedAt and a strict-`>` filter would under-count.
    var readAnchorMessageId by remember(chat.id) { mutableStateOf<String?>(null) }
    val currentHighestVisibleTimelineIndex by remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf -1
            val olderHeader = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
            // LazyColumn layout: [Spacer][maybe older-loading][timeline items][Spacer]
            val firstTimelineListIndex = 1 + olderHeader
            // Visible rows index into the rendered (edit-filtered) list, so clamp
            // to its last index, not the longer unfiltered timeline's.
            (visible.last().index - firstTimelineListIndex)
                .coerceAtMost(renderedSize - 1)
        }
    }
    LaunchedEffect(currentHighestVisibleTimelineIndex) {
        val idx = currentHighestVisibleTimelineIndex
        if (idx < 0) return@LaunchedEffect
        // Monotonic advance only — scroll-up keeps the existing anchor so the
        // read pointer never moves backwards. See [nextReadAnchor]. Resolve the
        // visible row against the filtered (rendered) list it indexes into, not
        // the unfiltered timeline.
        val rendered = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        readAnchorMessageId = nextReadAnchor(rendered, readAnchorMessageId, idx)
    }
    DisposableEffect(chat.id) {
        onDispose {
            val rendered = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
            val hasOlderHeader = controller.hasMoreBefore || controller.isLoadingOlder
            val firstTimelineIndex =
                listState.firstVisibleItemIndex - 1 - (if (hasOlderHeader) 1 else 0)
            val anchor = rendered.getOrNull(firstTimelineIndex)
            onSaveScrollSnapshot(
                conversationScrollSnapshotOnLeave(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    nearBottom =
                        isNearBottom(
                            listState,
                            rendered.size,
                            hasOlderHeader,
                        ),
                    anchorItemId = anchor?.id,
                    anchorMessageIdHex = anchor?.record?.messageIdHex,
                ),
            )
        }
    }
    val unreadIncomingCount by remember {
        derivedStateOf {
            if (!initialTimelineAnchored) {
                0
            } else {
                countUnreadIncoming(controller.timeline, readAnchorMessageId)
            }
        }
    }
    // Unread messages (after the read anchor) that mention the active account,
    // oldest first — drives the in-conversation jump-to-mention chip. Mirrors
    // countUnreadIncoming's anchor logic; kind-9 only, matching the engine's
    // mention classification, reusing the #414 per-message detection.
    val selfAccountIdHexForMentions = appState.activeAccount?.accountIdHex
    val unreadMentionMessageIds by remember {
        derivedStateOf {
            // Anchor on the UI read high-water mark. It advances immediately when
            // the user visits a mention and when the visible row settles, so a
            // recreated controller cannot briefly resurrect already-read mentions.
            if (!initialTimelineAnchored || selfAccountIdHexForMentions.isNullOrBlank() || readAnchorMessageId == null) {
                emptyList()
            } else {
                unreadReceivedMentionIds(controller.timeline, readAnchorMessageId) { msg ->
                    documentMentionsAccount(
                        document = msg.record.contentTokens,
                        accountIdHex = selfAccountIdHexForMentions,
                        resolveAccountIdHex = { bech32 -> appState.accountIdHexForMention(bech32) },
                    )
                }
            }
        }
    }
    // Reading the raw IME inset in the body would re-subscribe and recompose
    // this (very heavy) screen on every keyboard-animation frame. Capture the
    // ime WindowInsets (the @Composable read) once, then collapse to the
    // boolean edge inside derivedStateOf so only the open/close transition
    // triggers a recomposition. getBottom() reads the inset's snapshot state
    // inside the derived block. See #374.
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val imeIsOpen by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    // #589: composer focus is hoisted here so the resume lifecycle observer
    // below can drive it. `composerFocus` is the requester wired into the
    // composer's BasicTextField; `composerFocused` mirrors the live focus
    // edge reported by `onFocusChanged`; `wasComposerFocusedOnPause` snapshots
    // that edge on ON_PAUSE so resume can restore the exact keyboard state the
    // user left with (Case B). Keyed on chat.id so a conversation switch
    // doesn't carry the previous chat's keyboard state across.
    val composerFocus = remember(chat.id) { FocusRequester() }
    var composerFocused by remember(chat.id) { mutableStateOf(false) }
    var wasComposerFocusedOnPause by remember(chat.id) { mutableStateOf(false) }
    // #589: used by the resume observer to clear focus and drop the keyboard
    // when the composer was NOT focused on pause (Case B), without poking the
    // composer's own focus requester.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val groupTitleCopy = rememberGroupTitleCopy()
    val messageTextCopy = rememberMessageTextCopy()
    // Seeded empty and populated off the Main thread: the first access to a
    // SharedPreferences file blocks on disk, and doing that inside composition
    // stalls the conversation screen's first frame. See #147.
    var recentReactionEmojis by remember(context) {
        mutableStateOf(emptyList<String>())
    }
    var quickReactionEmojis by remember(context) {
        mutableStateOf(RecentEmojiList.DefaultQuickChoices)
    }
    var quickReactionEmojisTouched by remember(context) { mutableStateOf(false) }
    LaunchedEffect(context) {
        val (loaded, quick) =
            withContext(Dispatchers.IO) {
                RecentEmojiPreferences.load(context) to RecentEmojiPreferences.loadQuickReactions(context)
            }
        // A pick made before this load lands has already merged the disk list
        // (recordPicked re-reads prefs), so a non-empty state is strictly newer
        // — don't clobber it with the stale read.
        if (recentReactionEmojis.isEmpty()) {
            recentReactionEmojis = loaded
        }
        if (!quickReactionEmojisTouched) {
            quickReactionEmojis = quick
        }
    }
    // Serialize the recents read-modify-write so rapid taps don't lose updates
    // (concurrent recordPicked() is last-writer-wins on the disk list). See #172.
    val recentEmojiWriteMutex = remember { Mutex() }
    // Selected-but-not-yet-sent image attachments. The preview sheet opens
    // when this or `pendingDocumentUris` is non-empty; the whole queue
    // ships as one kind:9 album via `controller.sendAttachments(list, caption)`.
    //
    // Persist the staging shelf across process death (issue #531): capturing
    // in landscape foregrounds the external camera app, which on low/medium
    // memory devices gets the backgrounded host process killed. On return the
    // activity is recreated and a plain `remember` would have wiped the shelf,
    // dropping the just-captured image even though `cameraOutputUri` survived.
    // The camera capture is a FileProvider URI over an app-owned cache file,
    // so it re-opens fine post-restore. Photo Picker / GET_CONTENT / document
    // URIs carry session-scoped read grants that DON'T survive process death;
    // if such a URI was staged when the process died it returns as a ghost
    // that fails to open — that degrades gracefully through the existing
    // decode-failure toast path in `sendStagedAttachments`, which is a better
    // outcome than silently losing the camera capture the user just accepted.
    // Keyed on chat.id so a conversation switch still flushes the staging
    // shelf — ConversationScreen is reused when `selectedChat` changes in
    // place, and an unkeyed state would otherwise carry URIs from chat A into
    // chat B (where a Send would attach them to the wrong recipient).
    var pendingMediaUris by rememberSaveable(chat.id, stateSaver = UriListSaver) {
        mutableStateOf<List<android.net.Uri>>(emptyList())
    }
    var pendingDocumentUris by rememberSaveable(chat.id, stateSaver = UriListSaver) {
        mutableStateOf<List<android.net.Uri>>(emptyList())
    }
    // Survives process death while the camera app is foreground (the result
    // callback fires into a recreated activity, otherwise the capture is lost).
    var cameraOutputUri by rememberSaveable(stateSaver = NullableUriSaver) {
        mutableStateOf<android.net.Uri?>(null)
    }
    // Survives process death alongside `cameraOutputUri` so a capture
    // cancelled after a death-and-restore can still delete the empty temp
    // file instead of leaking it (issue #531).
    var cameraOutputFile by rememberSaveable(stateSaver = NullableFileSaver) {
        mutableStateOf<java.io.File?>(null)
    }

    // PickMultipleVisualMedia uses the system Photo Picker — no READ_MEDIA_IMAGES
    // permission needed (Android 13+ scopes the picker's own grant); on older
    // devices it falls back to GET_CONTENT with the same UX. The maxItems
    // cap comes from MEDIA_PICKER_MAX_ITEMS; picking a single image still works
    // (returns a one-element list).
    val imagePickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(maxItems = MEDIA_PICKER_MAX_ITEMS),
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            // Append rather than replace so a follow-up "Add more" tile-pick
            // grows the staging shelf instead of clobbering whatever the user
            // already queued. Dedupe on Uri identity to keep a double-pick
            // from doubling the row, and cap on MEDIA_PICKER_MAX_ITEMS so the
            // shelf can't exceed what a fresh pick would have been allowed.
            val merged = (pendingMediaUris + uris).distinct().take(MEDIA_PICKER_MAX_ITEMS)
            pendingMediaUris = merged
        }
    val cameraLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success ->
            val captured = cameraOutputUri
            if (success && captured != null) {
                // Append to whatever's already queued so an in-progress staging
                // shelf survives a camera capture.
                val merged = (pendingMediaUris + captured).distinct().take(MEDIA_PICKER_MAX_ITEMS)
                pendingMediaUris = merged
            } else {
                cameraOutputFile?.delete() // cancelled — don't leak the empty temp
            }
            cameraOutputUri = null
            cameraOutputFile = null
        }

    fun launchCameraCapture() {
        val file = createImageCaptureFile(context)
        if (file == null) {
            appState.present(R.string.toast_couldnt_decode_image, copyable = true)
            return
        }
        cameraOutputFile = file
        val uri = fileProviderUri(context, file)
        cameraOutputUri = uri
        cameraLauncher.launch(uri)
    }

    // TakePicture needs no permission of its own, but because CAMERA is declared
    // in the manifest (for the QR scanner) some OEMs require the runtime grant
    // before launching the capture intent — request it first if missing.
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> if (granted) launchCameraCapture() }

    // Voice-message recording surface — owned per ConversationScreen so a
    // backgrounded recording is dropped on dispose. The recorder writes
    // into a per-session temp dir; the file is consumed by `sendVoiceMessage`
    // below and then removed.
    val voiceOutputDir =
        remember(context) {
            java.io.File(context.cacheDir, "voice-recordings").apply { mkdirs() }
        }
    val micPermissionDeniedMsg = stringResource(R.string.voice_message_permission_denied)
    val voiceTooShortMsg = stringResource(R.string.voice_message_too_short)
    var voiceMicPermissionRequested by remember { mutableStateOf(false) }
    val voiceMicPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> if (!granted) appState.present(micPermissionDeniedMsg) }

    fun sendVoiceAttachment(
        file: java.io.File,
        durationMs: Long,
    ) {
        appState.launchMutation {
            val bytes =
                withContext(Dispatchers.IO) {
                    runCatching { file.readBytes() }.getOrNull()
                }
            withContext(Dispatchers.IO) { runCatching { file.delete() } }
            if (bytes == null || bytes.isEmpty()) return@launchMutation
            val attachment =
                PendingAttachment(
                    plaintextBytes = bytes,
                    mediaType = dev.ipf.whitenoise.android.audio.VoiceRecorder.MIME_TYPE,
                    fileName = "voice-${durationMs}ms.${dev.ipf.whitenoise.android.audio.VoiceRecorder.FILE_EXTENSION}",
                )
            val seeded = controller.queueAttachments(listOf(attachment), null) ?: return@launchMutation
            scope.launch {
                val target = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                listState.animateScrollToItem(target)
            }
            controller.uploadQueued(seeded)
        }
    }

    fun readImageAttachment(
        uri: android.net.Uri,
        remainingBytes: Long,
    ): ImageAttachmentReadOutcome {
        val quality = appState.mediaQuality
        // Animated images (GIF, animated WebP) can't survive the static JPEG
        // recompress path — it flattens them to a single frame. Preserve them at
        // any quality; the quality knob only governs static-image downscaling.
        val animatedSource = MediaPipeline.isAnimatedImageSource(context.contentResolver, uri)
        if (quality.preservesOriginalImageBytes || animatedSource) {
            val cap = remainingBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            when (val original = MediaPipeline.readOriginalImageForUpload(context.contentResolver, uri, cap)) {
                is MediaPipeline.OriginalImageReadResult.Success ->
                    return ImageAttachmentReadOutcome(
                        PendingAttachment(
                            plaintextBytes = original.image.bytes,
                            mediaType = original.image.mediaType,
                            fileName = original.image.fileName,
                            dim = original.image.dim,
                            thumbhash = original.image.thumbhash,
                        ),
                    )
                MediaPipeline.OriginalImageReadResult.TooLarge -> return ImageAttachmentReadOutcome(null, overflowed = true)
                MediaPipeline.OriginalImageReadResult.Failed,
                MediaPipeline.OriginalImageReadResult.Unsupported,
                // Never flatten an animation to JPEG — fail the attachment instead
                // of silently dropping the animation. Static sources still fall
                // back to the JPEG re-encode so unsupported containers strip metadata.
                -> if (animatedSource) return ImageAttachmentReadOutcome(null) else Unit
            }
        }
        val jpeg =
            MediaPipeline.readDownscaledJpeg(
                context.contentResolver,
                uri,
                maxEdgePx = quality.imageMaxEdgePx,
                quality = quality.imageJpegQuality,
            ) ?: return ImageAttachmentReadOutcome(null)
        if (jpeg.bytes.size.toLong() > remainingBytes) {
            return ImageAttachmentReadOutcome(null, overflowed = true)
        }
        val sourceName = queryDisplayName(context.contentResolver, uri) ?: "image.jpg"
        val fileName = MediaPipeline.swapExtensionToJpg(sourceName)
        return ImageAttachmentReadOutcome(
            PendingAttachment(
                plaintextBytes = jpeg.bytes,
                mediaType = MediaPipeline.RECOMPRESSED_MIME,
                fileName = fileName,
                dim = "${jpeg.width}x${jpeg.height}",
                thumbhash = jpeg.thumbhash,
            ),
        )
    }

    val voiceRecordingController =
        // Re-key on every captured dependency: chat.id (basic), controller
        // (avoids dispatching through a stale ConversationController when
        // appState.runtimeGeneration changes), and voiceOutputDir (a fresh
        // File reference if context/cacheDir flips — also future-proofs an
        // account-scoped dir).
        remember(chat.id, controller, voiceOutputDir) {
            dev.ipf.whitenoise.android.audio.VoiceRecordingController(
                context = context,
                outputDirectory = voiceOutputDir,
                scope = scope,
                onPermissionRequest = {
                    val granted =
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    if (!granted && !voiceMicPermissionRequested) {
                        voiceMicPermissionRequested = true
                        voiceMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    granted
                },
                onRecordingComplete = { file, durationMs -> sendVoiceAttachment(file, durationMs) },
                onError = { throwable ->
                    if (throwable is IllegalStateException && throwable.message == "voice recording too short") {
                        appState.present(voiceTooShortMsg)
                    }
                },
                // Honor the user's media-quality ceiling for voice notes.
                // Read at record-start (the controller is not re-keyed on the
                // quality state) so a setting change applies to the next clip.
                bitrateProvider = { appState.mediaQuality.audioBitrateBps },
            )
        }
    DisposableEffect(voiceRecordingController) {
        onDispose { voiceRecordingController.release() }
    }

    // Auto-chain voice playback: when one clip ends, play the IMMEDIATE
    // next message iff it's also a voice attachment. Stops on any
    // non-voice neighbor (text, image, system) or end-of-timeline. We do
    // not skip past unrelated messages to find a later voice note — that
    // would jump the user past content they hadn't consumed.
    DisposableEffect(controller, chat.id) {
        val ownerKey = controller.group.groupIdHex
        val unregister =
            dev.ipf.whitenoise.android.audio.VoicePlaybackController.registerCompletionCallback(ownerKey) { completedKey ->
                val completedMsgId = completedKey.substringBefore('#')
                val completedIdx = controller.timeline.indexOfFirst { it.record.messageIdHex == completedMsgId }
                if (completedIdx >= 0) {
                    // Walk forward only as long as the next item is a derived-
                    // state row (edit / group system) — those are invisible to
                    // the user, so skipping them doesn't violate "immediate
                    // neighbor" semantics.
                    var nextIdx = completedIdx + 1
                    while (nextIdx < controller.timeline.size &&
                        MessageProjector.isGroupSystem(controller.timeline[nextIdx].record)
                    ) {
                        nextIdx++
                    }
                    val nextMsg = controller.timeline.getOrNull(nextIdx)
                    val refs = nextMsg?.let { controller.mediaReferences[it.record.messageIdHex] }
                    val audioEntry =
                        refs?.withIndex()?.firstOrNull { (_, r) ->
                            r.mediaType.startsWith("audio/", ignoreCase = true)
                        }
                    if (nextMsg != null && audioEntry != null) {
                        val idx = audioEntry.index
                        val ref = audioEntry.value
                        scope.launch {
                            val mine = nextMsg.record.direction != "received"
                            val file =
                                runCatching {
                                    materializeVoiceAttachment(
                                        context = context,
                                        controller = controller,
                                        messageIdHex = nextMsg.record.messageIdHex,
                                        attachmentIndex = idx,
                                        reference = ref,
                                        mine = mine,
                                    )
                                }.getOrNull() ?: return@launch
                            dev.ipf.whitenoise.android.audio.VoicePlaybackController
                                .play("${nextMsg.record.messageIdHex}#$idx", file, ownerKey = ownerKey)
                        }
                    }
                }
            }
        onDispose {
            unregister()
        }
    }

    // Decode/compress each URI off the main thread, then hand the album to
    // the controller as a single `sendAttachments(list, caption)` call. One
    // kind:9 carries N imeta tags; the caption is shared across the whole
    // album. If any source fails to decode the rest still send (best
    // effort), but if NONE decode we bail without surfacing an empty send.
    fun sendPickedMedia(
        uris: List<android.net.Uri>,
        caption: String,
    ) {
        if (uris.isEmpty()) return
        val trimmedCaption = caption.trim().takeIf { it.isNotBlank() }
        appState.launchMutation {
            val result =
                withContext(Dispatchers.Default) {
                    val out = mutableListOf<PendingAttachment>()
                    var consumed = 0L
                    var overflowed = false
                    for (uri in uris) {
                        val remaining = (MEDIA_ALBUM_MAX_TOTAL_BYTES - consumed).coerceAtLeast(0L)
                        if (remaining <= 0L) {
                            overflowed = true
                            break
                        }
                        val mime = safeGetType(context.contentResolver, uri)
                        val attachment =
                            if (mime.startsWith("video/", ignoreCase = true)) {
                                when (val r = MediaPipeline.readVideoForUpload(context, uri, remaining)) {
                                    is MediaPipeline.VideoReadResult.Success ->
                                        PendingAttachment(
                                            plaintextBytes = r.video.bytes,
                                            mediaType = r.video.mediaType,
                                            fileName = r.video.fileName,
                                            dim = "${r.video.width}x${r.video.height}",
                                            thumbhash = r.video.thumbhash,
                                        )
                                    MediaPipeline.VideoReadResult.TooLarge -> {
                                        overflowed = true
                                        continue
                                    }
                                    MediaPipeline.VideoReadResult.Failed -> continue
                                }
                            } else {
                                val image = readImageAttachment(uri, remaining)
                                if (image.overflowed) {
                                    overflowed = true
                                    continue
                                }
                                image.attachment ?: continue
                            }
                        consumed += attachment.plaintextBytes.size.toLong()
                        out += attachment
                    }
                    out to overflowed
                }
            val attachments = result.first
            val albumOverflowed = result.second
            if (attachments.size < uris.size) {
                val anyVideoPicked =
                    uris.any {
                        safeGetType(context.contentResolver, it)
                            .startsWith("video/", ignoreCase = true)
                    }
                appState.present(
                    when {
                        albumOverflowed -> R.string.media_album_too_large
                        anyVideoPicked -> R.string.toast_couldnt_process_video
                        else -> R.string.toast_couldnt_decode_image
                    },
                    // Decode/process failures are diagnostic; the album size
                    // cap is a fixed validation limit (#796).
                    copyable = !albumOverflowed,
                )
                if (attachments.isEmpty()) return@launchMutation
            }
            controller.sendAttachments(attachments, trimmedCaption)
        }
    }

    // Read picked document URIs into attachments. Non-image documents are kept
    // as raw bytes; image/* picks from Files use the same media-quality and
    // metadata-stripping path as visual image picks before joining the document
    // send path. MIME comes from the content resolver; filename from
    // `OpenableColumns.DISPLAY_NAME`.
    //
    // Two-layer size guard:
    //   1. Per-attachment ceiling: skip any single pick that already declares
    //      a `OpenableColumns.SIZE` greater than [MEDIA_ATTACHMENT_MAX_BYTES],
    //      OR overruns the cap during a bounded streaming read (no fully-
    //      buffered `readBytes()` so a 500 MB pick can't OOM the JVM heap
    //      before the retained-uploads LRU has anything to evict).
    //   2. Album-total ceiling: stop accumulating once the cumulative payload
    //      crosses [MEDIA_ALBUM_MAX_TOTAL_BYTES]; remaining picks are dropped.
    //
    // Any reject surfaces a single user-visible toast; the rest of the album
    // continues. If NOTHING survives the gates we bail without an empty send.
    // Decoded outcome of the document read pass, surfaced so the unified
    // sendStagedAttachments path can blend its results with the image decode.
    data class DocumentReadOutcome(
        val attachments: List<PendingAttachment>,
        val rejected: Boolean,
        val albumOverflowed: Boolean,
        val totalBytes: Long,
    )

    suspend fun readPickedDocuments(
        uris: List<android.net.Uri>,
        bytesBudget: Long = MEDIA_ALBUM_MAX_TOTAL_BYTES,
    ): DocumentReadOutcome =
        withContext(Dispatchers.IO) {
            val accepted = mutableListOf<PendingAttachment>()
            var albumBytes = 0L
            var rejected = false
            var albumOverflowed = false
            for (uri in uris) {
                val resolvedMime =
                    safeGetType(context.contentResolver, uri)
                        .takeIf { it.isNotBlank() }
                        ?: "application/octet-stream"
                val remainingAlbumBudget = (bytesBudget - albumBytes).coerceAtLeast(0L)
                if (remainingAlbumBudget <= 0L) {
                    albumOverflowed = true
                    break
                }
                if (resolvedMime.startsWith("image/", ignoreCase = true)) {
                    val image = readImageAttachment(uri, remainingAlbumBudget)
                    if (image.overflowed) {
                        albumOverflowed = true
                        continue
                    }
                    val attachment = image.attachment
                    if (attachment == null) {
                        rejected = true
                        continue
                    }
                    if (attachment.plaintextBytes.isEmpty()) continue
                    val nextAlbumBytes = albumBytes + attachment.plaintextBytes.size.toLong()
                    if (nextAlbumBytes > bytesBudget) {
                        albumOverflowed = true
                        continue
                    }
                    albumBytes = nextAlbumBytes
                    accepted += attachment
                    continue
                }
                val declaredSize = queryContentSize(context.contentResolver, uri)
                if (declaredSize > 0L && declaredSize > MEDIA_ATTACHMENT_MAX_BYTES) {
                    rejected = true
                    continue
                }
                val perFileCap =
                    minOf(MEDIA_ATTACHMENT_MAX_BYTES, remainingAlbumBudget)
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                val bytes =
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            MediaPipeline.readBoundedBytes(stream, perFileCap)
                        }
                    }.getOrNull()
                if (bytes == null) {
                    rejected = true
                    continue
                }
                if (bytes.isEmpty()) continue
                if (albumBytes + bytes.size > bytesBudget) {
                    albumOverflowed = true
                    continue
                }
                albumBytes += bytes.size
                val name = queryDisplayName(context.contentResolver, uri) ?: "file"
                accepted +=
                    PendingAttachment(
                        plaintextBytes = bytes,
                        mediaType = resolvedMime,
                        fileName = name,
                        dim = null,
                    )
            }
            DocumentReadOutcome(accepted, rejected, albumOverflowed, albumBytes)
        }

    data class VisualReadOutcome(
        val attachments: List<PendingAttachment>,
        val albumOverflowed: Boolean,
    )

    suspend fun readPickedImages(uris: List<android.net.Uri>): VisualReadOutcome =
        withContext(Dispatchers.Default) {
            val out = mutableListOf<PendingAttachment>()
            var consumed = 0L
            var overflowed = false
            for (uri in uris) {
                val remaining = (MEDIA_ALBUM_MAX_TOTAL_BYTES - consumed).coerceAtLeast(0L)
                if (remaining <= 0L) {
                    overflowed = true
                    break
                }
                val mime = safeGetType(context.contentResolver, uri)
                val attachment =
                    if (mime.startsWith("video/", ignoreCase = true)) {
                        // Thread the remaining album budget into the video read so a
                        // multi-video pick can't accumulate hundreds of MB in heap
                        // before the cap downstream would reject the tail.
                        when (val r = MediaPipeline.readVideoForUpload(context, uri, remaining)) {
                            is MediaPipeline.VideoReadResult.Success ->
                                PendingAttachment(
                                    plaintextBytes = r.video.bytes,
                                    mediaType = r.video.mediaType,
                                    fileName = r.video.fileName,
                                    dim = "${r.video.width}x${r.video.height}",
                                    thumbhash = r.video.thumbhash,
                                )
                            MediaPipeline.VideoReadResult.TooLarge -> {
                                overflowed = true
                                continue
                            }
                            MediaPipeline.VideoReadResult.Failed -> continue
                        }
                    } else {
                        val image = readImageAttachment(uri, remaining)
                        if (image.overflowed) {
                            overflowed = true
                            continue
                        }
                        image.attachment ?: continue
                    }
                consumed += attachment.plaintextBytes.size.toLong()
                out += attachment
            }
            VisualReadOutcome(out, overflowed)
        }

    // Single-path send used by the unified staging shelf: decodes images
    // (downscale + JPEG) and documents (raw bytes with cap) in parallel,
    // concatenates the attachments, and ships them as one kind-9 album.
    fun sendStagedAttachments(
        imageUris: List<android.net.Uri>,
        documentUris: List<android.net.Uri>,
        caption: String,
        onAfterSend: () -> Unit = {},
    ) {
        if (imageUris.isEmpty() && documentUris.isEmpty()) return
        val trimmedCaption = caption.trim().takeIf { it.isNotBlank() }
        appState.launchMutation {
            // Enforce the album byte cap on images first so a multi-large-photo
            // pick can't push the cumulative payload past
            // MEDIA_ALBUM_MAX_TOTAL_BYTES and evict the retained-uploads LRU
            // mid-flight (which would break retry). Drop the tail and surface
            // a single oversize toast.
            val rawImages = readPickedImages(imageUris)
            var imageBytes = 0L
            val acceptedImages = mutableListOf<PendingAttachment>()
            var imageAlbumOverflowed = rawImages.albumOverflowed
            for (attachment in rawImages.attachments) {
                val next = imageBytes + attachment.plaintextBytes.size
                if (next > MEDIA_ALBUM_MAX_TOTAL_BYTES) {
                    imageAlbumOverflowed = true
                    continue
                }
                imageBytes = next
                acceptedImages += attachment
            }
            val docBudget = (MEDIA_ALBUM_MAX_TOTAL_BYTES - imageBytes).coerceAtLeast(0L)
            val docOutcome =
                if (documentUris.isEmpty()) {
                    DocumentReadOutcome(emptyList(), rejected = false, albumOverflowed = false, totalBytes = 0L)
                } else {
                    readPickedDocuments(documentUris, docBudget)
                }
            val merged = acceptedImages + docOutcome.attachments
            val pickHasVideo =
                imageUris.any {
                    safeGetType(context.contentResolver, it)
                        .startsWith("video/", ignoreCase = true)
                }
            val visualFailureToast =
                if (pickHasVideo) R.string.toast_couldnt_process_video else R.string.toast_couldnt_decode_image
            if (merged.isEmpty()) {
                // Only surface the visual-decode toast when there were visual
                // picks to begin with — a document-only send that failed every
                // file should fall through to the document toasts below
                // rather than misreporting as an image decode error. And if
                // the album overflowed the byte budget, surface that
                // explicitly instead of "couldn't process".
                if (imageUris.isNotEmpty()) {
                    val toast =
                        if (imageAlbumOverflowed) R.string.media_album_too_large else visualFailureToast
                    appState.present(toast, copyable = !imageAlbumOverflowed)
                    return@launchMutation
                }
            }
            if (acceptedImages.size < imageUris.size && !imageAlbumOverflowed) {
                appState.present(visualFailureToast, copyable = true)
            }
            if (imageAlbumOverflowed || docOutcome.albumOverflowed) {
                appState.present(R.string.media_album_too_large)
            } else if (docOutcome.rejected) {
                appState.present(R.string.media_file_too_large)
            }
            if (merged.isEmpty()) return@launchMutation
            // Two-phase ship: SEED every send synchronously (so all the
            // optimistic bubbles appear in the same recomposition pass and
            // the user sees the queue light up at once), THEN run the
            // FFI upload+publish for each in pick order (so the post-
            // confirm timeline keeps the order the user picked).
            //
            // Image attachments ride one kind-9 album (the masonry layout
            // wants multiple tiles in one message). Non-image attachments
            // ship as their own kind-9 each, because each carries distinct
            // filename/MIME metadata that doesn't benefit from grid
            // composition. Caption sticks with images when present;
            // otherwise it attaches to the first file send.
            // Backfill thumbhash for any image-typed doc-picker attachments that
            // lack one. image/* document picks usually arrive here already
            // processed by the image privacy pipeline; this keeps the renderer
            // defensive for legacy/raw sources while staying off-main so the
            // staging-shelf dismiss animation doesn't stutter on multi-image
            // picks.
            val readyDocAttachments =
                if (docOutcome.attachments.isEmpty()) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) {
                        docOutcome.attachments.map { attachment ->
                            if (!attachment.mediaType.startsWith("image/", ignoreCase = true) ||
                                attachment.thumbhash != null
                            ) {
                                attachment
                            } else {
                                val bitmap =
                                    MediaPipeline.decodeSampledBitmap(
                                        attachment.plaintextBytes,
                                        MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
                                    )
                                val hash = bitmap?.let { Thumbhash.encodeFromBitmap(it) }
                                bitmap?.recycle()
                                attachment.copy(thumbhash = hash)
                            }
                        }
                    }
                }
            // The image picker's multi-select UI groups its picks as one
            // batch, so they ride one kind-9 album — preserves the masonry
            // grouping the user opted into. Doc-picker items, by contrast,
            // are picked one-by-one in order, so each ships as its own
            // kind-9 in pick position regardless of MIME. Image MIMEs from
            // the doc picker still render as image bubbles (single-image
            // variant of the album shape) — same surface, different framing.
            val seeded = mutableListOf<ConversationController.QueuedAttachmentSend>()
            if (acceptedImages.isNotEmpty()) {
                controller.queueAttachments(acceptedImages, trimmedCaption)?.let(seeded::add)
            }
            val captionConsumedByAlbum = acceptedImages.isNotEmpty()
            readyDocAttachments.forEachIndexed { index, attachment ->
                val perItemCaption =
                    if (!captionConsumedByAlbum && index == 0) trimmedCaption else null
                controller.queueAttachments(listOf(attachment), perItemCaption)?.let(seeded::add)
            }
            // Pull the user down to the just-seeded bubbles before the
            // upload loop suspends — same UX as text-send. Firing after
            // queueAttachments (the optimistic seed) and before
            // uploadQueued (the FFI publish) means the scroll lands in the
            // same frame the bubble appears, instead of waiting on the
            // relay round-trip.
            if (seeded.isNotEmpty()) onAfterSend()
            // Run uploads sequentially so the kind-9 publishes go out in
            // pick order. The optimistic bubbles are already on screen.
            for (slot in seeded) {
                controller.uploadQueued(slot)
            }
        }
    }

    // Documents take a separate launcher because `OpenMultipleDocuments`
    // accepts any MIME — the image picker can't surface PDFs, archives, etc.
    // Picked URIs accumulate in `pendingDocumentUris` so they can ride the
    // same staging shelf as image picks; one Send dispatches both sides
    // through one kind:9 album. Bytes pass through without recompression —
    // including picked/forwarded audio files. The send-quality audio bitrate
    // is intentionally scoped to recorded voice notes until this client grows
    // a general audio transcode path.
    val documentPickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            // Append into the document side of the staging shelf rather than
            // sending immediately. The preview sheet renders both lists and
            // a single Send dispatches both decoders into one kind-9 album.
            val merged = (pendingDocumentUris + uris).distinct().take(MEDIA_PICKER_MAX_ITEMS)
            pendingDocumentUris = merged
        }

    // Wipe camera-capture temp files and retained outgoing attachment bytes
    // when leaving the conversation so plaintext media doesn't linger.
    // `shared_media` is deliberately NOT touched here — an external reader
    // (PDF viewer, etc.) may still be reading a granted FileProvider URI.
    // Those are reaped by the age-based `sweepStaleSharedMedia` janitor at
    // app start.
    DisposableEffect(Unit) {
        onDispose {
            clearMediaTempFiles(context)
            controller.clearRetainedUploads()
        }
    }

    // Scroll the lazy list so the item at [targetMessageId] sits roughly in the
    // vertical center of the message-list viewport, leaving context above and
    // below the target visible (#595, #794). Uses one animated scroll; if the
    // target was never measured before, any final exact-centering correction is
    // a non-animated snap rather than the visible bounce from #999.
    suspend fun centerTimelineItemAt(
        targetMessageId: String,
        fallbackTargetIndex: Int,
    ) {
        fun currentTargetIndex(): Int? {
            val timelineIndex =
                controller.timeline
                    .filterNot { MessageProjector.isEdit(it.record) }
                    .indexOfFirst { it.record.messageIdHex == targetMessageId }
                    .takeIf { it >= 0 }
                    ?: return null
            val olderMessagesHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
            return 1 + olderMessagesHeaderCount + timelineIndex
        }

        val targetIndex = currentTargetIndex() ?: fallbackTargetIndex
        val layoutInfo = listState.layoutInfo
        val viewportHeight = layoutInfo.viewportSize.height
        if (viewportHeight <= 0) {
            // Layout not measured yet (rare on a fresh open): fall back to the
            // plain top-aligned jump rather than guessing an offset.
            listState.animateScrollToItem(targetIndex)
            return
        }
        val olderMessagesHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
        val firstTimelineListIndex = 1 + olderMessagesHeaderCount
        val renderedForHeightSample = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        val lastTimelineListIndex = firstTimelineListIndex + renderedForHeightSample.size - 1
        val visibleTargetHeight = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }?.size
        val visibleTimelineHeights =
            layoutInfo.visibleItemsInfo
                .filter { visibleItem ->
                    if (visibleItem.index !in firstTimelineListIndex..lastTimelineListIndex) return@filter false
                    val row = renderedForHeightSample.getOrNull(visibleItem.index - firstTimelineListIndex) ?: return@filter false
                    timelineRowKind(row.record, appState.streamingDebugEnabled) == TimelineRowKind.Bubble
                }.map { it.size }
        val itemHeight =
            ReplyNavigation.itemHeightForScrollPx(
                targetMessageId = targetMessageId,
                measuredItemHeightsByMessageId = timelineItemHeightsPx,
                visibleTargetHeightPx = visibleTargetHeight,
                visibleTimelineItemHeightsPx = visibleTimelineHeights,
            )
        // Compose clamps the resulting scroll at the list bounds, so this still
        // degrades as #595 asks: a near-top match can't scroll up past the
        // first row (top-aligned) and a near-bottom match can't scroll down
        // past the last row (bottom-aligned); only the middle case truly
        // centers.
        val animatedOffset = ReplyNavigation.centeredScrollOffset(viewportHeight, itemHeight)
        listState.animateScrollToItem(targetIndex, animatedOffset)

        // After an off-screen row is composed, use its real measured height for
        // exact final centering. This must remain non-animated: the old second
        // animateScrollToItem is what produced the visible overshoot/backtrack.
        withFrameNanos { }
        val resolvedTargetIndex = currentTargetIndex() ?: targetIndex
        val postScrollLayoutInfo = listState.layoutInfo
        val measuredItemHeight =
            postScrollLayoutInfo.visibleItemsInfo.firstOrNull { it.index == resolvedTargetIndex }?.size
                ?: timelineItemHeightsPx[targetMessageId]
        val measuredViewportHeight = postScrollLayoutInfo.viewportSize.height
        if (measuredViewportHeight > 0 && measuredItemHeight != null) {
            val measuredOffset = ReplyNavigation.centeredScrollOffset(measuredViewportHeight, measuredItemHeight)
            if (resolvedTargetIndex != targetIndex || measuredOffset != animatedOffset) {
                listState.scrollToItem(resolvedTargetIndex, measuredOffset)
            }
        }
    }

    fun recordReactionEmoji(emoji: String) {
        // The read-modify-write touches SharedPreferences (disk); keep it off
        // the Main thread, matching the off-Main load above. See #147.
        // Held under a mutex so rapid taps serialize instead of losing updates.
        scope.launch {
            recentReactionEmojis =
                recentEmojiWriteMutex.withLock {
                    withContext(Dispatchers.IO) { RecentEmojiPreferences.recordPicked(context, emoji) }
                }
        }
    }

    fun saveQuickReactionEmojis(choices: List<String>) {
        quickReactionEmojisTouched = true
        scope.launch {
            quickReactionEmojis = withContext(Dispatchers.IO) { RecentEmojiPreferences.saveQuickReactions(context, choices) }
        }
    }

    fun resetQuickReactionEmojis() {
        quickReactionEmojisTouched = true
        scope.launch {
            quickReactionEmojis = withContext(Dispatchers.IO) { RecentEmojiPreferences.resetQuickReactions(context) }
        }
    }

    fun navigateToReplyTarget(item: TimelineMessage) {
        navigateReplyJob?.cancel()
        navigateReplyJob =
            scope.launch {
                val targetMessageId = controller.replyTargetMessageId(item)
                if (targetMessageId == null || !controller.loadUntilMessageAvailable(targetMessageId)) {
                    appState.present(R.string.toast_original_message_unavailable)
                    return@launch
                }
                // Resolve the target in the rendered (edit-filtered) list the
                // LazyColumn shows — an unfiltered index is off by the edits above it.
                val timelineIndex =
                    controller.timeline
                        .filterNot { MessageProjector.isEdit(it.record) }
                        .indexOfFirst { it.record.messageIdHex == targetMessageId }
                if (timelineIndex < 0) {
                    appState.present(R.string.toast_original_message_unavailable)
                    return@launch
                }
                val olderMessagesHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
                centerTimelineItemAt(targetMessageId, 1 + olderMessagesHeaderCount + timelineIndex)
                highlightedMessageId = targetMessageId
                delay(1_500L)
                if (highlightedMessageId == targetMessageId) {
                    highlightedMessageId = null
                }
            }
    }

    fun jumpToNextUnreadMention() {
        val targetMessageId = unreadMentionMessageIds.firstOrNull() ?: return
        navigateReplyJob?.cancel()
        navigateReplyJob =
            scope.launch {
                if (!controller.loadUntilMessageAvailable(targetMessageId)) {
                    appState.present(R.string.toast_original_message_unavailable)
                    return@launch
                }
                val timelineIndex =
                    controller.timeline
                        .filterNot { MessageProjector.isEdit(it.record) }
                        .indexOfFirst { it.record.messageIdHex == targetMessageId }
                if (timelineIndex < 0) {
                    appState.present(R.string.toast_original_message_unavailable)
                    return@launch
                }
                val olderMessagesHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
                centerTimelineItemAt(targetMessageId, 1 + olderMessagesHeaderCount + timelineIndex)
                highlightedMessageId = targetMessageId
                // Mark read up to the visited mention so the count — and the
                // chat-list @-badge — decrement in step; advance the local read
                // anchor so the chip's derived count updates immediately.
                readAnchorMessageId = targetMessageId
                controller.markReadUpTo(targetMessageId)
                delay(1_500L)
                if (highlightedMessageId == targetMessageId) {
                    highlightedMessageId = null
                }
            }
    }

    LaunchedEffect(controller) {
        initialTimelineLoadStarted = true
        controller.start()
    }
    LaunchedEffect(controller.group.pendingConfirmation, controller.group.groupIdHex) {
        if (controller.group.pendingConfirmation) {
            controller.dismissConversationNotifications()
        }
    }
    // inviteStreamScope outlives a single start() — acceptInvite() launches into
    // it from a separate mutation scope — so it's cancelled on controller
    // disposal here rather than in start()'s teardown (#279).
    DisposableEffect(controller) {
        appState.attachConversationController(controller)
        onDispose {
            appState.detachConversationController(controller)
            controller.onCleared()
        }
    }
    // Edits (kind-1009) are derived state, not chat — they mutate the
    // original message's body via [editsByTarget] and must not occupy a slot
    // in the lazy list. A naive `return@items` still reserves the slot, which
    // (combined with `Arrangement.spacedBy`) leaves a visible gap. Filter
    // them out up front and base every index/scroll calculation on the
    // filtered list so what we count matches what we render.
    val renderedTimeline =
        remember(controller.timeline) {
            controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        }
    val latestTimelineItemId = renderedTimeline.lastOrNull()?.id
    val transcriptLocale = LocalConfiguration.current.locales[0]
    val olderHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
    val bottomTimelineIndex = renderedTimeline.size + 1 + olderHeaderCount

    // Day label for the topmost visible message, surfaced by the sticky ribbon
    // overlay while scrolling. Hoisted into derivedStateOf and held as a State
    // (not read here) so the scroll-backed firstVisibleItemIndex read happens
    // inside the small ribbon child — not in the LazyColumn-hosting Box scope,
    // which would otherwise recompose the timeline container on every scroll
    // frame. Mirrors the nearBottom / currentHighestVisibleTimelineIndex
    // derived-state pattern above (#375).
    val stickyDayLabelState =
        remember(renderedTimeline, transcriptLocale, olderHeaderCount) {
            derivedStateOf {
                val i =
                    (listState.firstVisibleItemIndex - 1 - olderHeaderCount)
                        .coerceIn(0, (renderedTimeline.size - 1).coerceAtLeast(0))
                renderedTimeline
                    .getOrNull(i)
                    ?.record
                    ?.recordedAt
                    ?.let { messageDayLabel(it, transcriptLocale) }
                    .orEmpty()
            }
        }

    // In-chat search match set. Computed over the currently-loaded, rendered
    // (edit-filtered) timeline only — no relay fetch, no full-history preload.
    // Reactions / deletes / group-system / agent-stream rows carry no
    // user-typed body and are excluded by `MessageSearch.isSearchable`. As
    // older pages load, `renderedTimeline` grows and the match set expands
    // naturally. Keyed on `controller.timeline` (not just `renderedTimeline`'s
    // edges/size) so a kind-1009 edit — which changes the body returned by
    // `controller.displayedText(...)` without altering the rendered timeline's
    // first/last id or size — re-runs the derivation and keeps matches fresh.
    val searchMatchIds =
        remember(searchQuery, controller.timeline, renderedTimeline) {
            if (searchQuery.isBlank()) {
                emptyList()
            } else {
                // Restrict to rows that carry a user-typed body, then run the
                // shared substring matcher over those bodies and map the hit
                // indices back to message ids (timeline order preserved).
                // Snapshot the body once per row so the displayed (post-edit)
                // text used for matching is the same text used to map hits.
                val searchable =
                    renderedTimeline.mapNotNull { item ->
                        val body = controller.displayedText(item.record)
                        if (MessageSearch.isSearchable(item.record, body)) {
                            item.record.messageIdHex to body
                        } else {
                            null
                        }
                    }
                val bodies = searchable.map { it.second }
                MessageSearch
                    .matchIndices(bodies, searchQuery)
                    .map { searchable[it].first }
            }
        }
    // The active match ordinal, re-anchored to the pinned message id so it
    // tracks that message as the set grows. -1 when there are no matches.
    val searchActiveIndex = MessageSearch.resolveCursor(searchMatchIds, searchPinnedMatchId)
    // Keep the pin valid: if the resolved cursor fell back to the first match
    // (pin gone / unset) adopt that match id as the new pin so subsequent
    // steps move relative to a real anchor.
    LaunchedEffect(searchMatchIds, searchActiveIndex) {
        if (searchActiveIndex >= 0) {
            val resolvedId = searchMatchIds[searchActiveIndex]
            if (searchPinnedMatchId != resolvedId) searchPinnedMatchId = resolvedId
        }
    }

    fun scrollToSearchMatch(messageIdHex: String) {
        searchJob?.cancel()
        searchJob =
            scope.launch {
                // Local-only: the message is already in the loaded window
                // (matches are derived from it), so this resolves immediately;
                // the helper is reused for symmetry with reply navigation and
                // guards the rare case where a concurrent trim dropped the row.
                if (!controller.loadUntilMessageAvailable(messageIdHex)) return@launch
                val timelineIndex =
                    renderedTimeline.indexOfFirst { it.record.messageIdHex == messageIdHex }
                if (timelineIndex < 0) return@launch
                // Center the match so prior + subsequent context is visible (#595).
                centerTimelineItemAt(messageIdHex, 1 + olderHeaderCount + timelineIndex)
                highlightedMessageId = messageIdHex
                delay(1_500L)
                if (highlightedMessageId == messageIdHex) {
                    highlightedMessageId = null
                }
            }
    }

    // Step the cursor (next = forward/newer, previous = backward/older) with
    // wrap-around, pin the new match, and jump+highlight it.
    fun navigateToSearchMatch(forward: Boolean) {
        if (searchMatchIds.isEmpty()) return
        val next = MessageSearch.step(searchActiveIndex, searchMatchIds.size, forward)
        if (next < 0) return
        val targetId = searchMatchIds[next]
        searchPinnedMatchId = targetId
        scrollToSearchMatch(targetId)
    }

    fun closeSearch() {
        searchOpen = false
        searchQuery = ""
        searchPinnedMatchId = null
        searchJob?.cancel()
        highlightedMessageId = null
        // Restore the scroll position captured when search opened (#292). The
        // cancel above stops any in-flight search scroll-jump, so this resolves
        // to the pre-search anchor without racing the search animation.
        preSearchScrollAnchor?.let { (index, offset) ->
            searchJob =
                scope.launch {
                    listState.scrollToItem(index, offset)
                }
        }
        preSearchScrollAnchor = null
    }

    // Back closes an open search first (restoring the normal bar) before it
    // leaves the conversation — matching the chat-list search affordance.
    BackHandler {
        if (searchOpen) closeSearch() else onBack()
    }

    // Auto-focus the field on open; clear transient highlight on close.
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            searchFocusRequester.requestFocus()
        }
    }
    // Jump to the first match as soon as one exists for the current query, so
    // typing immediately scrolls to (and highlights) the newest match without
    // requiring the user to tap an arrow first.
    LaunchedEffect(searchMatchIds.firstOrNull(), searchOpen) {
        if (searchOpen && searchMatchIds.isNotEmpty()) {
            val firstId = searchMatchIds[searchActiveIndex.coerceAtLeast(0)]
            scrollToSearchMatch(firstId)
        }
    }

    // Extend history a few rows before the reader reaches the top, while a
    // keyed message is still the anchor. Compose's keyed prepend then holds
    // those messages at the same offset in the same measure pass — the new
    // page lands above the fold and the reader scrolls up into it with no jump
    // or blink (no post-hoc scroll, which is what caused the flip).
    LaunchedEffect(listState, controller) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { firstIndex ->
                if (
                    initialTimelineAnchored &&
                    controller.hasMoreBefore &&
                    !controller.isLoadingOlder &&
                    firstIndex <= olderHeaderCount + OLDER_PAGE_PREFETCH_ROWS
                ) {
                    controller.loadOlder()
                }
            }
    }
    // Capture the unread boundary at chat open. Stays fixed for the lifetime
    // of this composable (per chat.id) so the "N unread messages" divider
    // doesn't keep moving as the user scrolls and marks messages as read.
    val entryUnreadCount = remember(chat.id) { chat.unreadCount.toInt().coerceAtLeast(0) }
    var entryFirstUnreadMessageId by remember(chat.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(chat.id, controller.timeline.size) {
        if (entryFirstUnreadMessageId == null && entryUnreadCount > 0) {
            val firstUnreadIndex = controller.firstUnreadTimelineIndex(entryUnreadCount)
            if (firstUnreadIndex >= 0) {
                // Controller-side unread helpers already skip derived-state
                // kinds, so this is always a kind-9 chat present in the
                // filtered renderedTimeline.
                entryFirstUnreadMessageId =
                    controller.timeline[firstUnreadIndex]
                        .record.messageIdHex
                        .takeIf { it.isNotBlank() }
            }
        }
    }
    // Boolean-edge key avoids per-frame coroutine cancellation. The IME open
    // animation takes ~200ms; the LazyColumn measures a smaller viewport on
    // each tick, so a single snap at frame 0 leaves the bubble below the
    // final viewport. The repeat loop re-snaps every frame for ~24 frames,
    // chasing the shrinking viewport to its settled bottom. Gated on
    // nearBottom so reading history isn't interrupted. imeIsOpen is derived
    // once above (boolean edge of WindowInsets.ime) to avoid recomposing the
    // body on every keyboard-animation frame. Keyed on initialTimelineAnchored
    // too so that when you open a chat with the keyboard already up (no
    // imeIsOpen edge), the chase still fires the moment the first-open anchor
    // settles — otherwise the anchor lands against the pre-IME viewport and the
    // newest message sits a few rows above the bottom until the keyboard closes.
    LaunchedEffect(imeIsOpen, chat.id, initialTimelineAnchored) {
        if (!imeIsOpen || !initialTimelineAnchored || !nearBottom) return@LaunchedEffect
        repeat(24) {
            withFrameNanos { }
            val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            runCatching { listState.scrollToItem(last) }
        }
    }
    // #589: app-switch resume handling. Two bugs surfaced when returning to a
    // chat after backgrounding the app:
    //
    //   Case A (keyboard was OPEN on leave): the bottom scroll anchor was
    //   computed against the pre-resume viewport before the keyboard inset
    //   re-applied, so the latest bubble landed clipped behind the keyboard.
    //   The existing imeIsOpen chase above does NOT re-fire here because
    //   imeIsOpen never transitions (it was already true), so we add a
    //   resume-triggered re-anchor that waits for the IME inset to settle and
    //   then re-snaps to the newest row — reusing the 24-frame chase idiom.
    //
    //   Case B (keyboard was CLOSED on leave): Android/Compose restores the
    //   BasicTextField focus and IME visibility on its own, popping a keyboard
    //   the user never asked for. We snapshot the composer focus on ON_PAUSE
    //   and, on ON_RESUME, gate restoration through the pure
    //   [shouldRestoreComposerFocusOnResume] predicate: restore focus only if
    //   it was held on pause (or an edit/reply session is active); otherwise
    //   actively clear focus and hide the keyboard so it does not pop.
    //
    // Keyed on chat.id so a conversation switch rebinds the observer; resolved
    // through the existing Context.lifecycleOwner() idiom (no new Local import).
    val resumeLifecycleOwner = context.lifecycleOwner()
    DisposableEffect(chat.id, resumeLifecycleOwner) {
        if (resumeLifecycleOwner == null) {
            onDispose { }
        } else {
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> {
                            // Snapshot the keyboard state we're leaving with so
                            // resume can faithfully restore it (Case B).
                            wasComposerFocusedOnPause = composerFocused
                        }
                        Lifecycle.Event.ON_RESUME -> {
                            val restoreFocus =
                                shouldRestoreComposerFocusOnResume(
                                    wasComposerFocusedOnPause = wasComposerFocusedOnPause,
                                    hasActiveEditOrReplySession =
                                        controller.editingMessageId != null ||
                                            controller.replyingTo != null,
                                )
                            scope.launch {
                                if (restoreFocus) {
                                    // Case B (was focused): re-raise the keyboard
                                    // exactly as it was before the switch.
                                    runCatching { composerFocus.requestFocus() }
                                    keyboardController?.show()
                                } else if (shouldClearFocusOnResume(
                                        restoringComposerFocus = restoreFocus,
                                        searchOpen = searchOpen,
                                    )
                                ) {
                                    // Case B (was NOT focused): the system tried
                                    // to restore focus/IME — undo it so the
                                    // keyboard does not pop unrequested.
                                    //
                                    // Guarded on !searchOpen: in-chat search
                                    // (#292) legitimately owns focus + IME while
                                    // open, and clearFocus(force = true) is
                                    // screen-wide. Clearing here would drop the
                                    // search field's focus and hide its keyboard,
                                    // and LaunchedEffect(searchOpen) does not
                                    // re-fire on resume to restore it — so we
                                    // leave focus untouched while search is open.
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                }
                                // Case A: re-anchor to the newest row AFTER the
                                // IME inset has settled, so the latest bubble
                                // sits above (not behind) the keyboard. Gated on
                                // nearBottom so a reader scrolled up into history
                                // is never yanked down. Poll the ime bottom inset
                                // across frames until it stops changing, then
                                // run the same 24-frame chase the IME-open path
                                // uses so the snap tracks the settling viewport.
                                if (initialTimelineAnchored && nearBottom) {
                                    var lastInset = -1
                                    var stableFrames = 0
                                    // Cap the settle wait so a keyboard that
                                    // never animates (Case B, staying closed)
                                    // can't spin here forever. Bail out early
                                    // once the inset holds steady for two frames.
                                    var settleFrame = 0
                                    while (settleFrame < 24 && stableFrames < 2) {
                                        withFrameNanos { }
                                        val current = imeInsets.getBottom(density)
                                        if (current == lastInset) {
                                            stableFrames++
                                        } else {
                                            stableFrames = 0
                                            lastInset = current
                                        }
                                        settleFrame++
                                    }
                                    if (nearBottom) {
                                        repeat(24) {
                                            withFrameNanos { }
                                            val last =
                                                (listState.layoutInfo.totalItemsCount - 1)
                                                    .coerceAtLeast(0)
                                            runCatching { listState.scrollToItem(last) }
                                        }
                                    }
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            resumeLifecycleOwner.lifecycle.addObserver(observer)
            onDispose { resumeLifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    // Re-apply a saved scroll position once the timeline materializes (#1107).
    // Seeding rememberLazyListState alone is not enough: the list can clamp
    // while the window is still empty, and the first-open anchor would snap to
    // bottom before the reader's position is restored.
    LaunchedEffect(chat.id, scrollRestore) {
        val restore = scrollRestore ?: return@LaunchedEffect
        restore.anchorMessageIdHex
            ?.takeIf { it.isNotBlank() }
            ?.let { controller.loadUntilMessageAvailable(it) }
        val targetIndex =
            snapshotFlow {
                val rendered = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
                if (rendered.isEmpty()) {
                    null
                } else {
                    val liveOlderHeaderCount =
                        if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
                    val liveBottomTimelineIndex = rendered.size + 1 + liveOlderHeaderCount
                    conversationScrollRestoreListIndex(
                        snapshot = restore,
                        renderedItemIds = rendered.map { it.id },
                        renderedMessageIds = rendered.map { it.record.messageIdHex },
                        olderHeaderCount = liveOlderHeaderCount,
                    ).coerceAtMost(liveBottomTimelineIndex)
                }
            }.filterNotNull()
                .first()
        listState.scrollToItem(targetIndex, restore.firstVisibleItemScrollOffset)
        initialTimelineAnchored = true
        val restoredRendered =
            controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        lastFollowedLatestId = restoredRendered.lastOrNull()?.id
    }
    LaunchedEffect(latestTimelineItemId) {
        if (renderedTimeline.isNotEmpty()) {
            if (!initialTimelineAnchored) {
                if (scrollRestore != null) {
                    return@LaunchedEffect
                }
                // First-time anchor on chat open. If there are unread
                // messages, land at the first unread one so the user can
                // read forward from there; otherwise drop them at the
                // newest message. Re-resolve the index in renderedTimeline so
                // scrollToItem refers to the lazy-list slot order, not the
                // unfiltered controller timeline.
                val unreadId =
                    controller
                        .firstUnreadTimelineIndex(chat.unreadCount.toInt())
                        .takeIf { it >= 0 }
                        ?.let {
                            controller.timeline[it]
                                .record.messageIdHex
                                .takeIf { id -> id.isNotBlank() }
                        }
                val renderedUnreadIndex =
                    unreadId?.let { id -> renderedTimeline.indexOfFirst { it.record.messageIdHex == id } } ?: -1
                val targetIndex =
                    if (renderedUnreadIndex >= 0) {
                        1 + olderHeaderCount + renderedUnreadIndex
                    } else {
                        bottomTimelineIndex
                    }
                listState.scrollToItem(targetIndex)
                initialTimelineAnchored = true
                lastFollowedLatestId = renderedTimeline.lastOrNull()?.id
            } else {
                val latestId = renderedTimeline.lastOrNull()?.id
                val previousId = lastFollowedLatestId
                // A genuine append: the last id changed and the row we last
                // followed is still present (an older-page trim drops it, so
                // that path is excluded). Id-based, so same-second tails count.
                val isAppend =
                    previousId != null &&
                        latestId != null &&
                        latestId != previousId &&
                        renderedTimeline.any { it.id == previousId }
                lastFollowedLatestId = latestId ?: previousId
                if (isAppend && nearBottom) {
                    listState.scrollToItem(bottomTimelineIndex)
                }
            }
        }
    }

    // Reacting to the last message grows its bubble height (a reaction chip) but
    // doesn't change any timeline id, so the append-follow above never sees it
    // and the grown bubble pushes its own bottom up off the composer. When the
    // user is already at the bottom, re-assert the bottom so the bubble + chip
    // stay flush. Keyed on the last rendered message's reaction tally; mutating
    // an earlier (non-last) message leaves this key unchanged, so a react while
    // reading history never hijacks the scroll position.
    LaunchedEffect(
        renderedTimeline
            .lastOrNull()
            ?.record
            ?.messageIdHex
            ?.let { controller.reactions[it] },
    ) {
        if (initialTimelineAnchored && nearBottom && renderedTimeline.isNotEmpty()) {
            listState.scrollToItem(bottomTimelineIndex)
        }
    }

    fun reanchorNewestAfterBottomInputChange() {
        if (!initialTimelineAnchored || !nearBottom) return
        scope.launch {
            repeat(24) {
                withFrameNanos { }
                val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                runCatching { listState.scrollToItem(last) }
            }
        }
    }

    // Scroll-to-message for a chat-list message-body search hit (issue #290).
    // Waits for the first-open anchor to settle, then pages the local timeline
    // back until the matched message is materialized and scrolls to it with a
    // brief highlight — the same affordance the reply-jump uses. Fires once
    // per (chat.id, focusMessageId); a missing message (e.g. it was deleted
    // between the search and the tap) just toasts and leaves the user at the
    // normal anchor. Local-only: loadUntilMessageAvailable paginates the
    // already-persisted store, never a relay fetch.
    LaunchedEffect(chat.id, focusMessageId) {
        val focus = focusMessageId ?: return@LaunchedEffect
        // Let the initial unread/newest anchor run first so our scroll isn't
        // immediately overwritten by it.
        snapshotFlow { initialTimelineAnchored }.filter { it }.first()
        val target = controller.loadScrollNavigationTarget(focus)
        if (target == null) {
            appState.present(R.string.toast_original_message_unavailable)
            return@LaunchedEffect
        }
        val timelineIndex =
            controller.timeline
                .filterNot { MessageProjector.isEdit(it.record) }
                .indexOfFirst { it.record.messageIdHex == target }
        if (timelineIndex < 0) {
            appState.present(R.string.toast_original_message_unavailable)
            return@LaunchedEffect
        }
        val olderMessagesHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
        // Center the match so prior + subsequent context is visible (#595).
        centerTimelineItemAt(target, 1 + olderMessagesHeaderCount + timelineIndex)
        if (highlightFocusMessage) {
            highlightedMessageId = target
            delay(1_500L)
            if (highlightedMessageId == target) {
                highlightedMessageId = null
            }
        }
    }

    // Scroll-driven read pointer advance. Watches the shared read anchor
    // (`readAnchorMessageId`) so the FFI only sees IDs that strictly advance
    // the pointer — scroll-up cannot regress the count. Settle-gated
    // (`!isScrollInProgress`) avoids per-frame FFI hops while scrolling.
    LaunchedEffect(listState, chat.id) {
        snapshotFlow {
            if (!initialTimelineAnchored || listState.isScrollInProgress) {
                null
            } else {
                readAnchorMessageId
            }
        }.filterNotNull()
            .distinctUntilChanged()
            .collect { messageId ->
                if (messageId.isNotBlank()) {
                    controller.markReadUpTo(messageId)
                }
            }
    }
    // In-conversation profile sheet (issue #635). Driven by the same
    // appState.pendingProfileNpub the shell-level sheet uses, but passed the live
    // ConversationController so it can offer group-admin actions. The shell-level
    // sheet is suppressed while a conversation is active (selectedChat != null in
    // the app shell), so the sheet renders exactly once here. Placed before the
    // showDetails early-return so it also overlays the members-list row profile
    // tap inside GroupDetailsScreen, which keeps the group context too.
    appState.pendingProfileNpub?.let { profileNpub ->
        ProfileSheet(
            appState = appState,
            npub = profileNpub,
            onOpenGroup = { item, justCreatedChat ->
                onOpenProfileGroup(item, justCreatedChat)
            },
            onDismiss = { appState.clearPresentedProfile() },
            adminController = controller,
            securePolicy =
                if (appState.allowChatScreenshotsInChats) {
                    SecureFlagPolicy.SecureOff
                } else {
                    SecureFlagPolicy.SecureOn
                },
        )
    }

    if (showDetails) {
        GroupDetailsScreen(
            appState = appState,
            controller = controller,
            onBack = {
                showDetails = false
                openTransferOnDetails = false
                openAddMemberOnDetails = false
            },
            onLeft = onBack,
            onJumpToMessage = { messageId ->
                showDetails = false
                openTransferOnDetails = false
                scrollToSearchMatch(messageId)
            },
            autoOpenTransferAdmin = openTransferOnDetails,
            autoOpenAddMember = openAddMemberOnDetails,
            onAutoOpenAddMemberConsumed = { openAddMemberOnDetails = false },
            onOpenSearch = {
                showDetails = false
                searchOpen = true
            },
        )
        return
    }

    val openDetailsDescription = stringResource(R.string.details)
    Scaffold(
        // The transcript consumes IME insets; the composer bottom bar is the sole
        // owner of keyboard padding so the reply-preview chip and input row move
        // as one cluster (#895, #1109).
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            if (searchOpen) {
                ConversationSearchTopBar(
                    query = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        // Re-anchor the cursor to the new query's match set on
                        // the next derivation; clearing the pin makes it land
                        // on the first match again.
                        searchPinnedMatchId = null
                    },
                    onClear = {
                        searchQuery = ""
                        searchPinnedMatchId = null
                    },
                    onClose = { closeSearch() },
                    onSearchAction = { navigateToSearchMatch(forward = true) },
                    focusRequester = searchFocusRequester,
                )
            } else {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier =
                                Modifier
                                    // Fill the title slot so the whole strip between
                                    // the back arrow and the overflow menu opens
                                    // details, not just the avatar/name. Those two
                                    // live in their own slots and keep their taps.
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { showDetails = true }
                                    .semantics { contentDescription = openDetailsDescription },
                        ) {
                            Avatar(
                                title = controller.title(groupTitleCopy),
                                // For a 1:1 DM the seed must match the peer-derived
                                // avatar so the initials fallback stays stable, just
                                // like the chat-list row (#837).
                                seed = controller.avatarAccount ?: controller.group.groupIdHex,
                                size = 36.dp,
                                pictureUrl = controller.avatarUrl,
                            )
                            Column {
                                Text(
                                    controller.title(groupTitleCopy),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // Subtitle line: members count (groups) and the
                                // disappearing-timer indicator inline on ONE row,
                                // not stacked. The one-time tooltip anchors to the
                                // whole line when the timer is on.
                                val membersSubtitle =
                                    if (
                                        shouldShowConversationMembersSubtitle(
                                            membersLoaded = controller.membersLoaded,
                                            openedAsDmHint = openedAsDmHint,
                                            groupName = controller.group.name,
                                            memberCount = controller.members.size,
                                        )
                                    ) {
                                        controller.subtitle(
                                            justYou = stringResource(R.string.just_you),
                                            oneMember = stringResource(R.string.one_member),
                                            membersFormat = stringResource(R.string.members_count),
                                        )
                                    } else {
                                        null
                                    }
                                val disappearingSecs = controller.group.disappearingMessageSecs.toLong()
                                val showTimer = disappearingSecs > 0L
                                if (membersSubtitle != null || showTimer) {
                                    val labelStyle = MaterialTheme.typography.labelSmall
                                    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    val subtitleRow: @Composable () -> Unit = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            if (membersSubtitle != null) {
                                                Text(membersSubtitle, style = labelStyle, color = labelColor)
                                            }
                                            if (showTimer) {
                                                if (membersSubtitle != null) {
                                                    Text("·", style = labelStyle, color = labelColor)
                                                }
                                                Icon(
                                                    Icons.Default.Schedule,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(13.dp),
                                                    tint = labelColor,
                                                )
                                                Text(disappearingMessagesLabel(disappearingSecs), style = labelStyle, color = labelColor)
                                            }
                                        }
                                    }
                                    if (showTimer) {
                                        val timerTooltipState = rememberTooltipState(isPersistent = true)
                                        val timerTooltipText = stringResource(R.string.disappearing_tooltip_text)
                                        // Snapshot the one-time decision so marking the flag
                                        // (which we do first, to persist before a quick exit
                                        // can re-arm it) doesn't recompose this branch away and
                                        // cancel the still-suspended show().
                                        val showTooltipOnce =
                                            remember(controller.group.groupIdHex) {
                                                !appState.disappearingTooltipShown
                                            }
                                        if (showTooltipOnce) {
                                            LaunchedEffect(controller.group.groupIdHex) {
                                                appState.markDisappearingTooltipShown()
                                                timerTooltipState.show()
                                            }
                                        }
                                        TooltipBox(
                                            positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
                                            tooltip = { RichTooltip { Text(timerTooltipText) } },
                                            state = timerTooltipState,
                                            content = subtitleRow,
                                        )
                                    } else {
                                        subtitleRow()
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_actions))
                        }
                        KeyboardPreservingDropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            shape = RoundedCornerShape(20.dp),
                            // Inset the panel from the right screen edge instead
                            // of letting it sit flush against it.
                            offset = DpOffset(x = (-8).dp, y = 0.dp),
                            modifier = Modifier.widthIn(min = 232.dp),
                        ) {
                            // Iconless, roomier rows: each entry reads as a
                            // full-width tappable line of body-large text rather
                            // than a compact icon+label cell.
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.conversation_search_open),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                },
                                contentPadding = conversationMenuItemPadding,
                                onClick = {
                                    menuOpen = false
                                    // Snapshot the current scroll position before the
                                    // search auto-scroll effect can move the list, so
                                    // closing search can restore it (#292).
                                    preSearchScrollAnchor =
                                        listState.firstVisibleItemIndex to
                                        listState.firstVisibleItemScrollOffset
                                    searchOpen = true
                                },
                            )
                            if (!controller.group.pendingConfirmation) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(if (controller.group.archived) R.string.unarchive else R.string.archive),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    },
                                    contentPadding = conversationMenuItemPadding,
                                    enabled = !controller.mutationInFlight,
                                    onClick = {
                                        menuOpen = false
                                        appState.launchMutation { controller.setArchived(!controller.group.archived) }
                                    },
                                )
                                if (controller.isSelfMember) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.leave),
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        },
                                        contentPadding = conversationMenuItemPadding,
                                        // Gate on membersLoaded: the sole-admin routing
                                        // below reads the roster, and an empty (unloaded)
                                        // roster would misroute to a plain leave.
                                        enabled = !controller.mutationInFlight && controller.membersLoaded,
                                        onClick = {
                                            menuOpen = false
                                            // A sole admin with other members can't
                                            // leave until they transfer admin; route
                                            // them to the transfer flow instead of
                                            // the old leaveGroup() toast dead end.
                                            appState.launchMutation {
                                                when (val leaveAction = controller.leaveAction()) {
                                                    LeaveAction.SoleAdminMustTransfer -> showTransferAdminFirst = true
                                                    LeaveAction.SoleMemberDeletesGroup,
                                                    LeaveAction.Standard,
                                                    -> pendingTopBarLeaveAction = leaveAction
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            // Measure the real bottom chrome (composer with reply/edit banners and
            // grown multi-line input, search nav bar, invite bar, or notice) and lift
            // the global toast host by that amount, instead of assuming the resting
            // single-line composer height (#122, #796). Nav/IME insets are subtracted
            // because WhiteNoiseSnackbarHost pads for those itself.
            val chromeInsets = WindowInsets.navigationBars.union(WindowInsets.ime)
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        val chromeBottom = chromeInsets.getBottom(density)
                        snackbarBottomInset.value =
                            with(density) { (size.height - chromeBottom).coerceAtLeast(0).toDp() }
                    },
            ) {
                when {
                    // While search is open the composer steps aside for the match
                    // navigation bar pinned above the keyboard.
                    searchOpen ->
                        ConversationSearchNavBar(
                            matchCount = searchMatchIds.size,
                            activeIndex = searchActiveIndex,
                            hasQuery = searchQuery.isNotBlank(),
                            onPrev = { navigateToSearchMatch(forward = false) },
                            onNext = { navigateToSearchMatch(forward = true) },
                        )
                    controller.error != null -> Unit
                    // Membership-display gate (issues #545 and #623). During the
                    // brief window before refreshMembers() confirms the roster we
                    // must not flash a state we don't actually know: a left group
                    // flashing the active composer (#545) OR a member's group —
                    // especially an admin re-entering their own group — flashing the
                    // "no longer a member" notice (#623, the inverse). The gate
                    // paints the composer only for a (believed) member, the notice
                    // only for a known not-member, and NOTHING while membership is
                    // genuinely unknown (cold open with no seeding snapshot), where
                    // it upgrades on the confirmed result. The controller's
                    // `canSendMessages` guard still keeps any actual mutation safe
                    // until membership is verified.
                    else ->
                        when (
                            conversationComposerGate(
                                pendingInvite = controller.group.pendingConfirmation,
                                membersVerified = controller.membersVerified,
                                isSelfMember = controller.isSelfMember,
                                seededSelfMember = controller.seededSelfMember,
                                seededMembershipKnown = controller.seededMembershipKnown,
                                assumeMemberUntilVerified = openedFromNotification,
                            )
                        ) {
                            // Reserve the composer's resting height while membership
                            // is still unknown (e.g. right after an account switch),
                            // so the bottom inset is stable and the composer doesn't
                            // pop in over the last message once it resolves. Matches
                            // ComposerBar's resting height (the 44.dp pill plus its
                            // Column's 10.dp vertical padding top and bottom). Kept
                            // transparent so no surface colour flashes before the
                            // composer or notice resolves.
                            ComposerGate.PENDING ->
                                Spacer(
                                    Modifier
                                        .fillMaxWidth()
                                        .navigationBarsPadding()
                                        .imePadding()
                                        .height(64.dp),
                                )
                            ComposerGate.NOTICE -> RemovedMemberComposerNotice()
                            ComposerGate.INVITE ->
                                InvitePreviewActionBar(
                                    mutationInFlight = controller.mutationInFlight,
                                    onJoin = { appState.launchMutation { controller.acceptInvite() } },
                                    onDecline = {
                                        appState.launchMutation {
                                            if (controller.declineInvite()) onBack()
                                        }
                                    },
                                )
                            ComposerGate.COMPOSER -> {
                                val groupIdHex = controller.group.groupIdHex
                                val editingRecord =
                                    controller.editingMessageId?.let { id ->
                                        controller.timeline.firstOrNull { it.record.messageIdHex == id }?.record
                                    }
                                // #414: candidates for the @-mention picker — the group's
                                // own roster minus the local account (you can't mention
                                // yourself). Keyed on the roster + profile revision so a
                                // late-arriving display name / nip05 re-derives the list.
                                // The list already arrives most-recently-active first from
                                // the roster, and MentionComposer.filter preserves order.
                                val mentionPickerEnabled = !controller.isDm
                                val mentionMemberIds =
                                    remember(controller.members) {
                                        controller.members.map { it.memberIdHex }
                                    }
                                LaunchedEffect(mentionPickerEnabled, mentionMemberIds) {
                                    if (mentionPickerEnabled) {
                                        appState.requestProfiles(mentionMemberIds)
                                    }
                                }
                                val mentionCandidates =
                                    if (mentionPickerEnabled) {
                                        val revision = appState.profileRevisionForCompose
                                        val activeAccountIdHex = appState.activeAccount?.accountIdHex
                                        remember(controller.members, revision, activeAccountIdHex) {
                                            controller.members
                                                // Exclude only the active account, not every member
                                                // flagged `local`. Marmot sets `local` for any identity
                                                // present on the device, which on some rosters marks all
                                                // members local and would empty the mention list entirely.
                                                // Mirrors the isActiveAccountMember gate used for admin actions.
                                                .filterNot { GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
                                                .map { member ->
                                                    MentionComposer.Candidate(
                                                        accountIdHex = member.memberIdHex,
                                                        npub = appState.npub(member.memberIdHex),
                                                        displayName = appState.chatMemberTitleCached(member.memberIdHex),
                                                        nip05 = appState.userProfile(member.memberIdHex)?.nip05,
                                                    )
                                                }
                                        }
                                    } else {
                                        emptyList()
                                    }
                                ComposerBar(
                                    replyingTo = controller.replyingTo,
                                    messageTextCopy = messageTextCopy,
                                    onCancelReply = { controller.replyingTo = null },
                                    onSend = { text, onAccepted -> appState.launchMutation { controller.send(text, onAccepted) } },
                                    initialDraft = appState.draftFor(groupIdHex).orEmpty(),
                                    onDraftChange = { appState.setDraft(groupIdHex, it) },
                                    draftKey = groupIdHex,
                                    editingMessageId = controller.editingMessageId,
                                    editingInitialText = editingRecord?.let { controller.displayedText(it) },
                                    onCancelEdit = { controller.editingMessageId = null },
                                    onAfterSend = {
                                        // Always pull the user down to see their just-sent
                                        // bubble, even if they were reading older history.
                                        // Matches standard chat-app behavior.
                                        scope.launch {
                                            val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                            listState.animateScrollToItem(lastIndex)
                                        }
                                    },
                                    onPickFromGallery = {
                                        imagePickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                        )
                                    },
                                    onCaptureFromCamera = {
                                        val granted =
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.CAMERA,
                                            ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            launchCameraCapture()
                                        } else {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                    onPickDocument = {
                                        // `*/*` lets the system file picker surface every
                                        // installed provider (Drive, Downloads, Files…)
                                        // without restricting by MIME. Bytes upload as-is.
                                        documentPickerLauncher.launch(arrayOf("*/*"))
                                    },
                                    voiceRecordingController = voiceRecordingController,
                                    appState = appState,
                                    mentionCandidates = mentionCandidates,
                                    mentionPickerEnabled = mentionPickerEnabled,
                                    autoFocusOnEnter = justCreated,
                                    enterKeyBehavior = appState.enterKeyBehavior,
                                    // #589: hoisted focus plumbing — the requester lets the
                                    // resume observer restore focus, and the callback keeps
                                    // `composerFocused` tracking the live keyboard state.
                                    composerFocus = composerFocus,
                                    onComposerFocusChanged = { composerFocused = it },
                                    onBottomInputChanged = ::reanchorNewestAfterBottomInputChange,
                                )
                            }
                        }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // The composer bottomBar owns IME padding; consume here so the
                // transcript does not count the keyboard a second time (#895).
                .consumeWindowInsets(WindowInsets.ime),
        ) {
            when {
                controller.error != null -> ErrorContent(stringResource(R.string.couldnt_load_conversation), controller.error.orEmpty())
                controller.group.pendingConfirmation && renderedTimeline.isEmpty() && !controller.isLoading && initialTimelineLoadStarted ->
                    InvitePreviewPlaceholder(
                        inviterName = controller.inviteAccount?.let { appState.chatMemberTitle(it) },
                    )
                controller.group.pendingConfirmation && renderedTimeline.isEmpty() -> LoadingScreen()
                controller.timeline.isEmpty() && !controller.isLoading && initialTimelineLoadStarted -> {
                    if (
                        canInviteFromEmptyGroup(
                            isSelfMember = controller.isSelfMember,
                            isSelfAdmin = controller.isSelfAdmin,
                            membersLoaded = controller.membersLoaded,
                            memberCount = controller.members.size,
                        )
                    ) {
                        EmptyGroupConversation(
                            onAddMembers = {
                                openAddMemberOnDetails = true
                                showDetails = true
                            },
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.no_messages_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                else ->
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp)
                                    .alpha(if (initialTimelineAnchored) 1f else 0f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 8.dp),
                        ) {
                            item(key = "top-spacer") { Spacer(Modifier.height(4.dp)) }
                            if (controller.hasMoreBefore || controller.isLoadingOlder) {
                                item(key = "older-messages-loading") {
                                    Box(
                                        Modifier.fillMaxWidth().height(40.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (controller.isLoadingOlder) {
                                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        } else {
                                            IconButton(onClick = { scope.launch { controller.loadOlder() } }) {
                                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                                            }
                                        }
                                    }
                                }
                            }
                            itemsIndexed(
                                renderedTimeline,
                                key = { _, item -> item.id },
                                // Pool layouts by category so Compose can reuse
                                // the heavier MessageBubble slot across scroll
                                // without recreating layout nodes for the
                                // simpler centered group-system rows.
                                contentType = { _, item ->
                                    if (MessageProjector.isGroupSystem(item.record)) "groupSystem" else "message"
                                },
                            ) { index, item ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .onSizeChanged { size ->
                                            if (size.height > 0 && timelineItemHeightsPx[item.record.messageIdHex] != size.height) {
                                                timelineItemHeightsPx[item.record.messageIdHex] = size.height
                                            }
                                        },
                                ) {
                                    // Rendered inside the slot, not as its own item, so
                                    // the anchor index math stays intact.
                                    val older = renderedTimeline.getOrNull(index - 1)
                                    val daySeparatorLabel =
                                        remember(older?.record?.recordedAt, item.record.recordedAt, transcriptLocale) {
                                            if (older == null || differentDay(older.record.recordedAt, item.record.recordedAt)) {
                                                messageDayLabel(item.record.recordedAt, transcriptLocale)
                                            } else {
                                                null
                                            }
                                        }
                                    if (daySeparatorLabel != null) {
                                        DaySeparator(daySeparatorLabel)
                                    }
                                    if (entryUnreadCount > 0 && item.record.messageIdHex == entryFirstUnreadMessageId) {
                                        UnreadMessagesDivider(count = entryUnreadCount)
                                    }
                                    // Synthetic `dbg:stream:` rows must never fall
                                    // through to normal message rendering — not even in
                                    // the window between the toggle flipping off and the
                                    // republish that drops them. Draw the debug row only
                                    // when enabled; otherwise suppress the row entirely.
                                    if (item.id.startsWith(ConversationController.STREAM_DEBUG_ID_PREFIX)) {
                                        if (appState.streamingDebugEnabled) {
                                            StreamDebugEventRow(record = item.record)
                                        }
                                        return@Column
                                    }
                                    // One decision point for which row a record renders
                                    // as. Group-system (kind 1210) rows are derived state
                                    // facts, not chat: they render the centered one-line
                                    // summary, never a raw-JSON bubble — and that summary
                                    // stays the default even in developer mode, with the
                                    // MLS dump reachable per-row behind a tap (#857). The
                                    // debug-row path covers the other non-user-visible
                                    // signaling kinds only when streaming debug is on, so
                                    // the timeline is byte-identical to today when off.
                                    when (timelineRowKind(item.record, appState.streamingDebugEnabled)) {
                                        TimelineRowKind.GroupSystem -> {
                                            GroupSystemRow(
                                                record = item.record,
                                                appState = appState,
                                                groupSystem = item.projected?.groupSystem,
                                            )
                                            return@Column
                                        }
                                        TimelineRowKind.DebugRow -> {
                                            MessageDebugRow(
                                                style = MessageDebugClassifier.debugStyle(item.record),
                                                record = item.record,
                                            )
                                            return@Column
                                        }
                                        TimelineRowKind.Bubble -> Unit
                                    }
                                    MessageBubble(
                                        item = item,
                                        controller = controller,
                                        appState = appState,
                                        highlighted = item.record.messageIdHex == highlightedMessageId,
                                        quickReactionEmojis = quickReactionEmojis,
                                        isActionMenuOpen = openActionMenuId == item.record.messageIdHex,
                                        onActionMenuOpenChange = { open ->
                                            openActionMenuId = if (open) item.record.messageIdHex else null
                                        },
                                        // Lambdas, not method references: the Compose
                                        // compiler memoizes lambdas but allocates a fresh
                                        // function reference per recomposition, which made
                                        // every visible bubble recompose on any timeline
                                        // change. See #110.
                                        onReactionEmojiPicked = { recordReactionEmoji(it) },
                                        onQuickReactionsSave = { saveQuickReactionEmojis(it) },
                                        onQuickReactionsReset = { resetQuickReactionEmojis() },
                                        onReplyPreviewClick = { navigateToReplyTarget(it) },
                                        readOnly = controller.group.pendingConfirmation,
                                    )
                                }
                            }
                            // Kept minimal (matches the top-spacer) so the last
                            // bubble sits a tight breathing-room above the
                            // composer rather than orphaned in mid-screen; the
                            // 8dp item spacing + 8dp content padding already
                            // supply the gap. Retained (not removed) so the
                            // bottom-anchor index math stays stable.
                            item(key = "bottom-spacer") { Spacer(Modifier.height(4.dp)) }
                        }
                        // Day of the topmost visible message, shown only while
                        // scrolling — the inline separators carry it at rest.
                        // Confined to its own child so the scroll-backed reads
                        // (label + isScrollInProgress) recompose only the ribbon,
                        // not this LazyColumn-hosting Box scope (#375).
                        if (initialTimelineAnchored) {
                            StickyDayRibbon(
                                listState = listState,
                                labelState = stickyDayLabelState,
                            )
                        }
                        if (!initialTimelineAnchored) {
                            LoadingScreen()
                        }
                        if (initialTimelineAnchored) {
                            Column(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(12.dp),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // Jump-to-mention chip: tap visits the oldest unread
                                // mention and marks it read, so the count steps down.
                                val mentionCount = unreadMentionMessageIds.size
                                if (mentionCount > 0) {
                                    val jumpToMentionLabel = stringResource(R.string.conversation_jump_to_mention)
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                        shadowElevation = 2.dp,
                                        modifier =
                                            Modifier
                                                .height(34.dp)
                                                .semantics { contentDescription = jumpToMentionLabel }
                                                .clickable { jumpToNextUnreadMention() },
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                        ) {
                                            Text("@", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                if (mentionCount > 99) "99+" else mentionCount.toString(),
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                        }
                                    }
                                }
                                if (!nearBottom) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        shadowElevation = 2.dp,
                                        modifier =
                                            Modifier
                                                .size(34.dp)
                                                .clickable {
                                                    scope.launch {
                                                        val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                                        listState.animateScrollToItem(lastIndex)
                                                    }
                                                },
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.ArrowDownward,
                                                contentDescription = stringResource(R.string.jump_to_newest),
                                                modifier = Modifier.size(16.dp),
                                            )
                                            if (unreadIncomingCount > 0) {
                                                Badge(
                                                    modifier =
                                                        Modifier
                                                            .align(Alignment.TopEnd)
                                                            .offset(x = 10.dp, y = (-10).dp),
                                                ) {
                                                    Text(
                                                        if (unreadIncomingCount > 99) "99+" else unreadIncomingCount.toString(),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }

    pendingTopBarLeaveAction?.let { leaveAction ->
        val soleMember = leaveAction == LeaveAction.SoleMemberDeletesGroup
        val topBarGroupName = controller.title(groupTitleCopy)
        ConfirmDialog(
            title =
                if (soleMember) {
                    stringResource(R.string.confirm_leave_sole_member_title, topBarGroupName)
                } else {
                    stringResource(R.string.confirm_leave_title)
                },
            message =
                if (soleMember) {
                    stringResource(R.string.confirm_leave_sole_member_message)
                } else {
                    stringResource(R.string.confirm_leave_message)
                },
            confirmLabel = stringResource(R.string.leave),
            onConfirm = {
                pendingTopBarLeaveAction = null
                appState.launchMutation {
                    if (controller.leaveGroup()) onBack()
                }
            },
            onDismiss = { pendingTopBarLeaveAction = null },
            destructive = soleMember,
        )
    }

    if (showTransferAdminFirst) {
        ConfirmDialog(
            title = stringResource(R.string.sole_admin_leave_blocked_title),
            message = stringResource(R.string.sole_admin_leave_blocked_message),
            confirmLabel = stringResource(R.string.transfer_admin),
            onConfirm = {
                showTransferAdminFirst = false
                openTransferOnDetails = true
                showDetails = true
            },
            onDismiss = { showTransferAdminFirst = false },
        )
    }

    if (pendingMediaUris.isNotEmpty() || pendingDocumentUris.isNotEmpty()) {
        val imageUris = pendingMediaUris
        val documentUris = pendingDocumentUris
        MediaPreviewSheet(
            uris = imageUris,
            documentUris = documentUris,
            onDismiss = {
                pendingMediaUris = emptyList()
                pendingDocumentUris = emptyList()
            },
            onSend = { caption ->
                pendingMediaUris = emptyList()
                pendingDocumentUris = emptyList()
                sendStagedAttachments(
                    imageUris,
                    documentUris,
                    caption,
                    onAfterSend = {
                        // Pull the user down to the just-seeded bubble.
                        // `bottomTimelineIndex` reads from
                        // [renderedTimeline.size] (the snapshot-backed
                        // controller list) instead of
                        // [LazyListState.layoutInfo.totalItemsCount], which
                        // is stale until the next recompose — for a
                        // multi-file send that staleness leaves the user
                        // one-or-more rows above the new bubble.
                        scope.launch { listState.animateScrollToItem(bottomTimelineIndex) }
                    },
                )
            },
            onRemoveAt = { index ->
                pendingMediaUris =
                    pendingMediaUris.toMutableList().apply {
                        if (index in indices) removeAt(index)
                    }
            },
            onRemoveDocumentAt = { index ->
                pendingDocumentUris =
                    pendingDocumentUris.toMutableList().apply {
                        if (index in indices) removeAt(index)
                    }
            },
            onAddPhotos = {
                imagePickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            },
            onAddDocuments = { documentPickerLauncher.launch(arrayOf("*/*")) },
        )
    }
}

@Composable
private fun InvitePreviewPlaceholder(inviterName: String?) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Default.Group,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text =
                    inviterName
                        ?.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.invite_preview_with_inviter, it) }
                        ?: stringResource(R.string.invited_to_this_group),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun InvitePreviewActionBar(
    mutationInFlight: Boolean,
    onJoin: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onDecline,
                modifier = Modifier.weight(1f),
                enabled = !mutationInFlight,
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.decline))
            }
            Button(
                onClick = onJoin,
                modifier = Modifier.weight(1f),
                enabled = !mutationInFlight,
            ) {
                if (mutationInFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.join_group))
            }
        }
    }
}

@Composable
private fun EmptyGroupConversation(onAddMembers: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(56.dp))
            Text(
                stringResource(R.string.group_empty_only_you_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.group_empty_invite_members),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onAddMembers) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_member))
            }
        }
    }
}

@Composable
internal fun messageBubbleBorder(
    highlighted: Boolean,
    mine: Boolean,
): BorderStroke? =
    when {
        highlighted -> BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
        mine && isAmoledSurfaceTheme() -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        else -> amoledSurfaceBorderStroke()
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    item: TimelineMessage,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    highlighted: Boolean,
    quickReactionEmojis: List<String>,
    isActionMenuOpen: Boolean,
    onActionMenuOpenChange: (Boolean) -> Unit,
    onReactionEmojiPicked: (String) -> Unit,
    onQuickReactionsSave: (List<String>) -> Unit,
    onQuickReactionsReset: () -> Unit,
    onReplyPreviewClick: (TimelineMessage) -> Unit,
    readOnly: Boolean = false,
) {
    val amoledSurfaceTheme = isAmoledSurfaceTheme()
    val record = item.record
    val mine = MessageProjector.isMine(record, appState.activeAccount?.accountIdHex)
    val deleted = item.projected?.deleted == true || MessageProjector.isDeleted(record.messageIdHex, controller.deletedMessageIds)
    // Convergence dropped this message onto a losing branch: it never reached
    // the group. The record survives as a tombstone, so flag it (an explicit
    // delete takes precedence over an invalidation tombstone).
    val invalidated = !deleted && item.projected?.invalidationStatus != null
    val bubbleColor =
        when {
            invalidated -> MaterialTheme.colorScheme.errorContainer
            amoledSurfaceTheme -> Color.Black
            deleted -> MaterialTheme.colorScheme.surfaceVariant
            mine -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    // #414: "you were mentioned" treatment. A received (not mine), live (not
    // deleted/invalidated) message whose markdown body @-mentions the current
    // account gets a left-edge accent line so a self-mention is spottable while
    // scrolling. Keyed on the body tokens + account so a late account switch /
    // profile load re-evaluates. The resolver is the FFI bech32→hex encoding;
    // the detection walk itself is the pure documentMentionsAccount.
    val selfAccountIdHex = appState.activeAccount?.accountIdHex
    val mentionedSelf =
        !mine &&
            !deleted &&
            !invalidated &&
            remember(record.contentTokens, selfAccountIdHex) {
                documentMentionsAccount(
                    document = record.contentTokens,
                    accountIdHex = selfAccountIdHex,
                    resolveAccountIdHex = { bech32 -> appState.accountIdHexForMention(bech32) },
                )
            }
    val mentionedYouLabel = stringResource(R.string.mentioned_you)
    val scope = rememberCoroutineScope()
    // Window-space y of the long-press touch so the action popover can anchor
    // near the finger on a bubble taller than the screen instead of
    // degenerating to the visible bubble top (#326). Window space (not
    // row-local) so the menu can offset against its own anchor regardless of
    // where in the transcript the bubble sits.
    var longPressWindowY by remember { mutableStateOf<Float?>(null) }
    var rowBoundsTopPx by remember { mutableStateOf(0f) }
    var swipeDrag by remember(record.messageIdHex) { mutableStateOf(0f) }
    val animatedSwipeOffset by animateFloatAsState(targetValue = swipeDrag, label = "replySwipeOffset")
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val replySwipeThresholdPx = with(density) { 64.dp.toPx() }
    val maxSwipeOffsetPx = with(density) { 72.dp.toPx() }
    val messageTextCopy = rememberMessageTextCopy()
    val deletedBodyText = stringResource(R.string.message_deleted)
    val messageActionsLabel = stringResource(R.string.message_actions)
    val invalidatedBodyText = stringResource(R.string.message_invalidated)
    // Cached like the media references below: displayBody sanitizes/allocates
    // per call, and recomputing it for every visible bubble on every timeline
    // recomposition adds up. See #131.
    // Kind-1009 edits replace the body of an existing kind-9 chat. When an
    // edit is present for this message's id, prefer the latest edited text
    // over the original projection. Keyed on editState so a fresh edit
    // recomposes the bubble in place.
    val editState = controller.editsByTarget[record.messageIdHex]
    val displayedBody =
        remember(item, deleted, invalidated, messageTextCopy, deletedBodyText, invalidatedBodyText, editState) {
            when {
                // Check `deleted` first so the optimistic tombstone (from
                // controller.deletedMessageIds) renders immediately on tap.
                deleted -> deletedBodyText
                invalidated -> invalidatedBodyText
                // Edit overlay wins over both projected and raw plaintext.
                // We don't go through MessageProjector here — the edit
                // payload is plain text by spec; markdown re-parse will
                // happen below if record.contentTokens is populated, but
                // for kind-9 edits the body is the latest version verbatim.
                editState != null && record.kind == 9uL -> editState.latestText
                item.projected != null ->
                    TimelineProjector.displayBody(
                        item.projected,
                        messageTextCopy.copy(deleted = deletedBodyText),
                    )
                else -> MessageProjector.displayBody(record, messageTextCopy)
            }
        }
    // Issue #390 v1 forwards text only. Forward must be hidden for any record
    // whose displayed body is a synthetic surrogate (media filename/placeholder,
    // "Reacted …", delete/system summaries, agent-stream copy) — forwarding
    // `displayedBody` there would send misleading text into other groups. The
    // raw text to forward is the edit-aware verbatim body, never the display
    // fallback; `forwardBody` is null exactly when the message is not a
    // forwardable text record, which also drives the menu gate below.
    val forwardBody: String? =
        remember(record, editState, deleted, invalidated) {
            if (deleted || invalidated) {
                null
            } else {
                MessageProjector.forwardableText(
                    record,
                    editedText = editState?.latestText?.takeIf { record.kind == 9uL },
                )
            }
        }
    val showSenderAvatar =
        GroupProjector.shouldShowTranscriptSenderAvatar(
            memberCount = controller.members.size,
            mine = mine,
        )
    // Match the timestamp to the bubble's on-color family. The mine bubble
    // fills with primaryContainer, so the M3 paired token is onPrimaryContainer;
    // using onSurfaceVariant there blends into the tint and reads as invisible.
    val timestampColor =
        when {
            invalidated -> MaterialTheme.colorScheme.onErrorContainer
            amoledSurfaceTheme -> MaterialTheme.colorScheme.onSurfaceVariant
            mine && !deleted -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    var emojiPickerOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    // A long body clips to a few lines with an inline Read More; opening it
    // routes through a full-screen view rather than expanding in place, so the
    // only state to track is whether that view is showing. Resets on re-entry
    // by keying on the message id (#325).
    var expandedFullView by remember(record.messageIdHex) { mutableStateOf(false) }
    var infoSheetOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var forwardSheetOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var editHistoryOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var reactionSheetOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var customizeReactionsOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var restoreReactionPickerExpanded by remember(record.messageIdHex) { mutableStateOf(false) }
    // A deleted message is inert: tear down any open action/reaction surface if
    // the message is deleted out from under it (optimistic or remote delete).
    LaunchedEffect(deleted) {
        if (deleted) {
            onActionMenuOpenChange(false)
            emojiPickerOpen = false
            reactionSheetOpen = false
        }
    }

    fun beginReply() {
        if (readOnly) return
        controller.replyingTo = record
        onActionMenuOpenChange(false)
    }

    fun openInfoSheet() {
        onActionMenuOpenChange(false)
        infoSheetOpen = true
    }

    fun reactWithEmoji(emoji: String) {
        // Chokepoint guard: never react to a deleted message, whatever path
        // (menu, emoji picker) called in — even if that surface was open when
        // the delete landed.
        if (deleted || readOnly) return
        onReactionEmojiPicked(emoji)
        // Route via launchMutation: same survives-navigation rationale as delete/send.
        appState.launchMutation { controller.toggleReaction(emoji, record) }
    }

    fun copyMessageText() {
        clipboard.setText(AnnotatedString(displayedBody))
        appState.present(R.string.copied)
        onActionMenuOpenChange(false)
    }

    fun beginForward() {
        // Defensive: the menu only renders Forward when forwardBody != null, but
        // gate here too so a stale tap can never open the picker for a non-text
        // record (issue #390 is text-only).
        if (forwardBody == null) return
        onActionMenuOpenChange(false)
        forwardSheetOpen = true
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val messageGroupMaxWidth = maxWidth * 0.95f
        val senderAvatarWidth = if (showSenderAvatar) 40.dp else 0.dp
        val bubbleColumnMaxWidth = (messageGroupMaxWidth - senderAvatarWidth).coerceAtLeast(120.dp)

        Row(
            // Both reply-swipe and long-press hitboxes cover the ENTIRE row,
            // not just the bubble: a swipe-right or long-press starting on the
            // surrounding whitespace (avatar gutter, empty space next to the
            // bubble) triggers the same action as one starting on the bubble.
            // See #204. The visual slide stays on the Surface below via
            // `.offset`; only gesture detection lives on the row. Nested
            // handlers (avatar, sender name, reaction chips) are children and
            // still win for their own SHORT taps. Long-press is detected with a
            // raw pointerInput (below) rather than combinedClickable so it wins
            // over inner media `clickable` children for the long-press while
            // leaving their single-tap behavior intact (#262). The detector
            // raises no ripple, matching the previous full-row behavior.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        // A deleted message has no actionable content, so
                        // disable swipe-to-reply entirely: no drag, no trigger.
                        if (deleted || readOnly) {
                            Modifier
                        } else {
                            Modifier.pointerInput(record.messageIdHex, replySwipeThresholdPx, maxSwipeOffsetPx) {
                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { change, dragAmount ->
                                        val next = ReplySwipe.visualOffset(swipeDrag + dragAmount, maxSwipeOffsetPx)
                                        if (next != swipeDrag || dragAmount > 0f) change.consume()
                                        swipeDrag = next
                                    },
                                    onDragEnd = {
                                        if (ReplySwipe.shouldTriggerReply(swipeDrag, totalY = 0f, threshold = replySwipeThresholdPx)) {
                                            beginReply()
                                        }
                                        swipeDrag = 0f
                                    },
                                    onDragCancel = { swipeDrag = 0f },
                                )
                            }
                        },
                    ).then(
                        // Long-press lives in a raw pointerInput, not
                        // combinedClickable, so it WINS over inner media
                        // children (image/video/file/voice) that install their
                        // own tap `clickable`. Those children sit deeper in the
                        // hit-test tree and would otherwise swallow the press
                        // before a row-level combinedClickable saw the
                        // long-press — which is why long-press did nothing on a
                        // media bubble while it worked on a text bubble (#262).
                        // awaitLongPressOrCancellation observes the down WITHOUT
                        // consuming it (so a quick tap still reaches the child's
                        // viewer/player) and only fires once the press is held
                        // past the long-press timeout, at which point it wins
                        // the gesture and opens the actions menu. It self-cancels
                        // on movement beyond touch slop, so swipe-to-reply above
                        // is unaffected.
                        if (deleted) {
                            // A deleted message has no actions menu.
                            Modifier
                        } else {
                            Modifier.pointerInput(record.messageIdHex) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val longPress = awaitLongPressOrCancellation(down.id)
                                    if (longPress != null) {
                                        longPress.consume()
                                        // Capture the press y in window space before
                                        // opening so the popover anchors at the
                                        // finger, not the bubble top (#326).
                                        longPressWindowY = rowBoundsTopPx + longPress.position.y
                                        haptics.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                        )
                                        onActionMenuOpenChange(true)
                                    }
                                }
                            }
                        },
                    ).then(
                        // The raw pointerInput above only fires on a physical
                        // pointer long-press, so it leaves accessibility services
                        // (TalkBack, Switch Access) and keyboard/semantic callers
                        // without a way to reach the actions menu — a regression
                        // from the old combinedClickable, which exposed an
                        // onLongClick semantic action for the whole row (#262).
                        // Re-publish that action via Modifier.semantics so the
                        // reply/copy/delete/reaction entry point stays reachable
                        // without a hold gesture. Guarded by `!deleted` to match
                        // the pointer detector (a deleted message has no menu).
                        if (deleted) {
                            Modifier
                        } else {
                            Modifier.semantics {
                                onLongClick(label = messageActionsLabel) {
                                    // Accessibility entry has no touch point;
                                    // anchor to the bubble top (#326).
                                    longPressWindowY = null
                                    onActionMenuOpenChange(true)
                                    true
                                }
                            }
                        },
                    )
                    // Window-space top of the row, added to the local press y so
                    // the popover can be offset against the menu's own anchor (#326).
                    .onGloballyPositioned { rowBoundsTopPx = it.boundsInWindow().top },
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        ) {
            if (showSenderAvatar) {
                Box(
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .clickable { appState.presentProfile(appState.npub(record.sender)) },
                ) {
                    Avatar(
                        title = appState.displayName(record.sender),
                        seed = record.sender,
                        size = 32.dp,
                        pictureUrl = appState.avatarUrl(record.sender),
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(
                modifier = Modifier.widthIn(max = bubbleColumnMaxWidth),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                // #414: left-edge accent in the bubble's start gutter when
                // the current account is mentioned. Drawn in the 14.dp
                // start padding so it reads as a rail without reflowing the
                // content; uses the same content-derived accent the mention
                // highlight uses. drawBehind paints over the surface fill
                // (it's the Column's own background layer), so it stays
                // visible on the surfaceVariant received bubble. Hoisted out
                // of the bubble Surface so the caption bubble can reuse it when
                // media renders on its own (#527).
                val mentionAccentColor = MaterialTheme.colorScheme.primary
                val mentionRailModifier =
                    if (mentionedSelf) {
                        Modifier
                            .semantics { contentDescription = mentionedYouLabel }
                            .drawBehind {
                                val railWidth = 3.dp.toPx()
                                val inset = 4.dp.toPx()
                                val radius =
                                    androidx.compose.ui.geometry
                                        .CornerRadius(railWidth / 2f, railWidth / 2f)
                                drawRoundRect(
                                    color = mentionAccentColor,
                                    topLeft =
                                        androidx.compose.ui.geometry
                                            .Offset(inset, inset),
                                    size =
                                        androidx.compose.ui.geometry.Size(
                                            railWidth,
                                            (size.height - inset * 2).coerceAtLeast(railWidth),
                                        ),
                                    cornerRadius = radius,
                                )
                            }
                    } else {
                        Modifier
                    }
                // Resolved before the content column so its presence can pick
                // the column's width strategy (#428).
                //
                // Projected items: the preview is a pure function of
                // item.projected, so caching keyed on the item is always
                // correct (a reprojection replaces the instance). The
                // optimistic fallback instead resolves the target from
                // controller.messageById, which can gain the target after
                // this bubble composes — resolve those live. Display names
                // resolve outside the cache either way so a late profile
                // load still updates them. See #131.
                val replyPreview =
                    if (item.projected != null) {
                        remember(item, messageTextCopy) {
                            controller.replyPreview(item, messageTextCopy)
                        }
                    } else {
                        controller.replyPreview(item, messageTextCopy)
                    }
                // Prefer the controller's listMedia cache — it carries
                // the receive-side `sourceEpoch`, which the imeta-tag
                // parser can't recover (no epoch field in the wire
                // format). Fall back to the imeta parser for optimistic
                // bridge records that haven't been projected yet.
                val mediaReferences =
                    remember(record.tags, record.messageIdHex, controller.mediaReferences) {
                        controller.mediaReferences[record.messageIdHex]
                            ?: MediaReferenceParser.parseAllImetaTags(record.tags)
                    }
                // Split media into image refs (rendered as a bubble or
                // 2-col grid) and file refs (a list of pills). Mixed
                // albums render both: images on top, file pills below.
                // `IndexedValue` preserves the real protocol-level
                // attachmentIndex from the full `mediaReferences`
                // list so per-tile cache lookups never collide across
                // image and file subsets.
                val imageAttachments =
                    remember(mediaReferences) {
                        mediaReferences
                            .withIndex()
                            .filter { (_, ref) -> MediaReferenceParser.isImageMedia(ref) }
                            .toList()
                    }
                val audioAttachments =
                    remember(mediaReferences) {
                        mediaReferences
                            .withIndex()
                            .filter { (_, ref) -> MediaReferenceParser.isAudioMedia(ref) }
                            .toList()
                    }
                val videoAttachments =
                    remember(mediaReferences) {
                        mediaReferences
                            .withIndex()
                            .filter { (_, ref) -> MediaReferenceParser.isVideoMedia(ref) }
                            .toList()
                    }
                val fileAttachments =
                    remember(mediaReferences) {
                        mediaReferences
                            .withIndex()
                            .filter { (_, ref) ->
                                !MediaReferenceParser.isImageMedia(ref) &&
                                    !MediaReferenceParser.isAudioMedia(ref) &&
                                    !MediaReferenceParser.isVideoMedia(ref)
                            }.toList()
                    }
                val mediaPendingName =
                    remember(record.tags) {
                        record.tags
                            .firstOrNull { it.values.firstOrNull() == "_media_pending" }
                            ?.values
                            ?.getOrNull(1)
                    }
                // Visual attachments (image + video) ride one bubble:
                // a singleton routes to its dedicated bubble, a multi
                // goes to MediaVisualGridBubble which mixes image
                // and video tiles in pick order.
                val visualAttachments =
                    remember(imageAttachments, videoAttachments) {
                        (imageAttachments + videoAttachments).sortedBy { it.index }
                    }
                // An uncaptioned single image/video carries the footer
                // overlaid on its bottom-right; a caption (if any) takes
                // it instead via the text path below.
                val footerOnVisualMedia =
                    !deleted &&
                        !invalidated &&
                        visualAttachments.size == 1 &&
                        (editState?.latestText ?: record.plaintext).isBlank()
                val anyConfirmedMedia =
                    imageAttachments.isNotEmpty() ||
                        audioAttachments.isNotEmpty() ||
                        videoAttachments.isNotEmpty() ||
                        fileAttachments.isNotEmpty()
                val pendingAttachmentsForRecord =
                    remember(record.messageIdHex, controller.pendingAttachmentsList(record.messageIdHex)) {
                        controller.pendingAttachmentsList(record.messageIdHex)
                    }
                val pendingAudio =
                    remember(pendingAttachmentsForRecord) {
                        pendingAttachmentsForRecord
                            .withIndex()
                            .filter { (_, p) -> p.mediaType.startsWith("audio/", ignoreCase = true) }
                            .toList()
                    }
                val pendingVideo =
                    remember(pendingAttachmentsForRecord) {
                        pendingAttachmentsForRecord
                            .withIndex()
                            .filter { (_, p) -> p.mediaType.startsWith("video/", ignoreCase = true) }
                            .toList()
                    }
                val pendingImage =
                    remember(pendingAttachmentsForRecord) {
                        pendingAttachmentsForRecord
                            .withIndex()
                            .filter { (_, p) -> p.mediaType.startsWith("image/", ignoreCase = true) }
                            .toList()
                    }
                val pendingVisuals =
                    remember(pendingImage, pendingVideo) {
                        (pendingImage + pendingVideo).sortedBy { it.index }
                    }
                // Synthesize references for each pending visual so
                // the existing single-bubble + grid bubble can render
                // them. mine=true threads the bytes through the
                // pendingAttachmentsList fallback in the auto-download
                // path.
                val pendingVisualRefs =
                    remember(record.messageIdHex, pendingVisuals) {
                        pendingVisuals.map { (index, pending) ->
                            IndexedValue(
                                index,
                                MediaAttachmentReferenceFfi(
                                    locators = emptyList(),
                                    ciphertextSha256 = "",
                                    plaintextSha256 = "",
                                    nonceHex = "",
                                    fileName = pending.fileName,
                                    mediaType = pending.mediaType,
                                    version = "encrypted-media-v1",
                                    sourceEpoch = 0u,
                                    dim = pending.dim,
                                    thumbhash = pending.thumbhash,
                                ),
                            )
                        }
                    }
                val footerOnPendingVisual =
                    !deleted && !invalidated && !anyConfirmedMedia && pendingVisualRefs.size == 1
                val showPendingPlaceholder =
                    !deleted &&
                        !invalidated &&
                        !anyConfirmedMedia &&
                        pendingAudio.isEmpty() &&
                        pendingVisualRefs.isEmpty() &&
                        mediaPendingName != null
                // #527: media (images/video, audio, files) renders on its OWN,
                // outside the colored message bubble. `hasMedia` decides whether
                // this row splits into standalone media + an optional caption
                // bubble, or stays a single text bubble. Deleted/invalidated
                // tombstones never pull media out — they always render as the
                // single tombstone bubble.
                val hasMedia =
                    !deleted &&
                        !invalidated &&
                        (
                            anyConfirmedMedia ||
                                pendingAudio.isNotEmpty() ||
                                pendingVisualRefs.isNotEmpty() ||
                                showPendingPlaceholder
                        )
                // The media-rendering blocks. Each child keeps its own rounded
                // media Surface, so calling this directly in the row Column (not
                // inside the colored bubble Surface) gives every attachment its
                // own object (#527). Behavior — download gating, single-visual
                // footer overlay, tap-to-open viewers, upload/failed/retry — is
                // unchanged from the in-bubble version.
                // Long-press on any media tile opens the action menu (not the
                // viewer); anchored to the bubble top like the accessibility
                // long-click path. Hoisted so every media call site shares one
                // definition.
                val onMediaLongPress: () -> Unit = {
                    longPressWindowY = null
                    onActionMenuOpenChange(true)
                }
                val mediaBlocks: @Composable ColumnScope.() -> Unit = {
                    if (!deleted && !invalidated && visualAttachments.isNotEmpty()) {
                        if (visualAttachments.size == 1) {
                            val entry = visualAttachments.first()
                            Box {
                                if (MediaReferenceParser.isVideoMedia(entry.value)) {
                                    MediaVideoBubble(
                                        messageIdHex = record.messageIdHex,
                                        attachmentIndex = entry.index,
                                        reference = entry.value,
                                        mine = mine,
                                        controller = controller,
                                        appState = appState,
                                        onLongPress = onMediaLongPress,
                                    )
                                } else {
                                    MediaImageBubble(
                                        item = item,
                                        reference = entry.value,
                                        attachmentIndex = entry.index,
                                        controller = controller,
                                        appState = appState,
                                        mine = mine,
                                        onLongPress = onMediaLongPress,
                                    )
                                }
                                if (footerOnVisualMedia) {
                                    MediaFooterOverlay(
                                        timeText = rememberedClockTime(record.recordedAt),
                                        showStatus = mine,
                                        status = item.status,
                                    )
                                }
                            }
                        } else {
                            MediaVisualGridBubble(
                                item = item,
                                attachments = visualAttachments,
                                controller = controller,
                                appState = appState,
                                mine = mine,
                                onLongPress = onMediaLongPress,
                            )
                        }
                    }
                    if (!deleted && !invalidated && audioAttachments.isNotEmpty()) {
                        audioAttachments.forEach { entry ->
                            MediaVoiceBubble(
                                messageIdHex = record.messageIdHex,
                                attachmentIndex = entry.index,
                                reference = entry.value,
                                mine = mine,
                                controller = controller,
                                appState = appState,
                                onLongPress = onMediaLongPress,
                            )
                        }
                    }
                    if (!deleted && !invalidated && fileAttachments.isNotEmpty()) {
                        fileAttachments.forEach { entry ->
                            MediaFileBubble(
                                messageIdHex = record.messageIdHex,
                                attachmentIndex = entry.index,
                                reference = entry.value,
                                mine = mine,
                                controller = controller,
                                appState = appState,
                                onLongPress = onMediaLongPress,
                            )
                        }
                    }
                    if (!deleted && !invalidated && !anyConfirmedMedia && pendingAudio.isNotEmpty()) {
                        pendingAudio.forEach { (index, pending) ->
                            MediaVoiceBubble(
                                messageIdHex = record.messageIdHex,
                                attachmentIndex = index,
                                reference =
                                    remember(record.messageIdHex, index, pending) {
                                        MediaAttachmentReferenceFfi(
                                            locators = emptyList(),
                                            ciphertextSha256 = "",
                                            plaintextSha256 = "",
                                            nonceHex = "",
                                            fileName = pending.fileName,
                                            mediaType = pending.mediaType,
                                            version = "encrypted-media-v1",
                                            sourceEpoch = 0u,
                                            dim = null,
                                            thumbhash = null,
                                        )
                                    },
                                mine = true,
                                controller = controller,
                                appState = appState,
                                onLongPress = onMediaLongPress,
                            )
                        }
                    }
                    if (!deleted && !invalidated && !anyConfirmedMedia && pendingVisualRefs.isNotEmpty()) {
                        val uploadFailed = item.status == MessageStatus.Failed
                        val retryUpload: () -> Unit = {
                            appState.launchMutation { controller.retryFailedSend(item) }
                        }
                        if (pendingVisualRefs.size == 1) {
                            val entry = pendingVisualRefs.first()
                            Box {
                                if (MediaReferenceParser.isVideoMedia(entry.value)) {
                                    MediaVideoBubble(
                                        messageIdHex = record.messageIdHex,
                                        attachmentIndex = entry.index,
                                        reference = entry.value,
                                        mine = true,
                                        controller = controller,
                                        appState = appState,
                                        onLongPress = onMediaLongPress,
                                        uploading = !uploadFailed,
                                        uploadFailed = uploadFailed,
                                        onRetryUpload = if (uploadFailed) retryUpload else null,
                                    )
                                } else {
                                    MediaImageBubble(
                                        item = item,
                                        reference = entry.value,
                                        attachmentIndex = entry.index,
                                        controller = controller,
                                        appState = appState,
                                        mine = true,
                                        onLongPress = onMediaLongPress,
                                        uploading = !uploadFailed,
                                    )
                                }
                                MediaFooterOverlay(
                                    timeText = rememberedClockTime(record.recordedAt),
                                    showStatus = true,
                                    status = item.status,
                                )
                            }
                        } else {
                            MediaVisualGridBubble(
                                item = item,
                                attachments = pendingVisualRefs,
                                controller = controller,
                                appState = appState,
                                mine = true,
                                onLongPress = onMediaLongPress,
                                uploading = !uploadFailed,
                            )
                        }
                    }
                    if (showPendingPlaceholder) {
                        MediaPendingPlaceholder(
                            pendingAttachments = controller.pendingAttachmentsList(record.messageIdHex),
                            failed = item.status == MessageStatus.Failed,
                            onRetry =
                                if (mine && item.status == MessageStatus.Failed) {
                                    { appState.launchMutation { controller.retryFailedSend(item) } }
                                } else {
                                    null
                                },
                        )
                    }
                }
                // Body text policy:
                // - Pending optimistic with an attachment: placeholder
                //   composable already renders, suppress text.
                // - Confirmed media (imeta tag present): render the
                //   user-typed caption, edit-overlay-aware so a
                //   subsequent edit on a media bubble updates the
                //   caption in place. We deliberately don't use
                //   `displayedBody` directly because MessageProjector
                //   falls back to the imeta filename for a blank
                //   caption — fine for chat-list previews, wrong for
                //   a bubble already showing the image inline.
                // - Non-media: render displayedBody (covers reactions,
                //   deletions, agent streams, plain text).
                val bodyTextToRender: String? =
                    when {
                        // Deleted/invalidated tombstones show only the
                        // tombstone copy, never an inline image/caption.
                        deleted || invalidated -> displayedBody
                        mediaPendingName != null && !anyConfirmedMedia -> null
                        anyConfirmedMedia ->
                            (editState?.latestText ?: record.plaintext).takeIf { it.isNotBlank() }
                        else -> displayedBody
                    }
                val editedLabel =
                    if (editState != null && record.kind == 9uL && !deleted && !invalidated) {
                        if (editState.count > 1) {
                            stringResource(R.string.edited_count, editState.count)
                        } else {
                            stringResource(R.string.edited)
                        }
                    } else {
                        null
                    }
                val inlineFooter: @Composable () -> Unit = {
                    MessageInlineFooter(
                        timeText = rememberedClockTime(record.recordedAt),
                        color = timestampColor,
                        showStatus = mine && !deleted && !invalidated,
                        status = item.status,
                        editedLabel = editedLabel,
                        onEditedClick = if (editState != null) ({ editHistoryOpen = true }) else null,
                    )
                }
                // Last-line geometry of the body so the footer can sit on
                // that line when it fits, not merely when the widest line does.
                var lastLineLayout by remember(record.messageIdHex) { mutableStateOf<TextLayoutResult?>(null) }
                // Overflow decision is derived from a measurement of the FULL
                // body only. Keeping it separate from lastLineLayout (which
                // the currently-rendered text updates) avoids a recompose
                // loop: once we clip, the clipped text no longer overflows,
                // which would otherwise flip the decision back and forth.
                var bodyFullLayout by remember(record.messageIdHex) { mutableStateOf<TextLayoutResult?>(null) }
                // A long body collapses to MESSAGE_COLLAPSE_LINE_LIMIT lines
                // with an inline Read More that opens the full-screen view;
                // tombstones and edit/info copy never collapse (#325).
                val collapsible = !deleted && !invalidated
                val readMoreLabel = stringResource(R.string.message_read_more)
                val readMoreStyle =
                    SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                // The body/caption text + inline footer, plus the failed-send
                // retry row. Hoisted into a lambda so it can render either inside
                // the single text bubble (no media) or inside the caption bubble
                // just below standalone media (#527). When there is no body text
                // it falls through to the footer-only / retry handling exactly as
                // before.
                val bodyFooterAndRetry: @Composable ColumnScope.() -> Unit = {
                    if (bodyTextToRender != null) {
                        BubbleFooterLayout(
                            footer = inlineFooter,
                            // Body text is always start-aligned inside the
                            // bubble, regardless of which side the bubble sits
                            // on or how wide a sibling (reply quote, media)
                            // makes the content column. End-aligning own
                            // messages left a short reply drifting to the right
                            // of a wide bubble (#439). The footer still places
                            // itself at the block's trailing edge internally.
                            modifier = Modifier.align(Alignment.Start),
                            lastLineWidth =
                                lastLineLayout?.let { layout ->
                                    if (layout.lineCount > 0) ceil(layout.getLineRight(layout.lineCount - 1)).toInt() else null
                                },
                        ) {
                            // Markdown only when the tokens describe exactly
                            // the text we're about to show: tombstone copy,
                            // imeta-filename fallbacks, etc. all diverge from
                            // record.plaintext and must stay plain. An empty
                            // document (legacy record, parse failure) falls
                            // through to the unchanged plain-text path.
                            val markdownDocument = record.contentTokens
                            if (!deleted &&
                                !invalidated &&
                                markdownDocument.blocks.isNotEmpty() &&
                                bodyTextToRender == record.plaintext
                            ) {
                                // Markdown can't be cleanly truncated to a line
                                // count mid-document, so clip to the height of
                                // MESSAGE_COLLAPSE_LINE_LIMIT body-large lines
                                // and drop a Read More beneath when the natural
                                // content is taller. The natural height is
                                // measured on the inner content (clipToBounds is
                                // visual only and doesn't constrain it); the
                                // overflow flag latches true so applying the cap
                                // can't shrink the measurement and flip it back.
                                val lineHeightPx =
                                    with(density) { (MaterialTheme.typography.bodyLarge.lineHeight).toPx() }
                                val maxBodyHeightPx = lineHeightPx * MESSAGE_COLLAPSE_LINE_LIMIT
                                val maxBodyHeightDp = with(density) { maxBodyHeightPx.toDp() }
                                var markdownOverflows by remember(record.messageIdHex) { mutableStateOf(false) }
                                val collapseMarkdown = collapsible && markdownOverflows
                                Column {
                                    Box(
                                        modifier =
                                            Modifier
                                                .onSizeChanged {
                                                    if (collapsible && it.height > maxBodyHeightPx) {
                                                        markdownOverflows = true
                                                    }
                                                }.then(
                                                    if (collapseMarkdown) {
                                                        Modifier
                                                            .heightIn(max = maxBodyHeightDp)
                                                            .clipToBounds()
                                                    } else {
                                                        Modifier
                                                    },
                                                ),
                                    ) {
                                        // Mention names resolve through the profile
                                        // cache; npub/nprofile taps stay in-app via
                                        // the profile sheet (never an external nostr:
                                        // intent). The "@" mention treatment is
                                        // reserved for an account in the roster
                                        // snapshot captured for this bubble (#1017),
                                        // so later roster updates do not rewrite old
                                        // rendered message semantics. If the roster
                                        // has not loaded yet, leave membership unknown
                                        // and keep pre-#1017 rendering until it does.
                                        val mentionMemberSnapshot =
                                            remember(record.messageIdHex, controller.membersLoaded) {
                                                if (controller.membersLoaded) controller.members else null
                                            }
                                        val mentionMembershipResolver =
                                            remember(appState, mentionMemberSnapshot) {
                                                mentionMemberSnapshot?.let { members ->
                                                    { bech32: String -> appState.isRosterMember(bech32, members) }
                                                }
                                            }
                                        MarkdownMessageBody(
                                            markdownDocument,
                                            mentionDisplayName =
                                                remember(appState) {
                                                    { bech32: String -> appState.mentionDisplayName(bech32) }
                                                },
                                            isGroupMember = mentionMembershipResolver,
                                            onNostrProfileTap =
                                                remember(appState) {
                                                    { bech32: String -> appState.presentNostrProfile(bech32) }
                                                },
                                            onLastTextLayout = { lastLineLayout = it },
                                        )
                                    }
                                    if (collapseMarkdown) {
                                        Text(
                                            readMoreLabel,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable { expandedFullView = true },
                                        )
                                    }
                                }
                            } else {
                                // Plain text truncates inline: render up to the
                                // line limit, and if that overflows rebuild the
                                // clipped text ending with "… Read More" (bold,
                                // onSurface — reads as dark emphasis, not a link).
                                // Measured with maxLines = limit + 1, so visual
                                // overflow means the body needs more than one
                                // extra line past the limit — clip it.
                                val overflows =
                                    collapsible &&
                                        bodyFullLayout?.let {
                                            it.hasVisualOverflow && it.lineCount > MESSAGE_COLLAPSE_LINE_LIMIT
                                        } == true
                                if (overflows) {
                                    val layout = bodyFullLayout!!
                                    // Cut at the last fully-visible line, trim trailing
                                    // whitespace, then append the ellipsis + a Read More
                                    // link. Only the Read More span is clickable, so a
                                    // long-press anywhere on the bubble still falls through
                                    // to the action menu rather than expanding the bubble.
                                    val cut =
                                        remember(bodyTextToRender, layout) {
                                            bodyTextToRender
                                                .substring(0, layout.getLineEnd(MESSAGE_COLLAPSE_LINE_LIMIT - 1, visibleEnd = true))
                                                .trimEnd()
                                        }
                                    val clippedText =
                                        remember(cut, readMoreLabel, readMoreStyle) {
                                            buildAnnotatedString {
                                                append(cut)
                                                append("… ")
                                                withLink(
                                                    LinkAnnotation.Clickable("read_more") { expandedFullView = true },
                                                ) {
                                                    withStyle(readMoreStyle) { append(readMoreLabel) }
                                                }
                                            }
                                        }
                                    Text(
                                        clippedText,
                                        style = MaterialTheme.typography.bodyLarge,
                                        // Footer geometry follows the clipped text's
                                        // real last line, not the full measurement.
                                        onTextLayout = { lastLineLayout = it },
                                    )
                                } else {
                                    Text(
                                        bodyTextToRender,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = if (collapsible) MESSAGE_COLLAPSE_LINE_LIMIT + 1 else Int.MAX_VALUE,
                                        onTextLayout = {
                                            lastLineLayout = it
                                            bodyFullLayout = it
                                        },
                                    )
                                }
                            }
                        }
                    } else if (!footerOnVisualMedia && !footerOnPendingVisual) {
                        Box(modifier = Modifier.align(if (mine) Alignment.End else Alignment.Start)) {
                            inlineFooter()
                        }
                    }
                    if (mine && item.status == MessageStatus.Failed) {
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = { appState.launchMutation { controller.retryFailedSend(item) } },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.retry),
                                    tint = timestampColor,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(
                                onClick = { controller.discardFailedSend(item) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.discard_failed_message),
                                    tint = timestampColor,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
                // The sender-name label (group chats only). Rendered above the
                // media + caption when media is present (#527), or as the first
                // child of the single text bubble otherwise.
                val senderNameLabel: @Composable () -> Unit = {
                    if (showSenderAvatar) {
                        Text(
                            appState.displayName(record.sender),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier.combinedClickable(
                                    onClick = { appState.presentProfile(appState.npub(record.sender)) },
                                    onLongClick = {
                                        if (!deleted) {
                                            longPressWindowY = null
                                            onActionMenuOpenChange(true)
                                        }
                                    },
                                ),
                        )
                    }
                }
                // The reply quote card. Self-contained (own translucent Surface),
                // so it renders correctly whether inside the text bubble or
                // standalone above the media (#527).
                val replyPreviewCard: @Composable () -> Unit = {
                    replyPreview?.let { preview ->
                        ReplyPreviewCard(
                            senderTitle = senderTitleForReply(preview.sender, appState),
                            isOwn = isOwnReplySender(preview.sender, appState),
                            body = preview.body,
                            mediaKind = preview.mediaKind,
                            onClick = { onReplyPreviewClick(item) },
                            onDismiss = null,
                            // Fill the content width: in the text bubble the
                            // column is sized to its widest child (IntrinsicSize.Max
                            // below) so the quote matches the bubble instead of
                            // hugging its own text (#428); above standalone media
                            // it lines up with the media's width (#527). A short
                            // quote + short reply still keeps a narrow bubble
                            // because the widest child is then small (#208 preserved).
                            fillWidth = true,
                            mentionDisplayName = { appState.mentionDisplayName(it) },
                        )
                    }
                }
                if (hasMedia) {
                    // #527: media renders on its OWN, outside the colored bubble.
                    // The sender label and reply quote sit above the media, then
                    // the caption (if any) follows in its own bubble just below.
                    Column(
                        modifier = Modifier.offset { IntOffset(animatedSwipeOffset.roundToInt(), 0) },
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        senderNameLabel()
                        replyPreviewCard()
                        mediaBlocks()
                        // Caption: only when a non-blank caption accompanies the
                        // media. It gets the same colored bubble look as a plain
                        // text message, placed directly below the media.
                        if (bodyTextToRender != null) {
                            Surface(
                                color = bubbleColor,
                                shape = RoundedCornerShape(18.dp),
                                border = messageBubbleBorder(highlighted, mine),
                                tonalElevation = if (mine) 1.dp else 0.dp,
                            ) {
                                Column(
                                    mentionRailModifier
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    bodyFooterAndRetry()
                                }
                            }
                        } else {
                            // No caption: the footer (time/status) for audio,
                            // file, or multi-visual media still needs a home —
                            // and so does the failed-send retry row. Render them
                            // directly below the media, un-bubbled.
                            bodyFooterAndRetry()
                        }
                    }
                } else {
                    Surface(
                        // Swipe-to-reply and long-press now live on the parent Row
                        // (see #204) so the whole message row is the hitbox. The
                        // Surface keeps only the visual slide driven by swipeDrag.
                        modifier =
                            Modifier
                                .offset { IntOffset(animatedSwipeOffset.roundToInt(), 0) },
                        color = bubbleColor,
                        shape = RoundedCornerShape(18.dp),
                        border = messageBubbleBorder(highlighted, mine),
                        tonalElevation = if (mine) 1.dp else 0.dp,
                    ) {
                        Column(
                            mentionRailModifier
                                // With a reply quote present, size the column to its
                                // widest child so the inner quote can fill the bubble
                                // width instead of hugging its own (possibly short)
                                // text and leaving a gap on the right (#428). Non-reply
                                // bubbles keep the wrap-content path untouched, so only
                                // reply-bubble measurement changes.
                                .then(if (replyPreview != null) Modifier.width(IntrinsicSize.Max) else Modifier)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            senderNameLabel()
                            replyPreviewCard()
                            bodyFooterAndRetry()
                        }
                    }
                }
                MessageActionMenu(
                    // Never render the menu for a deleted message, even
                    // if it was open when the delete landed.
                    expanded = isActionMenuOpen && !deleted,
                    anchorWindowYPx = longPressWindowY,
                    alignEnd = mine,
                    canReply = !readOnly,
                    canReact = !readOnly,
                    canDelete = !readOnly && mine && record.messageIdHex.isNotBlank() && !deleted,
                    canEdit = !readOnly && mine && record.kind == 9uL && record.messageIdHex.isNotBlank() && !deleted,
                    canForward = !readOnly && forwardBody != null,
                    quickReactionEmojis = quickReactionEmojis,
                    onDismissRequest = { onActionMenuOpenChange(false) },
                    onReact = { emoji ->
                        onActionMenuOpenChange(false)
                        reactWithEmoji(emoji)
                    },
                    onOpenEmojiPicker = {
                        onActionMenuOpenChange(false)
                        emojiPickerOpen = true
                    },
                    onReply = ::beginReply,
                    onEdit = {
                        onActionMenuOpenChange(false)
                        // Cancel any reply-in-progress: reply and
                        // edit modes are mutually exclusive in the
                        // composer banner.
                        controller.replyingTo = null
                        controller.editingMessageId = record.messageIdHex
                    },
                    onCopyText = ::copyMessageText,
                    onForward = ::beginForward,
                    onInfo = ::openInfoSheet,
                    onDelete = {
                        onActionMenuOpenChange(false)
                        // launchMutation so the MLS commit + Nostr publish
                        // survive navigating away from the conversation —
                        // the optimistic tombstone is already set in the
                        // controller's state and the FFI write needs to
                        // complete regardless of whether this bubble is
                        // still in composition.
                        appState.launchMutation { controller.deleteMessage(record) }
                    },
                )
                if (expandedFullView) {
                    MessageFullScreenView(
                        senderDisplayName = appState.displayName(record.sender),
                        senderSeed = record.sender,
                        senderAvatarUrl = appState.avatarUrl(record.sender),
                        body = displayedBody,
                        timeText = rememberedClockTime(record.recordedAt),
                        showStatus = mine && !deleted && !invalidated,
                        status = item.status,
                        canReply = !readOnly,
                        canReact = !readOnly,
                        canDelete = !readOnly && mine && record.messageIdHex.isNotBlank() && !deleted,
                        onReply = {
                            expandedFullView = false
                            beginReply()
                        },
                        onReact = {
                            if (!readOnly) {
                                expandedFullView = false
                                emojiPickerOpen = true
                            }
                        },
                        onCopy = ::copyMessageText,
                        onDelete = {
                            expandedFullView = false
                            appState.launchMutation { controller.deleteMessage(record) }
                        },
                        onDismiss = { expandedFullView = false },
                    )
                }
                if (emojiPickerOpen && !readOnly) {
                    EmojiPickerSheet(
                        restoreExpanded = restoreReactionPickerExpanded,
                        messageReactionEmojis =
                            item.projected
                                ?.reactions
                                ?.byEmoji
                                .orEmpty()
                                .map { it.emoji },
                        onDismissRequest = {
                            restoreReactionPickerExpanded = false
                            emojiPickerOpen = false
                        },
                        onEmojiPicked = { emoji ->
                            restoreReactionPickerExpanded = false
                            emojiPickerOpen = false
                            reactWithEmoji(emoji)
                        },
                        onCustomizeReactions = { wasExpanded ->
                            restoreReactionPickerExpanded = wasExpanded
                            customizeReactionsOpen = true
                        },
                    )
                }
                if (customizeReactionsOpen) {
                    fun closeCustomizeToReactionSheet() {
                        customizeReactionsOpen = false
                    }
                    CustomizeReactionsDialog(
                        quickReactionEmojis = quickReactionEmojis,
                        onDismiss = ::closeCustomizeToReactionSheet,
                        onSave = { choices ->
                            onQuickReactionsSave(choices)
                            closeCustomizeToReactionSheet()
                        },
                        onReset = onQuickReactionsReset,
                    )
                }
                if (editHistoryOpen && editState != null) {
                    EditHistorySheet(
                        original = record.plaintext,
                        originalTimestamp = record.recordedAt,
                        editState = editState,
                        onDismissRequest = { editHistoryOpen = false },
                    )
                }
                if (infoSheetOpen) {
                    MessageInfoSheet(
                        item = item,
                        mine = mine,
                        senderDisplayName = appState.displayName(record.sender),
                        senderNpub = appState.npub(record.sender),
                        onDismissRequest = { infoSheetOpen = false },
                        onCopy = { value ->
                            clipboard.setText(AnnotatedString(value))
                            appState.present(R.string.copied)
                        },
                    )
                }
                if (forwardSheetOpen && forwardBody != null) {
                    ForwardMessageSheet(
                        appState = appState,
                        body = forwardBody,
                        originGroupIdHex = record.groupIdHex,
                        onDismiss = { forwardSheetOpen = false },
                        onForward = { targetGroupIds ->
                            appState.forwardText(targetGroupIds, forwardBody)
                        },
                    )
                }
                val tallies = controller.reactions[record.messageIdHex].orEmpty()
                // Hide reaction tallies on a deleted message — nothing to show.
                if (tallies.isNotEmpty() && !deleted) {
                    val reactionChipPadding =
                        if (mine) {
                            PaddingValues(end = 10.dp)
                        } else {
                            PaddingValues(start = 10.dp)
                        }
                    // Keep the chip tucked onto the bubble's lower edge without
                    // covering the final text line or outgoing status cluster.
                    Box(
                        modifier =
                            Modifier
                                .align(if (mine) Alignment.End else Alignment.Start)
                                .padding(reactionChipPadding)
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    val overlap = 6.dp.roundToPx()
                                    val height = (placeable.height - overlap).coerceAtLeast(0)
                                    layout(placeable.width, height) {
                                        placeable.place(0, -overlap)
                                    }
                                },
                    ) {
                        ReactionSummaryChip(
                            tallies = tallies,
                            onClick = { reactionSheetOpen = true },
                        )
                    }
                }
                if (reactionSheetOpen) {
                    val participants =
                        remember(record.messageIdHex, item.projected?.reactions, tallies) {
                            controller.reactionParticipantsFor(record.messageIdHex)
                        }
                    // Close when the participant list drains, without re-firing for every list update.
                    LaunchedEffect(participants.isEmpty()) {
                        if (participants.isEmpty()) reactionSheetOpen = false
                    }
                    if (participants.isNotEmpty()) {
                        ReactionDetailsSheet(
                            participants = participants,
                            appState = appState,
                            onRemoveOwnReaction =
                                if (readOnly) {
                                    null
                                } else {
                                    { emoji -> appState.launchMutation { controller.toggleReaction(emoji, record) } }
                                },
                            onDismissRequest = { reactionSheetOpen = false },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-screen reader for a body too long to show inline. Reached from the
 * collapsed bubble's Read More; Back returns to the conversation unchanged
 * (#325). A full-bleed Dialog avoids touching the existing nav backstack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageFullScreenView(
    senderDisplayName: String,
    senderSeed: String,
    senderAvatarUrl: String?,
    body: String,
    timeText: String,
    showStatus: Boolean,
    status: MessageStatus,
    canReply: Boolean,
    canReact: Boolean,
    canDelete: Boolean,
    onReply: () -> Unit,
    onReact: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
            ),
    ) {
        var overflowOpen by remember { mutableStateOf(false) }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        // The sender identity lives in the bar itself — avatar +
                        // name with the send time (and delivery status for own
                        // messages) as a subtitle — so the body below is just the
                        // message, no redundant in-content header.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Avatar(title = senderDisplayName, seed = senderSeed, size = 36.dp, pictureUrl = senderAvatarUrl)
                            Column {
                                Text(
                                    senderDisplayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        timeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (showStatus) {
                                        OutgoingMessageStatusIcon(status, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.message_actions))
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                            shape = MenuDefaults.shape,
                            border = amoledSurfaceBorderStroke(),
                        ) {
                            if (canReply) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.reply)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                                    onClick = {
                                        overflowOpen = false
                                        onReply()
                                    },
                                )
                            }
                            if (canReact) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.message_react)) },
                                    leadingIcon = { Icon(Icons.Default.EmojiEmotions, contentDescription = null) },
                                    onClick = {
                                        overflowOpen = false
                                        onReact()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.copy_text)) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    onCopy()
                                },
                            )
                            if (canDelete) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    onClick = {
                                        overflowOpen = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    },
                    // The Dialog window already sits below the status bar, so the
                    // bar's own status-bar inset would double the gap and inflate
                    // its height. Zero it to render at the standard compact height.
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(18.dp),
                    border = amoledSurfaceBorderStroke(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * One consolidated reaction pill: the distinct emojis clustered together with a
 * total count, mirroring the familiar messenger style — a single compact target
 * rather than a spread of separate chips. Tapping opens the reactor list, where
 * a reaction can be removed.
 */
@Composable
private fun ReactionSummaryChip(
    tallies: List<ReactionTally>,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val mine = tallies.any { it.mine }
    val total = tallies.sumOf { it.count }
    val emojis = tallies.take(MAX_VISIBLE_REACTIONS).joinToString(separator = "") { it.emoji }
    val viewReactorsLabel = stringResource(R.string.view_reactors)
    val border =
        if (isAmoledSurfaceTheme()) {
            BorderStroke(1.dp, colorScheme.outlineVariant)
        } else {
            BorderStroke(1.5.dp, colorScheme.surface)
        }
    Surface(
        modifier =
            Modifier
                // Expose the "you reacted" state to TalkBack, not just via color.
                .semantics { selected = mine }
                .clip(RoundedCornerShape(percent = 50))
                .clickable(role = Role.Button, onClick = onClick, onClickLabel = viewReactorsLabel),
        shape = RoundedCornerShape(percent = 50),
        color = if (mine) colorScheme.secondaryContainer else colorScheme.surfaceContainerHigh,
        contentColor = if (mine) colorScheme.onSecondaryContainer else colorScheme.onSurface,
        border = border,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(emojis, style = MaterialTheme.typography.labelLarge)
            if (total > 1) {
                Text(total.toString(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReactionDetailsSheet(
    participants: List<ReactionParticipant>,
    appState: WhiteNoiseAppState,
    onRemoveOwnReaction: ((String) -> Unit)?,
    onDismissRequest: () -> Unit,
) {
    var selectedEmoji by remember(participants) { mutableStateOf<String?>(null) }
    val activeAccountId = appState.activeAccount?.accountIdHex
    val emojiCounts =
        remember(participants) {
            participants
                .groupingBy { it.emoji }
                .eachCount()
                .toList()
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        }
    val visibleParticipants =
        remember(participants, selectedEmoji) {
            selectedEmoji?.let { emoji -> participants.filter { it.emoji == emoji } } ?: participants
        }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = amoledSheetContainerColor(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedEmoji == null,
                    onClick = { selectedEmoji = null },
                    label = { Text("${stringResource(R.string.reaction_filter_all)} · ${participants.size}") },
                )
                emojiCounts.forEach { (emoji, count) ->
                    FilterChip(
                        selected = selectedEmoji == emoji,
                        onClick = { selectedEmoji = emoji },
                        label = { Text("$emoji $count") },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(
                    visibleParticipants,
                    key = { _, participant -> "${participant.sender}:${participant.emoji}:${participant.reactedAt}" },
                ) { _, participant ->
                    val isMine = activeAccountId != null && participant.sender.equals(activeAccountId, ignoreCase = true)
                    ReactionParticipantRow(
                        participant = participant,
                        appState = appState,
                        mine = isMine,
                        onRemove = if (isMine && onRemoveOwnReaction != null) ({ onRemoveOwnReaction(participant.emoji) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReactionParticipantRow(
    participant: ReactionParticipant,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable {
                    if (mine && onRemove != null) onRemove() else appState.presentProfile(appState.npub(participant.sender))
                }.padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            title = appState.displayName(participant.sender),
            seed = participant.sender,
            size = 44.dp,
            pictureUrl = appState.avatarUrl(participant.sender),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = if (mine) stringResource(R.string.you) else appState.displayName(participant.sender),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (mine) {
                Text(
                    text = stringResource(R.string.reaction_tap_to_remove),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = participant.emoji,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

private data class EditHistoryRow(
    val versionNumber: Int,
    val text: String,
    val recordedAt: ULong,
    val isLatest: Boolean,
    val isOriginal: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditHistorySheet(
    original: String,
    originalTimestamp: ULong,
    editState: EditState,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Newest first reads as "this is what's shown now ← earlier revisions ← original".
    val rows =
        remember(original, originalTimestamp, editState) {
            buildList {
                editState.versions.reversed().forEachIndexed { reversedIndex, version ->
                    val versionNumber = editState.versions.size - reversedIndex
                    add(
                        EditHistoryRow(
                            versionNumber = versionNumber,
                            text = version.text,
                            recordedAt = version.recordedAt,
                            isLatest = reversedIndex == 0,
                            isOriginal = false,
                        ),
                    )
                }
                add(
                    EditHistoryRow(
                        versionNumber = 0,
                        text = original,
                        recordedAt = originalTimestamp,
                        isLatest = false,
                        isOriginal = true,
                    ),
                )
            }
        }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = amoledSheetContainerColor(),
    ) {
        // The header is anchored above the scroll region so the title and
        // count chip remain visible while the user pages through a long edit
        // chain. The rail keeps its visual continuity because every row is
        // a child of the same Column — a LazyColumn would compose each row
        // independently and break the dot-to-dot line through the rail.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.edit_history),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(999.dp),
                    border = amoledSurfaceBorderStroke(),
                ) {
                    Text(
                        text = stringResource(R.string.edited_count, editState.versions.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    EditHistoryVersionRow(
                        row = row,
                        isFirst = index == 0,
                        isLast = index == rows.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditHistoryVersionRow(
    row: EditHistoryRow,
    isFirst: Boolean,
    isLast: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Leading rail: dot anchored to the label row + a vertical line
        // connecting consecutive dots so the column reads as a single
        // timeline rather than disconnected cards.
        Column(
            Modifier.width(16.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(
                Modifier
                    .height(10.dp)
                    .width(2.dp)
                    .background(
                        if (isFirst) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
            val dotColor =
                when {
                    row.isLatest -> MaterialTheme.colorScheme.primary
                    row.isOriginal -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            Box(Modifier.size(10.dp).background(dotColor, shape = CircleShape))
            Spacer(
                Modifier
                    .weight(1f)
                    .width(2.dp)
                    .background(
                        if (isLast) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(bottom = if (isLast) 0.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (row.isLatest) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.edit_history_version_label, row.versionNumber),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                } else {
                    Text(
                        text =
                            if (row.isOriginal) {
                                stringResource(R.string.edit_history_original)
                            } else {
                                stringResource(R.string.edit_history_version_label, row.versionNumber)
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = rememberedRelativeTime(row.recordedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color =
                    if (row.isOriginal) {
                        MaterialTheme.colorScheme.surfaceContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                shape = RoundedCornerShape(14.dp),
                border = amoledSurfaceBorderStroke(),
            ) {
                Text(
                    text = row.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (row.isOriginal) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageActionMenu(
    expanded: Boolean,
    anchorWindowYPx: Float?,
    alignEnd: Boolean,
    canReply: Boolean,
    canReact: Boolean,
    canDelete: Boolean,
    canEdit: Boolean,
    canForward: Boolean,
    quickReactionEmojis: List<String>,
    onDismissRequest: () -> Unit,
    onReact: (String) -> Unit,
    onOpenEmojiPicker: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onForward: () -> Unit,
    onCopyText: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    // focusable = false keeps the soft keyboard up while this menu is open.
    // A focusable popup window steals window focus from the conversation's
    // host window, and Android dismisses the IME when the window holding the
    // focused composer loses focus. That collapse then removes the composer's
    // imePadding, reflowing the transcript down by the keyboard height mid
    // gesture — so the long-press popover lands at a shifted position rather
    // than where the user pressed (#284). Same "modal UI fights the IME"
    // family as the voice-record bar in #207.
    //
    // A non-focusable popup has two gaps versus the old focusable menu that we
    // restore explicitly here, without re-focusing (which would collapse the
    // IME again):
    //   1. Back dismissal — Popup's dismissOnBackPress is a no-op while the
    //      popup is non-focusable, so a Back press would fall through to the
    //      IME/activity instead of closing the menu. A host-window BackHandler
    //      (same pattern as QuickActionFabMenu) closes the menu on Back. It
    //      runs in the conversation window and does not touch IME focus.
    //   2. Outside-tap click-through — events outside a non-focusable popup are
    //      delivered to the windows beneath it, so a dismiss tap would also
    //      activate the underlying chat content (open a profile, a link, the
    //      media viewer, etc.). A full-window, non-focusable scrim Popup placed
    //      below this menu consumes those taps: tapping it dismisses the menu
    //      and the press is consumed so it never reaches the transcript. The
    //      scrim is itself non-focusable, so it preserves the open keyboard.
    //
    // Everything below is wrapped in a single zero-size Box so this composable
    // always contributes exactly ONE (zero-height) child to the caller's
    // spacedBy bubble Column, whether or not the menu is open. Emitting the
    // scrim popup as a second sibling only while expanded would otherwise add
    // an extra Arrangement.spacedBy gap, visibly growing the bubble height on
    // long-press (#284 review).
    val density = LocalDensity.current
    // Single zero-size Box wrapper: this composable always contributes exactly
    // ONE (zero-height) child to the caller's spacedBy bubble Column, open or
    // not. Emitting the scrim + menu popups as bare siblings only while expanded
    // adds extra Arrangement.spacedBy gaps that visibly grow the bubble on
    // long-press (#284 review). The popups themselves render in their own
    // windows, so the Box stays zero-size either way.
    Box {
        if (!expanded) return@Box
        BackHandler(enabled = true) { onDismissRequest() }
        // Scrim popup: composed before the menu so the menu renders on top of it.
        // Fills the window and swallows any tap as a pure dismissal.
        Popup(
            properties =
                PopupProperties(
                    focusable = false,
                    // We own dismissal via the tap handler below; let the menu's own
                    // outside-tap detection stay off so a single outside tap is
                    // handled exactly once, here, and consumed.
                    dismissOnClickOutside = false,
                ),
            onDismissRequest = onDismissRequest,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { onDismissRequest() }
                        },
            )
        }
        // Position the popup purely from the captured window touch y, independent of
        // any anchor's layout position. DropdownMenu derived flip-above from the
        // anchor's bounds, so a bubble taller than the viewport (anchor off-screen)
        // could send the menu above the finger even with room below (#326). This
        // provider clamps/flips against the window directly.
        val edgeInsetPx = with(density) { 8.dp.roundToPx() }
        // Compose runs calculatePosition on the FIRST layout pass with
        // popupContentSize == (0,0) (content not yet measured). With height 0 the
        // "fits below" branch is always true, so a near-top message would place
        // the menu top AT touchY on frame 1, then flip/clamp once the real tall
        // height arrives — a visible above-then-below jump (#389). Decide the side
        // deterministically from frame 1 by feeding a non-zero height into the
        // provider: the real measured height once known, else a per-variant
        // estimate derived from the menu's own layout so frame 1 already matches
        // the height the side decision will settle on.
        var measuredPopupHeightPx by remember { mutableStateOf(0) }
        // First-frame fallback only. A flat constant (the previous 240.dp) both
        // overestimated short menus — flipping them above even when they fit
        // below — and underestimated tall menus, so the measured height could
        // still flip the side on frame 2 (the same jump #389 set out to remove,
        // see #517). Instead, predict the height from the exact menu layout:
        //   - one emoji/quick-reaction Row (36.dp)
        //   - a HorizontalDivider (1.dp)
        //   - the action buttons (each 48.dp min) in a spacedBy(2.dp) Column:
        //       Copy and Info always; +Reply when canReply; +Edit when canEdit;
        //       +Forward when canForward; +Delete when canDelete
        //   - the outer Column's 8.dp padding (top + bottom) and its two
        //     spacedBy(8.dp) gaps between the three sections.
        // Keep this in sync with the menu Column below if its layout changes.
        val estimatedPopupHeightPx =
            with(density) {
                val actionButtonCount =
                    2 +
                        (if (canReply) 1 else 0) +
                        (if (canEdit) 1 else 0) +
                        (if (canForward) 1 else 0) +
                        (if (canDelete) 1 else 0)
                val actionsColumnHeight = (actionButtonCount * 48).dp + ((actionButtonCount - 1).coerceAtLeast(0) * 2).dp
                val reactionSectionHeight = if (canReact) 36.dp + 1.dp + 8.dp else 0.dp
                val totalHeight = (8.dp + 8.dp) + 8.dp + reactionSectionHeight + actionsColumnHeight
                totalHeight.roundToPx()
            }
        val positionProvider =
            remember(anchorWindowYPx, alignEnd, edgeInsetPx, measuredPopupHeightPx, estimatedPopupHeightPx) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        val touchY = anchorWindowYPx?.roundToInt() ?: (windowSize.height / 2)
                        // Horizontal: hug the bubble side, clamped inside the window.
                        val x =
                            if (alignEnd) {
                                windowSize.width - popupContentSize.width - edgeInsetPx
                            } else {
                                edgeInsetPx
                            }.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                        // Decide vertical placement against a non-zero height so the
                        // chosen side is stable from the first frame. Once the popup
                        // has any real measurement (content or onSizeChanged), use it
                        // directly so the *settled* placement reflects the true menu
                        // height; the per-variant estimate is consulted only on the
                        // first frame before either is known (#517).
                        val measuredHeight = maxOf(popupContentSize.height, measuredPopupHeightPx)
                        val effectiveHeight =
                            if (measuredHeight > 0) measuredHeight else estimatedPopupHeightPx
                        // Vertical: top at the touch y; flip upward if it would spill
                        // past the bottom inset; if it still doesn't fit, clamp to top.
                        val bottomLimit = windowSize.height - edgeInsetPx
                        val y =
                            when {
                                touchY + effectiveHeight <= bottomLimit -> touchY
                                effectiveHeight <= touchY - edgeInsetPx -> touchY - effectiveHeight
                                else -> edgeInsetPx
                            }.coerceIn(edgeInsetPx, (windowSize.height - effectiveHeight).coerceAtLeast(0))
                        return IntOffset(x, y)
                    }
                }
            }
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = onDismissRequest,
            properties =
                PopupProperties(
                    focusable = false,
                    // Outside taps are handled by the scrim above (which also blocks
                    // click-through); disabling the menu's own outside-dismiss keeps a
                    // single tap from being processed twice.
                    dismissOnClickOutside = false,
                ),
        ) {
            // Surface restores the menu chrome (rounded shape + elevation) that
            // DropdownMenu provided.
            Surface(
                modifier = Modifier.onSizeChanged { measuredPopupHeightPx = it.height },
                shape = RoundedCornerShape(12.dp),
                border = amoledSurfaceBorderStroke(),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(8.dp).widthIn(min = 292.dp, max = 328.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canReact) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            quickReactionEmojis.forEach { emoji ->
                                EmojiActionButton(
                                    emoji = emoji,
                                    onClick = { onReact(emoji) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            IconButton(
                                onClick = onOpenEmojiPicker,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Default.EmojiEmotions,
                                    contentDescription = stringResource(R.string.open_emoji_picker),
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (canReply) {
                            MessageActionButton(
                                label = stringResource(R.string.reply),
                                icon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = onReply,
                            )
                        }
                        if (canEdit) {
                            MessageActionButton(
                                label = stringResource(R.string.edit),
                                icon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = onEdit,
                            )
                        }
                        MessageActionButton(
                            label = stringResource(R.string.copy_text),
                            icon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = onCopyText,
                        )
                        if (canForward) {
                            MessageActionButton(
                                label = stringResource(R.string.forward),
                                icon = { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = onForward,
                            )
                        }
                        MessageActionButton(
                            label = stringResource(R.string.message_info),
                            icon = { Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = onInfo,
                        )
                        if (canDelete) {
                            MessageActionButton(
                                label = stringResource(R.string.delete),
                                icon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = onDelete,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Chat-picker sheet for forwarding a message into one or more other chats
 * (issue #390). Multi-select, searchable, recent-first. Confirming fans the
 * message out to every selected chat as an independent fresh send (see
 * [WhiteNoiseAppState.forwardText]) — each target is re-encrypted under its own
 * group state, with no source-group key reuse and no original-sender / source
 * attribution carried across the boundary.
 *
 * The picker deliberately omits the chat the message came from
 * ([originGroupIdHex]): forwarding a message back into its own conversation is
 * never the intent and would just duplicate it.
 */
internal fun forwardTargetAvatarAccount(item: ChatListItem): String? =
    GroupProjector.avatarAccount(
        group = item.group,
        otherMemberAccount = item.otherMemberAccount,
        memberCount = item.memberCount,
    )

internal fun forwardTargetMembersPreview(
    item: ChatListItem,
    activeAccountIdHex: String?,
    memberTitle: (String) -> String,
): String? {
    if (forwardTargetAvatarAccount(item) != null) return null
    return item.memberSnapshot
        ?.members
        ?.filterNot { it.memberIdHex.equals(activeAccountIdHex, ignoreCase = true) }
        ?.map { memberTitle(it.memberIdHex) }
        ?.filter { it.isNotBlank() }
        ?.take(6)
        ?.joinToString(", ")
        ?.takeIf { it.isNotBlank() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForwardMessageSheet(
    appState: WhiteNoiseAppState,
    body: String,
    originGroupIdHex: String,
    onDismiss: () -> Unit,
    onForward: (List<String>) -> Unit,
) {
    val groupTitleCopy = rememberGroupTitleCopy()
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    // Snapshot the forward targets once when the sheet opens. The chat list is
    // a live projection, but a picker that re-sorts under the user's finger as
    // a background send confirms would shuffle rows mid-selection; a stable
    // snapshot keeps the selection anchored to the rows the user actually saw.
    val targets =
        remember {
            appState.forwardTargets().filterNot { it.group.groupIdHex.equals(originGroupIdHex, ignoreCase = true) }
        }
    val titledTargets =
        remember(targets, groupTitleCopy) {
            targets.map { it to chatListItemDisplayTitle(it, appState, groupTitleCopy) }
        }
    val filtered =
        remember(titledTargets, query) {
            val needle = query.trim()
            if (needle.isEmpty()) {
                titledTargets
            } else {
                titledTargets.filter { (_, title) -> title.contains(needle, ignoreCase = true) }
            }
        }
    // Opens at half height with a drag up to full — a long chat list stays
    // reachable without the sheet swallowing the conversation behind it.
    val sheetState = rememberModalBottomSheetState()
    var searchFocused by remember { mutableStateOf(false) }
    val expanded = sheetState.currentValue == SheetValue.Expanded || sheetState.targetValue == SheetValue.Expanded
    val targetListMaxHeight = if (expanded) 420.dp else 152.dp
    LaunchedEffect(searchFocused) {
        if (searchFocused) sheetState.expand()
    }
    val activeAccountIdHex = appState.activeAccount?.accountIdHex
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = amoledSheetContainerColor(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.forward_to),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // Preview of what is being forwarded, so the user can confirm the
            // content before fanning it out to several chats.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                border = amoledSurfaceBorderStroke(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
            ) {
                // Preview only: resolve raw profile mention runs to display
                // names so the confirmation reads like the bubble (#615/#1090).
                // The forwarded text stays the verbatim `body` — onForward
                // never sees this string.
                Text(
                    resolveMentionsInPlaintext(body) { appState.mentionDisplayName(it) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            FlowSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.forward_search_chats),
                modifier =
                    Modifier
                        .padding(horizontal = Dimens.spaceLg)
                        .onFocusChanged { searchFocused = it.isFocused },
            )
            LazyColumn(
                modifier =
                    Modifier
                        .heightIn(max = targetListMaxHeight)
                        .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = Dimens.spaceLg),
            ) {
                if (targets.isEmpty() || filtered.isEmpty()) {
                    item {
                        Text(
                            stringResource(
                                if (targets.isEmpty()) R.string.forward_no_chats else R.string.forward_no_matches,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceLg),
                        )
                    }
                } else {
                    item { SectionHeader(stringResource(R.string.recent_chats)) }
                    items(filtered, key = { (item, _) -> item.group.groupIdHex }) { (item, title) ->
                        val groupId = item.group.groupIdHex
                        val isSelected = selected.contains(groupId)
                        val avatarAccount = forwardTargetAvatarAccount(item)
                        // Group rows preview the other members' names, mirroring
                        // the chat-list mental model; direct chats need none.
                        val membersPreview =
                            remember(item, appState.profileRevisionForCompose) {
                                forwardTargetMembersPreview(item, activeAccountIdHex) { memberIdHex ->
                                    appState.chatMemberTitleCached(memberIdHex)
                                }
                            }
                        ContactRow(
                            title = title,
                            subtitle = membersPreview,
                            avatarSeed = avatarAccount ?: item.group.groupIdHex,
                            avatarUrl = item.group.avatarUrl ?: avatarAccount?.let { appState.avatarUrl(it) },
                            onClick = {
                                if (isSelected) selected.remove(groupId) else selected.add(groupId)
                            },
                            trailing = { SelectionIndicator(selected = isSelected) },
                        )
                    }
                }
            }

            Surface(
                color = amoledSheetContainerColor(),
                shadowElevation = 6.dp,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .imePadding(),
            ) {
                Button(
                    onClick = {
                        onForward(selected.toList())
                        onDismiss()
                    },
                    enabled = selected.isNotEmpty(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.spaceLg, vertical = 12.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Forward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (selected.isEmpty()) {
                            stringResource(R.string.forward)
                        } else {
                            pluralStringResource(
                                R.plurals.forward_to_chats_count,
                                selected.size,
                                selected.size,
                            )
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageInfoSheet(
    item: TimelineMessage,
    mine: Boolean,
    senderDisplayName: String,
    senderNpub: String,
    onDismissRequest: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val record = item.record
    val configuration = LocalConfiguration.current
    val locale =
        remember(configuration) {
            ConfigurationCompat.getLocales(configuration).get(0) ?: Locale.getDefault()
        }
    val zone = remember { ZoneId.systemDefault() }
    val statusLabels =
        MessageStatusLabels(
            pending = stringResource(R.string.message_status_pending),
            sent = stringResource(R.string.message_status_sent),
            received = stringResource(R.string.message_status_received),
            failed = stringResource(R.string.message_status_failed),
            streaming = stringResource(R.string.message_status_streaming),
        )
    val statusText = labelFor(item.status, statusLabels)
    // Label derives from status, not `mine`, so an outgoing Failed bubble
    // doesn't read "Sent" while the Status row says "Failed". For outgoing
    // pending/failed the row reflects local composition time.
    val timestampLabel =
        when (item.status) {
            MessageStatus.Sent -> stringResource(R.string.message_info_sent_at)
            MessageStatus.Received, MessageStatus.Streaming -> stringResource(R.string.message_info_received_at)
            MessageStatus.Pending, MessageStatus.Failed -> stringResource(R.string.message_info_created_at)
        }
    // For incoming, prefer the *local* arrival time — sender's claimed
    // `recordedAt` can be spoofed. Surface `recordedAt` as a second row only
    // when it diverges from receivedAt by more than a few seconds (anything
    // less is clock-skew noise).
    val primarySeconds = if (!mine && record.receivedAt > 0uL) record.receivedAt else record.recordedAt
    val formattedTimestamp = formatExactTimestamp(primarySeconds, zone, locale)
    val showOriginal = !mine && shouldShowOriginalTimestamp(record.recordedAt, record.receivedAt)
    val formattedOriginalTimestamp =
        if (showOriginal) {
            formatExactTimestamp(record.recordedAt, zone, locale)
        } else {
            ""
        }
    val npubShort = shortHex(senderNpub, head = 12, tail = 6)
    val messageIdShort = shortHex(record.messageIdHex)
    val copyActionLabel = stringResource(R.string.copy_text)

    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.message_info),
                style = MaterialTheme.typography.titleMedium,
            )
            if (formattedTimestamp.isNotBlank()) {
                MessageInfoRow(
                    label = timestampLabel,
                    value = formattedTimestamp,
                )
            }
            if (formattedOriginalTimestamp.isNotBlank()) {
                // Sender's claimed send time. Suppressed when it matches the
                // local Received time within the skew tolerance — see
                // shouldShowOriginalTimestamp — so the row only appears when
                // it adds information.
                MessageInfoRow(
                    label = stringResource(R.string.message_info_sent_at),
                    value = formattedOriginalTimestamp,
                )
            }
            // "From" is meaningful only for incoming messages; hide for own
            // messages where it would read tautologically "From: <my name>".
            if (!mine && senderNpub.isNotBlank()) {
                MessageInfoRow(
                    label = stringResource(R.string.message_info_sender),
                    value = if (senderDisplayName.isNotBlank()) "$senderDisplayName · $npubShort" else npubShort,
                    onCopy = { onCopy(senderNpub) },
                    copyActionLabel = copyActionLabel,
                )
            }
            if (record.messageIdHex.isNotBlank()) {
                MessageInfoRow(
                    label = stringResource(R.string.message_info_message_id),
                    value = messageIdShort,
                    onCopy = { onCopy(record.messageIdHex) },
                    copyActionLabel = copyActionLabel,
                )
            }
            MessageInfoRow(
                label = stringResource(R.string.message_info_status),
                value = statusText,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MessageInfoRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null,
    copyActionLabel: String? = null,
) {
    val rowModifier =
        if (onCopy != null) {
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = copyActionLabel,
                    role = Role.Button,
                    onClick = onCopy,
                )
        } else {
            Modifier.fillMaxWidth()
        }
    Row(
        modifier = rowModifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (onCopy != null) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomizeReactionsDialog(
    quickReactionEmojis: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onReset: () -> Unit,
) {
    var draft by remember(quickReactionEmojis) { mutableStateOf(RecentEmojiList.normalizeQuickChoices(quickReactionEmojis)) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
            ),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.customize_reactions)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(32.dp),
                        border = amoledSurfaceBorderStroke(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            draft.forEachIndexed { index, emoji ->
                                Box(
                                    modifier =
                                        Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .clickable { editingIndex = index },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(emoji, style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.customize_reactions_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            draft = RecentEmojiList.DefaultQuickChoices
                            onReset()
                        },
                    ) {
                        Text(stringResource(R.string.reset_reactions))
                    }
                    Button(onClick = { onSave(draft) }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
        if (editingIndex != null) {
            EmojiPickerSheet(
                onDismissRequest = { editingIndex = null },
                onEmojiPicked = { emoji ->
                    val index = editingIndex ?: return@EmojiPickerSheet
                    draft = draft.toMutableList().also { it[index] = emoji }
                    editingIndex = null
                },
                recordRecentPicks = false,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiPickerSheet(
    onDismissRequest: () -> Unit,
    onEmojiPicked: (String) -> Unit,
    recordRecentPicks: Boolean = true,
    restoreExpanded: Boolean = false,
    messageReactionEmojis: List<String> = emptyList(),
    onCustomizeReactions: ((Boolean) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    LaunchedEffect(restoreExpanded, sheetState) {
        if (restoreExpanded) {
            runCatching { sheetState.expand() }
        }
    }
    val contentExpanded =
        sheetState.currentValue == SheetValue.Expanded || sheetState.targetValue == SheetValue.Expanded
    val visibleContentFraction = emojiPickerSheetVisibleContentFraction(expanded = contentExpanded)
    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = onDismissRequest,
        // Start at the partial detent, but keep the sheet composed under the
        // customize screen so an expanded picker returns expanded without a
        // visible collapse/re-expand.
        sheetState = sheetState,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(EmojiPickerSheetMaxHeightFraction)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            EmojiPickerContent(
                onEmojiPicked = onEmojiPicked,
                recordRecentPicks = recordRecentPicks,
                messageReactionEmojis = messageReactionEmojis,
                onCustomizeReactions =
                    onCustomizeReactions?.let { customize ->
                        { customize(sheetState.currentValue == SheetValue.Expanded) }
                    },
                searchFieldAlwaysVisible = true,
                searchStartsOpen = false,
                // Material3 measures the sheet at its full requested height, then
                // exposes only the top half at the partial detent. Keep the outer
                // shell tall enough for expansion, but lay out the picker controls
                // inside the actually visible partial viewport so the rail is not
                // hidden below the fold.
                // Focusing the search field expands the sheet to full screen;
                // imePadding then lifts the whole picker (grid + category rail)
                // above the keyboard so nothing hides behind the IME (#1104).
                onSearchActiveChange = { active ->
                    if (active) {
                        scope.launch { runCatching { sheetState.expand() } }
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(visibleContentFraction),
            )
        }
    }
}

@Composable
private fun ComposerEmojiPickerPane(
    height: Dp,
    alpha: Float,
    onEmojiPicked: (String) -> Unit,
    onBackspace: () -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .clipToBounds()
                .alpha(alpha),
        color = MaterialTheme.colorScheme.surface,
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
    ) {
        EmojiPickerContent(
            onEmojiPicked = onEmojiPicked,
            recordRecentPicks = false,
            onBackspace = onBackspace,
            searchStartsOpen = false,
            onSearchActiveChange = onSearchActiveChange,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

internal const val EmojiPickerSheetMaxHeightFraction = 0.86f

private const val EmojiPickerSheetPartialDetentFraction = 0.48f

internal fun emojiPickerSheetVisibleContentFraction(expanded: Boolean): Float =
    if (expanded) {
        1f
    } else {
        (EmojiPickerSheetPartialDetentFraction / EmojiPickerSheetMaxHeightFraction).coerceIn(0f, 1f)
    }

internal val ComposerEmojiPickerFallbackHeight = 320.dp

internal val ComposerEmojiPickerSearchExtraHeight = 112.dp

/**
 * Whether the composer bottom cluster (reply preview, edit banner, mention
 * picker, and input row) should apply [imePadding]. Suppressed while the emoji
 * pane owns the bottom region so the keyboard/emoji swap does not double-count
 * insets (#808, #895, #1109).
 */
internal fun composerBottomClusterAppliesImePadding(showEmojiPane: Boolean): Boolean = !showEmojiPane

internal fun composerBottomClusterModifier(
    showEmojiPane: Boolean,
    base: Modifier = Modifier,
): Modifier {
    val withNav = base.navigationBarsPadding()
    return if (composerBottomClusterAppliesImePadding(showEmojiPane)) {
        withNav.imePadding()
    } else {
        withNav
    }
}

/**
 * Starting a reply grows the bottom input cluster by inserting the preview card.
 * Re-anchor only on the null -> non-null edge; recompositions while the same
 * reply is active must not keep stealing scroll while the user types (#1109).
 */
internal fun shouldReanchorBottomInputForReplyTargetChange(
    hadReplyTarget: Boolean,
    hasReplyTarget: Boolean,
): Boolean = hasReplyTarget && !hadReplyTarget

internal fun composerEmojiPaneTargetHeight(
    currentImeHeight: Dp,
    rememberedImeHeight: Dp,
): Dp =
    when {
        currentImeHeight > 0.dp -> currentImeHeight
        rememberedImeHeight > 0.dp -> rememberedImeHeight
        else -> ComposerEmojiPickerFallbackHeight
    }

internal fun composerEmojiPaneHeight(
    lockedPaneHeight: Dp,
    currentImeHeight: Dp,
    rememberedImeHeight: Dp,
): Dp =
    if (lockedPaneHeight > 0.dp) {
        lockedPaneHeight
    } else {
        composerEmojiPaneTargetHeight(currentImeHeight, rememberedImeHeight)
    }

internal fun updatedComposerRememberedImeHeight(
    previousRememberedImeHeight: Dp,
    currentImeHeight: Dp,
    freezeUpdates: Boolean,
): Dp =
    if (!freezeUpdates && currentImeHeight > 0.dp) {
        currentImeHeight
    } else {
        previousRememberedImeHeight
    }

internal fun shouldSwapComposerEmojiPaneToIme(
    keyboardRestorePending: Boolean,
    currentImeHeight: Dp,
    targetImeHeight: Dp,
): Boolean =
    keyboardRestorePending &&
        currentImeHeight > 0.dp &&
        currentImeHeight.value >= targetImeHeight.value * 0.85f

@Composable
private fun EmojiPickerContent(
    onEmojiPicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    recordRecentPicks: Boolean = true,
    messageReactionEmojis: List<String> = emptyList(),
    onBackspace: (() -> Unit)? = null,
    onCustomizeReactions: (() -> Unit)? = null,
    searchStartsOpen: Boolean = false,
    searchFieldAlwaysVisible: Boolean = false,
    onSearchActiveChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(searchStartsOpen) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val browseEmoji by produceState(initialValue = emptyList<EmojiEntry>(), context) {
        value = withContext(Dispatchers.IO) { EmojiData.load(context) }
    }
    val searchResults =
        remember(searchQuery, browseEmoji) {
            EmojiData.search(browseEmoji, searchQuery)
        }
    val grouped = remember(browseEmoji) { browseEmoji.groupBy { it.group } }
    val messageReactions =
        remember(messageReactionEmojis) {
            messageReactionEmojis.filter { it.isNotBlank() }.distinct()
        }
    var recents by remember(context) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(context) {
        recents = withContext(Dispatchers.IO) { RecentEmojiPreferences.load(context).filter { it.isNotBlank() } }
    }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var activeCategory by remember(recents.isNotEmpty()) { mutableStateOf(if (recents.isNotEmpty()) -1 else 0) }
    // Grid item index of each section's header, so a bottom category tab scrolls
    // straight to it. Layout: recents (header + cells) then each group (header +
    // cells); every header and every emoji counts as one grid item.
    val sectionHeaderIndex =
        remember(grouped, messageReactions, recents) {
            IntArray(EmojiData.GroupCount).also { arr ->
                var index = if (messageReactions.isEmpty()) 0 else 1 + messageReactions.size
                if (recents.isNotEmpty()) index += 1 + recents.size
                for (group in 0 until EmojiData.GroupCount) {
                    arr[group] = index
                    val count = grouped[group]?.size ?: 0
                    if (count > 0) index += 1 + count
                }
            }
        }
    LaunchedEffect(searchOpen) {
        onSearchActiveChange(searchOpen)
        if (searchOpen) {
            withFrameNanos { }
            runCatching { searchFocusRequester.requestFocus() }
            keyboardController?.show()
        } else {
            searchQuery = ""
        }
    }
    LaunchedEffect(gridState, sectionHeaderIndex, messageReactions.size, recents.size) {
        snapshotFlow { gridState.firstVisibleItemIndex }
            .collect { index ->
                val recentsHeaderIndex = if (messageReactions.isEmpty()) 0 else 1 + messageReactions.size
                activeCategory =
                    if (recents.isNotEmpty() && index >= recentsHeaderIndex && index < (sectionHeaderIndex.firstOrNull() ?: 0)) {
                        -1
                    } else {
                        sectionHeaderIndex
                            .withIndex()
                            .lastOrNull { (_, headerIndex) -> index >= headerIndex }
                            ?.index
                            ?: 0
                    }
            }
    }

    fun pick(emoji: String) {
        if (recordRecentPicks) {
            scope.launch {
                recents = withContext(Dispatchers.IO) { RecentEmojiPreferences.recordPicked(context, emoji).filter { it.isNotBlank() } }
            }
        }
        onEmojiPicked(emoji)
    }

    // Keep the rail out of the weighted grid column so the partial sheet detent
    // reserves it before the emoji grid takes the remaining height.
    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            EmojiCategoryRail(
                onCustomizeReactions = onCustomizeReactions,
                showSearch = !searchFieldAlwaysVisible,
                searchSelected = searchOpen,
                onSearch = { searchOpen = true },
                showRecents = recents.isNotEmpty(),
                recentsSelected = activeCategory == -1,
                onRecents = {
                    searchOpen = false
                    keyboardController?.hide()
                    activeCategory = -1
                    scope.launch {
                        gridState.scrollToItem(if (messageReactions.isEmpty()) 0 else 1 + messageReactions.size)
                    }
                },
                selectedGroup = activeCategory,
                onGroup = { group ->
                    searchOpen = false
                    keyboardController?.hide()
                    activeCategory = group
                    scope.launch { gridState.scrollToItem(sectionHeaderIndex[group]) }
                },
                onBackspace = onBackspace,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (searchFieldAlwaysVisible || searchOpen) {
                EmojiSearchField(
                    value = searchQuery,
                    onValueChange = {
                        if (searchFieldAlwaysVisible) searchOpen = true
                        searchQuery = it
                    },
                    onClose = {
                        searchOpen = false
                        keyboardController?.hide()
                    },
                    onSearchIntent = { searchOpen = true },
                    focusRequester = searchFocusRequester,
                    showBackButton = !searchFieldAlwaysVisible,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!searchOpen || searchQuery.isBlank()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(9),
                    state = gridState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (messageReactions.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmojiSectionHeader(stringResource(R.string.emoji_category_this_message))
                        }
                        items(messageReactions, key = { "message-reaction:$it" }) { emoji ->
                            EmojiSearchResultCell(emoji = emoji, onClick = { pick(emoji) })
                        }
                    }
                    if (recents.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmojiSectionHeader(stringResource(R.string.emoji_category_recent))
                        }
                        items(recents, key = { "recent:$it" }) { emoji ->
                            EmojiSearchResultCell(emoji = emoji, onClick = { pick(emoji) })
                        }
                    }
                    for (group in 0 until EmojiData.GroupCount) {
                        val groupEmoji = grouped[group].orEmpty()
                        if (groupEmoji.isEmpty()) continue
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmojiSectionHeader(stringResource(emojiGroupTitleRes(group)))
                        }
                        items(groupEmoji, key = { it.emoji }) { entry ->
                            EmojiSearchResultCell(emoji = entry.emoji, onClick = { pick(entry.emoji) })
                        }
                    }
                }
            } else {
                EmojiSearchResultsGrid(
                    results = searchResults,
                    onEmojiPicked = { pick(it) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EmojiSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClose: () -> Unit,
    onSearchIntent: () -> Unit,
    focusRequester: FocusRequester,
    showBackButton: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(64.dp).padding(top = 14.dp, bottom = 6.dp),
        color = Color(0xFF303337),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(
                onClick = if (showBackButton) onClose else onSearchIntent,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    if (showBackButton) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Search,
                    contentDescription =
                        if (showBackButton) {
                            stringResource(R.string.back)
                        } else {
                            stringResource(R.string.emoji_search_hint)
                        },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                    modifier = Modifier.size(26.dp),
                )
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle =
                        LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { if (it.isFocused) onSearchIntent() },
                )
                if (value.isEmpty()) {
                    Text(
                        stringResource(R.string.emoji_search_hint),
                        style =
                            LocalTextStyle.current.copy(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
            }
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.emoji_search_clear),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmojiSearchResultsGrid(
    results: List<EmojiEntry>,
    onEmojiPicked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (results.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.emoji_search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(9),
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(results, key = { it.emoji }) { entry ->
            EmojiSearchResultCell(
                emoji = entry.emoji,
                onClick = { onEmojiPicked(entry.emoji) },
            )
        }
    }
}

@Composable
private fun EmojiSearchResultCell(
    emoji: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun EmojiCategoryRail(
    onCustomizeReactions: (() -> Unit)?,
    showSearch: Boolean,
    searchSelected: Boolean,
    onSearch: () -> Unit,
    showRecents: Boolean,
    recentsSelected: Boolean,
    onRecents: () -> Unit,
    selectedGroup: Int,
    onGroup: (Int) -> Unit,
    onBackspace: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .background(Color(0xFF202126))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onCustomizeReactions != null) {
            EmojiRailIconButton(
                onClick = onCustomizeReactions,
                selected = false,
                modifier = Modifier.weight(1f).height(42.dp),
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.customize_reactions),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showSearch) {
            EmojiRailIconButton(
                onClick = onSearch,
                selected = searchSelected,
                modifier = Modifier.weight(1f).height(42.dp),
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = stringResource(R.string.emoji_search_hint),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showRecents) {
            EmojiCategoryTab(
                icon = Icons.Default.History,
                contentDescription = stringResource(R.string.emoji_category_recent),
                selected = recentsSelected,
                modifier = Modifier.weight(1f).height(42.dp),
                onClick = onRecents,
            )
        }
        for (group in 0 until EmojiData.GroupCount) {
            EmojiCategoryTab(
                icon = emojiGroupIcon(group),
                contentDescription = stringResource(emojiGroupTitleRes(group)),
                selected = selectedGroup == group,
                modifier = Modifier.weight(1f).height(42.dp),
                onClick = { onGroup(group) },
            )
        }
        if (onBackspace != null) {
            EmojiRailIconButton(
                onClick = onBackspace,
                selected = false,
                modifier = Modifier.weight(1f).height(42.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = stringResource(R.string.emoji_backspace),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun EmojiRailIconButton(
    onClick: () -> Unit,
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.padding(horizontal = 1.dp).clip(CircleShape).clickable(onClick = onClick),
        shape = CircleShape,
        color =
            if (selected) {
                Color(0xFF424652)
            } else {
                Color.Transparent
            },
        contentColor =
            if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f)
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun EmojiCategoryTab(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    EmojiRailIconButton(
        onClick = onClick,
        selected = selected,
        modifier = modifier,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun EmojiSectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
    )
}

private fun emojiGroupIcon(group: Int): ImageVector =
    when (group) {
        0 -> Icons.Default.EmojiEmotions
        1 -> Icons.Default.Person
        2 -> Icons.Default.Pets
        3 -> Icons.Default.Restaurant
        4 -> Icons.Default.DirectionsCar
        5 -> Icons.Default.SportsSoccer
        6 -> Icons.Default.Favorite
        7 -> Icons.Default.Tag
        else -> Icons.Default.Public
    }

@StringRes
private fun emojiGroupTitleRes(group: Int): Int =
    when (group) {
        0 -> R.string.emoji_category_smileys
        1 -> R.string.emoji_category_people
        2 -> R.string.emoji_category_animals
        3 -> R.string.emoji_category_food
        4 -> R.string.emoji_category_travel
        5 -> R.string.emoji_category_activities
        6 -> R.string.emoji_category_objects
        7 -> R.string.emoji_category_symbols
        else -> R.string.emoji_category_flags
    }

@Composable
private fun EmojiActionButton(
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(36.dp).clip(CircleShape).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape,
        border = amoledSurfaceBorderStroke(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MessageActionButton(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OutgoingMessageStatusIcon(
    status: MessageStatus,
    tint: Color,
) {
    val indicator = status.outgoingIndicator() ?: return
    when (indicator) {
        OutgoingMessageIndicator.Sending ->
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = stringResource(R.string.sending),
                modifier = Modifier.size(14.dp),
                tint = tint.copy(alpha = 0.76f),
            )
        OutgoingMessageIndicator.Sent ->
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.sent),
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        OutgoingMessageIndicator.Failed ->
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = stringResource(R.string.send_failed),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error,
            )
    }
}

// Gap between a bubble's text and its trailing inline footer.
private val BubbleFooterGap = 8.dp

// A body longer than this many rendered lines collapses to a Read More that
// opens the full-screen view rather than spilling down the transcript (#325).
private const val MESSAGE_COLLAPSE_LINE_LIMIT = 18

// Distinct emojis shown in the consolidated reaction pill; the total count
// still reflects every reaction beyond them.
private const val MAX_VISIBLE_REACTIONS = 4

/** Legibility scrim for a footer overlaid on visual media (image/video). */
@Composable
private fun MediaScrimFooter(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        content()
    }
}

/**
 * Sticky day-ribbon overlay: the day label of the topmost visible message,
 * faded in only while the timeline is actively scrolling (the inline
 * [DaySeparator]s carry the day at rest).
 *
 * Reads the scroll-backed state (`labelState` derived from
 * `firstVisibleItemIndex`, and `listState.isScrollInProgress`) inside this
 * small child so per-scroll-frame recomposition is confined here and does not
 * propagate to the LazyColumn-hosting Box scope (#375).
 */
@Composable
private fun BoxScope.StickyDayRibbon(
    listState: androidx.compose.foundation.lazy.LazyListState,
    labelState: State<String>,
) {
    val label by labelState
    val alpha by animateFloatAsState(
        targetValue =
            if (shouldShowStickyDayRibbon(listState.isScrollInProgress, listState.canScrollBackward || listState.canScrollForward, label)) {
                1f
            } else {
                0f
            },
        label = "stickyDayRibbon",
    )
    if (alpha > 0.01f) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .alpha(alpha)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

internal fun shouldShowStickyDayRibbon(
    isScrollInProgress: Boolean,
    canScrollContent: Boolean,
    label: String,
): Boolean = isScrollInProgress && canScrollContent && label.isNotEmpty()

/** Time (+ outgoing status) overlaid on the bottom-right of a visual-media bubble. */
@Composable
private fun BoxScope.MediaFooterOverlay(
    timeText: String,
    showStatus: Boolean,
    status: MessageStatus,
) {
    MediaScrimFooter(
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp),
    ) {
        MessageInlineFooter(
            timeText = timeText,
            color = Color.White,
            showStatus = showStatus,
            status = status,
            editedLabel = null,
            onEditedClick = null,
        )
    }
}

/**
 * Bottom-end footer for a message bubble: an optional "edited" affordance, the
 * time, and (outgoing only) the send-status icon, in a subtle tint.
 */
@Composable
internal fun MessageInlineFooter(
    timeText: String,
    color: Color,
    showStatus: Boolean,
    status: MessageStatus,
    editedLabel: String?,
    onEditedClick: (() -> Unit)?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editedLabel != null) {
            Text(
                text = editedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = if (onEditedClick != null) Modifier.clickable(onClick = onEditedClick) else Modifier,
            )
        }
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
        if (showStatus) {
            OutgoingMessageStatusIcon(status, tint = color)
        }
    }
}

/**
 * Lays [content] with [footer] pinned bottom-end. The footer joins the last
 * line when it leaves room ([lastLineWidth], the real last-line right edge when
 * the caller can supply it; otherwise the widest line); else it drops to its
 * own line below. Either way it stays right of the text and never overlaps.
 */
@Composable
private fun BubbleFooterLayout(
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    lastLineWidth: Int? = null,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Box { content() }
            Box { footer() }
        },
    ) { measurables, constraints ->
        val footerPlaceable = measurables[1].measure(Constraints())
        val contentPlaceable = measurables[0].measure(constraints.copy(minWidth = 0))
        val gap = BubbleFooterGap.roundToPx()
        val lastRight = (lastLineWidth ?: contentPlaceable.width).coerceIn(0, contentPlaceable.width)
        val inline = lastRight + gap + footerPlaceable.width <= constraints.maxWidth
        if (inline) {
            val width =
                bubbleFooterInlineWidth(
                    contentWidth = contentPlaceable.width,
                    lastLineRight = lastRight,
                    footerWidth = footerPlaceable.width,
                    minWidth = constraints.minWidth,
                    maxWidth = constraints.maxWidth,
                    gap = gap,
                )
            layout(width, contentPlaceable.height) {
                contentPlaceable.place(0, 0)
                footerPlaceable.place(width - footerPlaceable.width, contentPlaceable.height - footerPlaceable.height)
            }
        } else {
            val width =
                maxOf(contentPlaceable.width, footerPlaceable.width, constraints.minWidth)
                    .coerceAtMost(constraints.maxWidth)
            layout(width, contentPlaceable.height + footerPlaceable.height) {
                contentPlaceable.place(0, 0)
                footerPlaceable.place(width - footerPlaceable.width, contentPlaceable.height)
            }
        }
    }
}

internal fun bubbleFooterInlineWidth(
    contentWidth: Int,
    lastLineRight: Int,
    footerWidth: Int,
    minWidth: Int,
    maxWidth: Int,
    gap: Int,
): Int =
    maxOf(contentWidth, lastLineRight + gap + footerWidth, minWidth)
        .coerceAtMost(maxWidth)

@Composable
private fun RemovedMemberComposerNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
    ) {
        Text(
            // Explains the disabled composer for a member who left or was
            // removed; the timeline system row carries the left-vs-removed
            // distinction on its own.
            text = stringResource(R.string.you_are_no_longer_a_member),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Inserts an emoji at the composer's current selection, replacing any selected
 * range and moving the caret just after the inserted glyph. Kept pure so the
 * cursor math is pinned by local unit tests instead of only by Compose wiring.
 */
internal fun insertComposerEmoji(
    value: TextFieldValue,
    emoji: String,
): TextFieldValue {
    val text = value.text
    val start =
        minOf(value.selection.start, value.selection.end)
            .coerceIn(0, text.length)
    val end =
        maxOf(value.selection.start, value.selection.end)
            .coerceIn(start, text.length)
    val updatedText =
        buildString {
            append(text, 0, start)
            append(emoji)
            append(text, end, text.length)
        }
    val caret = start + emoji.length
    return value.copy(text = updatedText, selection = TextRange(caret), composition = null)
}

@Composable
internal fun ComposerBar(
    replyingTo: AppMessageRecordFfi?,
    messageTextCopy: MessageTextCopy,
    onCancelReply: () -> Unit,
    onSend: (text: String, onAccepted: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    initialDraft: String = "",
    onDraftChange: (String) -> Unit = {},
    draftKey: Any? = null,
    onAfterSend: () -> Unit = {},
    onPickFromGallery: (() -> Unit)? = null,
    onCaptureFromCamera: (() -> Unit)? = null,
    onPickDocument: (() -> Unit)? = null,
    voiceRecordingController: dev.ipf.whitenoise.android.audio.VoiceRecordingController? = null,
    editingMessageId: String? = null,
    editingInitialText: String? = null,
    onCancelEdit: () -> Unit = {},
    appState: WhiteNoiseAppState? = null,
    // Group @-mention picker (#414): candidates the picker filters over, and
    // whether this conversation is a group (the picker is suppressed in DMs —
    // a 1:1 chat has no one to disambiguate, so typing `@` stays literal).
    mentionCandidates: List<MentionComposer.Candidate> = emptyList(),
    mentionPickerEnabled: Boolean = false,
    // When the conversation was just created in the same navigation step
    // (issue #321), request focus on the composer and raise the soft keyboard
    // once on entry so the user can type the first message without an extra
    // tap. One-shot: a guard flag stops a revisit / recomposition from
    // re-opening the IME, and the flag is not persisted across process death.
    autoFocusOnEnter: Boolean = false,
    enterKeyBehavior: EnterKeyBehavior = EnterKeyBehavior.SendMessage,
    // #589: the composer FocusRequester is hoisted from the conversation screen
    // so its resume lifecycle observer can restore focus after an app-switch.
    // Defaulted to a locally-remembered requester so other call sites keep the
    // previous self-contained behavior.
    composerFocus: FocusRequester = remember { FocusRequester() },
    // #589: surfaces the live focus state up to the conversation screen so the
    // resume observer can tell whether the keyboard was up when we were paused.
    onComposerFocusChanged: (Boolean) -> Unit = {},
    onBottomInputChanged: () -> Unit = {},
) {
    var attachMenuOpen by remember { mutableStateOf(false) }
    var composerEmojiPickerOpen by remember { mutableStateOf(false) }
    var composerEmojiSearchActive by remember { mutableStateOf(false) }
    var composerKeyboardRestorePending by remember { mutableStateOf(false) }
    // Field state is a TextFieldValue (not a bare String) so the caret can
    // be positioned at the end of the prefilled body on edit-entry, and so
    // a re-tap on a different message rebases the caret too. Keyed on
    // draftKey so switching to a different chat re-hydrates the text field
    // from that chat's saved draft rather than carrying state across.
    var textFieldValue by remember(draftKey) { mutableStateOf(TextFieldValue(initialDraft)) }
    val text = textFieldValue.text
    // Snapshot the in-flight composer state (full TextFieldValue — text +
    // caret) when entering edit mode so cancelling restores both. Keyed on
    // the message id so a tap-Edit on a different message snapshots a fresh
    // baseline.
    var preEditFieldValue by remember(draftKey) { mutableStateOf<TextFieldValue?>(null) }
    // Claim focus on edit-entry so the IME opens with the caret at the end
    // of the prefill, without making the user tap the field a second time.
    // `composerFocus` is now hoisted in via a parameter (#589) so the
    // conversation screen's resume observer can drive focus too.
    // Keyed on editingMessageId only: prefill once when an edit session starts,
    // not on every reprojection of editingInitialText — otherwise a background
    // timeline update would overwrite the user's in-progress edit.
    LaunchedEffect(editingMessageId) {
        if (editingMessageId != null) {
            // Save the in-flight composer once per edit session, then push
            // the message's current text into the input so the user edits
            // from where it stands today (which is the latest applied edit
            // if there's already an edit chain). Selection at `length` lands
            // the caret past the last character — same caret model as a
            // long-press-to-edit on every other modern chat composer.
            if (preEditFieldValue == null) preEditFieldValue = textFieldValue
            val prefill = editingInitialText.orEmpty()
            textFieldValue = TextFieldValue(text = prefill, selection = TextRange(prefill.length))
            onBottomInputChanged()
            runCatching { composerFocus.requestFocus() }
        } else if (preEditFieldValue != null) {
            // Edit cancelled or submitted: restore the draft the user had
            // been composing before they tapped Edit (text + original caret).
            textFieldValue = preEditFieldValue ?: TextFieldValue("")
            preEditFieldValue = null
        }
    }
    // #321: a just-created conversation opens directly with the composer ready.
    // Request focus and raise the soft keyboard exactly once, gated by a
    // plain-`remember` flag (NOT rememberSaveable) so it fires per composition
    // and never re-fires on a revisit or after process death. Skipped while
    // editing — the edit effect above already owns focus then.
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navigationInsets = WindowInsets.navigationBars
    val currentImePaneHeight =
        with(density) {
            (imeInsets.getBottom(this) - navigationInsets.getBottom(this))
                .coerceAtLeast(0)
                .toDp()
        }
    var rememberedImePaneHeight by remember { mutableStateOf(0.dp) }
    var lockedComposerEmojiPaneHeight by remember { mutableStateOf(0.dp) }
    LaunchedEffect(currentImePaneHeight, composerEmojiPickerOpen) {
        rememberedImePaneHeight =
            updatedComposerRememberedImeHeight(
                previousRememberedImeHeight = rememberedImePaneHeight,
                currentImeHeight = currentImePaneHeight,
                freezeUpdates = composerEmojiPickerOpen,
            )
    }
    val emojiPaneBaseHeight =
        composerEmojiPaneHeight(
            lockedPaneHeight = lockedComposerEmojiPaneHeight,
            currentImeHeight = currentImePaneHeight,
            rememberedImeHeight = rememberedImePaneHeight,
        )
    val emojiPaneHeight =
        if (composerEmojiSearchActive) {
            emojiPaneBaseHeight + ComposerEmojiPickerSearchExtraHeight
        } else {
            emojiPaneBaseHeight
        }
    val emojiPaneAlpha by animateFloatAsState(
        targetValue = if (composerEmojiPickerOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "composerEmojiPaneAlpha",
    )
    val showEmojiPane = composerEmojiPickerOpen || emojiPaneAlpha > 0.01f
    val latestImePaneHeight by rememberUpdatedState(currentImePaneHeight)
    LaunchedEffect(showEmojiPane) {
        if (!showEmojiPane) {
            lockedComposerEmojiPaneHeight = 0.dp
            composerEmojiSearchActive = false
        }
    }

    fun restoreKeyboardFromEmojiPane() {
        if (!composerEmojiPickerOpen) return
        if (lockedComposerEmojiPaneHeight == 0.dp) {
            lockedComposerEmojiPaneHeight =
                composerEmojiPaneTargetHeight(
                    currentImeHeight = currentImePaneHeight,
                    rememberedImeHeight = rememberedImePaneHeight,
                )
        }
        composerEmojiSearchActive = false
        composerKeyboardRestorePending = true
        onBottomInputChanged()
        runCatching { composerFocus.requestFocus() }
        keyboardController?.show()
    }

    LaunchedEffect(composerKeyboardRestorePending, currentImePaneHeight, emojiPaneHeight) {
        if (
            shouldSwapComposerEmojiPaneToIme(
                keyboardRestorePending = composerKeyboardRestorePending,
                currentImeHeight = currentImePaneHeight,
                targetImeHeight = emojiPaneHeight,
            )
        ) {
            composerKeyboardRestorePending = false
            composerEmojiPickerOpen = false
        }
    }

    LaunchedEffect(composerKeyboardRestorePending) {
        if (composerKeyboardRestorePending) {
            delay(600L)
            if (composerKeyboardRestorePending && latestImePaneHeight == 0.dp) {
                composerKeyboardRestorePending = false
                focusManager.clearFocus(force = true)
            }
        }
    }

    BackHandler(enabled = composerEmojiPickerOpen) {
        composerKeyboardRestorePending = false
        composerEmojiPickerOpen = false
    }
    var autoFocusConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(autoFocusOnEnter, editingMessageId) {
        if (autoFocusOnEnter && !autoFocusConsumed && editingMessageId == null) {
            autoFocusConsumed = true
            runCatching { composerFocus.requestFocus() }
            keyboardController?.show()
        }
    }
    // Starting a reply (swipe-to-reply or long-press → Reply both set the
    // controller's replyingTo) focuses the composer and raises the IME. Fire
    // only on the null → non-null edge so a recomposition mid-reply doesn't
    // re-toggle the keyboard while the user is already typing.
    var hadReplyTarget by remember { mutableStateOf(replyingTo != null) }
    LaunchedEffect(replyingTo) {
        val hasReplyTarget = replyingTo != null
        if (shouldReanchorBottomInputForReplyTargetChange(hadReplyTarget, hasReplyTarget)) {
            onBottomInputChanged()
            runCatching { composerFocus.requestFocus() }
            keyboardController?.show()
        }
        hadReplyTarget = hasReplyTarget
    }
    // Single send path shared by the FAB and the Enter key (#404). Clears the
    // input/draft and scroll-to-newest ONLY after the controller confirms the
    // optimistic bubble is committed (it invokes onAccepted then). If a guard
    // rejects the send the callback never runs, so the user's text stays in
    // the field instead of vanishing silently (issue #264). For an in-place
    // edit the controller short-circuits and never calls onAccepted; the
    // LaunchedEffect that watches `editingMessageId` restores the pre-edit
    // composer once edit state clears — so we pass a no-op and don't blank
    // the field here.
    val submitMessage: () -> Unit = {
        if (text.isNotBlank()) {
            val sendingEdit = editingMessageId != null
            val sentText = text
            onSend(sentText) {
                if (!sendingEdit) {
                    // onAccepted can land after the user has started typing the
                    // next message (Enter-to-send makes that common). Only clear
                    // if the field still holds exactly what we sent, so newly
                    // typed text is never wiped.
                    if (textFieldValue.text == sentText) {
                        textFieldValue = TextFieldValue("")
                        onDraftChange("")
                    }
                    onAfterSend()
                }
            }
        }
    }

    fun applyComposerFieldValue(value: TextFieldValue) {
        textFieldValue = value
        if (editingMessageId == null) onDraftChange(value.text)
    }

    fun deleteFromComposer() {
        val selection = textFieldValue.selection
        val textValue = textFieldValue.text
        val deleteStart =
            when {
                selection.start != selection.end -> minOf(selection.start, selection.end)
                selection.start <= 0 -> return
                else -> textValue.offsetByCodePoints(selection.start, -1)
            }
        val deleteEnd =
            if (selection.start != selection.end) {
                maxOf(selection.start, selection.end)
            } else {
                selection.start
            }
        val updatedText = textValue.removeRange(deleteStart, deleteEnd)
        applyComposerFieldValue(TextFieldValue(updatedText, selection = TextRange(deleteStart)))
    }

    fun openComposerEmojiPane() {
        attachMenuOpen = false
        composerKeyboardRestorePending = false
        composerEmojiSearchActive = false
        lockedComposerEmojiPaneHeight =
            composerEmojiPaneTargetHeight(
                currentImeHeight = currentImePaneHeight,
                rememberedImeHeight = rememberedImePaneHeight,
            )
        composerEmojiPickerOpen = true
        onBottomInputChanged()
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    fun showKeyboardFromEmojiPane() {
        attachMenuOpen = false
        restoreKeyboardFromEmojiPane()
    }
    Column(
        composerBottomClusterModifier(showEmojiPane, modifier.fillMaxWidth()),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (editingMessageId != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.editing_message),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onCancelEdit, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel_edit), modifier = Modifier.size(18.dp))
                    }
                }
            } else if (replyingTo != null) {
                val refs = remember(replyingTo.tags) { MediaReferenceParser.parseAllImetaTags(replyingTo.tags) }
                val mediaKind = remember(refs) { replyMediaKindFromMime(refs.firstOrNull()?.mediaType) }
                ReplyPreviewCard(
                    senderTitle =
                        if (replyingTo.direction == "sent") {
                            stringResource(R.string.reply_you)
                        } else {
                            appState?.displayName(replyingTo.sender) ?: replyingTo.sender.take(8)
                        },
                    isOwn = replyingTo.direction == "sent",
                    body = MessageProjector.displayBody(replyingTo, messageTextCopy),
                    mediaKind = mediaKind,
                    onClick = null,
                    onDismiss = onCancelReply,
                    mentionDisplayName = appState?.let { state -> { state.mentionDisplayName(it) } },
                )
            }
            // #414: live @-mention picker. Compute the open query from the current
            // caret; suppressed entirely in DMs and while editing/recording or with
            // no roster. Anchored directly above the composer input row, capped at
            // ~50% of the viewport height.
            val mentionQuery =
                if (mentionPickerEnabled && editingMessageId == null) {
                    MentionComposer
                        .activeMentionQuery(textFieldValue.text, textFieldValue.selection.start)
                        .takeIf { textFieldValue.selection.collapsed }
                } else {
                    null
                }
            val mentionMatches =
                remember(mentionQuery?.query, mentionCandidates) {
                    if (mentionQuery == null) emptyList() else MentionComposer.filter(mentionQuery.query, mentionCandidates)
                }
            if (mentionQuery != null && mentionMatches.isNotEmpty()) {
                val openQuery = mentionQuery
                MentionPicker(
                    candidates = mentionMatches,
                    onPick = { candidate ->
                        val insertion = MentionComposer.insertMention(textFieldValue.text, openQuery, candidate)
                        val updated = TextFieldValue(text = insertion.text, selection = TextRange(insertion.selection))
                        textFieldValue = updated
                        if (editingMessageId == null) onDraftChange(updated.text)
                        runCatching { composerFocus.requestFocus() }
                        composerEmojiPickerOpen = false
                    },
                )
            }
            val activeRecordingController = voiceRecordingController?.takeIf { it.isRecording }
            val isRecordingVoice = activeRecordingController != null
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Keep the text field composed while recording. Removing the focused
                // BasicTextField makes Android dismiss the IME, which then removes
                // imePadding and drops this whole bottom bar under the user's finger.
                // The recording strip is only a visual overlay; focus stays with the
                // hidden composer so an already-open keyboard remains open.
                Box(modifier = Modifier.weight(1f)) {
                    ComposerPill(
                        textFieldValue = textFieldValue,
                        composerFocus = composerFocus,
                        emojiPickerOpen = composerEmojiPickerOpen,
                        onComposerFocusChanged = { focused ->
                            if (focused && composerEmojiPickerOpen) restoreKeyboardFromEmojiPane()
                            onComposerFocusChanged(focused)
                        },
                        onValueChange = { value ->
                            if (!isRecordingVoice) {
                                // #414: a single Backspace at the right edge of an
                                // `@npub1…` chip (or just past its trailing space,
                                // the post-insert caret position) deletes the whole
                                // chip in one keypress, so a mention reads as one
                                // token. Falls through to the verbatim IME edit
                                // otherwise.
                                //
                                // #607: an IME swipe-to-delete (hold-backspace and
                                // swipe) fires per-char or multi-char deletes that
                                // can land *inside* a chip, chopping it into a
                                // truncated `@npub1…` run. A partial chip corrupts
                                // the npub reference and crashes the composer's
                                // chip renderer / offset mapping. repairChipDeletion
                                // detects any deletion that partially overlaps a
                                // chip and widens it to remove the whole chip, so a
                                // partial-chip state can never reach the renderer.
                                val whole =
                                    MentionComposer.wholeChipBackspace(
                                        oldText = textFieldValue.text,
                                        oldCaret = textFieldValue.selection.start,
                                        newText = value.text,
                                        newCaret = value.selection.start,
                                    )
                                        ?: MentionComposer.repairChipDeletion(
                                            oldText = textFieldValue.text,
                                            newText = value.text,
                                        )
                                val edited =
                                    if (whole != null) {
                                        TextFieldValue(text = whole.text, selection = TextRange(whole.selection))
                                    } else {
                                        value
                                    }
                                // #414: keep the caret/selection out of the interior
                                // of any `@npub1…` chip so a tap, drag, or arrow key
                                // can't land inside the token (which would let a
                                // stray edit corrupt it or reopen the picker
                                // mid-token). Only in groups, where chips exist.
                                val applied =
                                    if (mentionPickerEnabled) {
                                        val clamped =
                                            MentionComposer.clampSelectionOutOfChips(
                                                edited.text,
                                                edited.selection.start,
                                                edited.selection.end,
                                            )
                                        if (clamped.start != edited.selection.start ||
                                            clamped.end != edited.selection.end
                                        ) {
                                            edited.copy(selection = TextRange(clamped.start, clamped.end))
                                        } else {
                                            edited
                                        }
                                    } else {
                                        edited
                                    }
                                applyComposerFieldValue(applied)
                            }
                        },
                        onEmojiPickerToggle = {
                            if (composerEmojiPickerOpen) {
                                showKeyboardFromEmojiPane()
                            } else {
                                openComposerEmojiPane()
                            }
                        },
                        onAttachMenuToggle = { attachMenuOpen = !attachMenuOpen },
                        attachMenuOpen = attachMenuOpen,
                        onAttachMenuDismiss = { attachMenuOpen = false },
                        onCaptureFromCamera = onCaptureFromCamera,
                        onPickFromGallery = onPickFromGallery,
                        onPickDocument = onPickDocument,
                        // #414: tint inserted `@npub1…` chips so they read as a
                        // single styled token while composing. Only when the picker
                        // is enabled (groups) — DMs never insert chips.
                        highlightMentionChips = mentionPickerEnabled,
                        mentionCandidates = mentionCandidates,
                        enterKeyBehavior = enterKeyBehavior,
                        onImeSend = submitMessage,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .alpha(if (isRecordingVoice) 0f else 1f)
                                .then(if (isRecordingVoice) Modifier.clearAndSetSemantics {} else Modifier),
                    )
                    if (activeRecordingController != null) {
                        RecordingStripLeading(
                            controller = activeRecordingController,
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .pointerInput(activeRecordingController) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    },
                        )
                    }
                }
                // Trailing MicHoldButton call site below must stay shared by both
                // recording and non-recording states; separate call sites break the
                // pointer-gesture identity for the active hold gesture.
                val showMicButton =
                    (text.isBlank() || isRecordingVoice) &&
                        editingMessageId == null &&
                        voiceRecordingController != null
                if (showMicButton && voiceRecordingController?.locked == true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = { voiceRecordingController.cancel() },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.voice_message_cancel),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        FloatingActionButton(
                            onClick = { voiceRecordingController.stop() },
                            modifier = Modifier.size(44.dp),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.send),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                } else if (showMicButton) {
                    Box(contentAlignment = Alignment.BottomCenter) {
                        LockHintAbove(controller = voiceRecordingController!!)
                        MicHoldButton(controller = voiceRecordingController)
                    }
                } else {
                    FloatingActionButton(
                        onClick = { submitMessage() },
                        modifier = Modifier.size(44.dp),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        if (showEmojiPane) {
            ComposerEmojiPickerPane(
                height = emojiPaneHeight,
                alpha = emojiPaneAlpha,
                onEmojiPicked = { emoji ->
                    val updated = insertComposerEmoji(textFieldValue, emoji)
                    applyComposerFieldValue(updated)
                },
                onBackspace = ::deleteFromComposer,
                onSearchActiveChange = {
                    composerEmojiSearchActive = it
                    onBottomInputChanged()
                },
            )
        }
    }
}

/**
 * Floating member picker for the group composer's `@`-mention flow (#414).
 * Anchored directly above the composer input row (it's the child placed just
 * before the input [Row] in [ComposerBar]); capped at ~50% of the viewport
 * height so the keyboard and a long roster both stay reachable. Tapping a row
 * inserts that member as a chip via [MentionComposer.insertMention].
 */
@Composable
private fun MentionPicker(
    candidates: List<MentionComposer.Candidate>,
    onPick: (MentionComposer.Candidate) -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.heightIn(max = maxHeight)) {
            Text(
                stringResource(R.string.mention_picker_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            LazyColumn(Modifier.fillMaxWidth()) {
                items(candidates, key = { it.accountIdHex }) { candidate ->
                    val mentionLabel = stringResource(R.string.mention_picker_member, candidate.displayName)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(candidate) }
                                .semantics { contentDescription = mentionLabel }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Avatar(title = candidate.displayName, seed = candidate.accountIdHex, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                candidate.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subtitle = candidate.nip05?.takeIf { it.isNotBlank() }
                            if (subtitle != null) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hold-to-record voice button. Press → start; release inside the button
 * bounds → stop and send. Drag the finger outside the button before
 * releasing → cancel. The cancel threshold is `cancelThresholdPx` away
 * from the down position; the gesture stays as a pointerInput input so
 * Compose doesn't fight us for the up event.
 */
@Composable
private fun MicHoldButton(controller: dev.ipf.whitenoise.android.audio.VoiceRecordingController) {
    val haptics = LocalHapticFeedback.current
    val cancelThresholdDp = 120.dp
    val lockThresholdDp = 80.dp
    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { cancelThresholdDp.toPx() }
    val lockThresholdPx = with(density) { lockThresholdDp.toPx() }
    val recording = controller.isRecording
    FloatingActionButton(
        // Accessibility fallback: a tap (TalkBack double-tap, keyboard
        // Enter, switch access) toggles record-and-lock so users who can't
        // perform the press-and-hold gesture can still send voice notes.
        onClick = {
            if (controller.isRecording) {
                controller.stop()
            } else if (controller.start()) {
                controller.lock()
            }
        },
        modifier =
            Modifier
                .size(44.dp)
                .pointerInput(controller) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val started = controller.start()
                        if (!started) return@awaitEachGesture
                        // Consume the down so the FAB's internal clickable
                        // doesn't ALSO interpret this press as a tap and fire
                        // its accessibility onClick after our hold gesture
                        // already handled stop/send/cancel.
                        down.consume()
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        var canceled = false
                        var locked = false
                        var terminated = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    // Parent stole the pointer — cancel rather than orphan the recorder.
                                    controller.cancel()
                                    terminated = true
                                    break
                                }
                                change.consume()
                                val deltaX = change.position.x - down.position.x
                                val deltaY = change.position.y - down.position.y
                                controller.updateDrag(deltaX, deltaY, cancelThresholdPx, lockThresholdPx)
                                if (!locked && -deltaY > lockThresholdPx && -deltaX <= cancelThresholdPx) {
                                    locked = true
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                    )
                                    controller.lock()
                                    terminated = true
                                    return@awaitEachGesture
                                }
                                if (!canceled && -deltaX > cancelThresholdPx) {
                                    canceled = true
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                    )
                                } else if (canceled && -deltaX <= cancelThresholdPx) {
                                    canceled = false
                                }
                                if (change.changedToUp() || !change.pressed) {
                                    if (canceled) controller.cancel() else controller.stop()
                                    terminated = true
                                    break
                                }
                            }
                        } finally {
                            // Composable removal / coroutine cancellation while still
                            // recording-unlocked → cancel cleanly instead of letting
                            // the recorder tick to the MAX_RECORDING_MS auto-stop.
                            if (!terminated && controller.isRecording && !controller.locked) {
                                controller.cancel()
                            }
                        }
                    }
                },
        containerColor =
            if (recording) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = stringResource(R.string.voice_message_record),
        )
    }
}

@Composable
private fun RecordingStripLeading(
    controller: dev.ipf.whitenoise.android.audio.VoiceRecordingController,
    modifier: Modifier = Modifier,
) {
    val pulseScale by rememberInfiniteRecordingPulse()
    val canceling = controller.willCancel
    val locked = controller.locked
    val cancelTint = MaterialTheme.colorScheme.error

    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = null,
            tint = if (canceling) cancelTint else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Box(
            modifier =
                Modifier
                    .size((10 * pulseScale).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
        )
        Text(
            formatRecordingDuration(controller.elapsedMs),
            style = MaterialTheme.typography.labelLarge,
            color = if (canceling) cancelTint else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (locked) {
            // Locked: the user has handed off control. The hint copy
            // collapses to a compact "Locked" indicator so the row stays
            // visually quiet while the trailing Stop+Trash do the work.
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(R.string.voice_message_locked),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.voice_message_release_to_send),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LockHintAbove(
    controller: dev.ipf.whitenoise.android.audio.VoiceRecordingController,
    modifier: Modifier = Modifier,
) {
    if (controller.locked || !controller.isRecording) return
    val density = LocalDensity.current
    val rawDp = with(density) { (-controller.verticalOffsetPx).toDp() }
    val rise = rawDp.value.coerceIn(0f, 80f).dp
    val armed = controller.willLock
    Box(
        modifier =
            modifier
                .offset(y = -rise - 56.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (armed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint =
                if (armed) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(18.dp),
        )
    }
}

// BasicTextField (not Material3 TextField) so the pill height isn't pinned
// to the 56dp filled-textfield minimum.
@Composable
private fun ComposerPill(
    textFieldValue: TextFieldValue,
    composerFocus: FocusRequester,
    emojiPickerOpen: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onEmojiPickerToggle: () -> Unit,
    onAttachMenuToggle: () -> Unit,
    attachMenuOpen: Boolean,
    onAttachMenuDismiss: () -> Unit,
    onCaptureFromCamera: (() -> Unit)?,
    onPickFromGallery: (() -> Unit)?,
    onPickDocument: (() -> Unit)?,
    modifier: Modifier = Modifier,
    highlightMentionChips: Boolean = false,
    mentionCandidates: List<MentionComposer.Candidate> = emptyList(),
    enterKeyBehavior: EnterKeyBehavior = EnterKeyBehavior.SendMessage,
    onImeSend: () -> Unit = {},
    // #589: report the BasicTextField's focus edge up so the conversation
    // screen can record whether the keyboard was up when the app was paused.
    onComposerFocusChanged: (Boolean) -> Unit = {},
) {
    // #414/#442: paint stored `@npub1…` chip runs as friendly visible labels
    // (`@alice` when the profile is resolved, short `@npub1…` otherwise)
    // while keeping the backing TextFieldValue canonical for send/markdown.
    val chipColor = MaterialTheme.colorScheme.primary
    val mentionCandidateLookup =
        remember(highlightMentionChips, mentionCandidates) {
            if (highlightMentionChips) MentionComposer.candidatesByNpub(mentionCandidates) else emptyMap()
        }
    val mentionVisualTransformation =
        remember(highlightMentionChips, chipColor, mentionCandidateLookup) {
            if (!highlightMentionChips) {
                VisualTransformation.None
            } else {
                VisualTransformation { text ->
                    // #607: the chip renderer must never crash the composer. The
                    // primary fix (MentionComposer.repairChipDeletion in
                    // onValueChange) makes a partial `@npub1…` chip state
                    // impossible, but degrade gracefully — fall back to the
                    // untransformed text — if any unforeseen malformed input
                    // state still drives buildAnnotatedString / the offset
                    // mapping out of bounds, rather than letting it throw.
                    runCatching {
                        val visual = MentionComposer.visualText(text.text, mentionCandidateLookup)
                        val visualLength = visual.text.length
                        val styled =
                            buildAnnotatedString {
                                append(visual.text)
                                visual.ranges.forEach { range ->
                                    // Clamp span bounds into the transformed text
                                    // so a stale/oversized range can't trip
                                    // addStyle's range check.
                                    val spanStart = range.transformed.first.coerceIn(0, visualLength)
                                    val spanEnd = (range.transformed.last + 1).coerceIn(spanStart, visualLength)
                                    if (spanEnd > spanStart) {
                                        addStyle(
                                            SpanStyle(
                                                color = chipColor,
                                                fontWeight = FontWeight.Medium,
                                                background = chipColor.copy(alpha = 0.12f),
                                            ),
                                            spanStart,
                                            spanEnd,
                                        )
                                    }
                                }
                            }
                        val offsetMapping =
                            object : OffsetMapping {
                                override fun originalToTransformed(offset: Int): Int = visual.originalToTransformed(offset).coerceIn(0, visualLength)

                                override fun transformedToOriginal(offset: Int): Int = visual.transformedToOriginal(offset).coerceIn(0, text.text.length)
                            }
                        TransformedText(styled, offsetMapping)
                    }.getOrElse {
                        TransformedText(text, OffsetMapping.Identity)
                    }
                }
            }
        }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(22.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .heightIn(min = 44.dp)
                    .padding(start = 4.dp, end = 4.dp),
        ) {
            IconButton(
                onClick = onEmojiPickerToggle,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    if (emojiPickerOpen) Icons.Default.Keyboard else Icons.Default.EmojiEmotions,
                    contentDescription =
                        stringResource(
                            if (emojiPickerOpen) {
                                R.string.show_keyboard
                            } else {
                                R.string.open_emoji_picker
                            },
                        ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
            ) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = onValueChange,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(composerFocus)
                            // #589: track focus so the conversation screen's
                            // resume observer knows whether the keyboard was up
                            // when the app was backgrounded (Case B gate).
                            .onFocusChanged { onComposerFocusChanged(it.isFocused) }
                            // #404: honor the Enter-key toggle for hardware
                            // keyboards (Bluetooth/foldable/ChromeOS). Shift+Enter
                            // always inserts a line break as an escape hatch; in
                            // NewLine mode a bare Enter falls through to the normal
                            // newline insertion.
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                ) {
                                    when {
                                        event.isShiftPressed -> false
                                        enterKeyBehavior == EnterKeyBehavior.SendMessage -> {
                                            onImeSend()
                                            true
                                        }
                                        else -> false
                                    }
                                } else {
                                    false
                                }
                            },
                    textStyle =
                        LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = mentionVisualTransformation,
                    // #404: in SendMessage mode the soft-keyboard action sends;
                    // in NewLine mode the IME shows an Enter/newline key that
                    // inserts `\n`.
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            keyboardType = KeyboardType.Text,
                            imeAction =
                                if (enterKeyBehavior == EnterKeyBehavior.SendMessage) {
                                    ImeAction.Send
                                } else {
                                    ImeAction.Default
                                },
                        ),
                    keyboardActions = KeyboardActions(onSend = { onImeSend() }),
                    maxLines = 5,
                )
                if (textFieldValue.text.isEmpty()) {
                    Text(
                        stringResource(R.string.message),
                        style = LocalTextStyle.current.copy(fontSize = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
            if (onPickFromGallery != null || onPickDocument != null) {
                Box {
                    IconButton(
                        onClick = onAttachMenuToggle,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = stringResource(R.string.attach_image),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    KeyboardPreservingDropdownMenu(
                        expanded = attachMenuOpen,
                        onDismissRequest = onAttachMenuDismiss,
                    ) {
                        if (onPickFromGallery != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_photo_library)) },
                                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                onClick = {
                                    onAttachMenuDismiss()
                                    onPickFromGallery()
                                },
                            )
                        }
                        if (onPickDocument != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_document)) },
                                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                onClick = {
                                    onAttachMenuDismiss()
                                    onPickDocument()
                                },
                            )
                        }
                    }
                }
            }
            if (onCaptureFromCamera != null) {
                IconButton(
                    onClick = onCaptureFromCamera,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = stringResource(R.string.attach_take_photo),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberInfiniteRecordingPulse(): State<Float> {
    val transition = rememberInfiniteTransition(label = "rec-pulse")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "rec-pulse-scale",
    )
}

private fun formatRecordingDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

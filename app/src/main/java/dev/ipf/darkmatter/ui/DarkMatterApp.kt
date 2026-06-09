package dev.ipf.darkmatter.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.ipf.darkmatter.R
import dev.ipf.darkmatter.core.AvatarImageLoader
import dev.ipf.darkmatter.core.DiagnosticFormatter
import dev.ipf.darkmatter.core.GroupProjector
import dev.ipf.darkmatter.core.GroupTitleCopy
import dev.ipf.darkmatter.core.IdentityFormatter
import dev.ipf.darkmatter.core.MessageProjector
import dev.ipf.darkmatter.core.MessageTextCopy
import dev.ipf.darkmatter.core.ProfileFieldValidation
import dev.ipf.darkmatter.core.ProfileLink
import dev.ipf.darkmatter.core.ProfileSanitizer
import dev.ipf.darkmatter.core.QrCodeEncoder
import dev.ipf.darkmatter.core.ReactionTally
import dev.ipf.darkmatter.core.RecentEmojiList
import dev.ipf.darkmatter.core.RecipientReference
import dev.ipf.darkmatter.core.ReplySwipe
import dev.ipf.darkmatter.core.TimelineProjector
import dev.ipf.darkmatter.media.MediaPipeline
import dev.ipf.darkmatter.media.MediaReferenceParser
import dev.ipf.darkmatter.notifications.NotificationNavStep
import dev.ipf.darkmatter.notifications.NotificationTarget
import dev.ipf.darkmatter.notifications.resolveNotificationNav
import dev.ipf.darkmatter.state.AppPhase
import dev.ipf.darkmatter.state.AppText
import dev.ipf.darkmatter.state.AppThemeMode
import dev.ipf.darkmatter.state.ChatListItem
import dev.ipf.darkmatter.state.ChatsController
import dev.ipf.darkmatter.state.ConversationController
import dev.ipf.darkmatter.state.ConversationControllerCopy
import dev.ipf.darkmatter.state.DarkMatterAppState
import dev.ipf.darkmatter.state.MediaAutoDownloadPolicy
import dev.ipf.darkmatter.state.MessageStatus
import dev.ipf.darkmatter.state.MessageStatusLabels
import dev.ipf.darkmatter.state.OutgoingMessageIndicator
import dev.ipf.darkmatter.state.PendingAttachment
import dev.ipf.darkmatter.state.ReactionParticipant
import dev.ipf.darkmatter.state.RelayListKind
import dev.ipf.darkmatter.state.TimelineMessage
import dev.ipf.darkmatter.state.countUnreadIncoming
import dev.ipf.darkmatter.state.formatExactTimestamp
import dev.ipf.darkmatter.state.isAcceptableRelayUrl
import dev.ipf.darkmatter.state.labelFor
import dev.ipf.darkmatter.state.nextReadAnchor
import dev.ipf.darkmatter.state.outgoingIndicator
import dev.ipf.darkmatter.state.shortHex
import dev.ipf.darkmatter.state.shouldShowOriginalTimestamp
import dev.ipf.marmotkit.AccountKeyPackageFfi
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.RelayHealthFfi
import dev.ipf.marmotkit.RelayListFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private enum class MainSection {
    Chats,
    Settings,
    Diagnostics,
}

private enum class SettingsDetail {
    Appearance,
    Profile,
    Identity,
    Relays,
    KeyPackages,
    Notifications,
    SecurityPrivacy,
}

private data class DiagnosticLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: ULong = (System.currentTimeMillis() / 1000L).toULong(),
    val text: String,
)

private data class LanguageOption(
    val tag: String,
    @param:StringRes val labelRes: Int,
)

private val languageOptions =
    listOf(
        LanguageOption("", R.string.language_system),
        LanguageOption("en", R.string.language_english),
        LanguageOption("de", R.string.language_german),
        LanguageOption("es", R.string.language_spanish),
        LanguageOption("fr", R.string.language_french),
        LanguageOption("it", R.string.language_italian),
        LanguageOption("pt", R.string.language_portuguese),
        LanguageOption("ru", R.string.language_russian),
        LanguageOption("tr", R.string.language_turkish),
        LanguageOption("zh", R.string.language_chinese_simplified),
        LanguageOption("zh-Hant", R.string.language_chinese_traditional),
    )

@Composable
private fun rememberGroupTitleCopy(): GroupTitleCopy =
    GroupTitleCopy(
        inviteFromFormat = stringResource(R.string.group_title_invite_from),
        groupOfPeopleFormat = stringResource(R.string.group_title_people_count),
    )

@Composable
private fun rememberMessageTextCopy(): MessageTextCopy =
    MessageTextCopy(
        reactedFormat = stringResource(R.string.message_reacted),
        reactionFallback = stringResource(R.string.message_reaction_fallback),
        deleted = stringResource(R.string.message_deleted_preview),
        invalidated = stringResource(R.string.message_invalidated_preview),
        agentStreamStarted = stringResource(R.string.agent_stream_started),
        streamFinished = stringResource(R.string.stream_finished),
        mediaAttachment = stringResource(R.string.media_attachment),
        message = stringResource(R.string.generic_message),
    )

@Composable
private fun rememberConversationControllerCopy(): ConversationControllerCopy =
    ConversationControllerCopy(
        waitingForStream = stringResource(R.string.waiting_for_stream),
        streamFailedFormat = stringResource(R.string.stream_failed_format),
    )

private val AppThemeMode.labelRes: Int
    @StringRes
    get() =
        when (this) {
            AppThemeMode.System -> R.string.theme_system
            AppThemeMode.Light -> R.string.theme_light
            AppThemeMode.Dark -> R.string.theme_dark
        }

private val MediaAutoDownloadPolicy.labelRes: Int
    @StringRes
    get() =
        when (this) {
            MediaAutoDownloadPolicy.Always -> R.string.media_auto_download_always
            MediaAutoDownloadPolicy.WifiOnly -> R.string.media_auto_download_wifi
            MediaAutoDownloadPolicy.Never -> R.string.media_auto_download_never
        }

@Composable
fun DarkMatterApp(
    appState: DarkMatterAppState,
    inboundProfilePayload: String? = null,
    onProfilePayloadHandled: (String) -> Unit = {},
    inboundNotificationTarget: NotificationTarget? = null,
    onNotificationTargetHandled: (NotificationTarget) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
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
    }
    LaunchedEffect(
        appState.phase,
        appState.activeAccountRef,
        appState.localNotificationPermissionGranted,
        appState.backgroundConnectionEnabled,
        appState.localNotificationSettings?.localNotificationsEnabled,
        appState.runtimeGeneration,
    ) {
        if (appState.phase != AppPhase.Ready) return@LaunchedEffect
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
                listOfNotNull(toast.title.resolve(context), toast.detail?.resolve(context)).joinToString("\n"),
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

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { DarkMatterSnackbarHost(snackbarHostState) },
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
        }
    }
}

@Composable
fun DarkMatterSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    snackbar: @Composable (SnackbarData) -> Unit = { Snackbar(snackbarData = it) },
) {
    SnackbarHost(
        hostState = hostState,
        modifier =
            modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        snackbar = snackbar,
    )
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FailureScreen(
    message: String,
    onRetry: () -> Unit,
    onRetryAction: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(44.dp))
            Text(stringResource(R.string.dark_matter_couldnt_start), style = MaterialTheme.typography.titleLarge)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    onRetry()
                    scope.launch { onRetryAction() }
                },
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun OnboardingScreen(appState: DarkMatterAppState) {
    var identity by remember { mutableStateOf("") }
    var creatingIdentity by remember { mutableStateOf(false) }
    var signingInBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    OnboardingContent(
        identity = identity,
        creatingIdentity = creatingIdentity,
        signingInBusy = signingInBusy,
        onIdentityChange = { identity = it },
        onCreateIdentity = {
            creatingIdentity = true
            scope.launch {
                try {
                    appState.createIdentity()
                } finally {
                    creatingIdentity = false
                }
            }
        },
        onImportIdentity = { value ->
            signingInBusy = true
            scope.launch {
                try {
                    appState.importIdentity(value)
                } finally {
                    signingInBusy = false
                }
            }
        },
    )
}

@Composable
fun OnboardingContent(
    identity: String,
    creatingIdentity: Boolean,
    signingInBusy: Boolean,
    onIdentityChange: (String) -> Unit,
    onCreateIdentity: () -> Unit,
    onImportIdentity: (String) -> Unit,
) {
    var signingIn by remember { mutableStateOf(false) }
    val busy = creatingIdentity || signingInBusy
    val creatingIdentityDescription = stringResource(R.string.creating_identity)

    if (signingIn) {
        SignInContent(
            identity = identity,
            busy = signingInBusy,
            onIdentityChange = onIdentityChange,
            onBack = { signingIn = false },
            onSignIn = { onImportIdentity(identity.trim()) },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = stringResource(R.string.dark_matter_shield),
                modifier = Modifier.size(88.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.onboarding_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onCreateIdentity,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
            ) {
                if (creatingIdentity) {
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .size(20.dp)
                                .semantics { contentDescription = creatingIdentityDescription },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Key, contentDescription = null)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(if (creatingIdentity) R.string.creating_identity_title else R.string.create_new_identity),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedButton(
                onClick = { signingIn = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Icon(Icons.Default.Person, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.sign_in), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SignInContent(
    identity: String,
    busy: Boolean,
    onIdentityChange: (String) -> Unit,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    WindowSecureFlag()
    val canSignIn = identity.isNotBlank() && !busy

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(stringResource(R.string.sign_in_to_dark_matter), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                stringResource(R.string.sign_in_secret_key_help),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = identity,
                onValueChange = onIdentityChange,
                label = { Text(stringResource(R.string.nostr_nsec)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                // Treat the nsec entry as a password: hides the value on
                // screen, and tells the IME not to retain it for suggestions,
                // autofill, or third-party clipboard inspection.
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            if (canSignIn) onSignIn()
                        },
                    ),
            )
        }
        Button(
            onClick = onSignIn,
            enabled = canSignIn,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Icon(Icons.Default.Person, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.sign_in), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(
    appState: DarkMatterAppState,
    inboundNotificationTarget: NotificationTarget? = null,
    onNotificationTargetHandled: (NotificationTarget) -> Unit = {},
) {
    var sectionName by rememberSaveable { mutableStateOf(MainSection.Chats.name) }
    var settingsDetailName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedChat by remember { mutableStateOf<ChatListItem?>(null) }
    var profileQrAccountId by remember { mutableStateOf<String?>(null) }
    val chatsController = remember(appState.activeAccountRef, appState.runtimeGeneration) { ChatsController(appState) }
    val section = runCatching { MainSection.valueOf(sectionName) }.getOrDefault(MainSection.Chats)
    val settingsDetail = settingsDetailName?.let { runCatching { SettingsDetail.valueOf(it) }.getOrNull() }

    DisposableEffect(chatsController) {
        appState.attachChatsController(chatsController)
        onDispose { appState.attachChatsController(null) }
    }

    LaunchedEffect(chatsController, appState.activeAccountRef, appState.runtimeGeneration) {
        chatsController.bind(appState.activeAccountRef)
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
        val target = inboundNotificationTarget ?: return@LaunchedEffect
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
        }
        when (step) {
            is NotificationNavStep.SwitchAccount -> appState.setActiveAccount(step.accountRef)
            NotificationNavStep.AwaitChatList -> Unit // re-fires when list state settles
            is NotificationNavStep.OpenConversation -> {
                // Ensure we're on the Chats section so back-from-conversation
                // lands on the chat list, not whatever section was open.
                sectionName = MainSection.Chats.name
                settingsDetailName = null
                allChats
                    .firstOrNull { it.group.groupIdHex == step.groupIdHex }
                    ?.let { selectedChat = it }
                onNotificationTargetHandled(target)
            }
            NotificationNavStep.MissingAccount -> {
                fallBackToChatList()
                appState.present(R.string.toast_notification_account_unavailable)
                onNotificationTargetHandled(target)
            }
            NotificationNavStep.MissingConversation -> {
                fallBackToChatList()
                appState.present(R.string.toast_notification_conversation_unavailable)
                onNotificationTargetHandled(target)
            }
        }
    }

    DisposableEffect(selectedChat?.id) {
        appState.setActiveConversation(selectedChat?.group?.groupIdHex)
        onDispose {
            if (selectedChat != null) appState.setActiveConversation(null)
        }
    }

    appState.pendingProfileNpub?.let { npub ->
        ProfileSheet(
            appState = appState,
            npub = npub,
            onDismiss = { appState.clearPresentedProfile() },
        )
    }
    profileQrAccountId?.let { accountId ->
        ProfileQrSheet(
            appState = appState,
            accountIdHex = accountId,
            onDismiss = { profileQrAccountId = null },
        )
    }

    if (selectedChat != null) {
        ConversationScreen(
            appState = appState,
            chat = selectedChat!!,
            onBack = { selectedChat = null },
        )
        return
    }

    when (section) {
        MainSection.Chats ->
            ChatsScreen(
                appState = appState,
                controller = chatsController,
                onOpenSettings = {
                    sectionName = MainSection.Settings.name
                    settingsDetailName = null
                },
                onOpenGroup = { selectedChat = it },
                onOpenProfile = {
                    appState.activeAccount?.accountIdHex?.let { accountId ->
                        profileQrAccountId = accountId
                    } ?: appState.present(R.string.toast_no_active_account)
                },
            )
        MainSection.Settings ->
            SettingsScreen(
                appState = appState,
                onBackToChats = {
                    sectionName = MainSection.Chats.name
                    settingsDetailName = null
                },
                onOpenDiagnostics = {
                    sectionName = MainSection.Diagnostics.name
                    settingsDetailName = null
                },
                detail = settingsDetail,
                onDetailChange = { settingsDetailName = it?.name },
            )
        MainSection.Diagnostics ->
            DiagnosticsScreen(
                appState = appState,
                onBackToChats = {
                    sectionName = MainSection.Chats.name
                    settingsDetailName = null
                },
            )
    }
}

@Composable
fun AccountAvatarButton(
    title: String,
    seed: String,
    pictureUrl: String?,
    size: Dp = 40.dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openSettingsDescription = stringResource(R.string.open_settings)
    IconButton(
        onClick = onClick,
        modifier =
            modifier
                .size(56.dp)
                .semantics { contentDescription = openSettingsDescription },
    ) {
        Avatar(
            title = title,
            seed = seed,
            size = size,
            pictureUrl = pictureUrl,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsScreen(
    appState: DarkMatterAppState,
    controller: ChatsController,
    onOpenSettings: () -> Unit,
    onOpenGroup: (ChatListItem) -> Unit,
    onOpenProfile: () -> Unit,
) {
    var showNewChat by remember { mutableStateOf(false) }
    var newChatTitle by remember { mutableStateOf(R.string.new_chat) }
    var showScanner by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var quickActionsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(showArchived) {
        if (showArchived) quickActionsExpanded = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (showArchived) Text(stringResource(R.string.archived))
                },
                navigationIcon = {
                    val active = appState.activeAccount
                    if (showArchived) {
                        IconButton(onClick = { showArchived = false }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    } else {
                        AccountAvatarButton(
                            title = active?.let { appState.displayName(it.accountIdHex) } ?: stringResource(R.string.app_name),
                            seed = active?.accountIdHex ?: "darkmatter",
                            pictureUrl = active?.let { appState.avatarUrl(it.accountIdHex) },
                            onClick = onOpenSettings,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (!showArchived) {
                QuickActionFabMenu(
                    expanded = quickActionsExpanded,
                    onExpandedChange = { quickActionsExpanded = it },
                    onMyProfile = onOpenProfile,
                    onScanQr = { showScanner = true },
                    onCreateGroup = {
                        newChatTitle = R.string.create_group
                        showNewChat = true
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val visibleItems = if (showArchived) controller.archivedItems else controller.items
            when {
                controller.isLoading && visibleItems.isEmpty() -> LoadingScreen()
                controller.error != null -> ErrorContent(stringResource(R.string.couldnt_load_chats), controller.error.orEmpty())
                visibleItems.isEmpty() && showArchived ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_archived_chats), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                visibleItems.isEmpty() ->
                    EmptyChats(onCreate = {
                        newChatTitle = R.string.new_chat
                        showNewChat = true
                    })
                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(visibleItems, key = { it.id }) { item ->
                            ChatRow(
                                item = item,
                                appState = appState,
                                onClick = { onOpenGroup(item) },
                            )
                            HorizontalDivider()
                        }
                        if (!showArchived && controller.archivedItems.isNotEmpty()) {
                            item {
                                ListItem(
                                    modifier = Modifier.clickable { showArchived = true },
                                    headlineContent = { Text(stringResource(R.string.archived)) },
                                    supportingContent = { Text(stringResource(R.string.chats_count, controller.archivedItems.size)) },
                                    leadingContent = { Icon(Icons.Default.Archive, contentDescription = null) },
                                )
                            }
                        }
                    }
            }
        }
    }

    if (showNewChat) {
        NewChatSheet(appState = appState, titleRes = newChatTitle, onDismiss = { showNewChat = false })
    }

    if (showScanner) {
        QrScannerSheet(
            onDismiss = { showScanner = false },
            onScan = { raw ->
                showScanner = false
                val scanned = ProfileLink.parse(raw)
                if (scanned == null) {
                    appState.present(R.string.error_not_dark_matter_profile_qr)
                } else {
                    appState.presentProfile(scanned.npub)
                }
            },
        )
    }
}

@Composable
fun QuickActionFabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onMyProfile: () -> Unit,
    onScanQr: () -> Unit,
    onCreateGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun runAction(action: () -> Unit) {
        onExpandedChange(false)
        action()
    }

    BackHandler(enabled = expanded) {
        onExpandedChange(false)
    }

    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { onExpandedChange(it) },
            ) {
                val imageVector by remember {
                    derivedStateOf {
                        if (checkedProgress > 0.5f) Icons.Default.Close else Icons.Default.Add
                    }
                }
                Icon(
                    painter = rememberVectorPainter(imageVector),
                    contentDescription =
                        stringResource(
                            if (expanded) R.string.close_quick_actions else R.string.open_quick_actions,
                        ),
                    modifier = Modifier.animateIcon({ checkedProgress }),
                )
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = { runAction(onMyProfile) },
            icon = { Icon(Icons.Default.Person, contentDescription = null) },
            text = { Text(stringResource(R.string.my_profile)) },
        )
        FloatingActionButtonMenuItem(
            onClick = { runAction(onScanQr) },
            icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
            text = { Text(stringResource(R.string.scan_qr_code)) },
        )
        FloatingActionButtonMenuItem(
            onClick = { runAction(onCreateGroup) },
            icon = { Icon(Icons.Default.Group, contentDescription = null) },
            text = { Text(stringResource(R.string.create_group)) },
        )
    }
}

@Composable
private fun ChatRow(
    item: ChatListItem,
    appState: DarkMatterAppState,
    onClick: () -> Unit,
) {
    val groupTitleCopy = rememberGroupTitleCopy()
    val messageTextCopy = rememberMessageTextCopy()
    // derivedStateOf so the title is only recomputed when its snapshot reads
    // (item, the profile-presentation revision read inside chatMemberTitle)
    // actually change, instead of every chat-list recomposition pass.
    val title by remember(item) {
        derivedStateOf {
            item.projectedTitle ?: GroupProjector.displayTitle(
                group = item.group,
                otherMemberAccount = item.otherMemberAccount,
                memberCount = item.memberCount,
                memberTitle = { appState.chatMemberTitle(it) },
                copy = groupTitleCopy,
            )
        }
    }
    val inviteAccount = GroupProjector.inviteAccount(item.group, item.otherMemberAccount)
    val avatarAccount =
        inviteAccount
            ?: item.otherMemberAccount.takeIf { item.group.name.isBlank() && item.memberCount == 2 }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Avatar(
                title = title,
                seed = avatarAccount ?: item.group.groupIdHex,
                size = 44.dp,
                // A group's own avatar URL wins over the member-derived avatar.
                pictureUrl = item.group.avatarUrl ?: avatarAccount?.let { appState.avatarUrl(it) },
            )
        },
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val draft = appState.draftFor(item.group.groupIdHex)?.takeIf { it.isNotBlank() }
            val preview =
                when {
                    item.group.pendingConfirmation -> stringResource(R.string.invitation)
                    draft != null -> stringResource(R.string.chat_row_draft_prefix) + draft
                    else ->
                        item.projectedPreviewText(
                            copy = messageTextCopy,
                            empty = stringResource(R.string.no_messages_yet),
                        )
                }
            Text(
                text = preview,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontStyle = if (draft != null) FontStyle.Italic else FontStyle.Normal,
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    IdentityFormatter.relativeTime(item.latestAt ?: 0uL),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.group.pendingConfirmation) {
                    Badge { Text(stringResource(R.string.invite)) }
                } else if (item.hasUnread) {
                    Badge {
                        Text(if (item.unreadCount > 99uL) "99+" else item.unreadCount.toString())
                    }
                } else if (item.memberCount > 2) {
                    Badge { Text(item.memberCount.toString()) }
                }
            }
        },
    )
}

@Composable
private fun EmptyChats(onCreate: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.no_chats_yet), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.empty_chats_invite_npub),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.new_chat))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(
    appState: DarkMatterAppState,
    @StringRes titleRes: Int = R.string.new_chat,
    onDismiss: () -> Unit,
) {
    var members by remember { mutableStateOf<List<String>>(emptyList()) }
    var pending by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val validRecipientReferenceError = stringResource(R.string.error_valid_recipient_reference)
    val missingKeyPackageError = stringResource(R.string.error_missing_key_package)
    val invalidIdentityReferenceError = stringResource(R.string.error_invalid_identity_reference)
    val groupPublishFailedFormat = stringResource(R.string.error_group_publish_failed)
    val notDarkMatterProfileQrError = stringResource(R.string.error_not_dark_matter_profile_qr)

    fun addRecipient(reference: String) {
        val normalized = RecipientReference.normalize(reference)
        if (normalized == null) {
            error = validRecipientReferenceError
            return
        }
        if (!members.contains(normalized)) {
            members = members + normalized
            error = null
        }
        pending = ""
    }

    fun addPending() {
        val tokens = RecipientReference.tokenize(pending)
        if (tokens.isEmpty()) return
        tokens.forEach { addRecipient(it) }
    }

    fun createGroupErrorMessage(throwable: Throwable): String =
        when (throwable) {
            is MarmotKitException.MissingKeyPackage ->
                missingKeyPackageError
            is MarmotKitException.InvalidIdentity ->
                invalidIdentityReferenceError
            is MarmotKitException.Publish ->
                String.format(groupPublishFailedFormat, throwable.details)
            else -> throwable.message ?: throwable.javaClass.simpleName
        }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleLarge)
            if (members.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    members.forEach { member ->
                        AssistChip(
                            onClick = { members = members - member },
                            label = { Text(IdentityFormatter.short(member), maxLines = 1) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove)) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pending,
                    onValueChange = { pending = it },
                    label = { Text(stringResource(R.string.npub_or_hex_public_key)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = { addPending() }),
                )
                FloatingActionButton(onClick = { showScanner = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_recipient_qr_code))
                }
                FloatingActionButton(onClick = { addPending() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_recipient))
                }
            }
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text(stringResource(R.string.group_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.description)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            if (error != null) {
                Text(
                    error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = {
                    val pendingRecipients = RecipientReference.tokenize(pending)
                    val normalizedPending =
                        pendingRecipients.map { input ->
                            RecipientReference.normalize(input) ?: run {
                                error = validRecipientReferenceError
                                return@Button
                            }
                        }
                    val recipients = (members + normalizedPending).distinct()
                    members = recipients
                    pending = ""
                    val account = appState.activeAccountRef ?: return@Button
                    busy = true
                    error = null
                    // Process-lifetime scope so MLS commit + Nostr publish complete
                    // even if the sheet dismisses mid-flight.
                    appState.launchMutation {
                        runCatching {
                            appState.marmotIo {
                                createGroup(
                                    account,
                                    groupName.trim(),
                                    recipients,
                                    description.trim().ifBlank { null },
                                )
                            }
                        }.onSuccess {
                            appState.present(R.string.toast_chat_created)
                            onDismiss()
                        }.onFailure {
                            error = createGroupErrorMessage(it)
                        }
                        busy = false
                    }
                },
                enabled =
                    !busy &&
                        groupName.trim().isNotBlank() &&
                        (members.isNotEmpty() || pending.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.create))
            }
        }
    }

    if (showScanner) {
        QrScannerSheet(
            onDismiss = { showScanner = false },
            onScan = { raw ->
                showScanner = false
                val scanned = ProfileLink.parse(raw)
                if (scanned == null) {
                    error = notDarkMatterProfileQrError
                } else {
                    error = null
                    addRecipient(scanned.npub)
                }
            },
        )
    }
}

/** Within this many items of the trailing edge counts as "at bottom". */
private const val ConversationNearBottomItemSlack = 3

// Maximum images per multi-pick. The Android Photo Picker enforces this
// cap on the system dialog side; 10 keeps the album payload bounded
// (10 * 1920px JPEG ≈ a few MB encrypted) without feeling artificially low.
private const val MEDIA_PICKER_MAX_ITEMS = 10

/** Fixed height of an in-timeline image bubble — constant across load states
 *  so async decode never reflows the list (would break the open-time anchor). */
private val MediaBubbleHeight = 240.dp

/** Saves a nullable Uri across process death (camera capture round-trip). */
private val NullableUriSaver: Saver<android.net.Uri?, String> =
    Saver(
        save = { it?.toString() ?: "" },
        restore = { s -> s.takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse) },
    )

// Persist a multi-pick selection across rotation / process death. Empty list
// encodes "no preview shown" so the parent re-render skips the sheet on
// restore. Uses '\n' as the separator — content URIs don't contain newlines.
private val UriListSaver: Saver<List<android.net.Uri>, String> =
    Saver(
        save = { it.joinToString("\n") { uri -> uri.toString() } },
        restore = { s ->
            if (s.isEmpty()) {
                emptyList()
            } else {
                s.split('\n').mapNotNull { token ->
                    token.takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse)
                }
            }
        },
    )

@Composable
private fun MediaImageBubble(
    item: TimelineMessage,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: DarkMatterAppState,
    mine: Boolean,
) {
    val record = item.record
    val key = record.messageIdHex
    // Seed from the decoded-thumbnail cache so an already-fetched or just-sent
    // image paints on the first frame — no decode spinner, no visible "reload".
    var bitmap by remember(key) { mutableStateOf(controller.thumbnailFor(key, 0)?.asImageBitmap()) }
    var failed by remember(key) { mutableStateOf(false) }
    var viewerOpen by remember(key) { mutableStateOf(false) }
    var reloadToken by remember(key) { mutableStateOf(0) }
    // Auto-download gating (#10): own messages always render (bytes are cached
    // from the send), incoming honor the policy. Keyed on the policy so
    // flipping the setting re-gates undownloaded bubbles.
    var startDownload by remember(key, appState.mediaAutoDownloadPolicy) {
        mutableStateOf(mine || appState.shouldAutoDownloadMedia())
    }

    LaunchedEffect(key, startDownload, reloadToken) {
        if (bitmap != null) return@LaunchedEffect // already have a decoded thumbnail
        if (!startDownload) return@LaunchedEffect
        failed = false
        try {
            val data = controller.downloadAttachment(key, 0, reference)
            // Decode a sampled bitmap sized to the bubble — a full 1920px
            // image would be a ~14 MB ARGB_8888 bitmap per visible row.
            val decoded =
                withContext(Dispatchers.Default) {
                    MediaPipeline.decodeSampledBitmap(data, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
                }
            if (decoded != null) {
                controller.cacheThumbnail(key, 0, decoded)
                bitmap = decoded.asImageBitmap()
            } else {
                failed = true
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // Composable left composition or key changed — propagate. A
            // cancelled effect isn't a download failure; the bubble is gone.
            throw cancel
        } catch (_: Throwable) {
            failed = true
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        // FIXED height across every state (loading / image / failed / gated) so
        // the bubble never changes size when the image finishes decoding. A
        // variable height would reflow the timeline after the open-time
        // scroll-to-bottom and strand the user mid-list (and cause visible
        // flips). Full aspect-ratio sizing needs `dim` in the imeta tag (Rust).
        modifier =
            Modifier
                .fillMaxWidth()
                .height(MediaBubbleHeight),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val current = bitmap
            when {
                current != null ->
                    Image(
                        bitmap = current,
                        contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .clickable { viewerOpen = true },
                    )
                failed ->
                    MediaBubbleAction(
                        icon = Icons.Default.BrokenImage,
                        label = stringResource(R.string.media_tap_to_retry),
                        onClick = {
                            failed = false
                            reloadToken++
                        },
                    )
                !startDownload ->
                    MediaBubbleAction(
                        icon = Icons.Default.Download,
                        label = stringResource(R.string.media_tap_to_download),
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
    }

    if (viewerOpen) {
        FullScreenImageViewer(
            controller = controller,
            appState = appState,
            messageIdHex = key,
            references = listOf(reference),
            startIndex = 0,
            onDismiss = { viewerOpen = false },
        )
    }
}

/**
 * Multi-image album bubble: a 2-column grid of square thumbnails. Used for
 * any message carrying ≥2 image attachments. Each tile maintains its own
 * download/cache state (keyed by `(messageId, attachmentIndex)`); tap any
 * tile to open the full-screen viewer at that attachment. When the album
 * holds more than four images, the fourth tile gets a "+N" overlay and the
 * remaining images are reachable from the viewer (next-tile navigation
 * lands with the pager-viewer follow-up).
 */
@Composable
private fun MediaImageGridBubble(
    item: TimelineMessage,
    references: List<MediaAttachmentReferenceFfi>,
    controller: ConversationController,
    appState: DarkMatterAppState,
) {
    val record = item.record
    val visible = references.take(4)
    val overflow = (references.size - visible.size).coerceAtLeast(0)
    var viewerOpenAt by remember(record.messageIdHex) { mutableStateOf<Int?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(2.dp),
        ) {
            visible.chunked(2).forEachIndexed { rowIndex, row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    row.forEachIndexed { colIndex, reference ->
                        val tileIndex = rowIndex * 2 + colIndex
                        val showOverflow = tileIndex == visible.lastIndex && overflow > 0
                        MediaImageGridTile(
                            messageIdHex = record.messageIdHex,
                            attachmentIndex = tileIndex,
                            reference = reference,
                            controller = controller,
                            onTap = { viewerOpenAt = tileIndex },
                            overflowCount = if (showOverflow) overflow else 0,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f),
                        )
                    }
                    // Pad odd-count rows so a single-tile last row stays
                    // half-width instead of stretching across the bubble.
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    viewerOpenAt?.let { index ->
        FullScreenImageViewer(
            controller = controller,
            appState = appState,
            messageIdHex = record.messageIdHex,
            references = references,
            startIndex = index,
            onDismiss = { viewerOpenAt = null },
        )
    }
}

/**
 * One tile of the album grid: square thumbnail + per-tile download state.
 * The thumbnail-cache lookup is keyed on `(messageId, attachmentIndex)` so
 * tiles never clobber each other. Tap fires [onTap] (the parent opens the
 * full-screen viewer at this attachment's index).
 */
@Composable
private fun MediaImageGridTile(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    onTap: () -> Unit,
    overflowCount: Int,
    modifier: Modifier = Modifier,
) {
    val tileKey = "$messageIdHex#$attachmentIndex"
    var bitmap by remember(tileKey) {
        mutableStateOf(controller.thumbnailFor(messageIdHex, attachmentIndex)?.asImageBitmap())
    }
    var failed by remember(tileKey) { mutableStateOf(false) }
    var reloadToken by remember(tileKey) { mutableStateOf(0) }

    LaunchedEffect(tileKey, reloadToken) {
        if (bitmap != null) return@LaunchedEffect
        failed = false
        try {
            val data = controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
            val decoded =
                withContext(Dispatchers.Default) {
                    MediaPipeline.decodeSampledBitmap(data, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
                }
            if (decoded != null) {
                controller.cacheThumbnail(messageIdHex, attachmentIndex, decoded)
                bitmap = decoded.asImageBitmap()
            } else {
                failed = true
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            failed = true
        }
    }

    Box(
        modifier = modifier.clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        when {
            current != null ->
                Image(
                    bitmap = current,
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            failed ->
                IconButton(onClick = {
                    failed = false
                    reloadToken++
                }) {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = stringResource(R.string.media_tap_to_retry),
                    )
                }
            else ->
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
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
    }
}

/** Centered icon+label tap target used for the retry/download bubble states. */
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
    previewBytesList: List<ByteArray>,
    failed: Boolean,
) {
    val statusLabel = stringResource(if (failed) R.string.media_upload_failed else R.string.media_uploading)
    val statusColor = if (failed) MaterialTheme.colorScheme.error else Color.White

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (previewBytesList.size <= 1) {
                // Single-image: keep the existing fixed-height bubble so the
                // optimistic→projected swap doesn't reflow the timeline.
                val preview = rememberSampledBitmap(previewBytesList.firstOrNull())
                Box(
                    Modifier.fillMaxWidth().height(MediaBubbleHeight),
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
                    )
                }
            } else {
                // Album: render the same 2-col grid the post-upload bubble
                // uses, so the optimistic → confirmed transition is a visual
                // no-op. Each tile decodes from local bytes (no network), and
                // a single status overlay sits across the whole bubble.
                val visible = previewBytesList.take(4)
                val overflow = (previewBytesList.size - visible.size).coerceAtLeast(0)
                Box(Modifier.fillMaxWidth()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(2.dp),
                    ) {
                        visible.chunked(2).forEachIndexed { rowIndex, row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                row.forEachIndexed { colIndex, bytes ->
                                    val tileIndex = rowIndex * 2 + colIndex
                                    val showOverflow = tileIndex == visible.lastIndex && overflow > 0
                                    PendingGridTile(
                                        bytes = bytes,
                                        overflowCount = if (showOverflow) overflow else 0,
                                        modifier = Modifier.weight(1f).aspectRatio(1f),
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    Box(
                        Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)),
                    )
                    PendingStatusOverlay(
                        failed = failed,
                        hasPreview = true,
                        statusLabel = statusLabel,
                        statusColor = statusColor,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
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
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (failed) {
            Icon(
                Icons.Default.BrokenImage,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(28.dp),
            )
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
    var bitmap by remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bytes) {
        bitmap =
            if (bytes == null) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    MediaPipeline.decodeSampledBitmap(bytes, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)?.asImageBitmap()
                }
            }
    }
    return bitmap
}

@Composable
private fun FullScreenImageViewer(
    controller: ConversationController,
    appState: DarkMatterAppState,
    messageIdHex: String,
    references: List<MediaAttachmentReferenceFfi>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    if (references.isEmpty()) {
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
            initialPage = startIndex.coerceIn(0, references.lastIndex),
            pageCount = { references.size },
        )
    val currentReference = references[pagerState.currentPage]
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
                ViewerPage(
                    controller = controller,
                    messageIdHex = messageIdHex,
                    attachmentIndex = page,
                    reference = references[page],
                    scale = if (page == pagerState.currentPage) scale else 1f,
                    offset = if (page == pagerState.currentPage) offset else Offset.Zero,
                    onScaleChange = { if (page == pagerState.currentPage) scale = it },
                    onOffsetChange = { if (page == pagerState.currentPage) offset = it },
                )
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
                if (references.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${references.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Row {
                    IconButton(
                        onClick = {
                            val ref = currentReference
                            val pageIndex = pagerState.currentPage
                            scope.launch {
                                val data =
                                    runCatching {
                                        controller.downloadAttachment(messageIdHex, pageIndex, ref)
                                    }.getOrNull()
                                val ok =
                                    data != null &&
                                        withContext(Dispatchers.IO) {
                                            saveImageToGallery(context, data, ref.fileName, ref.mediaType)
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
                            val pageIndex = pagerState.currentPage
                            scope.launch {
                                runCatching {
                                    controller.downloadAttachment(messageIdHex, pageIndex, ref)
                                }.getOrNull()?.let { shareImage(context, it, ref.fileName, ref.mediaType) }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share), tint = Color.White)
                    }
                }
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
) {
    val pageKey = "$messageIdHex#$attachmentIndex"
    var androidBitmap by remember(pageKey) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var viewerFailed by remember(pageKey) { mutableStateOf(false) }
    var viewerReloadToken by remember(pageKey) { mutableStateOf(0) }
    val bitmap = remember(androidBitmap) { androidBitmap?.asImageBitmap() }
    LaunchedEffect(pageKey, viewerReloadToken) {
        viewerFailed = false
        try {
            val data = controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
            // Bounded sampled decode. A 5000px remote image decoded full-size
            // is ~100 MB ARGB_8888 and OOMs mid-class devices; the viewer
            // ceiling caps that while keeping quality high enough on phones.
            val decoded =
                withContext(Dispatchers.Default) {
                    MediaPipeline.decodeSampledBitmap(data, MediaPipeline.VIEWER_MAX_EDGE_PX)
                }
            if (decoded != null) {
                androidBitmap = decoded
            } else {
                viewerFailed = true
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            viewerFailed = true
        }
    }
    DisposableEffect(pageKey) {
        onDispose { androidBitmap?.recycle() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val current = bitmap
        when {
            current != null ->
                Image(
                    bitmap = current,
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.Fit,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(pageKey) {
                                detectTapGestures(onDoubleTap = {
                                    onScaleChange(1f)
                                    onOffsetChange(Offset.Zero)
                                })
                            }.pointerInput(pageKey) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                                    onScaleChange(nextScale)
                                    if (nextScale > 1f) {
                                        val viewportW = size.width.toFloat()
                                        val viewportH = size.height.toFloat()
                                        val imageAspect = current.width.toFloat() / current.height.toFloat()
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
                                        onOffsetChange(
                                            Offset(
                                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                                            ),
                                        )
                                    } else {
                                        onOffsetChange(Offset.Zero)
                                    }
                                }
                            }.graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            ),
                )
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

/**
 * Persist [bytes] to the device gallery (Pictures/DarkMatter). Returns success.
 * Uses the IS_PENDING dance so other apps never see a half-written entry, and
 * sanitizes the remote-supplied [fileName] to a basename.
 */
private fun saveImageToGallery(
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
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DarkMatter")
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

/** Share [bytes] via a FileProvider Uri using the system share sheet. */
private fun shareImage(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
) {
    try {
        val dir = java.io.File(context.cacheDir, "shared_media").apply { mkdirs() }
        // Unique temp keyed off a sanitized basename — avoids collisions and
        // path traversal from a remote-supplied filename.
        val file = java.io.File.createTempFile("share_", "_" + MediaPipeline.safeDisplayName(fileName), dir)
        file.outputStream().use { it.write(bytes) }
        val uri =
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
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
    } catch (_: Throwable) {
        // Best-effort; failure to share is non-fatal.
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

private fun fileProviderUri(
    context: android.content.Context,
    file: java.io.File,
): android.net.Uri =
    androidx.core.content.FileProvider
        .getUriForFile(context, "${context.packageName}.fileprovider", file)

/** Best-effort wipe of decrypted media temp files (share + camera) from cache. */
private fun clearMediaTempFiles(context: android.content.Context) {
    runCatching { java.io.File(context.cacheDir, "shared_media").deleteRecursively() }
    runCatching { java.io.File(context.cacheDir, "camera").deleteRecursively() }
}

/** Decode a downscaled preview bitmap for a local content Uri, off-thread. */
@Composable
private fun rememberLocalPreviewBitmap(uri: android.net.Uri): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap =
            withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = MediaPipeline.readDownscaledJpeg(context.contentResolver, uri)
                    bytes?.let {
                        android.graphics.BitmapFactory
                            .decodeByteArray(it, 0, it.size)
                            ?.asImageBitmap()
                    }
                }.getOrNull()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaPreviewSheet(
    uris: List<android.net.Uri>,
    onDismiss: () -> Unit,
    onSend: (caption: String) -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    // Local guard against a rapid double-tap firing onSend twice before the
    // parent clears pendingMediaUris and the sheet leaves composition.
    var sending by remember { mutableStateOf(false) }
    ModalBottomSheet(
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
            if (uris.size == 1) {
                LocalImagePreview(
                    uri = uris.first(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                // Album preview: a horizontally-scrollable strip of square
                // thumbnails. The strip-shape on the compose surface lets the
                // user scan + reorder mentally before sending; the grid shape
                // is reserved for the received-message bubble.
                LazyRow(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uris, key = { uri -> uri.toString() }) { uri ->
                        LocalImagePreview(
                            uri = uri,
                            modifier =
                                Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp)),
                        )
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
    return listState.firstVisibleItemIndex >= bottomTimelineIndex - ConversationNearBottomItemSlack
}

/** Read the user-visible filename a content Uri exposes via OpenableColumns,
 *  falling back to the Uri's path segment. Null when neither is available. */
private fun queryDisplayName(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri,
): String? {
    contentResolver
        .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
    return uri.lastPathSegment
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationScreen(
    appState: DarkMatterAppState,
    chat: ChatListItem,
    onBack: () -> Unit,
) {
    val controllerCopy = rememberConversationControllerCopy()
    val controller =
        remember(chat.id, appState.runtimeGeneration) {
            ConversationController(
                appState = appState,
                initialGroup = chat.group,
                initialMemberSnapshot =
                    chat.memberSnapshot
                        ?: appState.cachedGroupMemberSnapshot(appState.activeAccountRef, chat.group.groupIdHex),
                copy = controllerCopy,
            )
        }
    var menuOpen by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var confirmLeaveFromTopBar by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var initialTimelineAnchored by remember(chat.id) { mutableStateOf(false) }
    var initialTimelineLoadStarted by remember(chat.id) { mutableStateOf(false) }
    var highlightedMessageId by remember(chat.id) { mutableStateOf<String?>(null) }
    var navigateReplyJob by remember(chat.id) { mutableStateOf<Job?>(null) }
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
    val nearBottom by remember {
        derivedStateOf {
            isNearBottom(listState, controller.timeline.size, controller.hasMoreBefore || controller.isLoadingOlder)
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
            (visible.last().index - firstTimelineListIndex)
                .coerceAtMost(controller.timeline.lastIndex)
        }
    }
    LaunchedEffect(currentHighestVisibleTimelineIndex) {
        val idx = currentHighestVisibleTimelineIndex
        if (idx < 0) return@LaunchedEffect
        // Monotonic advance only — scroll-up keeps the existing anchor so the
        // read pointer never moves backwards. See [nextReadAnchor].
        readAnchorMessageId = nextReadAnchor(controller.timeline, readAnchorMessageId, idx)
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
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val groupTitleCopy = rememberGroupTitleCopy()
    val messageTextCopy = rememberMessageTextCopy()
    var recentReactionEmojis by remember(context) {
        mutableStateOf(RecentEmojiPreferences.load(context))
    }
    // Selected-but-not-yet-sent attachments: when non-empty the preview /
    // caption sheet is shown. Multi-pick goes through `PickMultipleVisualMedia`
    // with `MEDIA_PICKER_MAX_ITEMS` as the cap. Each picked URI is sent as
    // its own kind:9 for now (album-as-N-messages); the protocol-level
    // album-as-one-message uses the same `sendMediaAttachments(list, caption)`
    // FFI and is the next
    // follow-up — that one requires `RetainedMediaUpload` to hold a list.
    var pendingMediaUris by rememberSaveable(stateSaver = UriListSaver) {
        mutableStateOf<List<android.net.Uri>>(emptyList())
    }
    // Survives process death while the camera app is foreground (the result
    // callback fires into a recreated activity, otherwise the capture is lost).
    var cameraOutputUri by rememberSaveable(stateSaver = NullableUriSaver) {
        mutableStateOf<android.net.Uri?>(null)
    }
    var cameraOutputFile by remember { mutableStateOf<java.io.File?>(null) }

    // PickMultipleVisualMedia uses the system Photo Picker — no READ_MEDIA_IMAGES
    // permission needed (Android 13+ scopes the picker's own grant); on older
    // devices it falls back to GET_CONTENT with the same UX. The maxItems
    // cap comes from MEDIA_PICKER_MAX_ITEMS; picking a single image still works
    // (returns a one-element list).
    val imagePickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(maxItems = MEDIA_PICKER_MAX_ITEMS),
        ) { uris ->
            if (uris.isNotEmpty()) pendingMediaUris = uris
        }
    val cameraLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success ->
            val captured = cameraOutputUri
            if (success && captured != null) {
                pendingMediaUris = listOf(captured)
            } else {
                cameraOutputFile?.delete() // cancelled — don't leak the empty temp
            }
            cameraOutputUri = null
            cameraOutputFile = null
        }

    fun launchCameraCapture() {
        val file = createImageCaptureFile(context)
        if (file == null) {
            appState.present(R.string.toast_couldnt_decode_image)
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

    // Decode/compress each URI off the main thread, then hand the album to
    // the controller as a single `sendImageAttachments(list, caption)` call.
    // One kind:9 carries N imeta tags; the caption is shared across the
    // whole album. If any source fails to decode the rest still send (best
    // effort), but if NONE decode we bail without surfacing an empty send.
    fun sendPickedMedia(
        uris: List<android.net.Uri>,
        caption: String,
    ) {
        if (uris.isEmpty()) return
        val trimmedCaption = caption.trim().takeIf { it.isNotBlank() }
        appState.launchMutation {
            val attachments =
                withContext(Dispatchers.Default) {
                    uris.mapNotNull { uri ->
                        val jpeg =
                            MediaPipeline.readDownscaledJpeg(context.contentResolver, uri)
                                ?: return@mapNotNull null
                        val sourceName = queryDisplayName(context.contentResolver, uri) ?: "image.jpg"
                        val fileName = MediaPipeline.swapExtensionToJpg(sourceName)
                        PendingAttachment(
                            jpegBytes = jpeg,
                            mediaType = MediaPipeline.RECOMPRESSED_MIME,
                            fileName = fileName,
                        )
                    }
                }
            if (attachments.size < uris.size) {
                appState.present(R.string.toast_couldnt_decode_image)
                if (attachments.isEmpty()) return@launchMutation
            }
            controller.sendImageAttachments(attachments, trimmedCaption)
        }
    }

    // Wipe decrypted share/camera temp files and retained outgoing JPEG bytes
    // when leaving the conversation so plaintext media doesn't linger.
    DisposableEffect(Unit) {
        onDispose {
            clearMediaTempFiles(context)
            controller.clearRetainedUploads()
        }
    }

    fun recordReactionEmoji(emoji: String) {
        recentReactionEmojis = RecentEmojiPreferences.recordPicked(context, emoji)
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
                val timelineIndex = controller.timelineIndexOf(targetMessageId)
                if (timelineIndex < 0) {
                    appState.present(R.string.toast_original_message_unavailable)
                    return@launch
                }
                val olderMessagesHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
                listState.animateScrollToItem(1 + olderMessagesHeaderCount + timelineIndex)
                highlightedMessageId = targetMessageId
                delay(1_500L)
                if (highlightedMessageId == targetMessageId) {
                    highlightedMessageId = null
                }
            }
    }

    BackHandler {
        onBack()
    }

    LaunchedEffect(controller) {
        initialTimelineLoadStarted = true
        controller.start()
    }
    val latestTimelineItemId = controller.timeline.lastOrNull()?.id
    val olderHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
    val bottomTimelineIndex = controller.timeline.size + 1 + olderHeaderCount
    // Capture the unread boundary at chat open. Stays fixed for the lifetime
    // of this composable (per chat.id) so the "N unread messages" divider
    // doesn't keep moving as the user scrolls and marks messages as read.
    val entryUnreadCount = remember(chat.id) { chat.unreadCount.toInt().coerceAtLeast(0) }
    var entryFirstUnreadMessageId by remember(chat.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(chat.id, controller.timeline.size) {
        if (entryFirstUnreadMessageId == null && entryUnreadCount > 0) {
            val firstUnreadIndex = controller.firstUnreadTimelineIndex(entryUnreadCount)
            if (firstUnreadIndex >= 0) {
                entryFirstUnreadMessageId =
                    controller.timeline[firstUnreadIndex]
                        .record.messageIdHex
                        .takeIf { it.isNotBlank() }
            }
        }
    }
    LaunchedEffect(latestTimelineItemId, imeBottom) {
        if (controller.timeline.isNotEmpty()) {
            if (!initialTimelineAnchored) {
                // First-time anchor on chat open. If there are unread
                // messages, land at the first unread one so the user can
                // read forward from there; otherwise drop them at the
                // newest message.
                val firstUnreadTimelineIndex = controller.firstUnreadTimelineIndex(chat.unreadCount.toInt())
                val targetIndex =
                    if (firstUnreadTimelineIndex >= 0) {
                        1 + olderHeaderCount + firstUnreadTimelineIndex
                    } else {
                        bottomTimelineIndex
                    }
                listState.scrollToItem(targetIndex)
                initialTimelineAnchored = true
            } else if (nearBottom) {
                // User is still pinned to the newest message; follow new
                // incoming messages down. Reading older history isn't
                // interrupted by this branch (see issue #59).
                listState.scrollToItem(bottomTimelineIndex)
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
    if (showDetails) {
        GroupDetailsScreen(
            appState = appState,
            controller = controller,
            onBack = { showDetails = false },
            onLeft = onBack,
        )
        return
    }

    val openDetailsDescription = stringResource(R.string.details)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showDetails = true }
                                .semantics { contentDescription = openDetailsDescription },
                    ) {
                        Avatar(
                            title = controller.title(groupTitleCopy),
                            seed = controller.group.groupIdHex,
                            size = 36.dp,
                            pictureUrl = controller.group.avatarUrl,
                        )
                        Column {
                            Text(controller.title(groupTitleCopy), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                controller.subtitle(
                                    justYou = stringResource(R.string.just_you),
                                    oneMember = stringResource(R.string.one_member),
                                    membersFormat = stringResource(R.string.members_count),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (controller.group.archived) R.string.unarchive else R.string.archive)) },
                            leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                            enabled = !controller.mutationInFlight,
                            onClick = {
                                menuOpen = false
                                appState.launchMutation { controller.setArchived(!controller.group.archived) }
                            },
                        )
                        if (controller.isSelfMember) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.leave)) },
                                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                enabled = !controller.mutationInFlight,
                                onClick = {
                                    menuOpen = false
                                    confirmLeaveFromTopBar = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            when {
                controller.error != null || controller.group.pendingConfirmation -> Unit
                // Only suppress the composer when we've *confirmed* the user
                // is no longer a member. The kicked-notice branch must lose
                // to the composer during the load window (`membersLoaded`
                // still false) so the user isn't staring at a blank bottom
                // bar while `refreshMembers()` round-trips. The controller's
                // `canSendMessages` guard in send/upload/react/delete keeps
                // any actual mutation safe until membership is confirmed.
                controller.membersLoaded && !controller.isSelfMember -> RemovedMemberComposerNotice()
                else -> {
                    val groupIdHex = controller.group.groupIdHex
                    ComposerBar(
                        replyingTo = controller.replyingTo,
                        messageTextCopy = messageTextCopy,
                        onCancelReply = { controller.replyingTo = null },
                        onSend = { appState.launchMutation { controller.send(it) } },
                        initialDraft = appState.draftFor(groupIdHex).orEmpty(),
                        onDraftChange = { appState.setDraft(groupIdHex, it) },
                        draftKey = groupIdHex,
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
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
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
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                controller.error != null -> ErrorContent(stringResource(R.string.couldnt_load_conversation), controller.error.orEmpty())
                controller.group.pendingConfirmation ->
                    PendingInviteContent(
                        title = controller.title(groupTitleCopy),
                        pictureUrl = controller.inviteAccount?.let { appState.avatarUrl(it) },
                        avatarSeed = controller.inviteAccount ?: controller.group.groupIdHex,
                        onAccept = {
                            appState.launchMutation { controller.acceptInvite() }
                        },
                        onDecline = {
                            appState.launchMutation {
                                if (controller.declineInvite()) onBack()
                            }
                        },
                    )
                controller.timeline.isEmpty() && !controller.isLoading && initialTimelineLoadStarted ->
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.no_messages_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            item { Spacer(Modifier.height(4.dp)) }
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
                            items(controller.timeline, key = { it.id }) { item ->
                                if (entryUnreadCount > 0 && item.record.messageIdHex == entryFirstUnreadMessageId) {
                                    UnreadMessagesDivider(count = entryUnreadCount)
                                }
                                MessageBubble(
                                    item = item,
                                    controller = controller,
                                    appState = appState,
                                    highlighted = item.record.messageIdHex == highlightedMessageId,
                                    recentReactionEmojis = recentReactionEmojis,
                                    onReactionEmojiPicked = ::recordReactionEmoji,
                                    onReplyPreviewClick = ::navigateToReplyTarget,
                                )
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                        if (!initialTimelineAnchored) {
                            LoadingScreen()
                        }
                        if (initialTimelineAnchored && !nearBottom) {
                            SmallFloatingActionButton(
                                onClick = {
                                    scope.launch {
                                        val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                        listState.animateScrollToItem(lastIndex)
                                    }
                                },
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(12.dp),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.ArrowDownward,
                                        contentDescription = stringResource(R.string.jump_to_newest),
                                        modifier = Modifier.size(20.dp),
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

    if (confirmLeaveFromTopBar) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_leave_title),
            message = stringResource(R.string.confirm_leave_message),
            confirmLabel = stringResource(R.string.leave),
            onConfirm = {
                confirmLeaveFromTopBar = false
                appState.launchMutation {
                    if (controller.leaveGroup()) onBack()
                }
            },
            onDismiss = { confirmLeaveFromTopBar = false },
        )
    }

    if (pendingMediaUris.isNotEmpty()) {
        val uris = pendingMediaUris
        MediaPreviewSheet(
            uris = uris,
            onDismiss = { pendingMediaUris = emptyList() },
            onSend = { caption ->
                pendingMediaUris = emptyList()
                sendPickedMedia(uris, caption)
            },
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun PendingInviteContent(
    title: String,
    pictureUrl: String?,
    avatarSeed: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(title = title, seed = avatarSeed, size = 64.dp, pictureUrl = pictureUrl)
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.invited_to_this_chat),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDecline) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.decline))
                }
                Button(onClick = onAccept) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.accept))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDetailsScreen(
    appState: DarkMatterAppState,
    controller: ConversationController,
    onBack: () -> Unit,
    onLeft: () -> Unit,
) {
    var name by remember(controller.group.groupIdHex, controller.group.name) { mutableStateOf(controller.group.name) }
    var description by remember(controller.group.groupIdHex, controller.group.description) { mutableStateOf(controller.group.description) }
    var avatarUrl by remember(controller.group.groupIdHex, controller.group.avatarUrl) { mutableStateOf(controller.group.avatarUrl.orEmpty()) }
    var pendingMember by remember { mutableStateOf("") }
    var pendingMemberError by remember { mutableStateOf<String?>(null) }
    var showMemberScanner by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showEditProfile by remember { mutableStateOf(false) }
    var showAddMember by remember { mutableStateOf(false) }
    var mlsState by remember(controller.group.groupIdHex) { mutableStateOf<AppGroupMlsStateFfi?>(null) }
    var mlsLoading by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    // Scoped to the visible group; the controller mutation continues on appState
    // if the user switches conversations, but this sheet stops tracking it.
    var activeMutation by remember(controller.group.groupIdHex) { mutableStateOf<ActiveGroupMutation?>(null) }
    var pendingInvites by remember(controller.group.groupIdHex) { mutableStateOf<List<String>>(emptyList()) }
    var pendingConfirm by remember { mutableStateOf<DetailsConfirm?>(null) }
    val scope = rememberCoroutineScope()
    val groupTitleCopy = rememberGroupTitleCopy()
    val oneValidMemberReferenceError = stringResource(R.string.error_one_valid_member_reference)
    val qrNotValidNpubOrPublicKeyError = stringResource(R.string.error_qr_not_valid_npub_or_public_key)

    suspend fun refreshMlsDetails() {
        if (!appState.developerMode) return
        mlsLoading = true
        try {
            mlsState = controller.groupMlsState()
        } finally {
            mlsLoading = false
        }
    }

    fun runGroupMutation(
        action: GroupMutationAction,
        mutation: suspend () -> Boolean,
        target: String? = null,
        onSuccess: () -> Unit = {},
    ) {
        // Launched on a process-lifetime scope so the MLS commit + Nostr
        // publish complete even if the user dismisses this sheet mid-flight.
        // The refreshMembers() + appState.present(toast) inside each
        // ConversationController.* method then always run, regardless of
        // whether this composable is still on screen.
        activeMutation = ActiveGroupMutation(action, target)
        controller.clearLastMutationError()
        appState.launchMutation {
            try {
                if (mutation()) onSuccess()
            } finally {
                // onSuccess() may have already dismissed this sheet; clearing
                // detached Compose state is harmless in that case.
                activeMutation = null
            }
        }
    }

    LaunchedEffect(
        appState.developerMode,
        controller.group.groupIdHex,
        controller.group.admins,
        controller.members.map { it.memberIdHex },
    ) {
        refreshMlsDetails()
    }

    LaunchedEffect(controller.members.map { it.memberIdHex }, pendingInvites) {
        val memberIds = controller.members.map { it.memberIdHex.lowercase() }.toSet()
        val filtered =
            pendingInvites.filter { invite ->
                val accountIdHex = appState.accountIdHex(invite)?.lowercase()
                accountIdHex == null || accountIdHex !in memberIds
            }
        if (filtered != pendingInvites) pendingInvites = filtered
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.actions))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (controller.isSelfMember && controller.isSelfAdmin) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.edit)) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                enabled = activeMutation == null && !controller.mutationInFlight,
                                onClick = {
                                    menuOpen = false
                                    name = controller.group.name
                                    description = controller.group.description
                                    avatarUrl = controller.group.avatarUrl.orEmpty()
                                    showEditProfile = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        when {
                                            activeMutation?.action == GroupMutationAction.Archive && controller.group.archived -> R.string.restoring_chat
                                            activeMutation?.action == GroupMutationAction.Archive -> R.string.archiving_chat
                                            controller.group.archived -> R.string.unarchive_chat
                                            else -> R.string.archive_chat
                                        },
                                    ),
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                            enabled = activeMutation == null && !controller.mutationInFlight,
                            onClick = {
                                menuOpen = false
                                runGroupMutation(
                                    action = GroupMutationAction.Archive,
                                    mutation = { controller.setArchived(!controller.group.archived) },
                                )
                            },
                        )
                        if (controller.isSelfMember) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (activeMutation?.action ==
                                                GroupMutationAction.Leave
                                            ) {
                                                R.string.leaving_chat
                                            } else {
                                                R.string.leave_chat
                                            },
                                        ),
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                enabled = controller.canLeaveGroup && activeMutation == null && !controller.mutationInFlight,
                                onClick = {
                                    menuOpen = false
                                    pendingConfirm = DetailsConfirm.Leave
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GroupDetailsHeader(
                title = controller.title(groupTitleCopy),
                subtitle =
                    controller.subtitle(
                        justYou = stringResource(R.string.just_you),
                        oneMember = stringResource(R.string.one_member),
                        membersFormat = stringResource(R.string.members_count),
                    ),
                description = controller.group.description,
                groupIdHex = controller.group.groupIdHex,
                archived = controller.group.archived,
            )

            controller.lastMutationError?.let { message ->
                GroupMutationErrorBanner(
                    message = message,
                    onDismiss = { controller.clearLastMutationError() },
                )
            }

            SectionCardWithAction(
                title = "${stringResource(R.string.members)} · ${controller.members.size}",
                action = {
                    if (controller.isSelfMember && controller.isSelfAdmin) {
                        TextButton(
                            onClick = {
                                pendingMemberError = null
                                showAddMember = true
                            },
                            enabled = activeMutation == null && !controller.mutationInFlight,
                        ) {
                            if (activeMutation?.action == GroupMutationAction.InviteMember) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Add, contentDescription = null)
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.add_member))
                        }
                    }
                },
            ) {
                controller.members.forEach { member ->
                    GroupMemberRow(
                        member = member,
                        controller = controller,
                        appState = appState,
                        activeMutation = activeMutation,
                        onPromote = {
                            runGroupMutation(
                                action = GroupMutationAction.PromoteAdmin,
                                mutation = { controller.setMemberAdmin(member, admin = true) },
                                target = member.memberIdHex,
                            )
                        },
                        onDemote = {
                            runGroupMutation(
                                action = GroupMutationAction.DemoteAdmin,
                                mutation = { controller.setMemberAdmin(member, admin = false) },
                                target = member.memberIdHex,
                            )
                        },
                        onRemove = {
                            pendingConfirm = DetailsConfirm.RemoveMember(member)
                        },
                    )
                    if (member != controller.members.last()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    }
                }
                if (pendingInvites.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pendingInvites.forEach { invite ->
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                label = { Text(stringResource(R.string.invite_pending, IdentityFormatter.short(invite))) },
                                leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) },
                            )
                        }
                    }
                }
            }

            SectionCard(title = stringResource(R.string.info)) {
                DiagnosticRow(stringResource(R.string.group_id), IdentityFormatter.short(controller.group.groupIdHex))
                DiagnosticRow(stringResource(R.string.nostr_group), IdentityFormatter.short(controller.group.nostrGroupIdHex))
                DiagnosticRow(
                    stringResource(R.string.relays),
                    controller.group.relays.size
                        .toString(),
                )
                controller.group.relays.forEach { relay ->
                    Text(relay, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (appState.developerMode) {
                SectionCard(title = stringResource(R.string.mls)) {
                    when {
                        mlsLoading -> Text(stringResource(R.string.loading_mls_state), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        mlsState == null -> Text(stringResource(R.string.mls_state_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> {
                            val state = requireNotNull(mlsState)
                            DiagnosticRow(stringResource(R.string.group_id), IdentityFormatter.short(state.groupIdHex))
                            DiagnosticRow(stringResource(R.string.epoch), state.epoch.toString())
                            DiagnosticRow(stringResource(R.string.mls_members), state.memberCount.toString())
                            DiagnosticRow(stringResource(R.string.required_components), state.requiredAppComponents.joinToString(", "))
                        }
                    }
                }
            }
        }
    }
    if (showMemberScanner) {
        QrScannerSheet(
            onDismiss = { showMemberScanner = false },
            onScan = { raw ->
                val ref = RecipientReference.normalize(raw)
                showMemberScanner = false
                if (ref == null) {
                    pendingMemberError = qrNotValidNpubOrPublicKeyError
                } else {
                    pendingMember = ref
                    pendingMemberError = null
                }
            },
        )
    }

    if (showEditProfile) {
        ModalBottomSheet(onDismissRequest = { showEditProfile = false }) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.edit), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.description)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = avatarUrl,
                    onValueChange = { avatarUrl = it },
                    label = { Text(stringResource(R.string.group_avatar_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        runGroupMutation(
                            action = GroupMutationAction.SaveProfile,
                            mutation = {
                                // Name/description and the avatar are separate MLS
                                // commits; only fire the ones that actually changed.
                                val profileChanged =
                                    name != controller.group.name || description != controller.group.description
                                val avatarChanged = avatarUrl.trim() != controller.group.avatarUrl.orEmpty()
                                val profileOk = !profileChanged || controller.updateGroupProfile(name, description)
                                val avatarOk = !avatarChanged || controller.updateGroupAvatarUrl(avatarUrl)
                                profileOk && avatarOk
                            },
                            onSuccess = { showEditProfile = false },
                        )
                    },
                    enabled =
                        activeMutation == null &&
                            !controller.mutationInFlight &&
                            (
                                name != controller.group.name ||
                                    description != controller.group.description ||
                                    avatarUrl.trim() != controller.group.avatarUrl.orEmpty()
                            ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (activeMutation?.action == GroupMutationAction.SaveProfile) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (activeMutation?.action == GroupMutationAction.SaveProfile) R.string.saving_group else R.string.save_group))
                }
            }
        }
    }

    if (showAddMember) {
        ModalBottomSheet(onDismissRequest = { showAddMember = false }) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.add_member), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = pendingMember,
                    onValueChange = {
                        pendingMember = it
                        pendingMemberError = null
                    },
                    label = { Text(stringResource(R.string.npub_or_public_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = pendingMemberError != null,
                    supportingText =
                        pendingMemberError?.let { message ->
                            { Text(message) }
                        },
                    trailingIcon = {
                        IconButton(onClick = { showMemberScanner = true }) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_member_qr_code))
                        }
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
                Button(
                    onClick = {
                        val ref =
                            RecipientReference.normalize(pendingMember) ?: run {
                                pendingMemberError = oneValidMemberReferenceError
                                return@Button
                            }
                        pendingMemberError = null
                        runGroupMutation(
                            action = GroupMutationAction.InviteMember,
                            mutation = { controller.inviteMembers(listOf(ref)) },
                            target = ref,
                            onSuccess = {
                                pendingMember = ""
                                pendingMemberError = null
                                pendingInvites = (pendingInvites + ref).distinct()
                                showAddMember = false
                            },
                        )
                    },
                    enabled = pendingMember.isNotBlank() && activeMutation == null && !controller.mutationInFlight,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (activeMutation?.action == GroupMutationAction.InviteMember) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (activeMutation?.action == GroupMutationAction.InviteMember) R.string.adding_member else R.string.add_member))
                }
            }
        }
    }

    pendingConfirm?.let { confirm ->
        when (confirm) {
            is DetailsConfirm.RemoveMember ->
                ConfirmDialog(
                    title = stringResource(R.string.confirm_remove_member_title),
                    message =
                        stringResource(
                            R.string.confirm_remove_member_message,
                            controller.memberDisplayName(confirm.member),
                        ),
                    confirmLabel = stringResource(R.string.remove_member),
                    onConfirm = {
                        pendingConfirm = null
                        runGroupMutation(
                            action = GroupMutationAction.RemoveMember,
                            mutation = { controller.removeMember(confirm.member) },
                            target = confirm.member.memberIdHex,
                        )
                    },
                    onDismiss = { pendingConfirm = null },
                )
            DetailsConfirm.Leave ->
                ConfirmDialog(
                    title = stringResource(R.string.confirm_leave_title),
                    message = stringResource(R.string.confirm_leave_message),
                    confirmLabel = stringResource(R.string.leave),
                    onConfirm = {
                        pendingConfirm = null
                        // Process-lifetime scope so a swipe-dismiss mid-leave doesn't
                        // kill the toast/refresh; onDismiss + onLeft are safe Compose
                        // state writes from Main.immediate.
                        runGroupMutation(
                            action = GroupMutationAction.Leave,
                            mutation = { controller.leaveGroup() },
                            onSuccess = {
                                onLeft()
                            },
                        )
                    },
                    onDismiss = { pendingConfirm = null },
                )
        }
    }
}

@Composable
private fun GroupDetailsHeader(
    title: String,
    subtitle: String,
    description: String,
    groupIdHex: String,
    archived: Boolean,
) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(title = title, seed = groupIdHex, size = 88.dp)
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description.isNotBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (archived) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.archive_chat)) },
                        leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupMutationErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null)
            Text(
                stringResource(R.string.latest_group_error, message),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dismiss))
            }
        }
    }
}

@Composable
private fun GroupActionRow(
    icon: @Composable () -> Unit,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.5f else 0.28f),
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                icon()
            }
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private sealed class DetailsConfirm {
    data class RemoveMember(
        val member: AppGroupMemberRecordFfi,
    ) : DetailsConfirm()

    data object Leave : DetailsConfirm()
}

private enum class GroupMutationAction {
    SaveProfile,
    InviteMember,
    RemoveMember,
    PromoteAdmin,
    DemoteAdmin,
    Archive,
    Leave,
}

private data class ActiveGroupMutation(
    val action: GroupMutationAction,
    val target: String? = null,
)

@Composable
private fun GroupMemberRow(
    member: AppGroupMemberRecordFfi,
    controller: ConversationController,
    appState: DarkMatterAppState,
    activeMutation: ActiveGroupMutation?,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val isAdmin = controller.isAdmin(member)
    val isSelfRow =
        GroupProjector.isActiveAccountMember(
            member,
            appState.activeAccount?.accountIdHex,
        )
    val canManage = controller.isSelfMember && controller.isSelfAdmin && !isSelfRow
    val rowMutation = activeMutation?.takeIf { it.target == member.memberIdHex }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            title = controller.memberDisplayName(member),
            seed = member.memberIdHex,
            size = 40.dp,
            pictureUrl = controller.memberAvatarUrl(member),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(controller.memberDisplayName(member), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                controller.memberSubtitle(member),
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isSelfRow) {
                    Text(
                        stringResource(R.string.you),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isAdmin) {
                    Text(
                        stringResource(R.string.admin),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (rowMutation != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(rowMutation.action.memberStatusLabelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (canManage) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.member_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (isAdmin) R.string.remove_admin else R.string.make_admin)) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        enabled = !controller.mutationInFlight,
                        onClick = {
                            menuOpen = false
                            if (isAdmin) onDemote() else onPromote()
                        },
                    )
                    if (!isAdmin) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remove_member)) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            enabled = !controller.mutationInFlight,
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        }
    }
}

private val GroupMutationAction.memberStatusLabelRes: Int
    @StringRes
    get() =
        when (this) {
            GroupMutationAction.PromoteAdmin -> R.string.adding_admin
            GroupMutationAction.DemoteAdmin -> R.string.removing_admin
            GroupMutationAction.RemoveMember -> R.string.removing_member
            else -> R.string.member_actions
        }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    item: TimelineMessage,
    controller: ConversationController,
    appState: DarkMatterAppState,
    highlighted: Boolean,
    recentReactionEmojis: List<String>,
    onReactionEmojiPicked: (String) -> Unit,
    onReplyPreviewClick: (TimelineMessage) -> Unit,
) {
    val record = item.record
    val mine = MessageProjector.isMine(record, appState.activeAccount?.accountIdHex)
    val deleted = item.projected?.deleted == true || MessageProjector.isDeleted(record.messageIdHex, controller.deletedMessageIds)
    // Convergence dropped this message onto a losing branch: it never reached
    // the group. The record survives as a tombstone, so flag it (an explicit
    // delete takes precedence over an invalidation tombstone).
    val invalidated = !deleted && item.projected?.invalidationStatus != null
    val bubbleColor =
        when {
            deleted -> MaterialTheme.colorScheme.surfaceVariant
            invalidated -> MaterialTheme.colorScheme.errorContainer
            mine -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var swipeDrag by remember(record.messageIdHex) { mutableStateOf(0f) }
    val animatedSwipeOffset by animateFloatAsState(targetValue = swipeDrag, label = "replySwipeOffset")
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val replySwipeThresholdPx = with(density) { 64.dp.toPx() }
    val maxSwipeOffsetPx = with(density) { 72.dp.toPx() }
    val messageTextCopy = rememberMessageTextCopy()
    val displayedBody =
        if (deleted) {
            // Check `deleted` first so the optimistic tombstone (from
            // controller.deletedMessageIds) renders immediately on tap. Otherwise
            // the projected branch runs against the stale Rust-side `deleted`
            // flag and the bubble keeps showing the original body until the
            // delete echo arrives.
            stringResource(R.string.message_deleted)
        } else if (invalidated) {
            stringResource(R.string.message_invalidated)
        } else if (item.projected != null) {
            TimelineProjector.displayBody(
                item.projected,
                messageTextCopy.copy(deleted = stringResource(R.string.message_deleted)),
            )
        } else {
            MessageProjector.displayBody(record, messageTextCopy)
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
            mine && !deleted -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    var emojiPickerOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var infoSheetOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var reactionSheetOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    val quickReactionEmojis = RecentEmojiList.quickChoices(recentReactionEmojis)

    fun beginReply() {
        controller.replyingTo = record
        menuOpen = false
    }

    fun openInfoSheet() {
        menuOpen = false
        infoSheetOpen = true
    }

    fun reactWithEmoji(emoji: String) {
        onReactionEmojiPicked(emoji)
        // Route via launchMutation: same survives-navigation rationale as delete/send.
        appState.launchMutation { controller.toggleReaction(emoji, record) }
    }

    fun copyMessageText() {
        clipboard.setText(AnnotatedString(displayedBody))
        appState.present(R.string.copied)
        menuOpen = false
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val messageGroupMaxWidth = maxWidth * 0.95f
        val senderAvatarWidth = if (showSenderAvatar) 40.dp else 0.dp
        val bubbleColumnMaxWidth = (messageGroupMaxWidth - senderAvatarWidth).coerceAtLeast(120.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
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
                Surface(
                    modifier =
                        Modifier
                            .offset { IntOffset(animatedSwipeOffset.roundToInt(), 0) }
                            .pointerInput(record.messageIdHex, replySwipeThresholdPx, maxSwipeOffsetPx) {
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
                            }.combinedClickable(
                                onClick = {},
                                onLongClick = { menuOpen = true },
                            ),
                    color = bubbleColor,
                    shape = RoundedCornerShape(18.dp),
                    border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary) else null,
                    tonalElevation = if (mine) 1.dp else 0.dp,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!mine) {
                            Text(
                                appState.displayName(record.sender),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier =
                                    Modifier.combinedClickable(
                                        onClick = { appState.presentProfile(appState.npub(record.sender)) },
                                        onLongClick = { menuOpen = true },
                                    ),
                            )
                        }
                        controller.replyPreview(item, messageTextCopy)?.let { (name, body) ->
                            Surface(
                                modifier = Modifier.clickable { onReplyPreviewClick(item) },
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Column(Modifier.padding(8.dp)) {
                                    Text(name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                    Text(body, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
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
                        val imageReferences =
                            remember(mediaReferences) {
                                mediaReferences.filter { MediaReferenceParser.isImageMedia(it) }
                            }
                        val mediaPendingName =
                            remember(record.tags) {
                                record.tags
                                    .firstOrNull { it.values.firstOrNull() == "_media_pending" }
                                    ?.values
                                    ?.getOrNull(1)
                            }
                        if (!deleted && !invalidated && imageReferences.isNotEmpty()) {
                            if (imageReferences.size == 1) {
                                MediaImageBubble(
                                    item = item,
                                    reference = imageReferences.first(),
                                    controller = controller,
                                    appState = appState,
                                    mine = mine,
                                )
                            } else {
                                MediaImageGridBubble(
                                    item = item,
                                    references = imageReferences,
                                    controller = controller,
                                    appState = appState,
                                )
                            }
                        } else if (!deleted && !invalidated && mediaPendingName != null) {
                            MediaPendingPlaceholder(
                                previewBytesList = controller.pendingMediaBytesList(record.messageIdHex),
                                failed = item.status == MessageStatus.Failed,
                            )
                        }
                        // Body text policy:
                        // - Pending optimistic with an attachment: placeholder
                        //   composable already renders, suppress text.
                        // - Confirmed media (imeta tag present): render ONLY
                        //   the user-typed caption (record.plaintext). Never
                        //   use displayedBody here — MessageProjector falls
                        //   back to the imeta filename when the caption is
                        //   blank, which is the right answer for chat-list
                        //   previews but wrong for a bubble that's already
                        //   showing the image inline.
                        // - Non-media: render displayedBody (covers reactions,
                        //   deletions, agent streams, plain text).
                        val bodyTextToRender: String? =
                            when {
                                // Deleted/invalidated tombstones show only the
                                // tombstone copy, never an inline image/caption.
                                deleted || invalidated -> displayedBody
                                mediaPendingName != null -> null
                                imageReferences.isNotEmpty() -> record.plaintext.takeIf { it.isNotBlank() }
                                else -> displayedBody
                            }
                        if (bodyTextToRender != null) {
                            Text(
                                bodyTextToRender,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Row(
                            modifier = Modifier.align(if (mine) Alignment.End else Alignment.Start),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                IdentityFormatter.relativeTime(record.recordedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = timestampColor,
                            )
                            if (mine) {
                                OutgoingMessageStatusIcon(item.status, tint = timestampColor)
                            }
                            if (mine && item.status == MessageStatus.Failed) {
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
                            MessageActionMenu(
                                expanded = menuOpen,
                                canDelete = mine && record.messageIdHex.isNotBlank(),
                                quickReactionEmojis = quickReactionEmojis,
                                onDismissRequest = { menuOpen = false },
                                onReact = { emoji ->
                                    menuOpen = false
                                    reactWithEmoji(emoji)
                                },
                                onOpenEmojiPicker = {
                                    menuOpen = false
                                    emojiPickerOpen = true
                                },
                                onReply = ::beginReply,
                                onCopyText = ::copyMessageText,
                                onInfo = ::openInfoSheet,
                                onDelete = {
                                    menuOpen = false
                                    // launchMutation so the MLS commit + Nostr publish
                                    // survive navigating away from the conversation —
                                    // the optimistic tombstone is already set in the
                                    // controller's state and the FFI write needs to
                                    // complete regardless of whether this bubble is
                                    // still in composition.
                                    appState.launchMutation { controller.deleteMessage(record) }
                                },
                            )
                        }
                    }
                }
                if (emojiPickerOpen) {
                    EmojiPickerSheet(
                        onDismissRequest = { emojiPickerOpen = false },
                        onEmojiPicked = { emoji ->
                            emojiPickerOpen = false
                            reactWithEmoji(emoji)
                        },
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
                val tallies = controller.reactions[record.messageIdHex].orEmpty()
                if (tallies.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        tallies.forEach { tally ->
                            ReactionTallyChip(
                                tally = tally,
                                onClick = { reactionSheetOpen = true },
                                onLongClick = {
                                    appState.launchMutation { controller.toggleReaction(tally.emoji, record) }
                                },
                            )
                        }
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
                            onDismissRequest = { reactionSheetOpen = false },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReactionTallyChip(
    tally: ReactionTally,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val viewReactorsLabel = stringResource(R.string.view_reactors)
    val toggleReactionLabel = stringResource(R.string.toggle_reaction)
    Surface(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .semantics { selected = tally.mine }
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onClickLabel = viewReactorsLabel,
                    onLongClick = onLongClick,
                    onLongClickLabel = toggleReactionLabel,
                ),
        shape = RoundedCornerShape(8.dp),
        color = if (tally.mine) colorScheme.secondaryContainer else colorScheme.surfaceContainerHigh,
        contentColor = if (tally.mine) colorScheme.onSecondaryContainer else colorScheme.onSurface,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = "${tally.emoji} ${tally.count}",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReactionDetailsSheet(
    participants: List<ReactionParticipant>,
    appState: DarkMatterAppState,
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

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
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
                    key = { index, participant -> "${participant.sender}:${participant.emoji}:${participant.reactedAt}:$index" },
                ) { _, participant ->
                    ReactionParticipantRow(
                        participant = participant,
                        appState = appState,
                        mine = activeAccountId != null && participant.sender.equals(activeAccountId, ignoreCase = true),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReactionParticipantRow(
    participant: ReactionParticipant,
    appState: DarkMatterAppState,
    mine: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { appState.presentProfile(appState.npub(participant.sender)) }
                .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            title = appState.displayName(participant.sender),
            seed = participant.sender,
            size = 44.dp,
            pictureUrl = appState.avatarUrl(participant.sender),
        )
        Text(
            text = if (mine) stringResource(R.string.you) else appState.displayName(participant.sender),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = participant.emoji,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun MessageActionMenu(
    expanded: Boolean,
    canDelete: Boolean,
    quickReactionEmojis: List<String>,
    onDismissRequest: () -> Unit,
    onReact: (String) -> Unit,
    onOpenEmojiPicker: () -> Unit,
    onReply: () -> Unit,
    onCopyText: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier.padding(8.dp).widthIn(min = 292.dp, max = 328.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MessageActionButton(
                    label = stringResource(R.string.reply),
                    icon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = onReply,
                )
                MessageActionButton(
                    label = stringResource(R.string.copy_text),
                    icon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = onCopyText,
                )
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
private fun EmojiPickerSheet(
    onDismissRequest: () -> Unit,
    onEmojiPicked: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(384.dp).navigationBarsPadding(),
            factory = { context ->
                EmojiPickerView(context).apply {
                    emojiGridColumns = 8
                    emojiGridRows = 5.25f
                    setOnEmojiPickedListener { item -> onEmojiPicked(item.emoji) }
                }
            },
        )
    }
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

@Composable
private fun RemovedMemberComposerNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Text(
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

@Composable
private fun ComposerBar(
    replyingTo: AppMessageRecordFfi?,
    messageTextCopy: MessageTextCopy,
    onCancelReply: () -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialDraft: String = "",
    onDraftChange: (String) -> Unit = {},
    draftKey: Any? = null,
    onAfterSend: () -> Unit = {},
    onPickFromGallery: (() -> Unit)? = null,
    onCaptureFromCamera: (() -> Unit)? = null,
) {
    var attachMenuOpen by remember { mutableStateOf(false) }
    // Keyed on draftKey so switching to a different chat re-hydrates the text
    // field from that chat's saved draft rather than carrying state across.
    var text by remember(draftKey) { mutableStateOf(initialDraft) }
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (replyingTo != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    MessageProjector.displayBody(replyingTo, messageTextCopy),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onCancelReply, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel_reply), modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onPickFromGallery != null || onCaptureFromCamera != null) {
                Box {
                    IconButton(
                        onClick = { attachMenuOpen = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = stringResource(R.string.attach_image),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = attachMenuOpen,
                        onDismissRequest = { attachMenuOpen = false },
                    ) {
                        if (onCaptureFromCamera != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_take_photo)) },
                                leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                                onClick = {
                                    attachMenuOpen = false
                                    onCaptureFromCamera()
                                },
                            )
                        }
                        if (onPickFromGallery != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_photo_library)) },
                                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                onClick = {
                                    attachMenuOpen = false
                                    onPickFromGallery()
                                },
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    onDraftChange(it)
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.message)) },
                maxLines = 5,
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        errorContainerColor = Color.Transparent,
                    ),
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Default,
                    ),
            )
            FloatingActionButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                        onDraftChange("")
                        onAfterSend()
                    }
                },
                modifier = Modifier.size(52.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    appState: DarkMatterAppState,
    onBackToChats: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    detail: SettingsDetail?,
    onDetailChange: (SettingsDetail?) -> Unit,
) {
    BackHandler(enabled = detail != null) {
        onDetailChange(null)
    }

    when (detail) {
        SettingsDetail.Appearance -> AppearanceScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Profile -> ProfileEditScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Identity -> IdentityScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Relays -> RelaysScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.KeyPackages -> KeyPackagesScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Notifications -> NotificationsScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.SecurityPrivacy ->
            SecurityPrivacyScreen(
                appState = appState,
                onBack = { onDetailChange(null) },
                onOpenDiagnostics = onOpenDiagnostics,
            )
        null ->
            SettingsHomeScreen(
                appState = appState,
                onBackToChats = onBackToChats,
                onOpenDetail = { onDetailChange(it) },
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(onBackToChats: () -> Unit) {
    CenterAlignedTopAppBar(
        title = { Text(stringResource(R.string.settings)) },
        navigationIcon = {
            IconButton(onClick = onBackToChats) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_to_chats))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHomeScreen(
    appState: DarkMatterAppState,
    onBackToChats: () -> Unit,
    onOpenDetail: (SettingsDetail) -> Unit,
) {
    var qrAccountId by remember { mutableStateOf<String?>(null) }
    var showAccountSelector by remember { mutableStateOf(false) }
    var showAddIdentity by remember { mutableStateOf(false) }

    LaunchedEffect(appState.accounts.size) {
        if (showAddIdentity) showAddIdentity = false
    }

    Scaffold(
        topBar = {
            SettingsTopBar(onBackToChats = onBackToChats)
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.account)) {
                    appState.activeAccount?.let { account ->
                        SettingsAccountHeader(
                            title = appState.displayName(account.accountIdHex),
                            subtitle = appState.shortNpub(account.accountIdHex),
                            seed = account.accountIdHex,
                            pictureUrl = appState.avatarUrl(account.accountIdHex),
                            onOpenAccountSelector = { showAccountSelector = true },
                            onOpenQr = { qrAccountId = account.accountIdHex },
                        )
                    }
                    SettingsRow(stringResource(R.string.profile), stringResource(R.string.profile_settings_subtitle)) { onOpenDetail(SettingsDetail.Profile) }
                    SettingsRow(
                        stringResource(R.string.identity_and_keys),
                        stringResource(R.string.identity_settings_subtitle),
                    ) { onOpenDetail(SettingsDetail.Identity) }
                    SettingsRow(stringResource(R.string.relays), stringResource(R.string.relays_settings_subtitle)) { onOpenDetail(SettingsDetail.Relays) }
                    SettingsRow(
                        stringResource(R.string.key_packages),
                        stringResource(R.string.key_packages_settings_subtitle),
                    ) { onOpenDetail(SettingsDetail.KeyPackages) }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.app_preferences)) {
                    SettingsRow(
                        stringResource(R.string.appearance),
                        stringResource(R.string.appearance_settings_subtitle),
                    ) { onOpenDetail(SettingsDetail.Appearance) }
                    SettingsRow(
                        stringResource(R.string.notifications),
                        stringResource(R.string.notifications_settings_subtitle),
                    ) { onOpenDetail(SettingsDetail.Notifications) }
                    SettingsRow(stringResource(R.string.security_and_privacy), stringResource(R.string.security_privacy_settings_subtitle)) {
                        onOpenDetail(SettingsDetail.SecurityPrivacy)
                    }
                }
            }
        }
    }

    qrAccountId?.let { accountId ->
        ProfileQrSheet(
            appState = appState,
            accountIdHex = accountId,
            onDismiss = { qrAccountId = null },
        )
    }
    if (showAccountSelector) {
        AccountSelectorSheet(
            appState = appState,
            onDismiss = { showAccountSelector = false },
            onAddAccount = {
                showAccountSelector = false
                showAddIdentity = true
            },
        )
    }
    if (showAddIdentity) {
        AddIdentitySheet(appState = appState, onDismiss = { showAddIdentity = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appearance)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.theme)) {
                    AppThemeMode.entries.forEach { mode ->
                        SelectableSettingsRow(
                            title = stringResource(mode.labelRes),
                            selected = appState.themeMode == mode,
                            onClick = { appState.updateThemeMode(mode) },
                        )
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.language)) {
                    languageOptions.forEach { option ->
                        SelectableSettingsRow(
                            title = stringResource(option.labelRes),
                            selected = appState.languageTag == option.tag,
                            onClick = { appState.updateLanguageTag(option.tag) },
                        )
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.media_auto_download_title)) {
                    MediaAutoDownloadPolicy.entries.forEach { policy ->
                        SelectableSettingsRow(
                            title = stringResource(policy.labelRes),
                            selected = appState.mediaAutoDownloadPolicy == policy,
                            onClick = { appState.updateMediaAutoDownloadPolicy(policy) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableSettingsRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        trailingContent = {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.selected))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
) {
    var pendingNotificationEnable by remember { mutableStateOf(false) }
    var pendingBackgroundConnectionEnable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appState.refreshLocalNotificationPermission()
            if (granted && pendingNotificationEnable) {
                scope.launch { appState.setLocalNotificationsEnabled(true) }
            }
            if (granted && pendingBackgroundConnectionEnable) {
                scope.launch { appState.setBackgroundConnectionEnabled(true) }
            } else if (!granted) {
                appState.present(R.string.toast_notification_permission_denied)
            }
            pendingNotificationEnable = false
            pendingBackgroundConnectionEnable = false
        }

    LaunchedEffect(appState.activeAccountRef) {
        appState.refreshLocalNotificationPermission()
        appState.refreshLocalNotificationSettings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.notifications)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.local_notifications), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.local_notifications_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = appState.localNotificationSettings?.localNotificationsEnabled == true,
                            enabled = appState.activeAccountRef != null,
                            onCheckedChange = { enabled ->
                                if (enabled && !appState.localNotificationPermissionGranted) {
                                    pendingNotificationEnable = true
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    scope.launch { appState.setLocalNotificationsEnabled(enabled) }
                                }
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.keep_connected), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.keep_connected_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = appState.backgroundConnectionEnabled,
                            enabled = appState.activeAccountRef != null,
                            onCheckedChange = { enabled ->
                                if (enabled && !appState.localNotificationPermissionGranted) {
                                    pendingBackgroundConnectionEnable = true
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    scope.launch { appState.setBackgroundConnectionEnabled(enabled) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecurityPrivacyScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var telemetryBusy by remember { mutableStateOf(false) }
    var auditLogsBusy by remember { mutableStateOf(false) }

    LaunchedEffect(appState.runtimeGeneration) {
        appState.refreshSecurityPrivacySettings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.security_and_privacy)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.security_and_privacy)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.telemetry),
                        subtitle = stringResource(R.string.telemetry_settings_subtitle),
                        checked = appState.relayTelemetrySettings?.exportEnabled == true,
                        enabled = !telemetryBusy,
                        busy = telemetryBusy,
                        onCheckedChange = { enabled ->
                            telemetryBusy = true
                            scope.launch {
                                try {
                                    appState.setTelemetryEnabled(enabled)
                                } finally {
                                    telemetryBusy = false
                                }
                            }
                        },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSwitchRow(
                        title = stringResource(R.string.audit_logs),
                        subtitle = stringResource(R.string.audit_logs_settings_subtitle),
                        checked = appState.auditLogSettings?.enabled == true,
                        enabled = !auditLogsBusy,
                        busy = auditLogsBusy,
                        onCheckedChange = { enabled ->
                            auditLogsBusy = true
                            appState.launchMutation {
                                try {
                                    appState.setAuditLogsEnabled(enabled)
                                } finally {
                                    auditLogsBusy = false
                                }
                            }
                        },
                    )
                }
            }
            item {
                SectionCard(title = stringResource(R.string.developer)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.developer_mode),
                        subtitle = stringResource(R.string.developer_mode_subtitle),
                        checked = appState.developerMode,
                        onCheckedChange = { appState.updateDeveloperMode(it) },
                    )
                    if (appState.developerMode) {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        SettingsRow(stringResource(R.string.diagnostics), stringResource(R.string.diagnostics_settings_subtitle)) { onOpenDiagnostics() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    busy: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
fun SettingsAccountHeader(
    title: String,
    subtitle: String,
    seed: String,
    pictureUrl: String?,
    onOpenAccountSelector: () -> Unit,
    onOpenQr: () -> Unit,
) {
    val switchAccountDescription = stringResource(R.string.switch_account)
    ListItem(
        modifier =
            Modifier
                .clickable(onClick = onOpenAccountSelector)
                .semantics { contentDescription = switchAccountDescription },
        leadingContent = {
            Avatar(
                title = title,
                seed = seed,
                size = 52.dp,
                pictureUrl = pictureUrl,
            )
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, fontFamily = FontFamily.Monospace) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ExpandMore, contentDescription = null)
                IconButton(onClick = onOpenQr) {
                    Icon(Icons.Default.QrCode, contentDescription = stringResource(R.string.my_qr_code))
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSelectorSheet(
    appState: DarkMatterAppState,
    onDismiss: () -> Unit,
    onAddAccount: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.switch_account), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(appState.accounts, key = { it.label }) { account ->
                    ListItem(
                        modifier =
                            Modifier.clickable {
                                appState.setActiveAccount(account.label)
                                onDismiss()
                            },
                        leadingContent = {
                            Avatar(
                                title = appState.displayName(account.accountIdHex),
                                seed = account.accountIdHex,
                                size = 44.dp,
                                pictureUrl = appState.avatarUrl(account.accountIdHex),
                            )
                        },
                        headlineContent = { Text(appState.displayName(account.accountIdHex)) },
                        supportingContent = {
                            Text(
                                appState.shortNpub(account.accountIdHex),
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!account.localSigning) {
                                    Text(stringResource(R.string.read_only), style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (account.label == appState.activeAccountRef) {
                                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.active))
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
            OutlinedButton(
                onClick = onAddAccount,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_account))
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileQrSheet(
    appState: DarkMatterAppState,
    accountIdHex: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val npub = appState.npub(accountIdHex)
    val link = remember(npub) { ProfileLink(npub) }
    var copied by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shareProfileTitle = stringResource(R.string.share_profile)
    val notDarkMatterProfileQrError = stringResource(R.string.error_not_dark_matter_profile_qr)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(
                title = appState.displayName(accountIdHex),
                seed = accountIdHex,
                size = 120.dp,
                pictureUrl = appState.avatarUrl(accountIdHex),
            )
            Text(appState.displayName(accountIdHex), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(npub))
                    copied = true
                    appState.present(R.string.toast_copied_npub)
                },
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (copied) stringResource(R.string.copied) else IdentityFormatter.short(npub, prefix = 16, suffix = 14))
            }
            QrCodeImage(content = link.uri)
            scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        val sendIntent =
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, link.uri)
                        context.startActivity(Intent.createChooser(sendIntent, shareProfileTitle))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share))
                }
                Button(
                    onClick = {
                        scanError = null
                        showScanner = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan))
                }
            }
        }
    }

    if (showScanner) {
        QrScannerSheet(
            onDismiss = { showScanner = false },
            onScan = { raw ->
                showScanner = false
                val scanned = ProfileLink.parse(raw)
                if (scanned == null) {
                    scanError = notDarkMatterProfileQrError
                } else {
                    onDismiss()
                    appState.presentProfile(scanned.npub)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheet(
    appState: DarkMatterAppState,
    npub: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var hex by remember(npub) { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(npub) {
        val resolved = appState.accountIdHex(npub)
        hex = resolved
        if (resolved != null) appState.refreshProfile(resolved)
    }

    val title = hex?.let { appState.displayName(it) } ?: IdentityFormatter.short(npub)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(
                title = title,
                seed = hex ?: npub,
                size = 96.dp,
                pictureUrl = hex?.let { appState.avatarUrl(it) },
            )
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(npub))
                    appState.present(R.string.toast_copied_npub)
                },
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(IdentityFormatter.short(npub, prefix = 16, suffix = 14))
            }
            if (hex == null) {
                Text(stringResource(R.string.couldnt_read_profile_code), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = {},
                // 1:1 DMs aren't a product feature yet; button is intentionally
                // disabled until they are.
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Group, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.message))
            }
        }
    }
}

@Composable
private fun QrCodeImage(content: String) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, content) {
        value = withContext(Dispatchers.Default) { qrBitmap(content, 900).asImageBitmap() }
    }

    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
        if (image == null) {
            Box(Modifier.size(257.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Image(
                bitmap = image!!,
                contentDescription = stringResource(R.string.profile_qr_code),
                modifier = Modifier.size(257.dp).padding(16.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private fun qrBitmap(
    content: String,
    size: Int,
): Bitmap {
    val pixels =
        QrCodeEncoder.pixels(
            content = content,
            size = size,
            onColor = android.graphics.Color.BLACK,
            offColor = android.graphics.Color.WHITE,
        )
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrScannerSheet(
    onDismiss: () -> Unit,
    onScan: (String) -> Unit,
) {
    val context = LocalContext.current
    var scannerError by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted = granted
        }

    LaunchedEffect(Unit) {
        if (!permissionGranted) launcher.launch(Manifest.permission.CAMERA)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.scan), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                }
            }
            if (permissionGranted) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(520.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    CameraQrScanner(onScan = onScan, onError = { scannerError = it })
                    Text(
                        scannerError ?: stringResource(R.string.point_camera_at_profile_qr),
                        color = Color.White,
                        modifier =
                            Modifier
                                .padding(
                                    16.dp,
                                ).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            } else {
                Text(stringResource(R.string.camera_access_required), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.allow_camera))
                }
            }
        }
    }
}

@Composable
private fun CameraQrScanner(
    onScan: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = context.lifecycleOwner()
    val cameraLifecycleUnavailable = stringResource(R.string.camera_lifecycle_unavailable)
    val cameraUnavailable = stringResource(R.string.camera_unavailable)

    if (lifecycleOwner == null) {
        // Emit the error as a post-composition effect, not a side effect during
        // composition (which violates Compose's rules and can fire on every
        // recomposition). See #23.
        LaunchedEffect(cameraLifecycleUnavailable) {
            onError(cameraLifecycleUnavailable)
        }
        return
    }

    // Track the CameraX provider and ML Kit scanner so we can release them when
    // the QR sheet is dismissed. CameraX binds use cases to the host activity's
    // lifecycle, so without an explicit unbind the camera keeps streaming
    // (and the OS in-use indicator stays lit) until the activity stops. The
    // BarcodeScanner is Closeable and leaks native resources otherwise.
    //
    // `disposedRef` is a separate teardown signal so a late
    // ProcessCameraProvider.getInstance() callback (fired after the sheet
    // dismissed) can bail and clean up instead of binding into refs we just
    // nulled out. Using `null` to mean both "not yet set" and "torn down"
    // would let `compareAndSet(null, …)` succeed after onDispose, leaking the
    // camera again.
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val scannerRef = remember { AtomicReference<BarcodeScanner?>(null) }
    val disposedRef = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            disposedRef.set(true)
            runCatching { providerRef.getAndSet(null)?.unbindAll() }
            runCatching { scannerRef.getAndSet(null)?.close() }
        }
    }

    AndroidView(
        factory = { viewContext ->
            PreviewView(viewContext).also { previewView ->
                bindQrScannerCamera(
                    context,
                    lifecycleOwner,
                    previewView,
                    cameraUnavailable,
                    providerRef,
                    scannerRef,
                    disposedRef,
                    onScan,
                    onError,
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private tailrec fun Context.lifecycleOwner(): LifecycleOwner? =
    when (this) {
        is LifecycleOwner -> this
        is ContextWrapper -> baseContext.lifecycleOwner()
        else -> null
    }

// Compose's `LocalContext.current` is whatever the host wired in — often
// the Activity directly, but themed/wrapped contexts (test surfaces, custom
// theme wrappers) return a `ContextWrapper`. A direct `as? Activity` cast on
// those silently yields null, which for a FLAG_SECURE setter is the worst
// failure mode (looks like it works, doesn't). Walk the wrapper chain.
private tailrec fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

/**
 * Marks the host activity's window as secure for the duration of this
 * composition. `FLAG_SECURE` blocks the OS Recents/overview thumbnail,
 * screenshots, screen recording, and casting from capturing the window's
 * contents — the only practical protection for screens that handle the
 * nsec/private key, since `PasswordVisualTransformation` only masks
 * rendered glyphs. The flag is cleared on dispose so the rest of the
 * (non-sensitive) UI remains screenshottable as users expect.
 */
@Composable
private fun WindowSecureFlag() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.activity()?.window
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun bindQrScannerCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    cameraUnavailable: String,
    providerRef: AtomicReference<ProcessCameraProvider?>,
    scannerRef: AtomicReference<BarcodeScanner?>,
    disposedRef: AtomicBoolean,
    onScan: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(context)
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener(
        {
            val provider =
                runCatching { cameraProviderFuture.get() }.getOrElse {
                    onError(cameraUnavailable)
                    return@addListener
                }
            // If the sheet dismissed before this listener fired, the caller's
            // onDispose already ran and nulled the refs. Without disposedRef,
            // compareAndSet(null, provider) would succeed here and bind a
            // camera that nothing will ever unbind. Bail and clean up locally.
            if (disposedRef.get() || !providerRef.compareAndSet(null, provider)) {
                runCatching { provider.unbindAll() }
                return@addListener
            }
            val preview =
                Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            val scanner =
                BarcodeScanning.getClient(
                    BarcodeScannerOptions
                        .Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build(),
                )
            if (disposedRef.get() || !scannerRef.compareAndSet(null, scanner)) {
                runCatching { scanner.close() }
                runCatching { provider.unbindAll() }
                providerRef.set(null)
                return@addListener
            }
            val didScan = AtomicBoolean(false)
            val analyzerBusy = AtomicBoolean(false)
            val analysis =
                ImageAnalysis
                    .Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

            analysis.setAnalyzer(executor) { imageProxy ->
                if (!analyzerBusy.compareAndSet(false, true)) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    analyzerBusy.set(false)
                    imageProxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner
                    .process(image)
                    .addOnSuccessListener { codes ->
                        val raw = codes.firstOrNull { it.rawValue != null }?.rawValue
                        if (raw != null && didScan.compareAndSet(false, true)) onScan(raw)
                    }.addOnFailureListener {
                        onError(it.message ?: it.javaClass.simpleName)
                    }.addOnCompleteListener {
                        analyzerBusy.set(false)
                        imageProxy.close()
                    }
            }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure {
                // Failed before lifecycle binding could take over — the
                // composable's onDispose has nothing to unbind, so release
                // provider + scanner here instead of leaking them until the
                // sheet dismisses.
                runCatching { scannerRef.getAndSet(null)?.close() }
                runCatching { providerRef.getAndSet(null)?.unbindAll() }
                onError(it.message ?: it.javaClass.simpleName)
            }
        },
        executor,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
) {
    val active = appState.activeAccount
    var displayName by remember(active?.accountIdHex) { mutableStateOf("") }
    var about by remember(active?.accountIdHex) { mutableStateOf("") }
    var picture by remember(active?.accountIdHex) { mutableStateOf("") }
    var nip05 by remember(active?.accountIdHex) { mutableStateOf("") }
    var lud16 by remember(active?.accountIdHex) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(active?.accountIdHex) {
        val profile = active?.accountIdHex?.let { appState.loadUserProfile(it) }
        if (profile != null) {
            displayName = profile.displayName ?: profile.name ?: ""
            about = profile.about ?: ""
            picture = profile.picture ?: ""
            nip05 = profile.nip05 ?: ""
            lud16 = profile.lud16 ?: ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.preview)) {
                    if (active == null) {
                        Text(stringResource(R.string.no_active_account_period), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        ListItem(
                            leadingContent = {
                                Avatar(
                                    title = displayName.ifBlank { appState.shortNpub(active.accountIdHex) },
                                    seed = active.accountIdHex,
                                    size = 56.dp,
                                    pictureUrl = ProfileSanitizer.imageUrl(picture),
                                )
                            },
                            headlineContent = { Text(displayName.ifBlank { stringResource(R.string.anonymous) }) },
                            supportingContent = { Text(appState.shortNpub(active.accountIdHex), fontFamily = FontFamily.Monospace) },
                        )
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.profile)) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text(stringResource(R.string.display_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = about,
                        onValueChange = { about = it },
                        label = { Text(stringResource(R.string.about)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Client-side validation: flag a malformed picture URL or
                    // nip-05 (red field) and block publish so we don't push junk
                    // — or an SSRF-prone avatar URL — to relays. See #69.
                    val pictureValid = ProfileFieldValidation.isAcceptablePictureUrl(picture)
                    val nip05Valid = ProfileFieldValidation.isAcceptableNip05(nip05)
                    OutlinedTextField(
                        value = picture,
                        onValueChange = { picture = it },
                        label = { Text(stringResource(R.string.picture_url)) },
                        singleLine = true,
                        isError = !pictureValid,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Uri,
                            ),
                    )
                    OutlinedTextField(
                        value = nip05,
                        onValueChange = { nip05 = it },
                        label = { Text(stringResource(R.string.nip_05)) },
                        singleLine = true,
                        isError = !nip05Valid,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    )
                    OutlinedTextField(
                        value = lud16,
                        onValueChange = { lud16 = it },
                        label = { Text(stringResource(R.string.lightning)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    )
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                appState.publishProfile(
                                    UserProfileMetadataFfi(
                                        name = displayName.trim().ifBlank { null },
                                        displayName = displayName.trim().ifBlank { null },
                                        about = about.trim().ifBlank { null },
                                        picture = picture.trim().ifBlank { null },
                                        nip05 = nip05.trim().ifBlank { null },
                                        lud16 = lud16.trim().ifBlank { null },
                                    ),
                                )
                                busy = false
                            }
                        },
                        enabled = !busy && active != null && pictureValid && nip05Valid,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.publish_to_relays))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddIdentitySheet(
    appState: DarkMatterAppState,
    onDismiss: () -> Unit,
) {
    WindowSecureFlag()
    var identity by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val creatingIdentityDescription = stringResource(R.string.creating_identity)

    // ModalBottomSheet renders in its own window on Android, separate from
    // the host activity window — `WindowSecureFlag()` (which flags the
    // activity window) doesn't reach it. Set the sheet's own securePolicy
    // so the nsec field inside is also protected from Recents/screenshot
    // capture.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.add_account), style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            appState.createIdentity()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .size(18.dp)
                                .semantics { contentDescription = creatingIdentityDescription },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Key, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (busy) R.string.creating_identity_title else R.string.create_new_identity))
            }
            // Mask unless the value is unambiguously a public npub.
            // Treats partial / empty / unprefixed input as potentially secret,
            // so a pasted nsec is never rendered while the field is non-empty.
            // Keep `KeyboardType.Password` even when revealing the npub so the
            // IME stays opted out of suggestions / autofill / history.
            val maskSecret = !identity.trim().startsWith("npub1")
            OutlinedTextField(
                value = identity,
                onValueChange = { identity = it },
                label = { Text(stringResource(R.string.nsec_or_npub)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (maskSecret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            busy = true
                            scope.launch {
                                appState.importIdentity(identity)
                                busy = false
                            }
                        },
                    ),
            )
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        appState.importIdentity(identity)
                        busy = false
                    }
                },
                enabled = !busy && identity.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Person, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.import_existing_identity))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val active = appState.activeAccount

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.identity)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.identity)) {
                    if (active == null) {
                        Text(stringResource(R.string.no_active_account_period), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        DiagnosticRow(stringResource(R.string.display_name), appState.displayName(active.accountIdHex))
                        CopyableValueRow(
                            label = stringResource(R.string.public_key),
                            display = IdentityFormatter.short(active.accountIdHex),
                            value = active.accountIdHex,
                            clipboard = clipboard,
                            appState = appState,
                        )
                        CopyableValueRow(
                            label = "npub",
                            display = appState.shortNpub(active.accountIdHex),
                            value = appState.npub(active.accountIdHex),
                            clipboard = clipboard,
                            appState = appState,
                        )
                        DiagnosticRow(stringResource(R.string.local_signing), stringResource(if (active.localSigning) R.string.yes else R.string.no))
                        DiagnosticRow(stringResource(R.string.status), stringResource(if (active.running) R.string.online else R.string.idle))
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.account_session)) {
                    Text(
                        stringResource(R.string.sign_out_session_help),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            appState.signOutActiveAccount()
                            appState.present(R.string.toast_signed_out)
                        },
                        enabled = active != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sign_out_of_this_account))
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyableValueRow(
    label: String,
    display: String,
    value: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    appState: DarkMatterAppState,
) {
    Row(
        Modifier.fillMaxWidth().clickable {
            clipboard.setText(AnnotatedString(value))
            appState.presentText(AppText.Resource(R.string.toast_copied_value, listOf(label)))
        },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(display, fontFamily = FontFamily.Monospace)
            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaysScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
) {
    var pendingUrl by remember { mutableStateOf("") }
    var lists by remember(appState.activeAccountRef) { mutableStateOf<AccountRelayListsFfi?>(null) }
    var selectedKind by remember { mutableStateOf(RelayListKind.Nip65) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reloadLists() {
        lists = appState.accountRelayLists()
    }

    LaunchedEffect(appState.activeAccountRef) {
        reloadLists()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.relays)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { reloadLists() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.account_relay_lists)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        relayListKinds.forEach { option ->
                            FilterChip(
                                selected = selectedKind == option,
                                onClick = { selectedKind = option },
                                label = { Text(stringResource(option.labelRes)) },
                            )
                        }
                    }

                    val currentRelays = lists?.relaysFor(selectedKind).orEmpty()
                    if (currentRelays.isEmpty()) {
                        Text(stringResource(R.string.no_relays), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    currentRelays.forEach { relay ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(relay, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                            IconButton(
                                onClick = {
                                    saving = true
                                    scope.launch {
                                        lists = appState.setAccountRelays(selectedKind, currentRelays - relay) ?: appState.accountRelayLists()
                                        saving = false
                                    }
                                },
                                enabled = !saving && currentRelays.size > 1,
                            ) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_relay))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = pendingUrl,
                            onValueChange = { pendingUrl = it },
                            label = { Text("wss://relay.example.com") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions =
                                KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Uri,
                                ),
                        )
                        IconButton(
                            onClick = {
                                val trimmed = pendingUrl.trim()
                                saving = true
                                scope.launch {
                                    lists = appState.setAccountRelays(selectedKind, currentRelays + trimmed) ?: appState.accountRelayLists()
                                    pendingUrl = ""
                                    saving = false
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            enabled =
                                pendingUrl.trim().let {
                                    !saving &&
                                        appState.activeAccountRef != null &&
                                        isAcceptableRelayUrl(it) &&
                                        !currentRelays.contains(it)
                                },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_relay))
                        }
                    }
                }
            }
            item {
                PublishedRelayLists(lists)
            }
        }
    }
}

private val relayListKinds =
    listOf(
        RelayListKind.Nip65,
        RelayListKind.Inbox,
    )

private val RelayListKind.labelRes: Int
    get() =
        when (this) {
            RelayListKind.Nip65 -> R.string.nip_65
            RelayListKind.Inbox -> R.string.inbox
        }

private fun AccountRelayListsFfi.relaysFor(kind: RelayListKind): List<String> =
    when (kind) {
        RelayListKind.Nip65 -> nip65.relays
        RelayListKind.Inbox -> inbox.relays
    }

@Composable
private fun PublishedRelayLists(lists: AccountRelayListsFfi?) {
    SectionCard(title = stringResource(R.string.published_relay_lists)) {
        if (lists == null) {
            Text(stringResource(R.string.no_relay_projection), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        RelayListRow(stringResource(R.string.nip_65), lists.nip65)
        RelayListRow(stringResource(R.string.inbox), lists.inbox)
        if (lists.complete) {
            Text(stringResource(R.string.all_relay_lists_published), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.missing_relay_lists, lists.missing.joinToString(", ")), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RelayListRow(
    title: String,
    list: RelayListFfi,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text("${list.relays.size}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (list.relays.isEmpty()) {
            Text(stringResource(R.string.not_published), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            list.relays.forEach { relay ->
                Text(relay, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyPackagesScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var packages by remember(appState.activeAccountRef) { mutableStateOf<List<AccountKeyPackageFfi>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var loaded by remember(appState.activeAccountRef) { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AccountKeyPackageFfi?>(null) }

    suspend fun reload(refreshFromNetwork: Boolean = false) {
        loading = true
        try {
            packages = appState.fetchKeyPackages(refreshFromNetwork = refreshFromNetwork)
            loaded = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(appState.activeAccountRef) {
        if (appState.activeAccountRef != null) reload()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.key_packages)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { scope.launch { reload(refreshFromNetwork = true) } },
                        enabled = !loading && !working && appState.activeAccountRef != null,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionCard(title = stringResource(R.string.publishing)) {
                    Text(
                        stringResource(R.string.key_package_publishing_help),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                working = true
                                scope.launch {
                                    try {
                                        appState.republishKeyPackage()
                                        reload(refreshFromNetwork = true)
                                    } finally {
                                        working = false
                                    }
                                }
                            },
                            enabled = !working && !loading && appState.activeAccountRef != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.republish))
                        }
                        Button(
                            onClick = {
                                working = true
                                scope.launch {
                                    try {
                                        appState.publishNewKeyPackage()
                                        reload(refreshFromNetwork = true)
                                    } finally {
                                        working = false
                                    }
                                }
                            },
                            enabled = !working && !loading && appState.activeAccountRef != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.publish_new))
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.published), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (loading) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (loaded && packages.isEmpty() && !loading) {
                item {
                    SectionCard(title = stringResource(R.string.no_key_packages_found)) {
                        Text(
                            stringResource(R.string.no_key_packages_found_help),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(packages, key = { index, kp -> "${kp.eventIdHex}:$index" }) { _, kp ->
                KeyPackageCard(
                    kp = kp,
                    busy = working,
                    onDelete = { pendingDelete = kp },
                )
            }
        }
    }

    pendingDelete?.let { kp ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_key_package_question)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.delete_key_package_help),
                    )
                    Text(
                        stringResource(R.string.event_value, IdentityFormatter.short(kp.eventIdHex)),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val target = pendingDelete ?: return@Button
                    pendingDelete = null
                    working = true
                    scope.launch {
                        try {
                            appState.deleteKeyPackage(target.eventIdHex, target.sourceRelays)
                            reload(refreshFromNetwork = true)
                        } finally {
                            working = false
                        }
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun KeyPackageCard(
    kp: AccountKeyPackageFfi,
    busy: Boolean,
    onDelete: () -> Unit,
) {
    val localLabel = stringResource(R.string.local)
    val relayLabel = stringResource(R.string.relay)
    val unknownLabel = stringResource(R.string.unknown)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        IdentityFormatter.short(kp.keyPackageId),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        formatPublishedAt(kp.publishedAt, stringResource(R.string.unknown_publish_time), stringResource(R.string.published_at)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_key_package))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keyPackageSourceLabels(kp, localLabel, relayLabel, unknownLabel).forEach { label ->
                    AssistChip(onClick = {}, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                }
            }
            DiagnosticRow(stringResource(R.string.event), IdentityFormatter.short(kp.eventIdHex))
            DiagnosticRow(stringResource(R.string.ref), IdentityFormatter.short(kp.keyPackageRefHex))
            DiagnosticRow(stringResource(R.string.size), stringResource(R.string.bytes_count, kp.keyPackageBytes.toLong()))
            if (kp.sourceRelays.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.source_relays), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    kp.sourceRelays.forEach { relay ->
                        Text(relay, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun keyPackageSourceLabels(
    kp: AccountKeyPackageFfi,
    localLabel: String,
    relayLabel: String,
    unknownLabel: String,
): List<String> {
    val out = mutableListOf<String>()
    if (kp.local) out += localLabel
    if (kp.relay) out += relayLabel
    if (out.isEmpty()) out += unknownLabel
    return out
}

private val publishedAtFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())

private fun formatPublishedAt(
    unixSeconds: ULong,
    unknown: String,
    format: String,
): String {
    if (unixSeconds == 0uL) return unknown
    // ULong > Long.MAX_VALUE wraps to a negative epoch; Instant then rejects
    // anything below Instant.MIN. Garbage from a malicious relay shouldn't
    // crash the KeyPackage screen — fall back to "unknown" instead.
    if (unixSeconds > Long.MAX_VALUE.toULong()) return unknown
    val instant = runCatching { Instant.ofEpochSecond(unixSeconds.toLong()) }.getOrNull() ?: return unknown
    return String.format(format, publishedAtFormatter.format(instant))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(
    appState: DarkMatterAppState,
    onBackToChats: () -> Unit,
) {
    var health by remember { mutableStateOf<RelayHealthFfi?>(null) }
    var entries by remember { mutableStateOf<List<DiagnosticLogEntry>>(emptyList()) }
    var streaming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sentPingFormat = stringResource(R.string.diagnostic_sent_ping_to_self)
    val sendToSelfFailedFormat = stringResource(R.string.diagnostic_send_to_self_failed)

    fun appendLog(text: String) {
        entries = (entries + DiagnosticLogEntry(text = text)).takeLast(500)
    }

    LaunchedEffect(Unit) {
        streaming = true
        val subscription = appState.marmotIo { subscribeEvents() }
        try {
            while (true) {
                val event =
                    withContext(Dispatchers.IO) {
                        subscription.next()
                    } ?: break
                entries = (entries + DiagnosticLogEntry(text = DiagnosticFormatter.describe(event))).takeLast(500)
            }
        } catch (throwable: Throwable) {
            entries = (entries + DiagnosticLogEntry(text = "event stream failed: ${throwable.message ?: throwable.javaClass.simpleName}")).takeLast(500)
        } finally {
            streaming = false
            withContext(Dispatchers.IO) {
                subscription.destroy()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = onBackToChats) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_to_chats))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { health = appState.marmotIo { relayHealth() } }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val account = appState.activeAccountRef ?: return@launch
                                runCatching {
                                    val groupId =
                                        appState.marmotIo {
                                            createGroup(
                                                account,
                                                "diagnostic-${System.currentTimeMillis() / 1000L}",
                                                emptyList(),
                                                null,
                                            )
                                        }
                                    appState.marmotIo { sendText(account, groupId, "ping at ${System.currentTimeMillis() / 1000L}") }
                                    // Archive the throwaway diagnostic group so it doesn't
                                    // accumulate as an orphan in the chat list on every click. See #70.
                                    appState.marmotIo { setGroupArchived(account, groupId, true) }
                                    appendLog(String.format(sentPingFormat, IdentityFormatter.short(groupId)))
                                }.onFailure {
                                    appendLog(String.format(sendToSelfFailedFormat, it.message ?: it.javaClass.simpleName))
                                }
                            }
                        },
                        enabled = appState.activeAccountRef != null,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.send_to_self))
                    }
                    OutlinedButton(onClick = { entries = emptyList() }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.clear))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(if (streaming) R.string.live else R.string.idle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard(title = stringResource(R.string.relay_health)) {
                    if (health == null) {
                        Text(stringResource(R.string.no_relay_snapshot_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { scope.launch { health = appState.marmotIo { relayHealth() } } }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.refresh))
                        }
                    } else {
                        health?.let { relay ->
                            DiagnosticRow(stringResource(R.string.total), relay.totalRelays.toString())
                            DiagnosticRow(stringResource(R.string.connected), relay.connected.toString())
                            DiagnosticRow(stringResource(R.string.connecting), relay.connecting.toString())
                            DiagnosticRow(stringResource(R.string.disconnected), relay.disconnected.toString())
                            DiagnosticRow(stringResource(R.string.attempts), relay.connectionAttempts.toString())
                            DiagnosticRow(stringResource(R.string.successes), relay.connectionSuccesses.toString())
                        }
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.runtime)) {
                    DiagnosticRow(stringResource(R.string.active_account), appState.activeAccountRef ?: stringResource(R.string.none))
                    DiagnosticRow(stringResource(R.string.accounts), appState.accounts.size.toString())
                    DiagnosticRow(stringResource(R.string.bootstrap_relays), appState.bootstrapRelayCount().toString())
                }
            }
            // Event log: emitted as top-level lazy items (keyed by the entry's
            // id) rather than a forEach inside a single item, so the up-to-500
            // rows are actually virtualized instead of all composing at once.
            // See #35.
            item {
                Text(
                    stringResource(R.string.event_log),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (entries.isEmpty()) {
                item {
                    Text(stringResource(R.string.waiting_for_events), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            IdentityFormatter.relativeTime(entry.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(entry.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SectionCardWithAction(
    title: String,
    action: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                action()
            }
            content()
        }
    }
}

@Composable
private fun ErrorContent(
    title: String,
    message: String,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(40.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Avatar(
    title: String,
    seed: String,
    size: androidx.compose.ui.unit.Dp,
    pictureUrl: String? = null,
) {
    val palette =
        listOf(
            Color(0xFF006A6A),
            Color(0xFF8C4A00),
            Color(0xFF5B5FC7),
            Color(0xFF006D3B),
            Color(0xFF9A4055),
        )
    val color = palette[avatarPaletteIndex(seed.hashCode(), palette.size)]
    // Seed from the in-memory cache so re-entering a screen shows an
    // already-loaded avatar immediately, with no placeholder flash and no
    // re-fetch. See #31.
    val image by produceState(AvatarImageLoader.peek(pictureUrl), pictureUrl) {
        val url =
            pictureUrl ?: run {
                value = null
                return@produceState
            }
        if (value == null) value = AvatarImageLoader.load(url)
    }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                IdentityFormatter.initials(title),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

internal fun avatarPaletteIndex(
    seedHash: Int,
    paletteSize: Int,
): Int = Math.floorMod(seedHash, paletteSize)

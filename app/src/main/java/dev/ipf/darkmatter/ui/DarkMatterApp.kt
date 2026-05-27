package dev.ipf.darkmatter.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.emoji2.emojipicker.EmojiPickerView
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.ipf.darkmatter.R
import dev.ipf.darkmatter.core.DiagnosticFormatter
import dev.ipf.darkmatter.core.GroupProjector
import dev.ipf.darkmatter.core.GroupTitleCopy
import dev.ipf.darkmatter.core.IdentityFormatter
import dev.ipf.darkmatter.core.MessageProjector
import dev.ipf.darkmatter.core.MessageTextCopy
import dev.ipf.darkmatter.core.ProfileLink
import dev.ipf.darkmatter.core.ProfileSanitizer
import dev.ipf.darkmatter.core.QrCodeEncoder
import dev.ipf.darkmatter.core.RecipientReference
import dev.ipf.darkmatter.core.RecentEmojiList
import dev.ipf.darkmatter.core.ReplySwipe
import dev.ipf.darkmatter.state.AppPhase
import dev.ipf.darkmatter.state.AppText
import dev.ipf.darkmatter.state.AppThemeMode
import dev.ipf.darkmatter.state.ChatListItem
import dev.ipf.darkmatter.state.ChatsController
import dev.ipf.darkmatter.state.ConversationControllerCopy
import dev.ipf.darkmatter.state.ConversationController
import dev.ipf.darkmatter.state.DarkMatterAppState
import dev.ipf.darkmatter.state.MessageStatus
import dev.ipf.darkmatter.state.OutgoingMessageIndicator
import dev.ipf.darkmatter.state.RelayListKind
import dev.ipf.darkmatter.state.TimelineMessage
import dev.ipf.darkmatter.state.outgoingIndicator
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.LifecycleOwner
import dev.ipf.marmotkit.AccountKeyPackageFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.RelayHealthFfi
import dev.ipf.marmotkit.RelayListFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.marmotkit.MarmotKitException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

private val languageOptions = listOf(
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
private fun rememberGroupTitleCopy(): GroupTitleCopy {
    return GroupTitleCopy(
        inviteFromFormat = stringResource(R.string.group_title_invite_from),
        groupOfPeopleFormat = stringResource(R.string.group_title_people_count),
    )
}

@Composable
private fun rememberMessageTextCopy(): MessageTextCopy {
    return MessageTextCopy(
        reactedFormat = stringResource(R.string.message_reacted),
        reactionFallback = stringResource(R.string.message_reaction_fallback),
        deleted = stringResource(R.string.message_deleted_preview),
        agentStreamStarted = stringResource(R.string.agent_stream_started),
        streamFinished = stringResource(R.string.stream_finished),
        mediaAttachment = stringResource(R.string.media_attachment),
        message = stringResource(R.string.generic_message),
    )
}

@Composable
private fun rememberConversationControllerCopy(): ConversationControllerCopy {
    return ConversationControllerCopy(
        waitingForStream = stringResource(R.string.waiting_for_stream),
        streamFailedFormat = stringResource(R.string.stream_failed_format),
    )
}

private val AppThemeMode.labelRes: Int
    @StringRes
    get() = when (this) {
        AppThemeMode.System -> R.string.theme_system
        AppThemeMode.Light -> R.string.theme_light
        AppThemeMode.Dark -> R.string.theme_dark
    }

@Composable
fun DarkMatterApp(
    appState: DarkMatterAppState,
    inboundProfilePayload: String? = null,
    onProfilePayloadHandled: (String) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val toast = appState.toast
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        appState.bootstrap()
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
                AppPhase.Ready -> MainShell(appState)
                is AppPhase.Failed -> FailureScreen(
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
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        snackbar = snackbar,
    )
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.loading_dark_matter), style = MaterialTheme.typography.bodyMedium)
        }
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
                        modifier = Modifier
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
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
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
private fun MainShell(appState: DarkMatterAppState) {
    var sectionName by rememberSaveable { mutableStateOf(MainSection.Chats.name) }
    var settingsDetailName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedChat by remember { mutableStateOf<ChatListItem?>(null) }
    var profileQrAccountId by remember { mutableStateOf<String?>(null) }
    val chatsController = remember(appState.activeAccountRef) { ChatsController(appState) }
    val section = runCatching { MainSection.valueOf(sectionName) }.getOrDefault(MainSection.Chats)
    val settingsDetail = settingsDetailName?.let { runCatching { SettingsDetail.valueOf(it) }.getOrNull() }

    LaunchedEffect(chatsController, appState.activeAccountRef) {
        chatsController.bind(appState.activeAccountRef)
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
        MainSection.Chats -> ChatsScreen(
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
        MainSection.Settings -> SettingsScreen(
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
        MainSection.Diagnostics -> DiagnosticsScreen(
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
        modifier = modifier
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
                visibleItems.isEmpty() && showArchived -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_archived_chats), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                visibleItems.isEmpty() -> EmptyChats(onCreate = {
                    newChatTitle = R.string.new_chat
                    showNewChat = true
                })
                else -> LazyColumn(Modifier.fillMaxSize()) {
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
                    contentDescription = stringResource(
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
    val title = GroupProjector.displayTitle(
        group = item.group,
        otherMemberAccount = item.otherMemberAccount,
        memberCount = item.memberCount,
        memberTitle = { appState.chatMemberTitle(it) },
        copy = groupTitleCopy,
    )
    val inviteAccount = GroupProjector.inviteAccount(item.group, item.otherMemberAccount)
    val avatarAccount = inviteAccount
        ?: item.otherMemberAccount.takeIf { item.group.name.isBlank() && item.memberCount == 2 }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Avatar(
                title = title,
                seed = avatarAccount ?: item.group.groupIdHex,
                size = 44.dp,
                pictureUrl = avatarAccount?.let { appState.avatarUrl(it) },
            )
        },
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                if (item.group.pendingConfirmation) {
                    stringResource(R.string.invitation)
                } else {
                    MessageProjector.previewText(
                        message = item.latest,
                        copy = messageTextCopy,
                        empty = stringResource(R.string.no_messages_yet),
                    )
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    IdentityFormatter.relativeTime(item.latest?.recordedAt ?: 0uL),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.group.pendingConfirmation) {
                    Badge { Text(stringResource(R.string.invite)) }
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

    fun createGroupErrorMessage(throwable: Throwable): String {
        return when (throwable) {
            is MarmotKitException.MissingKeyPackage ->
                missingKeyPackageError
            is MarmotKitException.InvalidIdentity ->
                invalidIdentityReferenceError
            is MarmotKitException.Publish ->
                String.format(groupPublishFailedFormat, throwable.details)
            else -> throwable.message ?: throwable.javaClass.simpleName
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleLarge)
            if (members.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    members.take(3).forEach { member ->
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
                    keyboardOptions = KeyboardOptions(
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
                    val normalizedPending = pendingRecipients.map { input ->
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
                    scope.launch {
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
                enabled = !busy && (members.isNotEmpty() || pending.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Check, contentDescription = null)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationScreen(
    appState: DarkMatterAppState,
    chat: ChatListItem,
    onBack: () -> Unit,
) {
    val controllerCopy = rememberConversationControllerCopy()
    val controller = remember(chat.id) {
        ConversationController(
            appState = appState,
            initialGroup = chat.group,
            initialMemberSnapshot = chat.memberSnapshot
                ?: appState.cachedGroupMemberSnapshot(appState.activeAccountRef, chat.group.groupIdHex),
            copy = controllerCopy,
        )
    }
    var menuOpen by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val groupTitleCopy = rememberGroupTitleCopy()
    val messageTextCopy = rememberMessageTextCopy()
    var recentReactionEmojis by remember(context) {
        mutableStateOf(RecentEmojiPreferences.load(context))
    }

    fun recordReactionEmoji(emoji: String) {
        recentReactionEmojis = RecentEmojiPreferences.recordPicked(context, emoji)
    }

    BackHandler(enabled = !showDetails) {
        onBack()
    }

    LaunchedEffect(controller) {
        controller.start()
    }
    LaunchedEffect(controller.timeline.lastOrNull()?.id, imeBottom) {
        if (controller.timeline.isNotEmpty()) {
            listState.scrollToItem(controller.timeline.size + 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
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
                            text = { Text(stringResource(R.string.details)) },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                showDetails = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(if (controller.group.archived) R.string.unarchive else R.string.archive)) },
                            leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                scope.launch { controller.setArchived(!controller.group.archived) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.leave)) },
                            leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    if (controller.leaveGroup()) onBack()
                                }
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (controller.error == null && !controller.group.pendingConfirmation) {
                ComposerBar(
                    replyingTo = controller.replyingTo,
                    sendInFlight = controller.sendInFlight,
                    messageTextCopy = messageTextCopy,
                    onCancelReply = { controller.replyingTo = null },
                    onSend = { scope.launch { controller.send(it) } },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                controller.isLoading && controller.timeline.isEmpty() -> LoadingScreen()
                controller.error != null -> ErrorContent(stringResource(R.string.couldnt_load_conversation), controller.error.orEmpty())
                controller.group.pendingConfirmation -> PendingInviteContent(
                    title = controller.title(groupTitleCopy),
                    pictureUrl = controller.inviteAccount?.let { appState.avatarUrl(it) },
                    avatarSeed = controller.inviteAccount ?: controller.group.groupIdHex,
                    onAccept = {
                        scope.launch { controller.acceptInvite() }
                    },
                    onDecline = {
                        scope.launch {
                            if (controller.declineInvite()) onBack()
                        }
                    },
                )
                controller.timeline.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_messages_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(controller.timeline, key = { it.id }) { item ->
                        MessageBubble(
                            item = item,
                            controller = controller,
                            appState = appState,
                            recentReactionEmojis = recentReactionEmojis,
                            onReactionEmojiPicked = ::recordReactionEmoji,
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    if (showDetails) {
        GroupDetailsSheet(
            appState = appState,
            controller = controller,
            onDismiss = { showDetails = false },
            onLeft = onBack,
        )
    }
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
private fun GroupDetailsSheet(
    appState: DarkMatterAppState,
    controller: ConversationController,
    onDismiss: () -> Unit,
    onLeft: () -> Unit,
) {
    var name by remember(controller.group.groupIdHex, controller.group.name) { mutableStateOf(controller.group.name) }
    var description by remember(controller.group.groupIdHex, controller.group.description) { mutableStateOf(controller.group.description) }
    var pendingMember by remember { mutableStateOf("") }
    var pendingMemberError by remember { mutableStateOf<String?>(null) }
    var showMemberScanner by remember { mutableStateOf(false) }
    var mlsState by remember(controller.group.groupIdHex) { mutableStateOf<AppGroupMlsStateFfi?>(null) }
    var mlsLoading by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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

    fun runGroupMutation(mutation: suspend () -> Unit) {
        scope.launch {
            try {
                mutation()
            } finally {
                refreshMlsDetails()
            }
        }
    }

    LaunchedEffect(
        appState.developerMode,
        controller.group.groupIdHex,
        controller.group.name,
        controller.group.description,
        controller.group.admins,
        controller.members.map { it.memberIdHex },
    ) {
        refreshMlsDetails()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(title = controller.title(groupTitleCopy), seed = controller.group.groupIdHex, size = 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(controller.title(groupTitleCopy), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        controller.subtitle(
                            justYou = stringResource(R.string.just_you),
                            oneMember = stringResource(R.string.one_member),
                            membersFormat = stringResource(R.string.members_count),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(title = stringResource(R.string.profile)) {
                if (controller.isSelfAdmin) {
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
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            busy = true
                            runGroupMutation {
                                try {
                                    controller.updateGroupProfile(name, description)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.save_group))
                    }
                } else {
                    Text(controller.group.description.ifBlank { stringResource(R.string.no_description) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionCard(title = stringResource(R.string.info)) {
                DiagnosticRow(stringResource(R.string.group_id), IdentityFormatter.short(controller.group.groupIdHex))
                DiagnosticRow(stringResource(R.string.nostr_group), IdentityFormatter.short(controller.group.nostrGroupIdHex))
                DiagnosticRow(stringResource(R.string.relays), controller.group.relays.size.toString())
                controller.group.relays.forEach { relay ->
                    Text(relay, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionCard(title = stringResource(R.string.members)) {
                controller.members.forEach { member ->
                    GroupMemberRow(
                        member = member,
                        controller = controller,
                        onPromote = {
                            runGroupMutation { controller.setMemberAdmin(member, admin = true) }
                        },
                        onDemote = {
                            runGroupMutation { controller.setMemberAdmin(member, admin = false) }
                        },
                        onRemove = {
                            runGroupMutation { controller.removeMember(member) }
                        },
                    )
                }
                if (controller.isSelfAdmin) {
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
                        supportingText = pendingMemberError?.let { message ->
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
                            val ref = RecipientReference.normalize(pendingMember) ?: run {
                                pendingMemberError = oneValidMemberReferenceError
                                return@Button
                            }
                            pendingMember = ""
                            pendingMemberError = null
                            runGroupMutation { controller.inviteMembers(listOf(ref)) }
                        },
                        enabled = pendingMember.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_member))
                    }
                }
            }

            SectionCard(title = stringResource(R.string.actions)) {
                OutlinedButton(
                    onClick = { scope.launch { controller.setArchived(!controller.group.archived) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (controller.group.archived) R.string.unarchive_chat else R.string.archive_chat))
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            if (controller.leaveGroup()) {
                                onDismiss()
                                onLeft()
                            }
                        }
                    },
                    enabled = controller.canLeaveGroup,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.leave_chat))
                }
                if (!controller.canLeaveGroup) {
                    Text(stringResource(R.string.promote_admin_before_leaving), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
}

@Composable
private fun GroupMemberRow(
    member: AppGroupMemberRecordFfi,
    controller: ConversationController,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val isAdmin = controller.isAdmin(member)
    val canManage = controller.isSelfAdmin && !member.local

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
            if (isAdmin) {
                Text(
                    stringResource(R.string.admin),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (canManage) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.member_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (isAdmin) R.string.remove_admin else R.string.make_admin)) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            if (isAdmin) onDemote() else onPromote()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_member)) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    item: TimelineMessage,
    controller: ConversationController,
    appState: DarkMatterAppState,
    recentReactionEmojis: List<String>,
    onReactionEmojiPicked: (String) -> Unit,
) {
    val record = item.record
    val mine = MessageProjector.isMine(record, appState.activeAccount?.accountIdHex)
    val deleted = MessageProjector.isDeleted(record.messageIdHex, controller.deletedMessageIds)
    val bubbleColor = when {
        deleted -> MaterialTheme.colorScheme.surfaceVariant
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
    val displayedBody = if (deleted) {
        stringResource(R.string.message_deleted)
    } else {
        MessageProjector.displayBody(record, messageTextCopy)
    }
    val showSenderAvatar = GroupProjector.shouldShowTranscriptSenderAvatar(
        memberCount = controller.members.size,
        mine = mine,
    )
    val timestampColor = MaterialTheme.colorScheme.onSurfaceVariant
    var emojiPickerOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    val quickReactionEmojis = RecentEmojiList.quickChoices(recentReactionEmojis)
    fun beginReply() {
        controller.replyingTo = record
        menuOpen = false
    }

    fun reactWithEmoji(emoji: String) {
        onReactionEmojiPicked(emoji)
        scope.launch { controller.toggleReaction(emoji, record) }
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
                Avatar(
                    title = appState.displayName(record.sender),
                    seed = record.sender,
                    size = 32.dp,
                    pictureUrl = appState.avatarUrl(record.sender),
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(
                modifier = Modifier.widthIn(max = bubbleColumnMaxWidth),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                Surface(
                    modifier = Modifier
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
                        }
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { menuOpen = true },
                        ),
                    color = bubbleColor,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = if (mine) 1.dp else 0.dp,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!mine) {
                            Text(
                                appState.displayName(record.sender),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        controller.replyPreview(record, messageTextCopy)?.let { (name, body) ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Column(Modifier.padding(8.dp)) {
                                    Text(name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                    Text(body, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        Text(
                            displayedBody,
                            style = MaterialTheme.typography.bodyLarge,
                        )
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
                                onDelete = {
                                    menuOpen = false
                                    scope.launch { controller.deleteMessage(record) }
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
                val tallies = controller.reactions[record.messageIdHex].orEmpty()
                if (tallies.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        tallies.forEach { tally ->
                            FilterChip(
                                selected = tally.mine,
                                onClick = { scope.launch { controller.toggleReaction(tally.emoji, record) } },
                                label = { Text("${tally.emoji} ${tally.count}") },
                            )
                        }
                    }
                }
            }
        }
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
private fun OutgoingMessageStatusIcon(status: MessageStatus, tint: Color) {
    val indicator = status.outgoingIndicator() ?: return
    when (indicator) {
        OutgoingMessageIndicator.Sending -> SendingMessageIcon(
            tint = tint.copy(alpha = 0.76f),
        )
        OutgoingMessageIndicator.Sent -> Icon(
            imageVector = Icons.Default.Check,
            contentDescription = stringResource(R.string.sent),
            modifier = Modifier.size(14.dp),
            tint = tint,
        )
        OutgoingMessageIndicator.Failed -> Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = stringResource(R.string.send_failed),
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SendingMessageIcon(tint: Color) {
    val sendingDescription = stringResource(R.string.sending)
    Canvas(
        modifier = Modifier
            .size(14.dp)
            .semantics { contentDescription = sendingDescription },
    ) {
        val strokeWidth = 1.35.dp.toPx()
        val radius = size.minDimension / 2f - strokeWidth / 2f
        val dash = floatArrayOf(2.4.dp.toPx(), 1.8.dp.toPx())
        drawCircle(
            color = tint,
            radius = radius,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(dash),
            ),
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.29f, size.height * 0.53f),
            end = Offset(size.width * 0.43f, size.height * 0.67f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.43f, size.height * 0.67f),
            end = Offset(size.width * 0.72f, size.height * 0.34f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ComposerBar(
    replyingTo: AppMessageRecordFfi?,
    sendInFlight: Boolean,
    messageTextCopy: MessageTextCopy,
    onCancelReply: () -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
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
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
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
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.message)) },
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    },
                ),
            )
            FloatingActionButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
                modifier = Modifier.size(52.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                if (sendInFlight) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
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
    when (detail) {
        SettingsDetail.Appearance -> AppearanceScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Profile -> ProfileEditScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Identity -> IdentityScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Relays -> RelaysScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.KeyPackages -> KeyPackagesScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Notifications -> NotificationsScreen(appState, onBack = { onDetailChange(null) })
        null -> SettingsHomeScreen(
            appState = appState,
            onBackToChats = onBackToChats,
            onOpenDiagnostics = onOpenDiagnostics,
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
    onOpenDiagnostics: () -> Unit,
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
                    SettingsRow(stringResource(R.string.identity_and_keys), stringResource(R.string.identity_settings_subtitle)) { onOpenDetail(SettingsDetail.Identity) }
                    SettingsRow(stringResource(R.string.relays), stringResource(R.string.relays_settings_subtitle)) { onOpenDetail(SettingsDetail.Relays) }
                    SettingsRow(stringResource(R.string.key_packages), stringResource(R.string.key_packages_settings_subtitle)) { onOpenDetail(SettingsDetail.KeyPackages) }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.app_preferences)) {
                    SettingsRow(stringResource(R.string.appearance), stringResource(R.string.appearance_settings_subtitle)) { onOpenDetail(SettingsDetail.Appearance) }
                    SettingsRow(stringResource(R.string.notifications), stringResource(R.string.notifications_settings_subtitle)) { onOpenDetail(SettingsDetail.Notifications) }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.developer)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.developer_mode), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.developer_mode_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = appState.developerMode,
                            onCheckedChange = { appState.updateDeveloperMode(it) },
                        )
                    }
                    if (appState.developerMode) {
                        SettingsRow(stringResource(R.string.diagnostics), stringResource(R.string.diagnostics_settings_subtitle)) { onOpenDiagnostics() }
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
private fun AppearanceScreen(appState: DarkMatterAppState, onBack: () -> Unit) {
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
private fun NotificationsScreen(appState: DarkMatterAppState, onBack: () -> Unit) {
    var pendingNotificationEnable by remember { mutableStateOf(false) }
    var pendingBackgroundConnectionEnable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
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
        modifier = Modifier
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
                        modifier = Modifier.clickable {
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
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
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
                        val sendIntent = Intent(Intent.ACTION_SEND)
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
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(npub) {
        val resolved = appState.accountIdHex(npub)
        hex = resolved
        if (resolved != null) appState.refreshProfile(resolved)
    }

    val title = hex?.let { appState.displayName(it) } ?: IdentityFormatter.short(npub)
    val isSelf = hex?.let { resolved -> appState.accounts.any { it.accountIdHex == resolved } } == true

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
                onClick = {
                    creating = true
                    scope.launch {
                        if (appState.startProfileChat(npub)) onDismiss()
                        creating = false
                    }
                },
                enabled = !creating && hex != null && appState.activeAccountRef != null && !isSelf,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (creating) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Group, contentDescription = null)
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

private fun qrBitmap(content: String, size: Int): Bitmap {
    val matrix = QrCodeEncoder.matrix(content, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
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
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
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
                    Modifier.fillMaxWidth().height(520.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    CameraQrScanner(onScan = onScan, onError = { scannerError = it })
                    Text(
                        scannerError ?: stringResource(R.string.point_camera_at_profile_qr),
                        color = Color.White,
                        modifier = Modifier.padding(16.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
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
        onError(cameraLifecycleUnavailable)
        return
    }

    AndroidView(
        factory = { viewContext ->
            PreviewView(viewContext).also { previewView ->
                bindQrScannerCamera(context, lifecycleOwner, previewView, cameraUnavailable, onScan, onError)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private tailrec fun Context.lifecycleOwner(): LifecycleOwner? {
    return when (this) {
        is LifecycleOwner -> this
        is ContextWrapper -> baseContext.lifecycleOwner()
        else -> null
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun bindQrScannerCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    cameraUnavailable: String,
    onScan: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(context)
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener(
        {
            val provider = runCatching { cameraProviderFuture.get() }.getOrElse {
                onError(cameraUnavailable)
                return@addListener
            }
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build(),
            )
            val didScan = AtomicBoolean(false)
            val analyzerBusy = AtomicBoolean(false)
            val analysis = ImageAnalysis.Builder()
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
                scanner.process(image)
                    .addOnSuccessListener { codes ->
                        val raw = codes.firstOrNull { it.rawValue != null }?.rawValue
                        if (raw != null && didScan.compareAndSet(false, true)) onScan(raw)
                    }
                    .addOnFailureListener {
                        onError(it.message ?: it.javaClass.simpleName)
                    }
                    .addOnCompleteListener {
                        analyzerBusy.set(false)
                        imageProxy.close()
                    }
            }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure {
                onError(it.message ?: it.javaClass.simpleName)
            }
        },
        executor,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditScreen(appState: DarkMatterAppState, onBack: () -> Unit) {
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
                    OutlinedTextField(
                        value = picture,
                        onValueChange = { picture = it },
                        label = { Text(stringResource(R.string.picture_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
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
                        enabled = !busy && active != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Check, contentDescription = null)
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
private fun AddIdentitySheet(appState: DarkMatterAppState, onDismiss: () -> Unit) {
    var identity by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val creatingIdentityDescription = stringResource(R.string.creating_identity)

    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                        modifier = Modifier
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
            OutlinedTextField(
                value = identity,
                onValueChange = { identity = it },
                label = { Text(stringResource(R.string.nsec_or_npub)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
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
private fun IdentityScreen(appState: DarkMatterAppState, onBack: () -> Unit) {
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
            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.copy))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaysScreen(appState: DarkMatterAppState, onBack: () -> Unit) {
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
                            keyboardOptions = KeyboardOptions(
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
                            enabled = pendingUrl.trim().let {
                                !saving && appState.activeAccountRef != null &&
                                    (it.startsWith("wss://") || it.startsWith("ws://")) &&
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

private val relayListKinds = listOf(
    RelayListKind.Nip65,
    RelayListKind.Inbox,
    RelayListKind.KeyPackage,
)

private val RelayListKind.labelRes: Int
    get() = when (this) {
        RelayListKind.Nip65 -> R.string.nip_65
        RelayListKind.Inbox -> R.string.inbox
        RelayListKind.KeyPackage -> R.string.key_package
    }

private fun AccountRelayListsFfi.relaysFor(kind: RelayListKind): List<String> {
    return when (kind) {
        RelayListKind.Nip65 -> nip65.relays
        RelayListKind.Inbox -> inbox.relays
        RelayListKind.KeyPackage -> keyPackage.relays
    }
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
        RelayListRow(stringResource(R.string.key_package), lists.keyPackage)
        if (lists.complete) {
            Text(stringResource(R.string.all_relay_lists_published), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.missing_relay_lists, lists.missing.joinToString(", ")), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RelayListRow(title: String, list: RelayListFfi) {
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
private fun KeyPackagesScreen(appState: DarkMatterAppState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var packages by remember(appState.activeAccountRef) { mutableStateOf<List<AccountKeyPackageFfi>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var loaded by remember(appState.activeAccountRef) { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AccountKeyPackageFfi?>(null) }

    suspend fun reload() {
        loading = true
        packages = appState.fetchKeyPackages()
        loaded = true
        loading = false
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
                        onClick = { scope.launch { reload() } },
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
                                    appState.republishKeyPackage()
                                    reload()
                                    working = false
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
                                    appState.publishNewKeyPackage()
                                    reload()
                                    working = false
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
            items(packages, key = { it.eventIdHex }) { kp ->
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
                        appState.deleteKeyPackage(target.eventIdHex, target.sourceRelays)
                        reload()
                        working = false
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
            DiagnosticRow(stringResource(R.string.size), stringResource(R.string.bytes_count, kp.keyPackageBytes))
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

private val publishedAtFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatPublishedAt(unixSeconds: ULong, unknown: String, format: String): String {
    if (unixSeconds == 0uL) return unknown
    val millis = unixSeconds.toLong() * 1000L
    return String.format(format, publishedAtFormatter.format(Date(millis)))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(appState: DarkMatterAppState, onBackToChats: () -> Unit) {
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
                val event = withContext(Dispatchers.IO) {
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
                                    val groupId = appState.marmotIo {
                                        createGroup(
                                            account,
                                            "diagnostic-${System.currentTimeMillis() / 1000L}",
                                            emptyList(),
                                            null,
                                        )
                                    }
                                    appState.marmotIo { sendText(account, groupId, "ping at ${System.currentTimeMillis() / 1000L}") }
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
            item {
                SectionCard(title = stringResource(R.string.event_log)) {
                    if (entries.isEmpty()) {
                        Text(stringResource(R.string.waiting_for_events), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        entries.forEach { entry ->
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
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
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
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun ErrorContent(title: String, message: String) {
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
    val palette = listOf(
        Color(0xFF006A6A),
        Color(0xFF8C4A00),
        Color(0xFF5B5FC7),
        Color(0xFF006D3B),
        Color(0xFF9A4055),
    )
    val color = palette[kotlin.math.abs(seed.hashCode()) % palette.size]
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, pictureUrl) {
        value = null
        val url = pictureUrl ?: return@produceState
        value = runCatching {
            withContext(Dispatchers.IO) {
                URL(url).openStream().use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }
        }.getOrNull()
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

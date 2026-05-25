package dev.ipf.darkmatter.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.ipf.darkmatter.core.DiagnosticFormatter
import dev.ipf.darkmatter.core.GroupProjector
import dev.ipf.darkmatter.core.IdentityFormatter
import dev.ipf.darkmatter.core.MessageProjector
import dev.ipf.darkmatter.core.ProfileLink
import dev.ipf.darkmatter.core.ProfileSanitizer
import dev.ipf.darkmatter.core.QrCodeEncoder
import dev.ipf.darkmatter.state.AppPhase
import dev.ipf.darkmatter.state.ChatListItem
import dev.ipf.darkmatter.state.ChatsController
import dev.ipf.darkmatter.state.ConversationController
import dev.ipf.darkmatter.state.DarkMatterAppState
import dev.ipf.darkmatter.state.MessageStatus
import dev.ipf.darkmatter.state.RelayListKind
import dev.ipf.darkmatter.state.TimelineMessage
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
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.RelayHealthFfi
import dev.ipf.marmotkit.RelayListFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi

private enum class MainSection {
    Chats,
    Settings,
    Diagnostics,
}

private enum class SettingsDetail {
    Profile,
    Accounts,
    Identity,
    Relays,
}

private data class DiagnosticLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: ULong = (System.currentTimeMillis() / 1000L).toULong(),
    val text: String,
)

@Composable
fun DarkMatterApp(
    appState: DarkMatterAppState,
    inboundProfilePayload: String? = null,
    onProfilePayloadHandled: (String) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val toast = appState.toast

    LaunchedEffect(Unit) {
        appState.bootstrap()
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            snackbarHostState.showSnackbar(
                listOfNotNull(toast.title, toast.detail).joinToString("\n"),
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val phase = appState.phase) {
                AppPhase.Bootstrapping -> LoadingScreen()
                AppPhase.Onboarding -> OnboardingScreen(appState)
                AppPhase.Ready -> MainShell(appState)
                is AppPhase.Failed -> FailureScreen(
                    message = phase.message,
                    onRetry = { appState.present("Restarting"); },
                    onRetryAction = { appState.bootstrap() },
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text("Starting Marmot", style = MaterialTheme.typography.bodyMedium)
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
            Text("Dark Matter couldn't start", style = MaterialTheme.typography.titleLarge)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    onRetry()
                    scope.launch { onRetryAction() }
                },
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

@Composable
private fun OnboardingScreen(appState: DarkMatterAppState) {
    var identity by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(title = "Dark Matter", seed = "darkmatter", size = 88.dp)
            Text("Dark Matter", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "End-to-end encrypted group messaging.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        appState.createIdentity()
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Key, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Create New Identity")
            }
            OutlinedTextField(
                value = identity,
                onValueChange = { identity = it },
                label = { Text("nsec or npub") },
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
                Text("Import Existing Identity")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(appState: DarkMatterAppState) {
    var section by remember { mutableStateOf(MainSection.Chats) }
    var selectedGroup by remember { mutableStateOf<AppGroupRecordFfi?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    appState.pendingProfileNpub?.let { npub ->
        ProfileSheet(
            appState = appState,
            npub = npub,
            onDismiss = { appState.clearPresentedProfile() },
        )
    }

    if (selectedGroup != null) {
        ConversationScreen(
            appState = appState,
            group = selectedGroup!!,
            onBack = { selectedGroup = null },
        )
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerHeader(appState)
                NavigationDrawerItem(
                    label = { Text("Chats") },
                    selected = section == MainSection.Chats,
                    icon = { Icon(Icons.Default.Group, contentDescription = null) },
                    onClick = {
                        section = MainSection.Chats
                        scope.launch { drawerState.close() }
                    },
                )
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = section == MainSection.Settings,
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    onClick = {
                        section = MainSection.Settings
                        scope.launch { drawerState.close() }
                    },
                )
                if (appState.developerMode) {
                    NavigationDrawerItem(
                        label = { Text("Diagnostics") },
                        selected = section == MainSection.Diagnostics,
                        icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                        onClick = {
                            section = MainSection.Diagnostics
                            scope.launch { drawerState.close() }
                        },
                    )
                }
            }
        },
    ) {
        when (section) {
            MainSection.Chats -> ChatsScreen(
                appState = appState,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onOpenGroup = { selectedGroup = it },
            )
            MainSection.Settings -> SettingsScreen(appState, onOpenDrawer = { scope.launch { drawerState.open() } })
            MainSection.Diagnostics -> DiagnosticsScreen(appState, onOpenDrawer = { scope.launch { drawerState.open() } })
        }
    }
}

@Composable
private fun DrawerHeader(appState: DarkMatterAppState) {
    val active = appState.activeAccount
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar(
            title = active?.let { appState.displayName(it.accountIdHex) } ?: "Dark Matter",
            seed = active?.accountIdHex ?: "darkmatter",
            size = 56.dp,
            pictureUrl = active?.let { appState.avatarUrl(it.accountIdHex) },
        )
        Text(
            active?.let { appState.displayName(it.accountIdHex) } ?: "Dark Matter",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            active?.let { appState.shortNpub(it.accountIdHex) } ?: "No active account",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsScreen(
    appState: DarkMatterAppState,
    onOpenDrawer: () -> Unit,
    onOpenGroup: (AppGroupRecordFfi) -> Unit,
) {
    val controller = remember(appState.activeAccountRef) { ChatsController(appState) }
    var showNewChat by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }

    LaunchedEffect(controller, appState.activeAccountRef) {
        controller.bind(appState.activeAccountRef)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showArchived) "Archived" else "Chats") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (showArchived) showArchived = false else onOpenDrawer()
                    }) {
                        Icon(
                            if (showArchived) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                            contentDescription = if (showArchived) "Back" else "Open navigation",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (!showArchived) {
                FloatingActionButton(onClick = { showNewChat = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New chat")
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val visibleItems = if (showArchived) controller.archivedItems else controller.items
            when {
                controller.isLoading && visibleItems.isEmpty() -> LoadingScreen()
                controller.error != null -> ErrorContent("Couldn't load chats", controller.error.orEmpty())
                visibleItems.isEmpty() && showArchived -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No archived chats", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                visibleItems.isEmpty() -> EmptyChats(onCreate = { showNewChat = true })
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(visibleItems, key = { it.id }) { item ->
                        ChatRow(
                            item = item,
                            appState = appState,
                            onClick = { onOpenGroup(item.group) },
                        )
                        HorizontalDivider()
                    }
                    if (!showArchived && controller.archivedItems.isNotEmpty()) {
                        item {
                            ListItem(
                                modifier = Modifier.clickable { showArchived = true },
                                headlineContent = { Text("Archived") },
                                supportingContent = { Text("${controller.archivedItems.size} chats") },
                                leadingContent = { Icon(Icons.Default.Archive, contentDescription = null) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewChat) {
        NewChatSheet(appState = appState, onDismiss = { showNewChat = false })
    }
}

@Composable
private fun ChatRow(
    item: ChatListItem,
    appState: DarkMatterAppState,
    onClick: () -> Unit,
) {
    val title = GroupProjector.displayTitle(
        group = item.group,
        otherMemberAccount = item.otherMemberAccount,
        memberCount = item.memberCount,
        memberTitle = { appState.chatMemberTitle(it) },
    )
    val avatarAccount = item.otherMemberAccount.takeIf { item.group.name.isBlank() && item.memberCount == 2 }
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
                MessageProjector.previewText(item.latest),
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
                if (item.memberCount > 2) {
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
            Text("No chats yet", style = MaterialTheme.typography.titleLarge)
            Text(
                "Start a conversation by inviting someone with their npub.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New Chat")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(appState: DarkMatterAppState, onDismiss: () -> Unit) {
    var members by remember { mutableStateOf<List<String>>(emptyList()) }
    var pending by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun addRecipient(reference: String) {
        val trimmed = reference.trim()
        if (trimmed.isNotEmpty() && !members.contains(trimmed)) {
            members = members + trimmed
            pending = ""
        }
    }

    fun addPending() {
        addRecipient(pending)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("New Chat", style = MaterialTheme.typography.titleLarge)
            if (members.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    members.take(3).forEach { member ->
                        AssistChip(
                            onClick = { members = members - member },
                            label = { Text(IdentityFormatter.short(member), maxLines = 1) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove") },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pending,
                    onValueChange = { pending = it },
                    label = { Text("npub or hex public key") },
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
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan recipient QR code")
                }
                FloatingActionButton(onClick = { addPending() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Add recipient")
                }
            }
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Group name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            if (error != null) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    val recipients = (members + listOfNotNull(pending.trim().takeIf { it.isNotEmpty() }))
                        .distinct()
                    members = recipients
                    pending = ""
                    val account = appState.activeAccountRef ?: return@Button
                    busy = true
                    error = null
                    scope.launch {
                        runCatching {
                            appState.marmot().createGroup(
                                account,
                                groupName.trim(),
                                recipients,
                                description.trim().ifBlank { null },
                            )
                        }.onSuccess {
                            appState.present("Chat created")
                            onDismiss()
                        }.onFailure {
                            error = it.message ?: it.javaClass.simpleName
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
                Text("Create")
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
                    error = "That QR code is not a Dark Matter profile."
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
    group: AppGroupRecordFfi,
    onBack: () -> Unit,
) {
    val controller = remember(group.groupIdHex) { ConversationController(appState, group) }
    var menuOpen by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val scope = rememberCoroutineScope()

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
                        Text(controller.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            controller.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Chat actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Details") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                showDetails = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (controller.group.archived) "Unarchive" else "Archive") },
                            leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                scope.launch { controller.setArchived(!controller.group.archived) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Leave") },
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
            if (controller.error == null) {
                ComposerBar(
                    replyingTo = controller.replyingTo,
                    sendInFlight = controller.sendInFlight,
                    onCancelReply = { controller.replyingTo = null },
                    onSend = { scope.launch { controller.send(it) } },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                controller.isLoading && controller.timeline.isEmpty() -> LoadingScreen()
                controller.error != null -> ErrorContent("Couldn't load conversation", controller.error.orEmpty())
                controller.timeline.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No messages yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var pendingMembers by remember { mutableStateOf("") }
    var mlsState by remember(controller.group.groupIdHex) { mutableStateOf<AppGroupMlsStateFfi?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(title = controller.title, seed = controller.group.groupIdHex, size = 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(controller.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(controller.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionCard(title = "Profile") {
                if (controller.isSelfAdmin) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Group name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                controller.updateGroupProfile(name, description)
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Save Group")
                    }
                } else {
                    Text(controller.group.description.ifBlank { "No description" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionCard(title = "Info") {
                DiagnosticRow("Group ID", IdentityFormatter.short(controller.group.groupIdHex))
                DiagnosticRow("Nostr group", IdentityFormatter.short(controller.group.nostrGroupIdHex))
                DiagnosticRow("Relays", controller.group.relays.size.toString())
                controller.group.relays.forEach { relay ->
                    Text(relay, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionCard(title = "Members") {
                controller.members.forEach { member ->
                    GroupMemberRow(
                        member = member,
                        controller = controller,
                        onPromote = {
                            scope.launch { controller.setMemberAdmin(member, admin = true) }
                        },
                        onDemote = {
                            scope.launch { controller.setMemberAdmin(member, admin = false) }
                        },
                        onRemove = {
                            scope.launch { controller.removeMember(member) }
                        },
                    )
                }
                if (controller.isSelfAdmin) {
                    OutlinedTextField(
                        value = pendingMembers,
                        onValueChange = { pendingMembers = it },
                        label = { Text("npub or hex public keys") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    )
                    Button(
                        onClick = {
                            val refs = pendingMembers.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
                            pendingMembers = ""
                            scope.launch { controller.inviteMembers(refs) }
                        },
                        enabled = pendingMembers.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add Members")
                    }
                }
            }

            SectionCard(title = "Actions") {
                OutlinedButton(
                    onClick = { scope.launch { controller.setArchived(!controller.group.archived) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (controller.group.archived) "Unarchive Chat" else "Archive Chat")
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
                    Text("Leave Chat")
                }
                if (!controller.canLeaveGroup) {
                    Text("Make another member an admin before leaving.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (appState.developerMode) {
                SectionCard(title = "MLS") {
                    Button(
                        onClick = {
                            scope.launch {
                                mlsState = controller.groupMlsState()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Load MLS State")
                    }
                    mlsState?.let { state ->
                        DiagnosticRow("Group ID", IdentityFormatter.short(state.groupIdHex))
                        DiagnosticRow("Epoch", state.epoch.toString())
                        DiagnosticRow("MLS members", state.memberCount.toString())
                        DiagnosticRow("Required components", state.requiredAppComponents.joinToString(", "))
                    }
                }
            }
        }
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

    ListItem(
        leadingContent = {
            Avatar(
                title = controller.memberDisplayName(member),
                seed = member.account ?: member.memberIdHex,
                size = 40.dp,
                pictureUrl = controller.memberAvatarUrl(member),
            )
        },
        headlineContent = { Text(controller.memberDisplayName(member)) },
        supportingContent = { Text(controller.memberSubtitle(member), fontFamily = FontFamily.Monospace) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isAdmin) {
                    AssistChip(onClick = {}, label = { Text("Admin") })
                }
                if (canManage) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Member actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (isAdmin) "Remove admin" else "Make admin") },
                            onClick = {
                                menuOpen = false
                                if (isAdmin) onDemote() else onPromote()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove member") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    item: TimelineMessage,
    controller: ConversationController,
    appState: DarkMatterAppState,
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
    val quickReactions = listOf("👍", "❤️", "😂", "🎉", "😮")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        if (!mine) {
            Avatar(
                title = appState.displayName(record.sender),
                seed = record.sender,
                size = 32.dp,
                pictureUrl = appState.avatarUrl(record.sender),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Surface(
                modifier = Modifier.combinedClickable(
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
                    controller.replyPreview(record)?.let { (name, body) ->
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
                        if (deleted) "Message deleted" else MessageProjector.displayBody(record),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            IdentityFormatter.relativeTime(record.recordedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.status != MessageStatus.Received && item.status != MessageStatus.Sent) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                item.status.name.lowercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Reply") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                                onClick = {
                                    controller.replyingTo = record
                                    menuOpen = false
                                },
                            )
                            quickReactions.forEach { emoji ->
                                DropdownMenuItem(
                                    text = { Text(emoji) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch { controller.toggleReaction(emoji, record) }
                                    },
                                )
                            }
                            if (mine && record.messageIdHex.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch { controller.deleteMessage(record) }
                                    },
                                )
                            }
                        }
                    }
                }
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

@Composable
private fun ComposerBar(
    replyingTo: AppMessageRecordFfi?,
    sendInFlight: Boolean,
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
                    MessageProjector.displayBody(replyingTo),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onCancelReply, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel reply", modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
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
                else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(appState: DarkMatterAppState, onOpenDrawer: () -> Unit) {
    var detail by remember { mutableStateOf<SettingsDetail?>(null) }

    when (detail) {
        SettingsDetail.Profile -> ProfileEditScreen(appState, onBack = { detail = null })
        SettingsDetail.Accounts -> AccountsScreen(appState, onBack = { detail = null })
        SettingsDetail.Identity -> IdentityScreen(appState, onBack = { detail = null })
        SettingsDetail.Relays -> RelaysScreen(appState, onBack = { detail = null })
        null -> SettingsHomeScreen(
            appState = appState,
            onOpenDrawer = onOpenDrawer,
            onOpenDetail = { detail = it },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHomeScreen(
    appState: DarkMatterAppState,
    onOpenDrawer: () -> Unit,
    onOpenDetail: (SettingsDetail) -> Unit,
) {
    var qrAccountId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = "Account") {
                    appState.activeAccount?.let { account ->
                        ListItem(
                            modifier = Modifier.clickable { onOpenDetail(SettingsDetail.Profile) },
                            leadingContent = {
                                Avatar(
                                    title = appState.displayName(account.accountIdHex),
                                    seed = account.accountIdHex,
                                    size = 44.dp,
                                    pictureUrl = appState.avatarUrl(account.accountIdHex),
                                )
                            },
                            headlineContent = { Text(appState.displayName(account.accountIdHex)) },
                            supportingContent = { Text(appState.shortNpub(account.accountIdHex), fontFamily = FontFamily.Monospace) },
                            trailingContent = {
                                IconButton(onClick = { qrAccountId = account.accountIdHex }) {
                                    Icon(Icons.Default.QrCode, contentDescription = "My QR code")
                                }
                            },
                        )
                    }
                    SettingsRow("Profile", "Publish your Nostr kind:0 profile") { onOpenDetail(SettingsDetail.Profile) }
                    SettingsRow("Accounts", "${appState.accounts.size} identities on this device") { onOpenDetail(SettingsDetail.Accounts) }
                    SettingsRow("Identity & Keys", "Public key, npub, signing status") { onOpenDetail(SettingsDetail.Identity) }
                    SettingsRow("Relays", "Dark Matter-managed relay lists") { onOpenDetail(SettingsDetail.Relays) }
                }
            }
            item {
                SectionCard(title = "Developer") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Developer mode", style = MaterialTheme.typography.bodyLarge)
                            Text("Shows diagnostics and MLS internals.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = appState.developerMode,
                            onCheckedChange = { appState.updateDeveloperMode(it) },
                        )
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
                    appState.present("Copied npub")
                },
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (copied) "Copied" else IdentityFormatter.short(npub, prefix = 16, suffix = 14))
            }
            QrCodeImage(content = link.uri)
            scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, link.uri)
                        context.startActivity(Intent.createChooser(sendIntent, "Share profile"))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
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
                    Text("Scan")
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
                    scanError = "That QR code is not a Dark Matter profile."
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
                    appState.present("Copied npub")
                },
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(IdentityFormatter.short(npub, prefix = 16, suffix = 14))
            }
            if (hex == null) {
                Text("Couldn't read this profile code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Message")
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
                contentDescription = "Profile QR code",
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
                Text("Scan", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                }
            }
            if (permissionGranted) {
                Box(
                    Modifier.fillMaxWidth().height(520.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    CameraQrScanner(onScan = onScan, onError = { scannerError = it })
                    Text(
                        scannerError ?: "Point the camera at a Dark Matter profile QR",
                        color = Color.White,
                        modifier = Modifier.padding(16.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            } else {
                Text("Camera access is required to scan QR codes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Allow Camera")
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

    if (lifecycleOwner == null) {
        onError("Camera lifecycle is unavailable.")
        return
    }

    AndroidView(
        factory = { viewContext ->
            PreviewView(viewContext).also { previewView ->
                bindQrScannerCamera(context, lifecycleOwner, previewView, onScan, onError)
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
    onScan: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(context)
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener(
        {
            val provider = runCatching { cameraProviderFuture.get() }.getOrElse {
                onError("Camera is unavailable.")
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
        val profile = active?.accountIdHex?.let { appState.userProfile(it) }
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
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = "Preview") {
                    if (active == null) {
                        Text("No active account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            headlineContent = { Text(displayName.ifBlank { "Anonymous" }) },
                            supportingContent = { Text(appState.shortNpub(active.accountIdHex), fontFamily = FontFamily.Monospace) },
                        )
                    }
                }
            }
            item {
                SectionCard(title = "Profile") {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Display name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = about,
                        onValueChange = { about = it },
                        label = { Text("About") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = picture,
                        onValueChange = { picture = it },
                        label = { Text("Picture URL") },
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
                        label = { Text("NIP-05") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    )
                    OutlinedTextField(
                        value = lud16,
                        onValueChange = { lud16 = it },
                        label = { Text("Lightning") },
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
                        Text("Publish to Relays")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountsScreen(appState: DarkMatterAppState, onBack: () -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    var qrAccountId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(appState.accounts.size) {
        if (showAdd) showAdd = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add account")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(appState.accounts, key = { it.label }) { account ->
                ListItem(
                    modifier = Modifier.clickable { appState.setActiveAccount(account.label) },
                    leadingContent = {
                        Avatar(
                            title = appState.displayName(account.accountIdHex),
                            seed = account.accountIdHex,
                            size = 44.dp,
                            pictureUrl = appState.avatarUrl(account.accountIdHex),
                        )
                    },
                    headlineContent = { Text(appState.displayName(account.accountIdHex)) },
                    supportingContent = { Text(appState.shortNpub(account.accountIdHex), fontFamily = FontFamily.Monospace) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.End) {
                                if (account.label == appState.activeAccountRef) {
                                    Icon(Icons.Default.Check, contentDescription = "Active")
                                }
                                if (!account.localSigning) {
                                    Text("Read-only", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            IconButton(onClick = { qrAccountId = account.accountIdHex }) {
                                Icon(Icons.Default.QrCode, contentDescription = "Show profile QR code")
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    if (showAdd) {
        AddIdentitySheet(appState = appState, onDismiss = { showAdd = false })
    }
    qrAccountId?.let { accountId ->
        ProfileQrSheet(
            appState = appState,
            accountIdHex = accountId,
            onDismiss = { qrAccountId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddIdentitySheet(appState: DarkMatterAppState, onDismiss: () -> Unit) {
    var identity by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Add Account", style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        appState.createIdentity()
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Key, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Create New Identity")
            }
            OutlinedTextField(
                value = identity,
                onValueChange = { identity = it },
                label = { Text("nsec or npub") },
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
                Text("Import Existing Identity")
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
                title = { Text("Identity") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = "Identity") {
                    if (active == null) {
                        Text("No active account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        DiagnosticRow("Display name", appState.displayName(active.accountIdHex))
                        CopyableValueRow(
                            label = "Public key",
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
                        DiagnosticRow("Local signing", if (active.localSigning) "Yes" else "No")
                        DiagnosticRow("Status", if (active.running) "Online" else "Idle")
                    }
                }
            }
            item {
                SectionCard(title = "Account Session") {
                    Text(
                        "Signing out only forgets which account is active. Identities stay in the device keychain.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            appState.signOutActiveAccount()
                            appState.present("Signed out")
                        },
                        enabled = active != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Sign out of this account")
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
            appState.present("Copied $label")
        },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(display, fontFamily = FontFamily.Monospace)
            Icon(Icons.Default.Check, contentDescription = "Copy")
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

    fun reloadLists() {
        lists = appState.accountRelayLists()
    }

    LaunchedEffect(appState.activeAccountRef) {
        reloadLists()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relays") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { reloadLists() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = "Account Relay Lists") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        relayListKinds.forEach { option ->
                            FilterChip(
                                selected = selectedKind == option,
                                onClick = { selectedKind = option },
                                label = { Text(option.label) },
                            )
                        }
                    }

                    val currentRelays = lists?.relaysFor(selectedKind).orEmpty()
                    if (currentRelays.isEmpty()) {
                        Text("No relays", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Icon(Icons.Default.Close, contentDescription = "Remove relay")
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
                            Icon(Icons.Default.Add, contentDescription = "Add relay")
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

private val RelayListKind.label: String
    get() = when (this) {
        RelayListKind.Nip65 -> "NIP-65"
        RelayListKind.Inbox -> "Inbox"
        RelayListKind.KeyPackage -> "Key Package"
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
    SectionCard(title = "Published Relay Lists") {
        if (lists == null) {
            Text("No relay-list projection is available yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        RelayListRow("NIP-65", lists.nip65)
        RelayListRow("Inbox", lists.inbox)
        RelayListRow("Key Package", lists.keyPackage)
        if (lists.complete) {
            Text("All relay lists are published.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Missing: ${lists.missing.joinToString(", ")}", color = MaterialTheme.colorScheme.error)
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
            Text("Not published", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            list.relays.forEach { relay ->
                Text(relay, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(appState: DarkMatterAppState, onOpenDrawer: () -> Unit) {
    var health by remember { mutableStateOf<RelayHealthFfi?>(null) }
    var entries by remember { mutableStateOf<List<DiagnosticLogEntry>>(emptyList()) }
    var streaming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun appendLog(text: String) {
        entries = (entries + DiagnosticLogEntry(text = text)).takeLast(500)
    }

    LaunchedEffect(Unit) {
        streaming = true
        val subscription = appState.marmot().subscribeEvents()
        try {
            while (true) {
                val event = subscription.next() ?: break
                entries = (entries + DiagnosticLogEntry(text = DiagnosticFormatter.describe(event))).takeLast(500)
            }
        } catch (throwable: Throwable) {
            entries = (entries + DiagnosticLogEntry(text = "event stream failed: ${throwable.message ?: throwable.javaClass.simpleName}")).takeLast(500)
        } finally {
            streaming = false
            subscription.destroy()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { health = appState.marmot().relayHealth() }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
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
                                    val groupId = appState.marmot().createGroup(
                                        account,
                                        "diagnostic-${System.currentTimeMillis() / 1000L}",
                                        emptyList(),
                                        null,
                                    )
                                    appState.marmot().sendText(account, groupId, "ping at ${System.currentTimeMillis() / 1000L}")
                                    appendLog("sent ping to self in ${IdentityFormatter.short(groupId)}")
                                }.onFailure {
                                    appendLog("send-to-self failed: ${it.message ?: it.javaClass.simpleName}")
                                }
                            }
                        },
                        enabled = appState.activeAccountRef != null,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Send to self")
                    }
                    OutlinedButton(onClick = { entries = emptyList() }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Clear")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (streaming) "Live" else "Idle", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard(title = "Relay Health") {
                    if (health == null) {
                        Text("No relay snapshot yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { scope.launch { health = appState.marmot().relayHealth() } }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Refresh")
                        }
                    } else {
                        health?.let { relay ->
                            DiagnosticRow("Total", relay.totalRelays.toString())
                            DiagnosticRow("Connected", relay.connected.toString())
                            DiagnosticRow("Connecting", relay.connecting.toString())
                            DiagnosticRow("Disconnected", relay.disconnected.toString())
                            DiagnosticRow("Attempts", relay.connectionAttempts.toString())
                            DiagnosticRow("Successes", relay.connectionSuccesses.toString())
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Runtime") {
                    DiagnosticRow("Active account", appState.activeAccountRef ?: "none")
                    DiagnosticRow("Accounts", appState.accounts.size.toString())
                    DiagnosticRow("Bootstrap relays", appState.bootstrapRelayCount().toString())
                }
            }
            item {
                SectionCard(title = "Event Log") {
                    if (entries.isEmpty()) {
                        Text("Waiting for events.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace)
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

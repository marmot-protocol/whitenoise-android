package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.rethrowIfCancellation
import dev.ipf.whitenoise.android.state.startProfileChatFailureCopyable
import dev.ipf.whitenoise.android.state.startProfileChatFailureDetail
import dev.ipf.whitenoise.android.ui.qr.QrCodeImage
import dev.ipf.whitenoise.android.ui.qr.QrScannerSheet
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

private enum class NewChatStep { NewMessage, NewGroup }

private data class StartChatErrorUiState(
    val npub: String,
    val progressHex: String,
    val detail: AppText,
    val copyable: Boolean,
    val title: AppText = AppText.Resource(R.string.toast_couldnt_start_chat),
    val retryGroupIdHex: String? = null,
)

/**
 * Full-screen New Message flow: pick a person to open/start a direct chat, or
 * branch into the New Group picker + setup steps.
 */
@Composable
internal fun NewChatFlowHost(
    appState: WhiteNoiseAppState,
    onOpenConversation: (ChatListItem, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var stepName by rememberSaveable { mutableStateOf(NewChatStep.NewMessage.name) }
    val step = runCatching { NewChatStep.valueOf(stepName) }.getOrDefault(NewChatStep.NewMessage)
    when (step) {
        NewChatStep.NewMessage ->
            NewMessageScreen(
                appState = appState,
                onBack = onClose,
                onNewGroup = { stepName = NewChatStep.NewGroup.name },
                onOpenConversation = onOpenConversation,
            )
        NewChatStep.NewGroup ->
            NewGroupFlow(
                appState = appState,
                onOpenConversation = onOpenConversation,
                onClose = { stepName = NewChatStep.NewMessage.name },
            )
    }
}

/**
 * Member picker + group setup pair. Also used standalone by the profile
 * sheet's "Start new group with …" action via [initialMembers].
 */
@Composable
internal fun NewGroupFlow(
    appState: WhiteNoiseAppState,
    onOpenConversation: (ChatListItem, Boolean) -> Unit,
    onClose: () -> Unit,
    initialMembers: List<RecipientSearch.Candidate> = emptyList(),
) {
    val selected = remember { mutableStateListOf<RecipientSearch.Candidate>().apply { addAll(initialMembers) } }
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    if (setupOpen) {
        NewGroupSetupScreen(
            appState = appState,
            members = selected,
            onBack = { setupOpen = false },
            onOpenConversation = onOpenConversation,
        )
    } else {
        ContactPickerScreen(
            appState = appState,
            title = stringResource(R.string.new_group),
            selected = selected,
            onBack = onClose,
            onConfirm = { setupOpen = true },
            // Members are optional: you can proceed to name the group and create
            // it with nobody selected, then add people afterward from the group.
            allowEmptyConfirm = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewMessageScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenConversation: (ChatListItem, Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var showMyQr by remember { mutableStateOf(false) }
    var creatingHex by remember { mutableStateOf<String?>(null) }
    var startChatError by remember { mutableStateOf<StartChatErrorUiState?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val showMyQrLabel = stringResource(R.string.show_my_qr_code)

    // Back must stay installed (a disabled handler lets the event fall through
    // to the Activity) but no-op while a tapped person's chat is being created;
    // otherwise the process-lifetime create would yank the user into the new
    // conversation seconds after they left this screen.
    BackHandler {
        if (creatingHex == null) onBack()
    }

    val activeHex = appState.activeAccount?.accountIdHex
    val myNpub = activeHex?.let(appState::npub)
    val myQrUri = myNpub?.let(::nostrNpubUri)
    val candidates =
        remember(appState.chatListItems, activeHex, appState.profileRevisionForCompose) {
            deriveRecipientCandidates(appState, activeHex)
        }
    val identifierQuery = query.isNotBlank() && !isPlainNameQuery(query)
    val resolution = rememberRecipientResolution(query, appState)
    val matches =
        remember(query, candidates, activeHex) {
            if (query.isNotBlank() && !isPlainNameQuery(query)) {
                emptyList()
            } else {
                RecipientSearch.browse(query, candidates, activeHex)
            }
        }

    fun openOrCreateChat(
        npub: String,
        hexForProgress: String,
        retryGroupIdHex: String? = null,
    ) {
        if (creatingHex != null) return
        startChatError = null
        if (retryGroupIdHex == null) {
            appState.existingDirectChat(npub)?.let {
                onOpenConversation(it, false)
                return
            }
        }
        creatingHex = hexForProgress
        appState.launchMutation {
            try {
                runCatching {
                    retryGroupIdHex ?: appState.createProfileChatGroup(npub)
                }.onSuccess { groupIdHex ->
                    val item = appState.awaitChatListItem(groupIdHex)
                    if (item != null) {
                        onOpenConversation(item, true)
                    } else {
                        startChatError =
                            StartChatErrorUiState(
                                npub = npub,
                                progressHex = hexForProgress,
                                detail = AppText.Resource(R.string.error_chat_created_not_loaded),
                                copyable = false,
                                title = AppText.Resource(R.string.couldnt_load_chats),
                                retryGroupIdHex = groupIdHex,
                            )
                    }
                }.onFailure { error ->
                    rethrowIfCancellation(error)
                    startChatError =
                        StartChatErrorUiState(
                            npub = npub,
                            progressHex = hexForProgress,
                            detail = startProfileChatFailureDetail(error, appState::displayName),
                            copyable = startProfileChatFailureCopyable(error),
                        )
                }
            } finally {
                creatingHex = null
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_message)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = creatingHex == null) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            FlowSearchField(
                value = query,
                onValueChange = {
                    query = it
                    startChatError = null
                },
                placeholder = stringResource(R.string.search_people_hint),
                onScanQr = { showScanner = true },
                modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Dimens.spaceXl),
            ) {
                if (query.isBlank()) {
                    item {
                        FlowQuickActionRow(
                            icon = Icons.Default.Group,
                            title = stringResource(R.string.new_group),
                            onClick = onNewGroup,
                        )
                    }
                    item {
                        FlowQuickActionRow(
                            icon = Icons.Default.QrCodeScanner,
                            title = stringResource(R.string.scan_qr_code),
                            onClick = { showScanner = true },
                        )
                    }
                    item {
                        FlowQuickActionRow(
                            icon = Icons.Default.QrCode,
                            title = showMyQrLabel,
                            onClick = { showMyQr = true },
                            enabled = myQrUri != null,
                        )
                    }
                }
                startChatError?.let { error ->
                    item {
                        StartChatErrorCard(
                            error = error,
                            onRetry = { openOrCreateChat(error.npub, error.progressHex, error.retryGroupIdHex) },
                            onCopy = { detail ->
                                clipboard.setText(AnnotatedString(detail))
                                appState.present(R.string.copied)
                            },
                        )
                    }
                }
                // A pasted/scanned self identifier is dropped like the browse
                // list drops the active account, landing on "No matches".
                val resolvedHex =
                    resolution.resolvedHex?.takeUnless { it.equals(activeHex, ignoreCase = true) }
                if (identifierQuery && resolution.state == RecipientPreviewState.Resolving) {
                    item { ResolvingContactRow() }
                } else if (identifierQuery && resolvedHex != null) {
                    item {
                        ContactRow(
                            title = appState.displayName(resolvedHex),
                            subtitle = IdentityFormatter.short(appState.npub(resolvedHex)),
                            avatarSeed = resolvedHex,
                            avatarUrl = appState.avatarUrl(resolvedHex),
                            enabled = creatingHex == null,
                            onClick = { openOrCreateChat(appState.npub(resolvedHex), resolvedHex) },
                            onLongClick = { appState.presentProfile(appState.npub(resolvedHex)) },
                            trailing =
                                if (creatingHex == resolvedHex) {
                                    { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
                                } else {
                                    null
                                },
                        )
                    }
                } else if (identifierQuery || matches.isEmpty()) {
                    // Only surface a note when the user actually typed a query that
                    // matched nothing. The blank / no-contacts state relies on the
                    // New group + Scan QR quick actions above, so it needs no
                    // centered hero, paste, or scan affordance here (all redundant).
                    if (query.isNotBlank()) {
                        item {
                            Text(
                                stringResource(R.string.no_matches),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceLg),
                            )
                        }
                    }
                } else {
                    item { SectionHeader(stringResource(R.string.contacts)) }
                    items(matches, key = { it.accountIdHex }) { candidate ->
                        ContactRow(
                            title = appState.displayName(candidate.accountIdHex),
                            subtitle = IdentityFormatter.short(candidate.npub),
                            avatarSeed = candidate.accountIdHex,
                            avatarUrl = appState.avatarUrl(candidate.accountIdHex),
                            enabled = creatingHex == null,
                            onClick = { openOrCreateChat(candidate.npub, candidate.accountIdHex) },
                            onLongClick = { appState.presentProfile(candidate.npub) },
                            trailing =
                                if (creatingHex == candidate.accountIdHex) {
                                    { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
                                } else {
                                    null
                                },
                        )
                    }
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
                    appState.present(R.string.error_not_white_noise_profile_qr, copyable = true)
                } else {
                    query = scanned.npub
                    startChatError = null
                }
            },
        )
    }
    if (showMyQr && myNpub != null && myQrUri != null) {
        MyQrCodeSheet(
            npub = myNpub,
            qrUri = myQrUri,
            onDismiss = { showMyQr = false },
            onCopy = { npub ->
                clipboard.setText(AnnotatedString(npub))
                appState.present(R.string.toast_copied_npub)
            },
            onShare = { uri ->
                val sendIntent =
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, uri)
                context.startActivity(Intent.createChooser(sendIntent, showMyQrLabel))
            },
        )
    }
}

/**
 * New Message's self-QR intentionally emits the NIP-27 `nostr:npub…` form so
 * scanned self-QRs start chats in White Noise and third-party Nostr clients.
 * The existing profile QR sheet keeps using [ProfileLink.qrUri] because that
 * surface shares a Marmot profile link instead. Reuse [ProfileLink.parse] here
 * so the [WhiteNoiseAppState.npub] raw-hex fallback is never encoded as a QR.
 */
internal fun nostrNpubUri(npub: String): String? = ProfileLink.parse(npub)?.let { "nostr:${it.npub}" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyQrCodeSheet(
    npub: String,
    qrUri: String,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = amoledSheetContainerColor(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.show_my_qr_code),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                }
            }
            Text(
                stringResource(R.string.show_my_qr_code_helper),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            QrCodeImage(
                content = qrUri,
                contentDescription = stringResource(R.string.my_qr_code),
            )
            TextButton(onClick = { onCopy(npub) }) {
                Text(IdentityFormatter.short(npub, prefix = 16, suffix = 14))
            }
            Button(
                onClick = { onShare(qrUri) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.share))
            }
        }
    }
}

@Composable
private fun AppText.resolveForCompose(): String =
    when (this) {
        is AppText.Plain -> value
        is AppText.Resource ->
            if (args.isEmpty()) {
                stringResource(resId)
            } else {
                stringResource(resId, *args.toTypedArray())
            }
    }

@Composable
private fun StartChatErrorCard(
    error: StartChatErrorUiState,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val title = error.title.resolveForCompose()
    val detail = error.detail.resolveForCompose()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
            if (error.copyable) {
                TextButton(onClick = { onCopy(detail) }) {
                    Text(stringResource(R.string.copy))
                }
            }
        }
    }
}

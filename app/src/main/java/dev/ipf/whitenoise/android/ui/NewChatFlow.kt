package dev.ipf.whitenoise.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.Dimens

private enum class NewChatStep { NewMessage, NewGroup }

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
    var creatingHex by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    // Back must stay installed (a disabled handler lets the event fall through
    // to the Activity) but no-op while a tapped person's chat is being created;
    // otherwise the process-lifetime create would yank the user into the new
    // conversation seconds after they left this screen.
    BackHandler {
        if (creatingHex == null) onBack()
    }

    val activeHex = appState.activeAccount?.accountIdHex
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
    ) {
        if (creatingHex != null) return
        appState.existingDirectChat(npub)?.let {
            onOpenConversation(it, false)
            return
        }
        creatingHex = hexForProgress
        appState.launchMutation {
            try {
                val groupIdHex = appState.startProfileChat(npub)
                val item = groupIdHex?.let { appState.awaitChatListItem(it) }
                if (item != null) onOpenConversation(item, true)
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
                onValueChange = { query = it },
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
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(Dimens.spaceXl),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp),
                            )
                            Text(
                                stringResource(
                                    if (query.isBlank()) R.string.recipient_search_empty_hint else R.string.no_matches,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
                                TextButton(onClick = {
                                    clipboard
                                        .getText()
                                        ?.text
                                        ?.trim()
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { query = it }
                                }) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(
                                        stringResource(R.string.paste_npub),
                                        modifier = Modifier.padding(start = Dimens.spaceXs),
                                    )
                                }
                                TextButton(onClick = { showScanner = true }) {
                                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(
                                        stringResource(R.string.scan_qr_code),
                                        modifier = Modifier.padding(start = Dimens.spaceXs),
                                    )
                                }
                            }
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
                }
            },
        )
    }
}

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatCreateOpenTiming
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.state.startProfileChatFailureCopyable
import dev.ipf.whitenoise.android.state.startProfileChatFailureDetail
import dev.ipf.whitenoise.android.state.startProfileChatFailureIsMissingSetup
import dev.ipf.whitenoise.android.state.startProfileChatInviteDetail
import dev.ipf.whitenoise.android.ui.profile.ProfileQrSheet
import dev.ipf.whitenoise.android.ui.profile.profileQrContentForNpub
import dev.ipf.whitenoise.android.ui.qr.QrScanOutcome
import dev.ipf.whitenoise.android.ui.qr.QrScanResult
import dev.ipf.whitenoise.android.ui.qr.QrScanUseCase
import dev.ipf.whitenoise.android.ui.qr.QrScannerSheet
import dev.ipf.whitenoise.android.ui.theme.Dimens

internal enum class NewGroupCreateStage {
    Creating,
    ApplyingRetention,
}

private enum class NewChatStep { NewMessage, NewGroup }

internal data class StartChatErrorUiState(
    val npub: String,
    val progressHex: String,
    val detail: AppText,
    val copyable: Boolean,
    val recipientName: String? = null,
    val invitation: Boolean = false,
    val title: AppText = AppText.Resource(R.string.toast_couldnt_start_chat),
    val retryGroupIdHex: String? = null,
)

internal sealed interface StartChatAttemptResult {
    data class Open(
        val item: ChatListItem,
        val newlyCreated: Boolean = true,
    ) : StartChatAttemptResult

    data class Failed(
        val error: StartChatErrorUiState,
    ) : StartChatAttemptResult
}

internal fun startChatErrorUiState(
    npub: String,
    progressHex: String,
    error: Throwable,
    recipientName: String?,
    displayName: (String) -> String,
): StartChatErrorUiState {
    val invitation = startProfileChatFailureIsMissingSetup(error)
    return StartChatErrorUiState(
        npub = npub,
        progressHex = progressHex,
        detail =
            if (invitation) {
                startProfileChatInviteDetail(recipientName)
            } else {
                startProfileChatFailureDetail(error, displayName)
            },
        copyable = startProfileChatFailureCopyable(error),
        recipientName = recipientName,
        invitation = invitation,
        title =
            if (invitation) {
                AppText.Resource(R.string.invite_to_white_noise)
            } else {
                AppText.Resource(R.string.toast_couldnt_start_chat)
            },
    )
}

/**
 * Shared direct-chat create/retry state machine used by every profile entry
 * point. Keeping creation and the targeted authoritative read together is
 * important: a successful MLS create must retry by group id rather than
 * creating a second direct chat when projection is merely delayed (#1729).
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun attemptStartProfileChat(
    npub: String,
    progressHex: String,
    recipientName: String?,
    retryGroupIdHex: String? = null,
    createGroup: suspend (String) -> String,
    loadCreatedChatListItem: suspend (String) -> ChatListItem,
    displayName: (String) -> String,
    markCreateOpenStage: (String) -> Unit = {},
    abandonCreateOpenTiming: (String) -> Unit = {},
): StartChatAttemptResult {
    val groupIdHex: String =
        try {
            retryGroupIdHex
                ?: run {
                    markCreateOpenStage(ChatCreateOpenTiming.STAGE_MDK_CREATE_START)
                    createGroup(npub).also { markCreateOpenStage(ChatCreateOpenTiming.STAGE_MDK_CREATE_RETURN) }
                }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) {
                abandonCreateOpenTiming(ChatCreateOpenTiming.STAGE_CANCELLED)
                throw error
            }
            abandonCreateOpenTiming(ChatCreateOpenTiming.STAGE_CREATE_FAILED)
            return StartChatAttemptResult.Failed(
                startChatErrorUiState(
                    npub = npub,
                    progressHex = progressHex,
                    error = error,
                    recipientName = recipientName,
                    displayName = displayName,
                ),
            )
        }
    return try {
        runCatchingCancellable {
            StartChatAttemptResult.Open(loadCreatedChatListItem(groupIdHex))
        }.getOrElse { error ->
            abandonCreateOpenTiming(ChatCreateOpenTiming.STAGE_AUTHORITATIVE_READ_FAILED)
            StartChatAttemptResult.Failed(
                StartChatErrorUiState(
                    npub = npub,
                    progressHex = progressHex,
                    detail = startProfileChatFailureDetail(error, displayName),
                    copyable = startProfileChatFailureCopyable(error),
                    recipientName = recipientName,
                    title = AppText.Resource(R.string.couldnt_load_chats),
                    retryGroupIdHex = groupIdHex,
                ),
            )
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        abandonCreateOpenTiming(ChatCreateOpenTiming.STAGE_CANCELLED)
        throw cancelled
    }
}

internal suspend fun attemptOpenOrStartProfileChat(
    npub: String,
    progressHex: String,
    recipientName: String?,
    retryGroupIdHex: String? = null,
    resolveDirectChat: suspend () -> NewMessageDirectChatResolution,
    createGroup: suspend (String) -> String,
    loadCreatedChatListItem: suspend (String) -> ChatListItem,
    displayName: (String) -> String,
    markCreateOpenStage: (String) -> Unit = {},
    abandonCreateOpenTiming: (String) -> Unit = {},
): StartChatAttemptResult {
    val existingChatResult =
        if (retryGroupIdHex == null) {
            markCreateOpenStage(ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_START)
            val resolution =
                try {
                    resolveDirectChat()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    abandonCreateOpenTiming(ChatCreateOpenTiming.STAGE_CANCELLED)
                    throw cancelled
                }
            markCreateOpenStage(ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_RETURN)
            when {
                resolution.item != null ->
                    StartChatAttemptResult.Open(item = resolution.item, newlyCreated = false)
                !resolution.createRequired -> {
                    abandonCreateOpenTiming(ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_FAILED)
                    StartChatAttemptResult.Failed(
                        StartChatErrorUiState(
                            npub = npub,
                            progressHex = progressHex,
                            detail = AppText.Resource(R.string.couldnt_load_chats),
                            copyable = false,
                            recipientName = recipientName,
                        ),
                    )
                }
                else -> null
            }
        } else {
            null
        }
    return existingChatResult ?: attemptStartProfileChat(
        npub = npub,
        progressHex = progressHex,
        recipientName = recipientName,
        retryGroupIdHex = retryGroupIdHex,
        createGroup = createGroup,
        loadCreatedChatListItem = loadCreatedChatListItem,
        displayName = displayName,
        markCreateOpenStage = markCreateOpenStage,
        abandonCreateOpenTiming = abandonCreateOpenTiming,
    )
}

internal fun inviteShareIntent(message: String): Intent =
    Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, message)

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
    val inviteTitle = stringResource(R.string.invite_to_white_noise)
    val inviteMessage = stringResource(R.string.invite_message)

    // Back must stay installed (a disabled handler lets the event fall through
    // to the Activity) but no-op while a tapped person's chat is being created;
    // otherwise the process-lifetime create would yank the user into the new
    // conversation seconds after they left this screen.
    BackHandler {
        if (creatingHex == null) onBack()
    }

    val activeHex = appState.activeAccount?.accountIdHex
    val myNpub = activeHex?.let(appState::npub)
    val myQrContent = myNpub?.let(::profileQrContentForNpub)
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
        recipientName: String? = null,
        retryGroupIdHex: String? = null,
        existingDmGroupIdHex: String? = null,
    ) {
        if (creatingHex != null) return
        startChatError = null
        creatingHex = hexForProgress
        appState.beginChatCreateOpenTiming()
        appState.launchMutation {
            try {
                when (
                    val result =
                        attemptOpenOrStartProfileChat(
                            npub = npub,
                            progressHex = hexForProgress,
                            recipientName = recipientName,
                            retryGroupIdHex = retryGroupIdHex,
                            resolveDirectChat = {
                                resolveNewMessageDirectChat(
                                    npub = npub,
                                    existingDmGroupIdHex = existingDmGroupIdHex,
                                    provenanceDirectChat = appState::resolveProvenanceDirectChat,
                                    existingDirectChat = { target ->
                                        appState.resolveExistingDirectChat(target, existingDmGroupIdHex)
                                    },
                                )
                            },
                            createGroup = appState::createProfileChatGroup,
                            loadCreatedChatListItem = appState::loadCreatedChatListItem,
                            displayName = appState::displayName,
                            markCreateOpenStage = appState::markChatCreateOpenStage,
                            abandonCreateOpenTiming = appState::abandonChatCreateOpenTiming,
                        )
                ) {
                    is StartChatAttemptResult.Open ->
                        onOpenConversation(result.item, result.newlyCreated)
                    is StartChatAttemptResult.Failed -> startChatError = result.error
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
                            enabled = myQrContent != null,
                        )
                    }
                }
                startChatError?.let { error ->
                    item {
                        StartChatErrorCard(
                            error = error,
                            onRetry = {
                                openOrCreateChat(
                                    npub = error.npub,
                                    hexForProgress = error.progressHex,
                                    recipientName = error.recipientName,
                                    retryGroupIdHex = error.retryGroupIdHex,
                                )
                            },
                            onInvite = {
                                context.startActivity(Intent.createChooser(inviteShareIntent(inviteMessage), inviteTitle))
                            },
                            onCopy = { detail ->
                                clipboard.setText(AnnotatedString(detail))
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
                            onClick = {
                                openOrCreateChat(
                                    npub = appState.npub(resolvedHex),
                                    hexForProgress = resolvedHex,
                                    recipientName =
                                        appState
                                            .displayName(resolvedHex)
                                            .takeIf { resolution.state == RecipientPreviewState.Loaded },
                                )
                            },
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
                            onClick = {
                                openOrCreateChat(
                                    npub = candidate.npub,
                                    hexForProgress = candidate.accountIdHex,
                                    recipientName = appState.displayName(candidate.accountIdHex),
                                    existingDmGroupIdHex = candidate.existingDmGroupIdHex,
                                )
                            },
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
                when (val outcome = QrScanResult.resolve(raw, QrScanUseCase.ViewProfile)) {
                    is QrScanOutcome.OpenProfileNpub -> {
                        query = outcome.npub
                        startChatError = null
                    }
                    is QrScanOutcome.OpenProfileNprofile -> {
                        query = outcome.accountIdHex
                        startChatError = null
                    }
                    QrScanOutcome.Invalid ->
                        appState.present(R.string.error_not_white_noise_profile_qr, copyable = true)
                    is QrScanOutcome.FillRecipientQuery ->
                        appState.present(R.string.error_not_white_noise_profile_qr, copyable = true)
                }
            },
        )
    }
    if (showMyQr && activeHex != null && myQrContent != null) {
        ProfileQrSheet(
            appState = appState,
            accountIdHex = activeHex,
            onDismiss = { showMyQr = false },
            showScan = false,
        )
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
internal fun StartChatErrorCard(
    error: StartChatErrorUiState,
    onRetry: () -> Unit,
    onInvite: () -> Unit,
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
            color =
                if (error.invitation) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            if (error.invitation) {
                Button(onClick = onInvite) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share))
                }
            }
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

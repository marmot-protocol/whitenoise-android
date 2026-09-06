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
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
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
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.share.launchInviteShare
import dev.ipf.whitenoise.android.share.presentOutboundShareFailure
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatCreateOpenTiming
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.privacySafeErrorPresentation
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
    val diagnosticReport: String? = null,
    val recipientName: String? = null,
    val invitation: Boolean = false,
    val title: AppText = AppText.Resource(R.string.toast_couldnt_start_chat),
    val retryGroupIdHex: String? = null,
) {
    val copyable: Boolean
        get() = !diagnosticReport.isNullOrBlank()
}

/**
 * MDK created the canonical group but could not load its local chat row yet.
 * The id is safe to retain for a targeted read retry; creating again would
 * create a duplicate conversation.
 */
internal fun createdGroupIdAfterProjectionUnavailable(error: Throwable): String? =
    (error as? MarmotKitException.CreatedGroupProjectionUnavailable)
        ?.groupIdHex
        ?.takeIf { it.isNotBlank() }

private fun startChatFailureReport(error: Throwable): String? =
    if (startProfileChatFailureCopyable(error)) {
        privacySafeErrorPresentation("START_PROFILE_CHAT", error).report
    } else {
        null
    }

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
        diagnosticReport = startChatFailureReport(error),
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
            createdGroupIdAfterProjectionUnavailable(error)?.also {
                markCreateOpenStage(ChatCreateOpenTiming.STAGE_MDK_CREATE_RETURN)
            } ?: run {
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
                    diagnosticReport = startChatFailureReport(error),
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
                            diagnosticReport = null,
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
    dev.ipf.whitenoise.android.share
        .inviteShareIntent(message)

/**
 * Full-screen New Message flow: pick a person to open/start a direct chat, or
 * branch into the New Group picker + setup steps.
 */
@Composable
internal fun NewChatFlowHost(
    appState: WhiteNoiseAppState,
    onOpenConversation: (ChatListItem, Boolean) -> Unit,
    onClose: () -> Unit,
    onGroupCreateSubmitted: () -> Long = { 0L },
    onGroupCreateCompletedOpen: (ChatListItem, Long) -> Unit = { item, _ -> onOpenConversation(item, false) },
    onGroupCreateFlowSuperseded: () -> Unit = {},
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
                onCreateCompletedOpen = onGroupCreateCompletedOpen,
                onCreateSubmitted = onGroupCreateSubmitted,
                onCreateFlowSuperseded = onGroupCreateFlowSuperseded,
                onClose = {
                    onGroupCreateFlowSuperseded()
                    stepName = NewChatStep.NewMessage.name
                },
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
    onCreateCompletedOpen: (ChatListItem, Long) -> Unit,
    onClose: () -> Unit,
    onCreateSubmitted: () -> Long = { 0L },
    onCreateFlowSuperseded: () -> Unit = {},
    initialMembers: List<RecipientSearch.Candidate> = emptyList(),
) {
    val selected = remember { mutableStateListOf<RecipientSearch.Candidate>().apply { addAll(initialMembers) } }
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    if (setupOpen) {
        NewGroupSetupScreen(
            appState = appState,
            members = selected,
            onBack = {
                setupOpen = false
                onCreateFlowSuperseded()
            },
            onCreateCompletedOpen = onCreateCompletedOpen,
            onCreateSubmitted = onCreateSubmitted,
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
    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    var showScanner by remember { mutableStateOf(false) }
    var showMyQr by remember { mutableStateOf(false) }
    var creatingHex by remember { mutableStateOf<String?>(null) }
    var startChatError by remember { mutableStateOf<StartChatErrorUiState?>(null) }
    LaunchedEffect(query) { startChatError = null }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val showMyQrLabel = stringResource(R.string.show_my_qr_code)
    val inviteTitle = stringResource(R.string.invite_to_white_noise)
    val inviteMessage = stringResource(R.string.invite_message)

    fun shareInvite() {
        launchInviteShare(context, inviteMessage, inviteTitle)
            .onFailure { appState.presentOutboundShareFailure("INVITE_SHARE", it) }
    }

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
    val userSearch by rememberRecipientUserSearchState(query, appState)
    val discovered = userSearch.candidates
    val followedIds = userSearch.followedAccountIds
    val matches =
        remember(query, candidates, discovered, followedIds, activeHex) {
            if (identifierQuery) {
                emptyList()
            } else {
                RecipientSearch.mergeAndBrowse(
                    query = query,
                    known = candidates,
                    discovered = discovered,
                    activeAccountIdHex = activeHex,
                    followedAccountIds = followedIds,
                )
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

    fun startOrOpenConversation(candidate: RecipientSearch.Candidate) {
        openOrCreateChat(
            npub = candidate.npub,
            hexForProgress = candidate.accountIdHex,
            recipientName = candidate.displayName,
            existingDmGroupIdHex = candidate.existingDmGroupIdHex,
        )
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
            RecipientSearchField(
                state = queryState,
                placeholder = stringResource(R.string.search_people_hint),
                onPasteRejected = { appState.present(R.string.error_invalid_identity_reference) },
                onScanQr = { showScanner = true },
                modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Dimens.spaceXl),
            ) {
                item {
                    NewMessageQuickActions(
                        query = query,
                        showMyQrLabel = showMyQrLabel,
                        showMyQrEnabled = myQrContent != null,
                        onNewGroup = onNewGroup,
                        onScanQr = { showScanner = true },
                        onShowMyQr = { showMyQr = true },
                        onInviteFriends = ::shareInvite,
                    )
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
                            onInvite = ::shareInvite,
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
                            subtitle = appState.shortNpub(resolvedHex).takeIf { it.isNotBlank() },
                            avatarSeed = resolvedHex,
                            avatarUrl = appState.avatarUrl(resolvedHex),
                            enabled = creatingHex == null,
                            onClick = {
                                startOrOpenConversation(
                                    RecipientSearch.Candidate(
                                        accountIdHex = resolvedHex,
                                        npub = appState.npub(resolvedHex),
                                        displayName = appState.displayName(resolvedHex),
                                    ),
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
                            if (!identifierQuery && userSearch.isSearching) {
                                UserSearchStatusRow(R.string.user_search_searching, showProgress = true)
                            } else {
                                Text(
                                    stringResource(
                                        when {
                                            userSearch.failed -> R.string.user_search_failed
                                            userSearch.isIncomplete -> R.string.user_search_incomplete
                                            else -> R.string.no_matches
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceLg),
                                )
                            }
                        }
                    }
                } else {
                    item { SectionHeader(stringResource(R.string.contacts)) }
                    items(matches, key = { it.accountIdHex }) { candidate ->
                        ContactRow(
                            title = candidate.displayName,
                            subtitle =
                                when {
                                    candidate.isFollowing -> stringResource(R.string.user_search_you_follow)
                                    candidate.searchProfile != null -> stringResource(R.string.user_search_result)
                                    else -> appState.shortNpub(candidate.accountIdHex).takeIf { it.isNotBlank() }
                                },
                            avatarSeed = candidate.accountIdHex,
                            avatarUrl =
                                appState.avatarUrl(candidate.accountIdHex)
                                    ?: ProfileSanitizer.protocolImageUrl(candidate.searchProfile?.picture),
                            isFollowed = candidate.isFollowing,
                            enabled = creatingHex == null,
                            onClick = {
                                if (candidate.source == null && candidate.searchProfile != null) {
                                    appState.presentDiscoveredProfile(candidate.npub, candidate.searchProfile)
                                } else {
                                    startOrOpenConversation(candidate)
                                }
                            },
                            onLongClick = {
                                if (candidate.searchProfile != null) {
                                    appState.presentDiscoveredProfile(candidate.npub, candidate.searchProfile)
                                } else {
                                    appState.presentProfile(candidate.npub)
                                }
                            },
                            trailing =
                                if (creatingHex == candidate.accountIdHex) {
                                    { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
                                } else {
                                    null
                                },
                        )
                    }
                    if (userSearch.isSearching) {
                        item { UserSearchStatusRow(R.string.user_search_searching, showProgress = true) }
                    } else if (userSearch.failed || userSearch.isIncomplete) {
                        item {
                            UserSearchStatusRow(
                                if (userSearch.failed) R.string.user_search_failed else R.string.user_search_incomplete,
                            )
                        }
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
                        queryState.replaceRecipientText(outcome.npub)
                        startChatError = null
                    }
                    is QrScanOutcome.OpenProfileNprofile -> {
                        queryState.replaceRecipientText(outcome.accountIdHex)
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
                TextButton(onClick = { onCopy(requireNotNull(error.diagnosticReport)) }) {
                    Text(stringResource(R.string.copy))
                }
            }
        }
    }
}

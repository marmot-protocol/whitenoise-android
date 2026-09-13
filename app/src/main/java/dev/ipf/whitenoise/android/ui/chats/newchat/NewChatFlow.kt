package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import dev.ipf.whitenoise.android.core.ChatListIdentifierSearch
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
import dev.ipf.whitenoise.android.ui.profile.profileQrContentForNpub
import dev.ipf.whitenoise.android.ui.qr.QrScanOutcome
import dev.ipf.whitenoise.android.ui.qr.QrScannerSheet
import dev.ipf.whitenoise.android.ui.settings.ShareConnectScreen
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
    NewGroupCreationFlow(
        appState = appState,
        onCreateCompletedOpen = onCreateCompletedOpen,
        onClose = onClose,
        onCreateSubmitted = onCreateSubmitted,
        onCreateFlowSuperseded = onCreateFlowSuperseded,
        initialMembers = initialMembers,
    )
}

/** Owns recipient presentation state per active account while native mutations retain their owner. */
@Suppress("FunctionNaming") // Framework naming for the account-owned composable wrapper.
@Composable
internal fun NewMessageScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenConversation: (ChatListItem, Boolean) -> Unit,
    scannerContent: @Composable (() -> Unit, (String) -> Unit) -> Unit = { dismiss, scan ->
        QrScannerSheet(onDismiss = dismiss, onScan = scan)
    },
) {
    if (appState.signOutInProgress || appState.wipeInProgress) return
    key(appState.activeAccountRef, appState.runtimeGeneration) {
        NewMessageAccountScreen(appState, onBack, onNewGroup, onOpenConversation, scannerContent)
    }
}

/** Opens the recipient picker and records a content-free compose observation. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod") // One captured UI/native callback owner.
@Composable
private fun NewMessageAccountScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenConversation: (ChatListItem, Boolean) -> Unit,
    scannerContent: @Composable (() -> Unit, (String) -> Unit) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        appState.recordProductObservation(dev.ipf.whitenoise.android.state.ProductObservation.COMPOSE)
    }
    val accountRef = appState.activeAccountRef
    val runtimeGeneration = remember { appState.runtimeGeneration }
    val session =
        remember(accountRef) {
            NewMessageSession {
                val sameOwner =
                    accountRef != null &&
                        appState.activeAccountRef == accountRef &&
                        appState.runtimeGeneration == runtimeGeneration
                sameOwner && !appState.signOutInProgress && !appState.wipeInProgress
            }
        }
    val queryState = rememberTextFieldState()
    var searchRetry by remember { mutableIntStateOf(0) }
    val query = queryState.text.toString()
    var scannerSession by remember { mutableStateOf<Long?>(null) }
    var nextScannerSession by remember { mutableStateOf(0L) }
    var creatingHex by remember { mutableStateOf<String?>(null) }
    var startChatError by remember { mutableStateOf<StartChatErrorUiState?>(null) }
    DisposableEffect(session) {
        onDispose {
            session.dispose()
            scannerSession = null
        }
    }
    LaunchedEffect(creatingHex, appState.signOutInProgress, appState.wipeInProgress) {
        if (creatingHex != null || !session.isCurrent()) scannerSession = null
    }
    LaunchedEffect(query) { startChatError = null }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val inviteTitle = stringResource(R.string.invite_to_white_noise)
    val inviteMessage = stringResource(R.string.invite_message)

    fun shareInvite() {
        if (!session.isCurrent() || creatingHex != null || scannerSession != null) return
        launchInviteShare(context, inviteMessage, inviteTitle)
            .onFailure { appState.presentOutboundShareFailure("INVITE_SHARE", it) }
    }

    fun leaveScreen(action: () -> Unit) {
        if (!session.isCurrent() || creatingHex != null || scannerSession != null) return
        scannerSession = null
        session.dispose()
        action()
    }

    // Back must stay installed (a disabled handler lets the event fall through
    // to the Activity) but no-op while a tapped person's chat is being created;
    // otherwise the process-lifetime create would yank the user into the new
    // conversation seconds after they left this screen.
    BackHandler {
        if (scannerSession != null && session.isCurrent()) scannerSession = null else leaveScreen(onBack)
    }

    val activeHex = appState.activeAccount?.accountIdHex
    val myNpub = activeHex?.let(appState::npubForDisplay)
    val myQrContent = myNpub?.let(::profileQrContentForNpub)
    val candidates =
        remember(appState.chatListItems, activeHex, appState.profileRevisionForCompose) {
            deriveRecipientCandidates(appState, activeHex)
        }
    val identifierQuery = query.isNotBlank() && !isPlainNameQuery(query)
    val resolution = rememberRecipientResolution(query, appState, retryKey = searchRetry)
    val userSearch by key(query, searchRetry, appState.relationshipRevision) {
        rememberRecipientUserSearchState(query, appState, retryKey = searchRetry)
    }
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

    @Suppress("LongMethod") // Native attempt callbacks share the same captured recipient and lifetime.
    fun openOrCreateChat(
        npub: String,
        hexForProgress: String,
        recipientName: String? = null,
        retryGroupIdHex: String? = null,
        existingDmGroupIdHex: String? = null,
    ) {
        val canOpen = session.isCurrent() && creatingHex == null && scannerSession == null
        if (!canOpen || queryState.text.toString() != query) return
        startChatError = null
        creatingHex = hexForProgress
        scannerSession = null
        appState.beginChatCreateOpenTiming()
        appState.launchMutation {
            try {
                session.ensureCurrent()
                when (
                    val result =
                        attemptOpenOrStartProfileChat(
                            npub = npub,
                            progressHex = hexForProgress,
                            recipientName = recipientName,
                            retryGroupIdHex = retryGroupIdHex,
                            resolveDirectChat = {
                                session.currentValue {
                                    resolveNewMessageDirectChat(
                                        npub = npub,
                                        existingDmGroupIdHex = existingDmGroupIdHex,
                                        provenanceDirectChat = { provenance, target ->
                                            session.currentValue {
                                                appState.resolveProvenanceDirectChat(provenance, target)
                                            }
                                        },
                                        existingDirectChat = { target ->
                                            session.currentValue {
                                                appState.resolveExistingDirectChat(target, existingDmGroupIdHex)
                                            }
                                        },
                                    )
                                }
                            },
                            createGroup = { target ->
                                session.currentValue { appState.createProfileChatGroup(target) }
                            },
                            loadCreatedChatListItem = { id ->
                                session.currentValue { appState.loadCreatedChatListItem(id) }
                            },
                            displayName = appState::displayName,
                            markCreateOpenStage = { if (session.isCurrent()) appState.markChatCreateOpenStage(it) },
                            abandonCreateOpenTiming = {
                                if (session.isCurrent()) appState.abandonChatCreateOpenTiming(it)
                            },
                        )
                ) {
                    is StartChatAttemptResult.Open ->
                        if (session.isCurrent()) {
                            session.dispose()
                            onOpenConversation(result.item, result.newlyCreated)
                        }
                    is StartChatAttemptResult.Failed -> if (session.isCurrent()) startChatError = result.error
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

    val resolvedHex = resolution.resolvedHex?.takeUnless { it.equals(activeHex, ignoreCase = true) }
    val displayedCandidates =
        if (identifierQuery) {
            resolvedHex
                ?.let {
                    listOf(RecipientSearch.Candidate(it, appState.displayName(it), appState.npub(it)))
                }.orEmpty()
        } else {
            matches
        }
    val people =
        displayedCandidates.map { candidate ->
            NewMessagePerson(
                candidate = candidate,
                subtitle = appState.shortNpub(candidate.accountIdHex).takeIf { it.isNotBlank() },
                avatarUrl =
                    appState.avatarUrl(candidate.accountIdHex)
                        ?: ProfileSanitizer.protocolImageUrl(candidate.searchProfile?.picture),
            )
        }

    fun canInteract() =
        session.isCurrent() &&
            creatingHex == null &&
            scannerSession == null &&
            queryState.text.toString() == query

    fun presentPerson(candidate: RecipientSearch.Candidate) {
        if (!canInteract()) return
        if (candidate.searchProfile != null) {
            appState.presentDiscoveredProfile(candidate.npub, candidate.searchProfile)
        } else {
            appState.presentProfile(candidate.npub)
        }
    }
    val connectSession = scannerSession
    if (connectSession != null && creatingHex == null && session.isCurrent()) {
        ShareConnectScreen(
            appState = appState,
            onBack = { if (scannerSession == connectSession) scannerSession = null },
            isCurrent = { session.isCurrent() && creatingHex == null && scannerSession == connectSession },
            onScannedProfile = scanned@{ outcome ->
                if (!session.isCurrent() || creatingHex != null || scannerSession != connectSession) return@scanned
                val recipient =
                    when (outcome) {
                        is QrScanOutcome.OpenProfileNpub -> outcome.npub
                        is QrScanOutcome.OpenProfileNprofile -> outcome.accountIdHex
                        QrScanOutcome.Invalid, is QrScanOutcome.FillRecipientQuery -> return@scanned
                    }
                scannerSession = null
                queryState.replaceRecipientText(recipient)
                startChatError = null
            },
            scannerContent = scannerContent,
        )
    } else {
        NewMessageContent(
            queryState = queryState,
            people = people,
            search = userSearch,
            identifierQuery = identifierQuery,
            resolvingIdentifier = identifierQuery && resolution.state == RecipientPreviewState.Resolving,
            connectQrEnabled = myQrContent != null,
            creatingHex = creatingHex,
            error = startChatError,
            retryableIdentifier = ChatListIdentifierSearch.classify(query) is ChatListIdentifierSearch.Identifier.Nip05,
            actions =
                NewMessageActions(
                    back = { leaveScreen(onBack) },
                    newGroup = { leaveScreen(onNewGroup) },
                    scanQr = {
                        if (canInteract() && myQrContent != null) {
                            nextScannerSession++
                            scannerSession = nextScannerSession
                        }
                    },
                    invite = ::shareInvite,
                    retrySearch = { if (canInteract()) searchRetry++ },
                    retryChat = {
                        startChatError?.let { error ->
                            openOrCreateChat(error.npub, error.progressHex, error.recipientName, error.retryGroupIdHex)
                        }
                    },
                    pasteRejected = {
                        if (session.isCurrent()) appState.present(R.string.error_invalid_identity_reference)
                    },
                    person = { candidate ->
                        if (canInteract()) {
                            if (candidate.source == null && candidate.searchProfile != null) {
                                presentPerson(candidate)
                            } else {
                                startOrOpenConversation(candidate)
                            }
                        }
                    },
                    profile = ::presentPerson,
                    copyError = { if (canInteract()) clipboard.setText(AnnotatedString(it)) },
                ),
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

/** Keeps native failure details and canonical retry identity actionable at large text sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
internal fun StartChatErrorCard(
    error: StartChatErrorUiState,
    onRetry: () -> Unit,
    onInvite: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val title =
        if (error.retryGroupIdHex != null) {
            stringResource(R.string.new_message_chat_created)
        } else {
            error.title.resolveForCompose()
        }
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            if (error.invitation) {
                Button(onClick = onInvite) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share))
                }
            }
            TextButton(onClick = onRetry) {
                Text(
                    stringResource(
                        if (error.retryGroupIdHex != null) R.string.new_message_open_chat else R.string.retry,
                    ),
                )
            }
            if (error.copyable) {
                TextButton(onClick = { onCopy(requireNotNull(error.diagnosticReport)) }) {
                    Text(stringResource(R.string.copy))
                }
            }
        }
    }
}

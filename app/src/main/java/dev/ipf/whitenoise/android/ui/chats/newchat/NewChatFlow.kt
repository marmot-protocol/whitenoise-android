package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ChatListIdentifierSearch
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.diagnostics.DmCreationAttempt
import dev.ipf.whitenoise.android.diagnostics.DmCreationDiagnostics
import dev.ipf.whitenoise.android.diagnostics.DmCreationInteraction
import dev.ipf.whitenoise.android.diagnostics.DmCreationOutcome
import dev.ipf.whitenoise.android.diagnostics.DmCreationPhase
import dev.ipf.whitenoise.android.share.activeAccountInvite
import dev.ipf.whitenoise.android.share.shareAppInvite
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatCreateOpenTiming
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.createProfileChatGroup
import dev.ipf.whitenoise.android.state.forwardTargetsRevision
import dev.ipf.whitenoise.android.state.groupCreateFailureCopyable
import dev.ipf.whitenoise.android.state.privacySafeErrorPresentation
import dev.ipf.whitenoise.android.state.recordProductObservation
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.state.startProfileChatErrorDetail
import dev.ipf.whitenoise.android.state.startProfileChatErrorTitle
import dev.ipf.whitenoise.android.state.startProfileChatFailureDetail
import dev.ipf.whitenoise.android.state.startProfileChatFailureIsMissingSetup
import dev.ipf.whitenoise.android.ui.account.startingAccount
import dev.ipf.whitenoise.android.ui.common.abbreviatesPublicKey
import dev.ipf.whitenoise.android.ui.common.rememberClipboardCopier
import dev.ipf.whitenoise.android.ui.profile.profileQrContentForNpub
import dev.ipf.whitenoise.android.ui.qr.QrScanOutcome
import dev.ipf.whitenoise.android.ui.qr.QrScannerDialog
import dev.ipf.whitenoise.android.ui.settings.AiAgentsScreen
import dev.ipf.whitenoise.android.ui.settings.SettingsRouteMotion
import dev.ipf.whitenoise.android.ui.settings.ShareConnectScreen

internal enum class NewGroupCreateStage {
    Creating,
}

private enum class NewChatStep { NewMessage, NewGroup }

/**
 * MDK created the canonical group but could not load its local chat row yet.
 * The id is safe to retain for a targeted read retry; creating again would
 * create a duplicate conversation.
 */
internal fun createdGroupIdAfterProjectionUnavailable(error: Throwable): String? =
    when (error) {
        is MarmotKitException.CreatedGroupProjectionUnavailable -> error.groupIdHex
        is MarmotKitException.CreatedGroupMemberReportUnavailable -> error.groupIdHex
        else -> null
    }?.takeIf { it.isNotBlank() }

private fun startChatFailureReport(error: Throwable): String? =
    if (groupCreateFailureCopyable(error)) {
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
    invitationRecipientName: (String) -> String? = { recipientName },
    displayName: (String) -> String,
): StartChatErrorUiState {
    val invitation = startProfileChatFailureIsMissingSetup(error)
    return StartChatErrorUiState(
        npub = npub,
        progressHex = progressHex,
        detail = startProfileChatErrorDetail(error, displayName, recipientName),
        diagnosticReport = startChatFailureReport(error),
        recipientName = recipientName,
        invitation = invitation,
        missingInbox = error is MarmotKitException.MissingMemberInboxRoute,
        title = startProfileChatErrorTitle(error, invitationRecipientName),
    )
}

/**
 * Shared direct-chat create/retry state machine used by every profile entry
 * point. Keeping creation and the targeted authoritative read together is
 * important: a successful MLS create must retry by group id rather than
 * creating a second direct chat when projection is merely delayed (#1729).
 */
@Suppress("TooGenericExceptionCaught", "LongParameterList")
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
    diagnosticAttempt: DmCreationAttempt? = null,
    invitationRecipientName: (String) -> String? = { recipientName },
): StartChatAttemptResult {
    val groupIdHex: String =
        try {
            retryGroupIdHex
                ?: run {
                    diagnosticAttempt?.record(DmCreationPhase.CREATE, DmCreationOutcome.START)
                    markCreateOpenStage(ChatCreateOpenTiming.STAGE_MDK_CREATE_START)
                    createGroup(npub).also {
                        diagnosticAttempt?.record(DmCreationPhase.CREATE, DmCreationOutcome.SUCCESS)
                        markCreateOpenStage(ChatCreateOpenTiming.STAGE_MDK_CREATE_RETURN)
                    }
                }
        } catch (error: Throwable) {
            val committedGroupId = createdGroupIdAfterProjectionUnavailable(error)
            if (committedGroupId == null) diagnosticAttempt?.failed(DmCreationPhase.CREATE, error)
            if (error is kotlinx.coroutines.CancellationException) {
                abandonCreateOpenTiming(ChatCreateOpenTiming.STAGE_CANCELLED)
                throw error
            }
            committedGroupId?.also {
                diagnosticAttempt?.record(DmCreationPhase.CREATE, DmCreationOutcome.SUCCESS)
                diagnosticAttempt?.failed(DmCreationPhase.PROJECTION, error)
                markCreateOpenStage(ChatCreateOpenTiming.STAGE_MDK_CREATE_RETURN)
            } ?: run {
                abandonCreateOpenTiming(ChatCreateOpenTiming.STAGE_CREATE_FAILED)
                return StartChatAttemptResult.Failed(
                    startChatErrorUiState(
                        npub = npub,
                        progressHex = progressHex,
                        error = error,
                        recipientName = recipientName,
                        invitationRecipientName = invitationRecipientName,
                        displayName = displayName,
                    ),
                )
            }
        }
    return try {
        runCatchingCancellable {
            diagnosticAttempt?.record(DmCreationPhase.PROJECTION, DmCreationOutcome.START)
            StartChatAttemptResult
                .Open(
                    loadCreatedChatListItem(groupIdHex),
                ).also { diagnosticAttempt?.record(DmCreationPhase.PROJECTION, DmCreationOutcome.SUCCESS) }
        }.getOrElse { error ->
            diagnosticAttempt?.failed(DmCreationPhase.PROJECTION, error)
            abandonCreateOpenTiming(ChatCreateOpenTiming.STAGE_AUTHORITATIVE_READ_FAILED)
            StartChatAttemptResult.Failed(
                projectionFailureUi(npub, progressHex, recipientName, groupIdHex, error, displayName),
            )
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        diagnosticAttempt?.failed(DmCreationPhase.PROJECTION, cancelled)
        abandonCreateOpenTiming(ChatCreateOpenTiming.STAGE_CANCELLED)
        throw cancelled
    }
}

/** Retains the committed group for read-only retry while preserving the existing failure UI. */
private fun projectionFailureUi(
    npub: String,
    progressHex: String,
    recipientName: String?,
    groupIdHex: String,
    error: Throwable,
    displayName: (String) -> String,
): StartChatErrorUiState =
    StartChatErrorUiState(
        npub = npub,
        progressHex = progressHex,
        detail = startProfileChatFailureDetail(error, displayName),
        diagnosticReport = startChatFailureReport(error),
        recipientName = recipientName,
        title = AppText.Resource(R.string.couldnt_load_chats),
        retryGroupIdHex = groupIdHex,
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
    onGroupCreateSubmitted: () -> Long = { 0L },
    onGroupCreateCompletedOpen: (ChatListItem, Long) -> Unit = { item, _ -> onOpenConversation(item, false) },
    onGroupCreateFlowSuperseded: () -> Unit = {},
) {
    var stepName by rememberSaveable { mutableStateOf(NewChatStep.NewMessage.name) }
    val step = runCatching { NewChatStep.valueOf(stepName) }.getOrDefault(NewChatStep.NewMessage)
    SettingsRouteMotion(
        target = step,
        depth = if (step == NewChatStep.NewGroup) 1 else 0,
        ownerKey = appState.activeAccountRef to appState.runtimeGeneration,
        cardMorph = true,
    ) { visibleStep ->
        when (visibleStep) {
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
        QrScannerDialog(onDismiss = dismiss, onScan = scan)
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
    val preparationCoordinator = remember(session) { NewMessageRecipientPreparationCoordinator() }
    val contactsLoadAttempt =
        remember(session) {
            appState.beginHostPerformance(HostPerformanceOperationFfi.CONTACTS_LOAD)
        }
    var profileTimingKey by remember(session) { mutableStateOf<NewMessageRecipientPreparationKey?>(null) }
    val queryState = rememberTextFieldState()
    var searchRetry by remember { mutableIntStateOf(0) }
    val query = queryState.text.toString()
    var scannerSession by remember { mutableStateOf<Long?>(null) }
    var agentSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var nextScannerSession by remember { mutableStateOf(0L) }
    var creatingHex by remember { mutableStateOf<String?>(null) }
    var inviteShareInProgress by remember { mutableStateOf(false) }
    var startChatError by remember { mutableStateOf<StartChatErrorUiState?>(null) }
    DisposableEffect(session) {
        onDispose {
            preparationCoordinator.clear()
            contactsLoadAttempt.cancel()
            session.dispose()
            scannerSession = null
        }
    }
    LaunchedEffect(creatingHex, appState.signOutInProgress, appState.wipeInProgress) {
        if (creatingHex != null || !session.isCurrent()) scannerSession = null
    }
    LaunchedEffect(query) { startChatError = null }
    val clipboard = rememberClipboardCopier()
    val context = LocalContext.current
    val invite = activeAccountInvite(appState)

    /** Shares the invite QR card and personal text, retaining the localized text-only fallback. */
    fun shareInvite() {
        val flowBusy = creatingHex != null || scannerSession != null || agentSettingsOpen || inviteShareInProgress
        if (!session.isCurrent() || flowBusy) return
        inviteShareInProgress = true
        appState.launchMutation {
            appState.shareAppInvite(context, invite, "INVITE_SHARE") { session.isCurrent() }
            if (session.isCurrent()) inviteShareInProgress = false
        }
    }

    /** Leaves the screen through [action] after closing the scanner, unless the flow is busy. */
    fun leaveScreen(action: () -> Unit) {
        if (!session.isCurrent() || creatingHex != null || scannerSession != null) return
        if (agentSettingsOpen) return
        scannerSession = null
        session.dispose()
        action()
    }

    // Back must stay installed (a disabled handler lets the event fall through
    // to the Activity) but no-op while a tapped person's chat is being created;
    // otherwise the process-lifetime create would yank the user into the new
    // conversation seconds after they left this screen.
    BackHandler {
        when {
            agentSettingsOpen -> if (session.isCurrent()) agentSettingsOpen = false
            scannerSession != null && session.isCurrent() -> scannerSession = null
            else -> leaveScreen(onBack)
        }
    }

    val activeHex = appState.activeAccount?.accountIdHex
    val myNpub = activeHex?.let(appState::npubForDisplay)
    val myQrContent = myNpub?.let(::profileQrContentForNpub)
    val candidates =
        remember(appState.chatListItems, activeHex, appState.profileRevisionForCompose) {
            deriveRecipientCandidates(appState, activeHex)
        }
    val identifierQuery = query.isNotBlank() && !isPlainNameQuery(query, appState::accountIdHexForMention)
    val addressQuery = ChatListIdentifierSearch.classify(query) is ChatListIdentifierSearch.Identifier.Nip05
    val resolution =
        rememberRecipientResolution(query, appState, retryKey = searchRetry) { stage ->
            when (stage) {
                RecipientResolutionStage.Cleared ->
                    appState.abandonChatCreateOpenTiming(ChatCreateOpenTiming.STAGE_RECIPIENT_REPLACED)
                RecipientResolutionStage.IdentifierStarted ->
                    appState.beginChatCreateOpenTiming(
                        ChatCreateOpenTiming.STAGE_IDENTIFIER_INPUT,
                        restart = true,
                    )
                RecipientResolutionStage.IdentifierResolved ->
                    appState.markChatCreateOpenStage(ChatCreateOpenTiming.STAGE_IDENTIFIER_RESOLVED)
                RecipientResolutionStage.Invalid ->
                    appState.abandonChatCreateOpenTiming(ChatCreateOpenTiming.STAGE_IDENTIFIER_INVALID)
                RecipientResolutionStage.ProfileRefreshStarted ->
                    appState.markChatCreateOpenStage(ChatCreateOpenTiming.STAGE_PROFILE_REFRESH_START)
                RecipientResolutionStage.ProfileRefreshFinished ->
                    appState.markChatCreateOpenStage(ChatCreateOpenTiming.STAGE_PROFILE_REFRESH_RETURN)
            }
        }
    val directoryQuery = query.takeIf { resolution.resolvedHex == null }.orEmpty()
    val userSearch by key(query, searchRetry, appState.relationshipRevision) {
        rememberRecipientUserSearchState(directoryQuery, appState, retryKey = searchRetry)
    }
    val discovered = userSearch.candidates
    val followedIds = userSearch.followedAccountIds
    val matches =
        remember(query, resolution.resolvedHex, candidates, discovered, followedIds, activeHex) {
            recipientDirectoryMatches(
                query = query,
                resolvedHex = resolution.resolvedHex,
                known = candidates,
                discovered = discovered,
                activeAccountIdHex = activeHex,
                followedAccountIds = followedIds,
                accountIdHex = appState::accountIdHexForMention,
            )
        }
    SideEffect { contactsLoadAttempt.success() }

    val resolvedHex = resolution.resolvedHex?.takeUnless { it.equals(activeHex, ignoreCase = true) }
    val identifierDiagnostic = remember(accountRef, runtimeGeneration, resolvedHex) { DmCreationInteraction() }
    var tappedDiagnostic by remember(accountRef, runtimeGeneration, resolvedHex) {
        mutableStateOf<Pair<String, DmCreationInteraction>?>(null)
    }
    val identifierPreparationKey =
        if (identifierQuery && accountRef != null && resolvedHex != null) {
            NewMessageRecipientPreparationKey(
                accountRef = accountRef,
                runtimeGeneration = runtimeGeneration,
                query = query,
                targetReference = appState.npub(resolvedHex),
                retryKey = searchRetry,
                chatRevision = appState.forwardTargetsRevision,
            )
        } else {
            null
        }
    LaunchedEffect(identifierPreparationKey) {
        val key = identifierPreparationKey
        if (key == null) {
            preparationCoordinator.clear()
            profileTimingKey = null
            return@LaunchedEffect
        }
        appState.markChatCreateOpenStage(ChatCreateOpenTiming.STAGE_RECIPIENT_ROW_READY)
        val diagnosticPreparation = identifierDiagnostic.preparation()
        val preparation =
            preparationCoordinator.prepare(
                scope = this,
                key = key,
                prewarm = {
                    session.currentValue {
                        appState.prewarmNewMessageRecipient(key.accountRef, key.targetReference)
                    }
                },
                lookup = {
                    session.currentValue {
                        appState.resolveExistingDirectChat(
                            key.targetReference,
                            diagnosticAttempt = diagnosticPreparation,
                        )
                    }
                },
                markStage = { if (session.isCurrent()) appState.markChatCreateOpenStage(it) },
                diagnosticAttempt = diagnosticPreparation,
            )
        preparation.awaitCompletion()
    }
    val resolvedProfileAvailable = resolvedHex?.let(appState::userProfile) != null
    LaunchedEffect(identifierPreparationKey, appState.profileRevisionForCompose, resolvedProfileAvailable) {
        val key = identifierPreparationKey
        if (key != null && resolvedProfileAvailable && profileTimingKey != key) {
            profileTimingKey = key
            appState.markChatCreateOpenStage(ChatCreateOpenTiming.STAGE_PROFILE_DISPLAYED)
        }
    }

    /** Opens the existing direct chat with the recipient or creates it, tracking progress by hex. */
    @Suppress("LongMethod") // Native attempt callbacks share the same captured recipient and lifetime.
    fun openOrCreateChat(
        npub: String,
        hexForProgress: String,
        recipientName: String? = null,
        retryGroupIdHex: String? = null,
        existingDmGroupIdHex: String? = null,
    ) {
        val canOpen = session.isCurrent() && creatingHex == null && scannerSession == null && !agentSettingsOpen
        if (!canOpen || queryState.text.toString() != query) return
        startChatError = null
        creatingHex = hexForProgress
        scannerSession = null
        appState.beginChatCreateOpenTiming()
        val interaction =
            tappedDiagnostic?.takeIf { it.first == npub }?.second
                ?: if (resolvedHex == hexForProgress) identifierDiagnostic else DmCreationInteraction()
        tappedDiagnostic = npub to interaction
        val diagnosticAttempt = interaction.nextAttempt()
        val preparationKeyForTap =
            identifierPreparationKey?.takeIf {
                retryGroupIdHex == null &&
                    existingDmGroupIdHex == null &&
                    it.targetReference == npub &&
                    it.chatRevision == appState.forwardTargetsRevision
            }
        val preparedLookup = preparationKeyForTap?.let(preparationCoordinator::current)
        appState.launchMutation {
            try {
                withDmCreationOwner(diagnosticAttempt, session::isCurrent) { markOpened ->
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
                                        preparedLookupOrFresh(
                                            preparedLookup,
                                            revisionMatches = {
                                                preparedLookup?.key?.chatRevision == appState.forwardTargetsRevision
                                            },
                                        ) {
                                            resolveNewMessageDirectChat(
                                                npub = npub,
                                                existingDmGroupIdHex = existingDmGroupIdHex,
                                                provenanceDirectChat = { provenance, target ->
                                                    session.currentValue {
                                                        appState.resolveProvenanceDirectChat(
                                                            provenance,
                                                            target,
                                                            diagnosticAttempt,
                                                        )
                                                    }
                                                },
                                                existingDirectChat = { target ->
                                                    session.currentValue {
                                                        appState.resolveExistingDirectChat(
                                                            target,
                                                            existingDmGroupIdHex,
                                                            diagnosticAttempt,
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    }
                                },
                                createGroup = { target ->
                                    session.currentValue { appState.createProfileChatGroup(target) }
                                },
                                loadCreatedChatListItem = { id ->
                                    session.currentValue { appState.loadCreatedChatListItem(id) }
                                },
                                displayName = appState::displayName,
                                invitationRecipientName = { accountIdHex ->
                                    appState.contactDisplayNameCachedOrNull(accountIdHex)
                                        ?: recipientName?.takeUnless { abbreviatesPublicKey(it, npub) }
                                },
                                markCreateOpenStage = { if (session.isCurrent()) appState.markChatCreateOpenStage(it) },
                                abandonCreateOpenTiming = {
                                    if (session.isCurrent()) appState.abandonChatCreateOpenTiming(it)
                                },
                                directChatLookupAlreadyStarted = preparedLookup != null,
                                diagnosticAttempt = diagnosticAttempt,
                            )
                    ) {
                        is StartChatAttemptResult.Open ->
                            if (session.isCurrent()) {
                                accountRef?.let {
                                    DmCreationDiagnostics.awaitFrame(
                                        it,
                                        result.item.group.groupIdHex,
                                        runtimeGeneration,
                                        diagnosticAttempt,
                                    )
                                }
                                markOpened()
                                session.dispose()
                                onOpenConversation(result.item, result.newlyCreated)
                            }
                        is StartChatAttemptResult.Failed -> if (session.isCurrent()) startChatError = result.error
                    }
                }
            } finally {
                creatingHex = null
            }
        }
    }

    /** Starts or opens the direct chat with a search candidate. */
    fun startOrOpenConversation(candidate: RecipientSearch.Candidate) {
        openOrCreateChat(
            npub = candidate.npub,
            hexForProgress = candidate.accountIdHex,
            recipientName = candidate.displayName,
            existingDmGroupIdHex = candidate.existingDmGroupIdHex,
        )
    }

    val displayedCandidates =
        if (identifierQuery && resolution.resolvedHex != null) {
            resolvedHex
                ?.let {
                    listOf(RecipientSearch.Candidate(it, appState.displayName(it), appState.npub(it)))
                }.orEmpty()
        } else {
            matches
        }
    val people =
        displayedCandidates.map { person ->
            NewMessagePerson(
                candidate = person.copy(npub = appState.npubForDisplay(person.accountIdHex).ifBlank(person::npub)),
                subtitle =
                    if (addressQuery && resolution.resolvedHex == null) {
                        stringResource(R.string.user_search_address_result)
                    } else {
                        appState.shortNpub(person.accountIdHex).takeIf { it.isNotBlank() }
                    },
                avatarUrl =
                    appState.contactAvatarSource(person.accountIdHex)
                        ?: ProfileSanitizer.protocolImageUrl(person.searchProfile?.picture),
            )
        }

    /** True while the flow is current and no creation or scan is in progress. */
    fun canInteract() =
        session.isCurrent() &&
            creatingHex == null &&
            scannerSession == null &&
            !agentSettingsOpen &&
            queryState.text.toString() == query

    /** Opens the candidate's profile, discovered or known. */
    fun presentPerson(
        candidate: RecipientSearch.Candidate,
        origin: Rect?,
    ) {
        if (!canInteract()) return
        if (candidate.searchProfile != null) {
            appState.presentDiscoveredProfile(candidate.npub, candidate.searchProfile, origin = origin)
        } else {
            appState.presentProfile(candidate.npub, origin = origin)
        }
    }
    val connectSession = scannerSession
    if (agentSettingsOpen && session.isCurrent()) {
        AiAgentsScreen(appState, onBack = { if (session.isCurrent()) agentSettingsOpen = false })
    } else if (connectSession != null && creatingHex == null && session.isCurrent()) {
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
            startingAs = startingAccount(appState, accountRef, runtimeGeneration),
            inviteShareInProgress = inviteShareInProgress,
            isValidNpub = { npub -> appState.accountIdHexForMention(npub) != null },
            queryState = queryState,
            people = people,
            search = userSearch,
            identifierQuery = identifierQuery,
            resolvingIdentifier = identifierQuery && resolution.state == RecipientPreviewState.Resolving,
            connectQrEnabled = myQrContent != null,
            creatingHex = creatingHex,
            error = startChatError,
            retryableIdentifier = addressQuery,
            identifierLookupFailed = recipientAddressLookupFailed(query, resolution.state),
            addressFallback = addressQuery && resolution.resolvedHex == null,
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
                    agentSettings = { if (canInteract() && !inviteShareInProgress) agentSettingsOpen = true },
                    retrySearch = { if (canInteract()) searchRetry++ },
                    retryChat = {
                        startChatError?.let { error ->
                            openOrCreateChat(error.npub, error.progressHex, error.recipientName, error.retryGroupIdHex)
                        }
                    },
                    pasteRejected = {
                        if (session.isCurrent()) appState.present(R.string.error_invalid_identity_reference)
                    },
                    person = { candidate, origin ->
                        if (canInteract()) {
                            if (candidate.source == null && candidate.searchProfile != null) {
                                presentPerson(candidate, origin)
                            } else {
                                startOrOpenConversation(candidate)
                            }
                        }
                    },
                    profile = ::presentPerson,
                    copyError = { if (canInteract()) clipboard.copy(it) },
                ),
        )
    }
}

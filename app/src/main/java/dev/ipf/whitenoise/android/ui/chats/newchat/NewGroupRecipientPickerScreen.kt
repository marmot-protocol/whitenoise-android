package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.qr.QrScanOutcome
import dev.ipf.whitenoise.android.ui.qr.QrScanResult
import dev.ipf.whitenoise.android.ui.qr.QrScanUseCase
import dev.ipf.whitenoise.android.ui.qr.QrScannerSheet

/** New-group-only picker; Add Members retains its separate selection and auto-resolution contract. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
internal fun NewGroupRecipientPickerScreen(
    appState: WhiteNoiseAppState,
    selected: SnapshotStateList<RecipientSearch.Candidate>,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    scannerContent: @Composable (onDismiss: () -> Unit, onScan: (String) -> Unit) -> Unit = { dismiss, scan ->
        QrScannerSheet(onDismiss = dismiss, onScan = scan)
    },
) {
    if (appState.signOutInProgress || appState.wipeInProgress) return
    key(appState.activeAccountRef, appState.runtimeGeneration) {
        NewGroupRecipientAccountScreen(appState, selected, onBack, onConfirm, scannerContent)
    }
}

/** Existing local/native recipient stream and resolution; every action rechecks the live account and screen. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod") // Compose naming follows the framework convention.
private fun NewGroupRecipientAccountScreen(
    appState: WhiteNoiseAppState,
    selected: SnapshotStateList<RecipientSearch.Candidate>,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    scannerContent: @Composable (onDismiss: () -> Unit, onScan: (String) -> Unit) -> Unit,
) {
    val followedLabel = stringResource(R.string.user_search_you_follow)
    val resultLabel = stringResource(R.string.user_search_result)
    val account = appState.activeAccountRef
    val runtime = remember { appState.runtimeGeneration }
    val owner =
        remember {
            GroupCreationSession {
                account != null &&
                    appState.activeAccountRef == account &&
                    appState.runtimeGeneration == runtime &&
                    !appState.signOutInProgress &&
                    !appState.wipeInProgress
            }
        }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    var retry by remember { mutableIntStateOf(0) }
    var reviewing by remember { mutableStateOf(false) }
    var scannerSession by remember { mutableStateOf<Long?>(null) }
    var nextScannerSession by remember { mutableLongStateOf(0L) }
    val resolution = rememberRecipientResolution(query, appState, retry)
    val search by rememberRecipientUserSearchState(query, appState, retry)
    val activeHex = appState.activeAccount?.accountIdHex
    val candidates =
        remember(appState.chatListItems, activeHex, appState.profileRevisionForCompose) {
            deriveRecipientCandidates(appState, activeHex)
        }
    val identifier = query.isNotBlank() && !isPlainNameQuery(query)
    val resolvedHex = resolution.resolvedHex?.takeUnless { it.equals(activeHex, true) }
    val matches =
        if (identifier) {
            resolvedHex
                ?.let { hex ->
                    listOf(RecipientSearch.Candidate(hex, appState.displayName(hex), appState.npub(hex)))
                }.orEmpty()
        } else {
            RecipientSearch.mergeAndBrowse(
                query,
                candidates,
                search.candidates,
                activeHex,
                followedAccountIds = search.followedAccountIds,
            )
        }

    fun person(candidate: RecipientSearch.Candidate) =
        GroupCreationPerson(
            candidate.copy(displayName = selectedMemberDisplayName(candidate, appState)),
            when {
                candidate.isFollowing -> followedLabel
                candidate.searchProfile != null -> resultLabel
                else -> appState.shortNpub(candidate.accountIdHex).takeIf { it.isNotBlank() }
            },
            appState.avatarUrl(candidate.accountIdHex)
                ?: ProfileSanitizer.protocolImageUrl(candidate.searchProfile?.picture),
        )

    fun toggle(candidate: RecipientSearch.Candidate) {
        if (!owner.isCurrent() || candidate.accountIdHex.equals(activeHex, true)) return
        if (selected.any { it.accountIdHex.equals(candidate.accountIdHex, true) }) {
            selected.removeAll { it.accountIdHex.equals(candidate.accountIdHex, true) }
        } else {
            selected.add(candidate)
            queryState.replaceRecipientText("")
        }
        if (selected.isEmpty()) reviewing = false
    }

    fun leave(callback: () -> Unit) {
        if (!owner.isCurrent()) return
        scannerSession = null
        owner.dispose()
        callback()
    }

    BackHandler {
        if (owner.isCurrent()) {
            if (reviewing) reviewing = false else leave(onBack)
        }
    }
    if (reviewing) {
        SelectedMembersReviewScreen(
            selected,
            appState,
            busy = false,
            onBack = { if (owner.isCurrent()) reviewing = false },
            onRemove = ::toggle,
            onConfirm = { leave(onConfirm) },
            confirmIcon = Icons.AutoMirrored.Filled.ArrowForward,
            confirmLabel = stringResource(R.string.group_continue),
        )
    } else {
        NewGroupRecipientContent(
            queryState,
            matches.map(::person),
            selected.map(::person),
            searching = if (identifier) resolution.state == RecipientPreviewState.Resolving else search.isSearching,
            failed = !identifier && search.failed,
            incomplete = !identifier && search.isIncomplete,
            actions =
                NewGroupRecipientActions(
                    back = { leave(onBack) },
                    confirm = { leave(onConfirm) },
                    review = { if (owner.isCurrent() && selected.isNotEmpty()) reviewing = true },
                    scan = { if (owner.isCurrent()) scannerSession = ++nextScannerSession },
                    pasteRejected = {
                        if (owner.isCurrent()) appState.present(R.string.error_invalid_identity_reference)
                    },
                    retry = { if (owner.isCurrent()) retry++ },
                    toggle = ::toggle,
                    profile = { candidate ->
                        if (owner.isCurrent()) {
                            if (candidate.searchProfile != null) {
                                appState.presentDiscoveredProfile(candidate.npub, candidate.searchProfile)
                            } else {
                                appState.presentProfile(candidate.npub)
                            }
                        }
                    },
                ),
        )
    }
    scannerSession?.takeIf { owner.isCurrent() }?.let { token ->
        scannerContent(
            { if (scannerSession == token) scannerSession = null },
            { raw ->
                if (owner.isCurrent() && scannerSession == token) {
                    scannerSession = null
                    when (val outcome = QrScanResult.resolve(raw, QrScanUseCase.PickRecipient)) {
                        is QrScanOutcome.FillRecipientQuery -> queryState.replaceRecipientText(outcome.reference)
                        else -> appState.present(R.string.error_qr_not_valid_npub_or_public_key, copyable = true)
                    }
                }
            },
        )
    }
}

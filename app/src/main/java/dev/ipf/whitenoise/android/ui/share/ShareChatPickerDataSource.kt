package dev.ipf.whitenoise.android.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.loadAccountWideForwardTargets
import dev.ipf.whitenoise.android.state.mergeForwardTargets

internal data class ShareChatPickerDataSource(
    val controller: ChatsController?,
    val targets: List<ChatListItem>,
    val isLoading: Boolean,
    val targetsComplete: Boolean,
    val error: ErrorPresentation?,
    val memberSnapshotsRevision: Long,
    // Observe projection publication even while the visible chat list is frozen.
    val targetsRevision: Long,
    val retryLoad: () -> Unit,
)

internal data class ShareChatPickerSelectionState(
    val selectedAccountRefState: MutableState<String?>,
    val selectedAccountRef: String?,
    val queryState: MutableState<String>,
    val selectedState: MutableState<ArrayList<String>>,
    val searchFocusedState: MutableState<Boolean>,
    val accountSelectorOpenState: MutableState<Boolean>,
)

@Composable
internal fun rememberShareChatPickerSelectionState(
    accounts: List<AccountSummaryFfi>,
    activeAccountRef: String?,
    requestId: String,
    payload: SharePayload,
): ShareChatPickerSelectionState {
    val initialAccountRef =
        activeAccountRef?.takeIf { active -> accounts.any { it.label == active } }
            ?: accounts.firstOrNull()?.label
    val selectedAccountRefState =
        rememberSaveable(requestId, payload) {
            mutableStateOf(initialAccountRef)
        }
    val selectedAccountRef =
        selectedAccountRefState.value.takeIf { selected -> accounts.any { it.label == selected } }
            ?: initialAccountRef
    val selectedState = rememberSaveable(requestId, payload) { mutableStateOf(arrayListOf<String>()) }
    LaunchedEffect(selectedAccountRef, selectedAccountRefState.value) {
        if (selectedAccountRefState.value != selectedAccountRef) {
            selectedAccountRefState.value = selectedAccountRef
            selectedState.value = arrayListOf()
        }
    }
    return ShareChatPickerSelectionState(
        selectedAccountRefState = selectedAccountRefState,
        selectedAccountRef = selectedAccountRef,
        queryState = rememberSaveable(requestId, payload) { mutableStateOf("") },
        selectedState = selectedState,
        searchFocusedState = remember(requestId, payload) { mutableStateOf(false) },
        accountSelectorOpenState = remember(requestId, payload) { mutableStateOf(false) },
    )
}

/**
 * The picker's target source for [selectedAccountRef]: the active account's controller, or a picker-owned
 * controller bound to another account, each merged with the one account-wide read behind the open picker.
 */
@Composable
@Suppress("LongMethod") // The three branches assemble the same account-owned source and retry action.
internal fun rememberShareChatPickerDataSource(
    appState: WhiteNoiseAppState,
    selectedAccountRef: String?,
    controllerFactory: (WhiteNoiseAppState) -> ChatsController,
    controllerBinder: suspend (ChatsController, String) -> Unit,
): ShareChatPickerDataSource {
    val accountController =
        if (selectedAccountRef != null && selectedAccountRef != appState.activeAccountRef) {
            remember(appState, selectedAccountRef) { controllerFactory(appState) }
        } else {
            null
        }
    DisposableEffect(accountController) {
        onDispose { accountController?.onCleared() }
    }
    LaunchedEffect(accountController, selectedAccountRef) {
        if (accountController != null && selectedAccountRef != null) {
            controllerBinder(accountController, selectedAccountRef)
        }
    }
    val retryRevision = remember(appState, selectedAccountRef) { mutableStateOf(0) }
    val accountWideTargets =
        rememberAccountWideForwardTargets(
            appState = appState,
            accountController = accountController,
            selectedAccountRef = selectedAccountRef,
            retryRevision = retryRevision.value,
        )

    return when {
        selectedAccountRef == null ->
            ShareChatPickerDataSource(
                controller = null,
                targets = emptyList(),
                isLoading = false,
                targetsComplete = true,
                error = null,
                memberSnapshotsRevision = 0L,
                targetsRevision = 0L,
                retryLoad = {},
            )
        accountController != null ->
            ShareChatPickerDataSource(
                controller = accountController,
                targets = mergeForwardTargets(accountController.forwardTargets(), accountWideTargets.items),
                isLoading = accountController.isLoading,
                targetsComplete = accountWideTargets.complete,
                error = accountController.error,
                memberSnapshotsRevision = accountController.memberSnapshotsRevision,
                targetsRevision = accountController.forwardTargetsRevision,
                retryLoad = {
                    retryRevision.value += 1
                    accountController.retryLoad()
                },
            )
        else ->
            ShareChatPickerDataSource(
                controller = null,
                targets = mergeForwardTargets(appState.forwardTargets(), accountWideTargets.items),
                isLoading = appState.forwardTargetsLoading,
                targetsComplete = accountWideTargets.complete,
                error = appState.forwardTargetsError,
                memberSnapshotsRevision = appState.forwardTargetMembersRevision,
                targetsRevision = appState.forwardTargetsRevision,
                retryLoad = {
                    retryRevision.value += 1
                    appState.retryForwardTargets()
                },
            )
    }
}

/**
 * The one account-wide MDK read behind an open picker (#2618): chats beyond the retained window for
 * [selectedAccountRef], with explicit completeness. Retry retains the same account's already presented rows
 * until a successful replacement arrives. The read is keyed to the account
 * the source controller is actually bound to, so switching accounts cancels it and a late result for the
 * previous account is never merged into the next one; the controller rejects a result whose binding
 * changed while the read ran.
 */
@Composable
private fun rememberAccountWideForwardTargets(
    appState: WhiteNoiseAppState,
    accountController: ChatsController?,
    selectedAccountRef: String?,
    retryRevision: Int,
): AccountWidePickerTargets {
    // The controller has no account to read for until its bind has started, which is when it publishes
    // boundAccountRef; the merge prefers retained rows, so reading before the first window frame is safe.
    val boundAccountRef = accountController?.boundAccountRef ?: appState.activeAccountRef
    val targets =
        remember(appState, accountController, selectedAccountRef, boundAccountRef) {
            mutableStateOf(AccountWidePickerTargets())
        }
    LaunchedEffect(accountController, selectedAccountRef, boundAccountRef, retryRevision) {
        if (selectedAccountRef == null || boundAccountRef != selectedAccountRef) return@LaunchedEffect
        targets.value = targets.value.copy(complete = false)
        // A bound controller is authoritative for its account: a null read must not fall back to the active
        // account's rows, which would list another account's chats under this selection.
        val loaded =
            if (accountController != null) {
                accountController.loadAccountWideForwardTargets()
            } else {
                appState.loadAccountWideForwardTargets()
            }
        targets.value = AccountWidePickerTargets(loaded ?: targets.value.items, loaded != null)
    }
    return targets.value
}

/** Transient picker projection and whether its latest account-wide read completed successfully. */
private data class AccountWidePickerTargets(
    val items: List<ChatListItem>? = null,
    val complete: Boolean = false,
)

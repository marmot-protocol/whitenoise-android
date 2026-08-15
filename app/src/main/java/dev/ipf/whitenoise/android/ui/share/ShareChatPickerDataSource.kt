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

internal data class ShareChatPickerDataSource(
    val controller: ChatsController?,
    val targets: List<ChatListItem>,
    val isLoading: Boolean,
    val error: ErrorPresentation?,
    val memberSnapshotsRevision: Long,
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

@Composable
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

    return when {
        selectedAccountRef == null ->
            ShareChatPickerDataSource(
                controller = null,
                targets = emptyList(),
                isLoading = false,
                error = null,
                memberSnapshotsRevision = 0L,
                retryLoad = {},
            )
        accountController != null ->
            ShareChatPickerDataSource(
                controller = accountController,
                targets = accountController.forwardTargets(),
                isLoading = accountController.isLoading,
                error = accountController.error,
                memberSnapshotsRevision = accountController.memberSnapshotsRevision,
                retryLoad = accountController::retryLoad,
            )
        else ->
            ShareChatPickerDataSource(
                controller = null,
                targets = appState.forwardTargets(),
                isLoading = appState.forwardTargetsLoading,
                error = appState.forwardTargetsError,
                memberSnapshotsRevision = appState.forwardTargetMembersRevision,
                retryLoad = appState::retryForwardTargets,
            )
    }
}

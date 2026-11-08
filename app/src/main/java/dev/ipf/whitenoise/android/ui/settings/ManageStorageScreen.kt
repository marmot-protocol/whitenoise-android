@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ManageStorageController
import dev.ipf.whitenoise.android.state.ManageStorageUiState
import dev.ipf.whitenoise.android.state.StorageAction
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.manageStorageAccess
import dev.ipf.whitenoise.android.state.manageStorageEligible

/** The list key that opens the account-wide largest-first list instead of one chat's. */
internal const val LARGEST_ATTACHMENTS_LIST = "*"

/** A new controller for every account, runtime or eligibility change, so no figure crosses accounts. */
@Composable
internal fun rememberManageStorageController(appState: WhiteNoiseAppState): ManageStorageController {
    val scope = rememberCoroutineScope()
    val eligible = appState.manageStorageEligible()
    val controller =
        remember(appState.activeAccountRef, appState.runtimeGeneration, eligible) {
            ManageStorageController(appState.manageStorageAccess(), scope)
        }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller) { controller.refresh() }
    return controller
}

/**
 * Manage Storage: what this profile keeps on the device and the ways to free space. Opening a chat or the largest
 * attachments shows that list in place, and Back returns to the overview with fresh figures.
 */
@Composable
internal fun ManageStorageScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val controller = rememberManageStorageController(appState)
    val state by controller.state.collectAsStateWithLifecycle()
    var list by rememberSaveable(appState.activeAccountRef) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(state.outcome) {
        state.outcome?.let {
            appState.present(storageOutcomeText(context, it))
            controller.outcomeShown()
        }
    }
    val openList = list
    if (openList == null) {
        ManageStorageContent(
            appState = appState,
            state = state,
            onBack = onBack,
            actions =
                ManageStorageActions(
                    onRefresh = controller::refresh,
                    onAction = controller::perform,
                    onOpenList = { list = it },
                    onLoadMoreChats = controller::loadMoreChats,
                ),
        )
    } else {
        BackHandler { list = null }
        StoredAttachmentsRoute(
            appState = appState,
            storage = state,
            groupIdHex = openList.takeUnless { it == LARGEST_ATTACHMENTS_LIST },
            onBack = { list = null },
            onAction = controller::perform,
        )
    }
}

/** What the overview can ask its owner to do. */
internal data class ManageStorageActions(
    val onRefresh: () -> Unit,
    val onAction: (StorageAction) -> Unit,
    val onOpenList: (String) -> Unit,
    val onLoadMoreChats: () -> Unit,
)

/** The overview: usage, free-up actions, Keep media and chats by size, with its confirmations. */
@Composable
internal fun ManageStorageContent(
    appState: WhiteNoiseAppState,
    state: ManageStorageUiState,
    onBack: () -> Unit,
    actions: ManageStorageActions,
) {
    val dialogs = rememberStorageDialogs()
    SettingsScaffold(title = stringResource(R.string.manage_storage_title), onBack = onBack) {
        SettingsList(listTag = "storage.list") {
            storageUsageItems(state, actions.onRefresh)
            storageFreeUpItems(state, dialogs, actions.onOpenList)
            storageKeepMediaItems(state, dialogs)
            storageChatItems(appState, state, actions)
        }
    }
    StorageOverviewDialogs(state, dialogs, actions.onAction)
}

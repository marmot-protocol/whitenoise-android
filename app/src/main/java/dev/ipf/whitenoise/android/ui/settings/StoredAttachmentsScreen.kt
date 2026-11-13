@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ManageStorageUiState
import dev.ipf.whitenoise.android.state.StorageAction
import dev.ipf.whitenoise.android.state.StoredAttachmentsController
import dev.ipf.whitenoise.android.state.StoredAttachmentsUiState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.manageStorageAccess
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

private val StorageLoadingIndicatorSize = 32.dp

/** What the attachment list can ask its owner to do. */
internal data class StoredAttachmentsActions(
    val onToggle: (String) -> Unit,
    val onSelectAll: (Boolean) -> Unit,
    val onLoadMore: () -> Unit,
    val onRetry: () -> Unit,
    val onAction: (StorageAction) -> Unit,
)

/**
 * The largest-first list for one chat, or for the whole account when [groupIdHex] is null. It reloads after every
 * finished action, so removed rows leave and the selection never outlives them.
 */
@Composable
internal fun StoredAttachmentsRoute(
    appState: WhiteNoiseAppState,
    storage: ManageStorageUiState,
    groupIdHex: String?,
    onBack: () -> Unit,
    onAction: (StorageAction) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller =
        remember(appState.activeAccountRef, appState.runtimeGeneration, groupIdHex) {
            StoredAttachmentsController(appState.manageStorageAccess(), scope, groupIdHex)
        }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller, storage.revision) { controller.reload() }
    val state by controller.state.collectAsStateWithLifecycle()
    StoredAttachmentsContent(
        appState = appState,
        storage = storage,
        list = state,
        groupIdHex = groupIdHex,
        onBack = onBack,
        actions =
            StoredAttachmentsActions(
                onToggle = controller::toggle,
                onSelectAll = controller::selectAll,
                onLoadMore = controller::loadMore,
                onRetry = { if (state.items.isEmpty()) controller.reload() else controller.loadMore() },
                onAction = onAction,
            ),
    )
}

/** A chat's actions above its attachments, or the account's largest attachments, with a selection bar. */
@Composable
internal fun StoredAttachmentsContent(
    appState: WhiteNoiseAppState,
    storage: ManageStorageUiState,
    list: StoredAttachmentsUiState,
    groupIdHex: String?,
    onBack: () -> Unit,
    actions: StoredAttachmentsActions,
) {
    val dialogs = rememberStorageDialogs()
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val title =
        groupIdHex?.let { storageChatTitle(appState, it) } ?: stringResource(R.string.storage_largest_attachments)
    SettingsScaffold(
        title = title,
        onBack = onBack,
        topBarActions = { if (list.items.isNotEmpty()) SelectAllAction(list, actions.onSelectAll) },
        bottomBar = { if (list.selecting) StorageSelectionBar(list, storage.busy) { confirm = SELECTION } },
    ) {
        // No list-wide spacing: attachment rows sit on the segmented gap, other items pad themselves.
        SettingsList(listTag = "storage.attachments", verticalArrangement = Arrangement.Top) {
            if (groupIdHex != null) storageChatActionItems(storage, list, dialogs) { confirm = groupIdHex }
            storageAttachmentItems(appState, list, groupIdHex == null, storage.busy, actions)
        }
    }
    RemoveOlderDialogs(dialogs, groupIdHex, actions.onAction)
    // The confirmation survives recreation but the selection does not, so an emptied selection closes it rather than
    // offering to remove nothing.
    val selectionGone = confirm == SELECTION && list.selection.isEmpty()
    LaunchedEffect(selectionGone) { if (selectionGone) confirm = null }
    confirm?.takeUnless { selectionGone }?.let { target ->
        val selection = target == SELECTION
        val figures =
            if (selection) list.selection.size.toLong() to list.selectedBytes else chatFigures(storage, list, target)
        val onDismiss = { confirm = null }
        val onConfirm = {
            confirm = null
            actions.onAction(
                if (selection) StorageAction.RemoveAttachments(list.selection) else StorageAction.RemoveChat(target),
            )
        }
        if (figures != null) {
            RemoveAttachmentsDialog(figures.first, figures.second, onDismiss, onConfirm)
        } else {
            RemoveChatAttachmentsDialog(onDismiss, onConfirm)
        }
    }
}

/**
 * The chat's attachment count and bytes: summed from its own list once every page is loaded, else the overview's row,
 * else null. The overview pages its chats and reloads only the first page after an action, so it may not hold one, and
 * a list emptied under an open confirmation answers null rather than zero.
 */
internal fun chatFigures(
    storage: ManageStorageUiState,
    list: StoredAttachmentsUiState,
    groupIdHex: String,
): Pair<Long, Long>? =
    if (list.complete && !list.failed && list.items.isNotEmpty()) {
        list.items.size.toLong() to list.items.sumOf { it.bytes }
    } else {
        storage.chats.firstOrNull { it.groupIdHex == groupIdHex }?.let { it.attachmentCount to it.bytes }
    }

/** The confirmation key for removing the selection rather than a whole chat. */
private const val SELECTION = "selection"

/** Remove all attachments and Remove older attachments for one chat. */
private fun LazyListScope.storageChatActionItems(
    storage: ManageStorageUiState,
    list: StoredAttachmentsUiState,
    dialogs: StorageDialogs,
    onRemoveAll: () -> Unit,
) {
    val enabled = storage.available && !storage.busy && list.items.isNotEmpty()
    item(key = "storage.chat.actions") {
        SettingsGroup(modifier = Modifier.testTag("storage.chat.actions")) {
            row("remove_all") { context ->
                SettingsAction(
                    context = context,
                    title = stringResource(R.string.storage_remove_all_in_chat),
                    onClick = onRemoveAll,
                    enabled = enabled,
                    destructive = true,
                    modifier = Modifier.testTag("storage.chat.remove_all"),
                    leading = {
                        SettingsLeadingIcon(R.drawable.ic_delete_sweep, tint = MaterialTheme.colorScheme.error)
                    },
                )
            }
            row("older") { context ->
                SettingsLink(
                    context = context,
                    title = stringResource(R.string.storage_remove_older),
                    onClick = { dialogs.olderPicker.value = true },
                    enabled = enabled,
                    modifier = Modifier.testTag("storage.chat.remove_older"),
                    leading = { SettingsLeadingIcon(R.drawable.ic_auto_delete) },
                )
            }
        }
        Box(Modifier.padding(top = WhiteNoiseSpacing.Related, bottom = WhiteNoiseSpacing.Related)) {
            SettingsExplainer(stringResource(R.string.storage_removal_help))
        }
    }
}

/**
 * Select all, or clear the selection once everything loaded is selected. Before the last page loads it selects only
 * the rows shown, and says so.
 */
@Composable
private fun SelectAllAction(
    list: StoredAttachmentsUiState,
    onSelectAll: (Boolean) -> Unit,
) {
    val all = list.selected.size == list.items.size
    IconButton(onClick = { onSelectAll(!all) }, modifier = Modifier.testTag("storage.select_all")) {
        Icon(
            painterResource(if (all) R.drawable.ic_deselect_all else R.drawable.ic_select_all),
            contentDescription = stringResource(selectAllLabel(all, list.complete)),
        )
    }
}

/** Names the select-all action for its current effect and scope. */
private fun selectAllLabel(
    allSelected: Boolean,
    complete: Boolean,
): Int =
    when {
        allSelected -> R.string.storage_clear_selection
        complete -> R.string.storage_select_all
        else -> R.string.storage_select_shown
    }

/** The pinned bar while selecting: the live count and size, and Remove. */
@Composable
private fun StorageSelectionBar(
    list: StoredAttachmentsUiState,
    busy: Boolean,
    onRemove: () -> Unit,
) {
    val count = list.selected.size
    SettingsBottomAction(modifier = Modifier.testTag("storage.selection")) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
        ) {
            Text(
                pluralStringResource(R.plurals.storage_selected, count, count, storageBytes(list.selectedBytes)),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            WhiteNoiseButton(
                onClick = onRemove,
                enabled = !busy,
                loading = busy,
                loadingLabel = stringResource(R.string.storage_removing),
                modifier = Modifier.testTag("storage.selection.remove"),
            ) { Text(stringResource(R.string.storage_remove)) }
        }
    }
}

/** A failed page's error with a Try again row that resumes from where the list stopped. */
@Composable
internal fun StorageListRetry(
    @StringRes message: Int,
    enabled: Boolean,
    onRetry: () -> Unit,
) {
    SettingsCallout(stringResource(message), isError = true)
    SettingsGroup {
        row("retry") { context ->
            SettingsAction(
                context = context,
                title = stringResource(R.string.storage_retry),
                onClick = onRetry,
                enabled = enabled,
                modifier = Modifier.testTag("storage.list.retry"),
                leading = { SettingsLeadingIcon(R.drawable.ic_replay) },
            )
        }
    }
}

/** A centered progress indicator for a list still paging in. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StorageLoadingRow() {
    Box(Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.FormField), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.size(StorageLoadingIndicatorSize).testTag("storage.loading"))
    }
}

/** A square glyph tile standing in for an attachment's preview. */
@Composable
internal fun StoredAttachmentGlyph(kind: StoredAttachmentKind) {
    Box(
        Modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        Icon(painterResource(kind.iconRes), contentDescription = null, tint = tint)
    }
}

@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDialogCheckRow
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEntityPickerSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.search.GlobalSearchContentFilterChips
import dev.ipf.whitenoise.android.ui.search.GlobalSearchDateCustomStage
import dev.ipf.whitenoise.android.ui.search.GlobalSearchDateFilterDialog
import java.time.ZoneId

internal const val CHAT_LIST_SEARCH_FILTER_DIALOG_TAG = "chat-list-search-filter-dialog"
internal const val CHAT_LIST_SEARCH_FILTER_SEARCH_TAG = "global.filterSearch"
internal const val CHAT_LIST_SEARCH_FILTER_CHOICE_PREFIX = "global.choice"

/** Stable test tag for a check row in a filter picker. */
internal fun globalSearchFilterOptionTag(id: String): String = "chat-list-search-filter-option-$id"

internal data class GlobalSearchFolderOption(
    val id: String,
    val name: String,
)

/** Choices the pickers offer: the account's folders, the chats in scope and the people who write in them. */
internal data class GlobalSearchFilterOptions(
    val folders: List<GlobalSearchFolderOption> = emptyList(),
    val chats: List<WhiteNoisePickerItem> = emptyList(),
    val senders: List<WhiteNoisePickerItem> = emptyList(),
)

/**
 * One picker per prototype category, driven by the shell-owned open category so back,
 * selection mode and closing search all dismiss it through the same state.
 */
@Composable
internal fun GlobalSearchFilterPicker(
    state: GlobalSearchState,
    options: GlobalSearchFilterOptions,
    onStateChange: ((GlobalSearchState) -> GlobalSearchState) -> Unit,
    nowMillis: () -> Long = System::currentTimeMillis,
    zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    val category = state.openFilterCategory ?: return
    if (!state.isOpen) return
    val dismiss = { onStateChange(GlobalSearchTransitions::dismissFilterSheet) }
    when (category) {
        GlobalSearchFilterCategory.Folder -> GlobalSearchFolderPicker(state, options.folders, onStateChange, dismiss)
        GlobalSearchFilterCategory.ChatType -> GlobalSearchChatTypePicker(state, onStateChange, dismiss)
        GlobalSearchFilterCategory.Chat ->
            GlobalSearchEntityPicker(
                title = stringResource(R.string.chat_list_search_filter_chat),
                items = options.chats,
                selectedIds = state.chatFilters.map { it.stableId }.toSet(),
                onToggle = { id, title ->
                    onStateChange { GlobalSearchTransitions.toggleChatFilter(it, GlobalSearchChatFilter(id, title)) }
                },
                onDismiss = dismiss,
            )
        GlobalSearchFilterCategory.Sender ->
            GlobalSearchEntityPicker(
                title = stringResource(R.string.chat_list_search_filter_sender),
                items = options.senders,
                selectedIds = state.senderFilters.map { it.stableId }.toSet(),
                onToggle = { id, title ->
                    val filter = GlobalSearchSenderFilter(id, title)
                    onStateChange { GlobalSearchTransitions.toggleSenderFilter(it, filter) }
                },
                onDismiss = dismiss,
            )
        GlobalSearchFilterCategory.Date -> GlobalSearchDatePicker(state, onStateChange, dismiss, nowMillis, zoneId)
        GlobalSearchFilterCategory.Content ->
            GlobalSearchCheckDialog(
                title = stringResource(R.string.chat_list_search_filter_content),
                onDismiss = dismiss,
                scrollable = false,
            ) {
                GlobalSearchContentFilterChips(
                    selection = state.contentFilterSelection,
                    onSelectionChange = { selection ->
                        onStateChange { GlobalSearchTransitions.setContentFilterSelection(it, selection) }
                    },
                )
            }
    }
}

/** Check dialog over the account's folders, or the no-folders note. */
@Composable
private fun GlobalSearchFolderPicker(
    state: GlobalSearchState,
    folders: List<GlobalSearchFolderOption>,
    onStateChange: ((GlobalSearchState) -> GlobalSearchState) -> Unit,
    onDismiss: () -> Unit,
) {
    GlobalSearchCheckDialog(title = stringResource(R.string.chat_list_search_filter_folders), onDismiss = onDismiss) {
        if (folders.isEmpty()) {
            Text(stringResource(R.string.chat_list_search_no_folders))
        }
        folders.forEach { folder ->
            GlobalSearchCheckRow(
                label = folder.name,
                checked = folder.id in state.folderFilters,
                tag = globalSearchFilterOptionTag(folder.id),
                onToggle = { onStateChange { GlobalSearchTransitions.toggleFolderFilter(it, folder.id) } },
            )
        }
    }
}

/** Check dialog over the two prototype chat types. */
@Composable
private fun GlobalSearchChatTypePicker(
    state: GlobalSearchState,
    onStateChange: ((GlobalSearchState) -> GlobalSearchState) -> Unit,
    onDismiss: () -> Unit,
) {
    GlobalSearchCheckDialog(title = stringResource(R.string.chat_list_search_filter_chat_type), onDismiss = onDismiss) {
        GlobalSearchChatType.entries.forEach { type ->
            GlobalSearchCheckRow(
                label = stringResource(type.labelRes()),
                checked = type in state.chatTypeFilters,
                tag = globalSearchFilterOptionTag(type.name),
                onToggle = { onStateChange { GlobalSearchTransitions.toggleChatTypeFilter(it, type) } },
            )
        }
    }
}

/** Chats and senders share the prototype's searchable multi-select sheet. */
@Composable
private fun GlobalSearchEntityPicker(
    title: String,
    items: List<WhiteNoisePickerItem>,
    selectedIds: Set<String>,
    onToggle: (id: String, title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    WhiteNoiseEntityPickerSheet(
        title = title,
        items = items,
        onDismiss = onDismiss,
        onSelect = { id -> items.firstOrNull { it.id == id }?.let { item -> onToggle(id, item.title) } },
        selectedIds = selectedIds,
        multiple = true,
        onDone = onDismiss,
        searchTag = CHAT_LIST_SEARCH_FILTER_SEARCH_TAG,
        rowTagPrefix = CHAT_LIST_SEARCH_FILTER_CHOICE_PREFIX,
    )
}

/** The existing date dialog, applying the selection and closing the picker in one transition. */
@Composable
private fun GlobalSearchDatePicker(
    state: GlobalSearchState,
    onStateChange: ((GlobalSearchState) -> GlobalSearchState) -> Unit,
    onDismiss: () -> Unit,
    nowMillis: () -> Long,
    zoneId: () -> ZoneId,
) {
    var customStage by remember { mutableStateOf<GlobalSearchDateCustomStage?>(null) }
    GlobalSearchDateFilterDialog(
        selection = state.dateFilterSelection,
        customStage = customStage,
        onDismiss = {
            customStage = null
            onDismiss()
        },
        onApply = { selection ->
            customStage = null
            onStateChange { current ->
                GlobalSearchTransitions.dismissFilterSheet(GlobalSearchTransitions.applyDateFilter(current, selection))
            }
        },
        onCustomStageChange = { customStage = it },
        nowMillis = nowMillis,
        zoneId = zoneId,
    )
}

/** The prototype's check-list dialog for a filter category, closed with Done. */
@Composable
private fun GlobalSearchCheckDialog(
    title: String,
    onDismiss: () -> Unit,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(CHAT_LIST_SEARCH_FILTER_DIALOG_TAG),
        title = { Text(title) },
        text = {
            Column(
                modifier = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

/** One tagged check row inside a filter dialog. */
@Composable
private fun GlobalSearchCheckRow(
    label: String,
    checked: Boolean,
    tag: String,
    onToggle: () -> Unit,
) {
    WhiteNoiseDialogCheckRow(
        title = label,
        checked = checked,
        onCheckedChange = { onToggle() },
        modifier = Modifier.testTag(tag),
    )
}

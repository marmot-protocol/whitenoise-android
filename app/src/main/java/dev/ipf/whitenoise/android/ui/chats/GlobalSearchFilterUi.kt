@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.labelRes
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.SystemFolderKind
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.search.globalSearchDateFilterLabel
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val CHAT_LIST_SEARCH_FILTERS_ACTION_TAG = "chat-list-search-filters-action"
internal const val CHAT_LIST_SEARCH_CLEAR_ALL_FILTERS_TAG = "chat-list-search-clear-all-filters"
internal const val CHAT_LIST_SEARCH_FILTER_MENU_TAG = "chat-list-search-filter-menu"
internal const val CHAT_LIST_SEARCH_FILTER_MENU_CLEAR_TAG = "chat-list-search-filter-menu-clear"
internal const val CHAT_LIST_SEARCH_FILTER_CONTROLS_TAG = "chat-list-search-filter-controls"

internal fun globalSearchFilterChipTag(chipId: String): String = "chat-list-search-filter-chip-$chipId"

internal fun globalSearchFilterMenuItemTag(category: GlobalSearchFilterCategory): String {
    val name = category.name
    return "chat-list-search-filter-menu-$name"
}

internal fun GlobalSearchFilterCategory.labelRes(): Int =
    when (this) {
        GlobalSearchFilterCategory.Folder -> R.string.chat_list_search_filter_folders
        GlobalSearchFilterCategory.ChatType -> R.string.chat_list_search_filter_chat_type
        GlobalSearchFilterCategory.Chat -> R.string.chat_list_search_filter_chat
        GlobalSearchFilterCategory.Sender -> R.string.chat_list_search_filter_sender
        GlobalSearchFilterCategory.Date -> R.string.chat_list_search_filter_date
        GlobalSearchFilterCategory.Content -> R.string.chat_list_search_filter_content
    }

internal fun GlobalSearchChatType.labelRes(): Int =
    when (this) {
        GlobalSearchChatType.DIRECT -> R.string.chat_list_search_direct_chats
        GlobalSearchChatType.GROUPS -> R.string.chat_list_search_groups
    }

/** System folders carry no stored name; their label comes from the same resources the folder pills use. */
@Composable
internal fun chatFolderDisplayName(folder: ChatFolder): String =
    folder.name.ifEmpty {
        when (folder.systemKind) {
            SystemFolderKind.UNREAD -> stringResource(R.string.chat_list_filter_unread)
            SystemFolderKind.GROUPS -> stringResource(R.string.chat_list_filter_groups)
            SystemFolderKind.ARCHIVED -> stringResource(R.string.archived)
            null -> ""
        }
    }

/** The prototype's filter entry beside the search field: a plain icon that fills once any filter is active. */
@Composable
internal fun GlobalSearchFilterIconButton(
    state: GlobalSearchState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeCount = GlobalSearchActiveChips.from(state).count
    val description = globalSearchFiltersActionDescription(activeCount)
    val semantics =
        modifier
            .testTag(CHAT_LIST_SEARCH_FILTERS_ACTION_TAG)
            .semantics {
                contentDescription = description
                selected = activeCount > 0
            }
    val icon: @Composable () -> Unit = {
        Icon(painterResource(R.drawable.ic_filter_list), contentDescription = null)
    }
    if (activeCount > 0) {
        FilledIconButton(onClick = onClick, modifier = semantics, content = icon)
    } else {
        IconButton(onClick = onClick, modifier = semantics, content = icon)
    }
}

/** The prototype's category menu anchored on the filter button; Clear All joins once a filter is active. */
@Composable
internal fun GlobalSearchFilterMenu(
    expanded: Boolean,
    state: GlobalSearchState,
    onDismiss: () -> Unit,
    onCategory: (GlobalSearchFilterCategory) -> Unit,
    onClearAll: () -> Unit,
) {
    val categories =
        GlobalSearchFilterCategory.entries.map { category ->
            WhiteNoiseMenuItem(
                label = stringResource(category.labelRes()),
                selected = state.isCategoryActive(category),
                onClick = { onCategory(category) },
                modifier = Modifier.testTag(globalSearchFilterMenuItemTag(category)),
            )
        }
    val clearAll =
        if (state.hasActiveFilters) {
            listOf(
                WhiteNoiseMenuItem(
                    label = stringResource(R.string.chat_list_search_clear_all_filters),
                    onClick = onClearAll,
                    modifier = Modifier.testTag(CHAT_LIST_SEARCH_FILTER_MENU_CLEAR_TAG),
                ),
            )
        } else {
            emptyList()
        }
    WhiteNoiseDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        items = categories + clearAll,
        modifier = Modifier.testTag(CHAT_LIST_SEARCH_FILTER_MENU_TAG),
    )
}

/** The prototype's active-filter bar: Clear All first, then one removable chip per filter. */
@Composable
internal fun GlobalSearchFilterControlsRow(
    state: GlobalSearchState,
    onRemoveFilter: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    folderNames: Map<String, String> = emptyMap(),
) {
    val chips = GlobalSearchActiveChips.from(state)
    val clearAllDescription = stringResource(R.string.chat_list_search_clear_all_filters)
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag(CHAT_LIST_SEARCH_FILTER_CONTROLS_TAG),
        contentPadding = PaddingValues(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chips.count > 0) {
            item(key = "clear-all") {
                TextButton(
                    onClick = onClearAll,
                    modifier =
                        Modifier
                            .testTag(CHAT_LIST_SEARCH_CLEAR_ALL_FILTERS_TAG)
                            .semantics { contentDescription = clearAllDescription },
                ) {
                    Text(stringResource(R.string.chat_list_search_clear_all_filters))
                }
            }
        }
        items(chips.items, key = { it.chipId }) { chip ->
            val chipLabel = globalSearchActiveChipLabel(chip, state, folderNames)
            val removeDescription = stringResource(R.string.chat_list_search_filter_remove, chipLabel)
            InputChip(
                selected = true,
                onClick = { onRemoveFilter(chip.chipId) },
                label = { Text(chipLabel) },
                trailingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = null) },
                modifier =
                    Modifier
                        .testTag(globalSearchFilterChipTag(chip.chipId))
                        .semantics { contentDescription = removeDescription },
            )
        }
    }
}

@Composable
internal fun globalSearchActiveChipLabel(
    chip: GlobalSearchActiveChip,
    state: GlobalSearchState,
    folderNames: Map<String, String> = emptyMap(),
): String =
    when (chip.category) {
        GlobalSearchFilterCategory.Folder -> {
            val folderName = folderNames[chip.chipId.removePrefix("folder:")]
            if (folderName == null) {
                stringResource(R.string.chat_list_search_filter_folders)
            } else {
                stringResource(R.string.chat_list_search_folder_chip, folderName)
            }
        }
        GlobalSearchFilterCategory.ChatType -> {
            val type = runCatching { GlobalSearchChatType.valueOf(chip.chipId.removePrefix("type:")) }.getOrNull()
            if (type == null) "" else stringResource(type.labelRes())
        }
        GlobalSearchFilterCategory.Chat -> stringResource(R.string.chat_list_search_chat_chip, chip.displayLabel)
        GlobalSearchFilterCategory.Sender -> stringResource(R.string.chat_list_search_sender_chip, chip.displayLabel)
        GlobalSearchFilterCategory.Date -> globalSearchDateFilterLabel(state.dateFilterSelection)
        GlobalSearchFilterCategory.Content -> {
            val kindName = chip.chipId.removePrefix("content:")
            val kind = runCatching { GlobalSearchContentKind.valueOf(kindName) }.getOrNull()
            if (kind == null) "" else stringResource(kind.labelRes())
        }
    }

@Composable
internal fun globalSearchFiltersActionDescription(activeFilterCount: Int): String =
    if (activeFilterCount > 0) {
        pluralStringResource(
            R.plurals.chat_list_search_filters_active,
            activeFilterCount,
            activeFilterCount,
        )
    } else {
        stringResource(R.string.chat_list_search_filters)
    }

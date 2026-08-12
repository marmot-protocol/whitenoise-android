@file:Suppress("FunctionNaming", "LongMethod")

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.newchat.SectionHeader
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

internal const val CHAT_LIST_SEARCH_FILTERS_ACTION_TAG = "chat-list-search-filters-action"
internal const val CHAT_LIST_SEARCH_CLEAR_ALL_FILTERS_TAG = "chat-list-search-clear-all-filters"
internal const val CHAT_LIST_SEARCH_FILTER_SHEET_TAG = "chat-list-search-filter-sheet"
internal const val CHAT_LIST_SEARCH_FILTER_CONTROLS_TAG = "chat-list-search-filter-controls"

internal fun globalSearchFilterChipTag(chipId: String): String = "chat-list-search-filter-chip-$chipId"

@Composable
internal fun GlobalSearchFilterControlsRow(
    state: GlobalSearchState,
    onOpenFilters: (() -> Unit)?,
    onRemoveFilter: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = GlobalSearchActiveChips.from(state)
    val activeFilterCount = chips.count
    val filtersButtonLabel = globalSearchFiltersButtonLabel(activeFilterCount)
    val filtersContentDescription = globalSearchFiltersActionDescription(activeFilterCount)
    val clearAllDescription = stringResource(R.string.chat_list_search_clear_all_filters)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(CHAT_LIST_SEARCH_FILTER_CONTROLS_TAG)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onOpenFilters != null) {
            TextButton(
                onClick = onOpenFilters,
                modifier =
                    Modifier
                        .testTag(CHAT_LIST_SEARCH_FILTERS_ACTION_TAG)
                        .semantics {
                            contentDescription = filtersContentDescription
                        },
            ) {
                Text(filtersButtonLabel)
            }
        }
        chips.items.forEach { chip ->
            val removeDescription = stringResource(R.string.chat_list_search_filter_remove, chip.displayLabel)
            FilterChip(
                selected = true,
                onClick = { onRemoveFilter(chip.chipId) },
                label = { Text(chip.displayLabel) },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.padding(2.dp),
                    )
                },
                modifier =
                    Modifier
                        .testTag(globalSearchFilterChipTag(chip.chipId))
                        .semantics { contentDescription = removeDescription },
            )
        }
        if (activeFilterCount > 0) {
            TextButton(
                onClick = onClearAll,
                modifier =
                    Modifier
                        .testTag(CHAT_LIST_SEARCH_CLEAR_ALL_FILTERS_TAG)
                        .semantics {
                            contentDescription = clearAllDescription
                        },
            ) {
                Text(stringResource(R.string.chat_list_search_clear_all_filters))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlobalSearchFilterSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    chatSection: (@Composable () -> Unit)? = null,
    senderSection: (@Composable () -> Unit)? = null,
    dateSection: (@Composable () -> Unit)? = null,
    contentSection: (@Composable () -> Unit)? = null,
) {
    val hasInteractiveSections =
        chatSection != null || senderSection != null || dateSection != null || contentSection != null
    if (!visible || !hasInteractiveSections) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = amoledSheetContainerColor(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(CHAT_LIST_SEARCH_FILTER_SHEET_TAG)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_list_search_filter_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (chatSection != null) {
                SectionHeader(stringResource(R.string.chat_list_search_filter_chat))
                chatSection()
            }
            if (senderSection != null) {
                SectionHeader(stringResource(R.string.chat_list_search_filter_sender))
                senderSection()
            }
            if (dateSection != null) {
                SectionHeader(stringResource(R.string.chat_list_search_filter_date))
                dateSection()
            }
            if (contentSection != null) {
                SectionHeader(stringResource(R.string.chat_list_search_filter_content))
                contentSection()
            }
        }
    }
}

@Composable
internal fun globalSearchFiltersButtonLabel(activeFilterCount: Int): String =
    if (activeFilterCount > 0) {
        stringResource(R.string.chat_list_search_filters_button, activeFilterCount)
    } else {
        stringResource(R.string.chat_list_search_filters)
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

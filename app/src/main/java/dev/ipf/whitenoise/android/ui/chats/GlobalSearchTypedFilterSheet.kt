package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.ui.search.GlobalSearchContentFilterChips
import dev.ipf.whitenoise.android.ui.search.GlobalSearchDateCustomStage
import dev.ipf.whitenoise.android.ui.search.GlobalSearchDateFilterDialog
import dev.ipf.whitenoise.android.ui.search.GlobalSearchDateFilterSection
import java.time.ZoneId

/**
 * Production wrapper for typed date/content filter UI: owns the filter sheet and
 * date-dialog staging state keyed to [GlobalSearchState.isOpen] and
 * [GlobalSearchState.filterSheetOpen] so dismissal cannot leave a stale dialog.
 */
@Suppress("FunctionNaming")
@Composable
internal fun GlobalSearchTypedFilterSheet(
    state: GlobalSearchState,
    onStateChange: ((GlobalSearchState) -> GlobalSearchState) -> Unit,
    nowMillis: () -> Long = System::currentTimeMillis,
    zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    val sheetActive = state.isOpen && state.filterSheetOpen
    var dateDialogOpen by remember(state.isOpen, state.filterSheetOpen) { mutableStateOf(false) }
    var customStage by remember(state.isOpen, state.filterSheetOpen) {
        mutableStateOf<GlobalSearchDateCustomStage?>(null)
    }

    GlobalSearchFilterSheet(
        visible = sheetActive,
        onDismiss = {
            dateDialogOpen = false
            customStage = null
            onStateChange(GlobalSearchTransitions::dismissFilterSheet)
        },
        dateSection = {
            GlobalSearchDateFilterSection(
                selection = state.dateFilterSelection,
                onOpenDateDialog = { dateDialogOpen = true },
            )
        },
        contentSection = {
            GlobalSearchContentFilterChips(
                selection = state.contentFilterSelection,
                onSelectionChange = { selection ->
                    onStateChange { current ->
                        GlobalSearchTransitions.setContentFilterSelection(current, selection)
                    }
                },
            )
        },
    )
    if (sheetActive && dateDialogOpen) {
        GlobalSearchDateFilterDialog(
            selection = state.dateFilterSelection,
            customStage = customStage,
            onDismiss = {
                dateDialogOpen = false
                customStage = null
            },
            onApply = { selection ->
                onStateChange { current ->
                    GlobalSearchTransitions.applyDateFilter(current, selection)
                }
                dateDialogOpen = false
                customStage = null
            },
            onCustomStageChange = { customStage = it },
            nowMillis = nowMillis,
            zoneId = zoneId,
        )
    }
}

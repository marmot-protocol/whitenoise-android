package dev.ipf.whitenoise.android.ui.search

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.search.GlobalSearchCustomRangeValidation
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.search.civilDateFromPickerUtcMillis
import dev.ipf.whitenoise.android.search.pickerUtcMillisForCivilDate
import dev.ipf.whitenoise.android.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal const val GLOBAL_SEARCH_DATE_DIALOG_TAG = "global-search-date-dialog"
internal const val GLOBAL_SEARCH_DATE_APPLY_TAG = "global-search-date-apply"
internal const val GLOBAL_SEARCH_DATE_FROM_CONFIRM_TAG = "global-search-date-from-confirm"
internal const val GLOBAL_SEARCH_DATE_TO_CONFIRM_TAG = "global-search-date-to-confirm"

internal sealed interface GlobalSearchDateCustomStage {
    data class FromDate(
        val snapshottedZoneId: ZoneId,
    ) : GlobalSearchDateCustomStage

    data class ToDate(
        val from: LocalDate,
        val snapshottedZoneId: ZoneId,
    ) : GlobalSearchDateCustomStage

    data class Review(
        val from: LocalDate,
        val to: LocalDate,
        val snapshottedZoneId: ZoneId,
    ) : GlobalSearchDateCustomStage
}

@Suppress("MaxLineLength")
internal fun globalSearchDatePresetTag(selection: GlobalSearchDateFilterSelection): String = "global-search-date-preset-${selection.codecKey()}"

@Suppress("FunctionNaming")
@Composable
internal fun GlobalSearchDateFilterDialog(
    selection: GlobalSearchDateFilterSelection,
    customStage: GlobalSearchDateCustomStage?,
    onDismiss: () -> Unit,
    onApply: (GlobalSearchDateFilterSelection) -> Unit,
    onCustomStageChange: (GlobalSearchDateCustomStage?) -> Unit,
    nowMillis: () -> Long = System::currentTimeMillis,
    zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    if (customStage != null) {
        GlobalSearchDateCustomStageDialog(
            stage = customStage,
            onDismiss = { onCustomStageChange(null) },
            onApply = onApply,
            onCustomStageChange = onCustomStageChange,
            nowMillis = nowMillis,
        )
        return
    }

    GlobalSearchDatePresetDialog(
        selection = selection,
        onDismiss = onDismiss,
        onApply = onApply,
        onCustomStageChange = onCustomStageChange,
        zoneId = zoneId,
    )
}

@Suppress("FunctionNaming")
@Composable
private fun GlobalSearchDateCustomStageDialog(
    stage: GlobalSearchDateCustomStage,
    onDismiss: () -> Unit,
    onApply: (GlobalSearchDateFilterSelection) -> Unit,
    onCustomStageChange: (GlobalSearchDateCustomStage?) -> Unit,
    nowMillis: () -> Long,
) {
    when (stage) {
        is GlobalSearchDateCustomStage.FromDate -> {
            GlobalSearchCivilDatePickerDialog(
                titleRes = R.string.global_search_date_custom_from,
                initialDate = Instant.ofEpochMilli(nowMillis()).atZone(stage.snapshottedZoneId).toLocalDate(),
                confirmTag = GLOBAL_SEARCH_DATE_FROM_CONFIRM_TAG,
                onDismiss = onDismiss,
                onConfirm = { pickedDate ->
                    onCustomStageChange(
                        GlobalSearchDateCustomStage.ToDate(
                            from = pickedDate,
                            snapshottedZoneId = stage.snapshottedZoneId,
                        ),
                    )
                },
            )
        }
        is GlobalSearchDateCustomStage.ToDate -> {
            GlobalSearchCivilDatePickerDialog(
                titleRes = R.string.global_search_date_custom_to,
                initialDate = stage.from,
                confirmTag = GLOBAL_SEARCH_DATE_TO_CONFIRM_TAG,
                onDismiss = onDismiss,
                onConfirm = { pickedDate ->
                    onCustomStageChange(
                        GlobalSearchDateCustomStage.Review(
                            from = stage.from,
                            to = pickedDate,
                            snapshottedZoneId = stage.snapshottedZoneId,
                        ),
                    )
                },
            )
        }
        is GlobalSearchDateCustomStage.Review -> {
            GlobalSearchCustomRangeReviewDialog(
                stage = stage,
                onDismiss = onDismiss,
                onApply = onApply,
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun GlobalSearchDatePresetDialog(
    selection: GlobalSearchDateFilterSelection,
    onDismiss: () -> Unit,
    onApply: (GlobalSearchDateFilterSelection) -> Unit,
    onCustomStageChange: (GlobalSearchDateCustomStage?) -> Unit,
    zoneId: () -> ZoneId,
) {
    var stagedSelection by remember(selection) { mutableStateOf(selection) }
    AlertDialog(
        modifier = Modifier.testTag(GLOBAL_SEARCH_DATE_DIALOG_TAG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.global_search_date_filter_title)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = Dimens.spaceSm),
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
            ) {
                globalSearchDatePresets.forEach { preset ->
                    val selected =
                        when (preset.selection) {
                            is GlobalSearchDateFilterSelection.Custom ->
                                stagedSelection is GlobalSearchDateFilterSelection.Custom
                            else -> stagedSelection.codecKey() == preset.selection.codecKey()
                        }
                    GlobalSearchDatePresetRow(
                        preset = preset,
                        selected = selected,
                        onClick = {
                            if (preset.selection is GlobalSearchDateFilterSelection.Custom) {
                                val snapshottedZone = zoneId()
                                onCustomStageChange(GlobalSearchDateCustomStage.FromDate(snapshottedZone))
                            } else {
                                stagedSelection = preset.selection
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(GLOBAL_SEARCH_DATE_APPLY_TAG),
                onClick = { onApply(stagedSelection) },
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun GlobalSearchCustomRangeReviewDialog(
    stage: GlobalSearchDateCustomStage.Review,
    onDismiss: () -> Unit,
    onApply: (GlobalSearchDateFilterSelection) -> Unit,
) {
    val customSelection =
        GlobalSearchDateFilterSelection.Custom(
            from = stage.from,
            to = stage.to,
            zoneId = stage.snapshottedZoneId,
        )
    val validation = customSelection.validateCustomRange()
    AlertDialog(
        modifier = Modifier.testTag(GLOBAL_SEARCH_DATE_DIALOG_TAG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.global_search_date_custom)) },
        text = {
            Column {
                Text(
                    text = globalSearchCustomDateRangeLabel(stage.from, stage.to),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (validation == GlobalSearchCustomRangeValidation.Reversed) {
                    Text(
                        text = stringResource(R.string.global_search_date_custom_reversed_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Dimens.spaceSm),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = validation.canApply,
                modifier = Modifier.testTag(GLOBAL_SEARCH_DATE_APPLY_TAG),
                onClick = { onApply(customSelection) },
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun GlobalSearchDatePresetRow(
    preset: GlobalSearchDatePresetOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .semantics { this.selected = selected }
                .testTag(globalSearchDatePresetTag(preset.selection))
                .padding(vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(preset.labelRes), style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
private fun GlobalSearchCivilDatePickerDialog(
    @StringRes titleRes: Int,
    initialDate: LocalDate,
    confirmTag: String,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val dateState =
        rememberDatePickerState(
            initialSelectedDateMillis = pickerUtcMillisForCivilDate(initialDate),
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean = true
                },
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = dateState.selectedDateMillis != null,
                modifier = Modifier.testTag(confirmTag),
                onClick = {
                    dateState.selectedDateMillis?.let { selectedDateMillis ->
                        onConfirm(civilDateFromPickerUtcMillis(selectedDateMillis))
                    }
                },
            ) { Text(stringResource(R.string.next)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        Column {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            DatePicker(state = dateState)
        }
    }
}

private data class GlobalSearchDatePresetOption(
    @StringRes val labelRes: Int,
    val selection: GlobalSearchDateFilterSelection,
)

private val globalSearchDatePresets =
    listOf(
        GlobalSearchDatePresetOption(
            R.string.global_search_date_any_time,
            GlobalSearchDateFilterSelection.AnyTime,
        ),
        GlobalSearchDatePresetOption(
            R.string.global_search_date_today,
            GlobalSearchDateFilterSelection.Today,
        ),
        GlobalSearchDatePresetOption(
            R.string.global_search_date_last_7_days,
            GlobalSearchDateFilterSelection.Last7Days,
        ),
        GlobalSearchDatePresetOption(
            R.string.global_search_date_last_30_days,
            GlobalSearchDateFilterSelection.Last30Days,
        ),
        GlobalSearchDatePresetOption(
            R.string.global_search_date_custom,
            GlobalSearchDateFilterSelection.Custom(
                LocalDate.EPOCH,
                LocalDate.EPOCH,
                ZoneId.of("UTC"),
            ),
        ),
    )

private fun GlobalSearchDateFilterSelection.codecKey(): String =
    when (this) {
        GlobalSearchDateFilterSelection.AnyTime -> "any"
        GlobalSearchDateFilterSelection.Today -> "today"
        GlobalSearchDateFilterSelection.Last7Days -> "last7"
        GlobalSearchDateFilterSelection.Last30Days -> "last30"
        is GlobalSearchDateFilterSelection.Custom -> "custom"
    }

package dev.ipf.whitenoise.android.ui.group

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

internal const val MUTE_CUSTOM_DATE_CONFIRM_TAG = "mute-custom-date-confirm"
internal const val MUTE_CUSTOM_DATE_CANCEL_TAG = "mute-custom-date-cancel"
internal const val MUTE_CUSTOM_TIME_CONFIRM_TAG = "mute-custom-time-confirm"
internal const val MUTE_CUSTOM_TIME_PICKER_TAG = "mute-custom-time-picker"
internal const val MUTE_CUSTOM_PREVIEW_TAG = "mute-custom-preview"

private const val MUTE_HOUR_MILLIS = 3_600_000L
private const val MUTE_EIGHT_HOURS_MILLIS = 8 * MUTE_HOUR_MILLIS
private const val MUTE_ONE_DAY_MILLIS = 24 * MUTE_HOUR_MILLIS
private const val MUTE_ONE_WEEK_MILLIS = 7 * 24 * MUTE_HOUR_MILLIS

private data class MutePreset(
    @StringRes val labelId: Int,
    val durationMillis: Long,
)

private val mutePresets =
    listOf(
        MutePreset(R.string.mute_duration_1_hour, MUTE_HOUR_MILLIS),
        MutePreset(R.string.mute_duration_8_hours, MUTE_EIGHT_HOURS_MILLIS),
        MutePreset(R.string.mute_duration_1_day, MUTE_ONE_DAY_MILLIS),
        MutePreset(R.string.mute_duration_7_days, MUTE_ONE_WEEK_MILLIS),
    )

internal sealed interface MuteTarget {
    data class After(
        val durationMillis: Long,
    ) : MuteTarget

    data class At(
        val expiryMillis: Long,
    ) : MuteTarget

    data object Always : MuteTarget
}

private enum class CustomMutePickerStage {
    DATE,
    TIME,
}

internal fun customMuteExpiryMillis(
    date: LocalDate,
    time: LocalTime,
    zoneId: ZoneId,
): Long =
    date
        .atTime(time)
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

private fun defaultCustomDateTime(
    nowMillis: Long,
    zoneId: ZoneId,
): LocalDateTime =
    Instant
        .ofEpochMilli(nowMillis)
        .atZone(zoneId)
        .toLocalDateTime()
        .plusHours(1)
        .withSecond(0)
        .withNano(0)

@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
@Composable
internal fun MuteDurationDialog(
    onDismiss: () -> Unit,
    onConfirm: (MuteTarget) -> Unit,
    nowMillis: () -> Long = System::currentTimeMillis,
    zoneId: () -> ZoneId = ZoneId::systemDefault,
    initialCustomDateTime: LocalDateTime? = null,
) {
    val initialCustom =
        remember(initialCustomDateTime) {
            initialCustomDateTime ?: defaultCustomDateTime(nowMillis(), zoneId())
        }
    var selected by remember { mutableStateOf<MuteTarget>(MuteTarget.Always) }
    var customDate by remember { mutableStateOf(initialCustom.toLocalDate()) }
    var customTime by remember { mutableStateOf(initialCustom.toLocalTime()) }
    var customStage by remember { mutableStateOf<CustomMutePickerStage?>(null) }
    var customValidationError by remember { mutableStateOf(false) }

    if (customStage == CustomMutePickerStage.DATE) {
        CustomMuteDateDialog(
            initialDate = customDate,
            onDismiss = { customStage = null },
            onConfirm = { pickedDate ->
                customDate = pickedDate
                customValidationError = false
                customStage = CustomMutePickerStage.TIME
            },
        )
        return
    }

    if (customStage == CustomMutePickerStage.TIME) {
        CustomMuteTimeDialog(
            initialTime = customTime,
            showValidationError = customValidationError,
            onDismiss = { customStage = null },
            onConfirm = { pickedTime ->
                val expiryMillis = customMuteExpiryMillis(customDate, pickedTime, zoneId())
                if (expiryMillis <= nowMillis()) {
                    customValidationError = true
                } else {
                    customTime = pickedTime
                    selected = MuteTarget.At(expiryMillis)
                    customValidationError = false
                    customStage = null
                }
            },
        )
        return
    }

    MuteDurationConfirmationDialog(
        selected = selected,
        onSelect = { selected = it },
        onCustom = {
            customValidationError = false
            customStage = CustomMutePickerStage.DATE
        },
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selected) },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun MuteDurationConfirmationDialog(
    selected: MuteTarget,
    onSelect: (MuteTarget) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mute_dialog_title)) },
        text = { MuteDurationOptions(selected, onSelect, onCustom) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun MuteDurationOptions(
    selected: MuteTarget,
    onSelect: (MuteTarget) -> Unit,
    onCustom: () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.mute_dialog_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Dimens.spaceSm),
        )
        mutePresets.forEach { preset ->
            val target = MuteTarget.After(preset.durationMillis)
            NotificationModeRow(preset.labelId, selected = selected == target) { onSelect(target) }
        }
        NotificationModeRow(R.string.mute_duration_custom, selected = selected is MuteTarget.At, onClick = onCustom)
        (selected as? MuteTarget.At)?.let { CustomMutePreview(it.expiryMillis) }
        NotificationModeRow(R.string.mute_duration_always, selected = selected == MuteTarget.Always) {
            onSelect(MuteTarget.Always)
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun CustomMutePreview(expiryMillis: Long) {
    val context = LocalContext.current
    val dateTime =
        DateUtils.formatDateTime(
            context,
            expiryMillis,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
        )
    Text(
        text = stringResource(R.string.mute_custom_selected, dateTime),
        modifier = Modifier.padding(horizontal = Dimens.spaceLg).testTag(MUTE_CUSTOM_PREVIEW_TAG),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
private fun CustomMuteDateDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val dateState =
        rememberDatePickerState(
            initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(MUTE_CUSTOM_DATE_CONFIRM_TAG),
                onClick = {
                    dateState.selectedDateMillis?.let { selectedDateMillis ->
                        onConfirm(Instant.ofEpochMilli(selectedDateMillis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
            ) { Text(stringResource(R.string.next)) }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag(MUTE_CUSTOM_DATE_CANCEL_TAG),
                onClick = onDismiss,
            ) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(state = dateState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
private fun CustomMuteTimeDialog(
    initialTime: LocalTime,
    showValidationError: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val timeState = rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mute_custom_time_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = timeState, modifier = Modifier.testTag(MUTE_CUSTOM_TIME_PICKER_TAG))
                if (showValidationError) {
                    Text(
                        stringResource(R.string.mute_custom_future_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(MUTE_CUSTOM_TIME_CONFIRM_TAG),
                onClick = { onConfirm(LocalTime.of(timeState.hour, timeState.minute)) },
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

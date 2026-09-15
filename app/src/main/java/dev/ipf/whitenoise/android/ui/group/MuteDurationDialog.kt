package dev.ipf.whitenoise.android.ui.group

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDialogChoiceRow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

internal const val MUTE_DURATION_DIALOG_TAG = "mute.duration.dialog"
internal const val MUTE_CUSTOM_DATE_CONFIRM_TAG = "mute-custom-date-confirm"
internal const val MUTE_CUSTOM_DATE_CANCEL_TAG = "mute-custom-date-cancel"
internal const val MUTE_CUSTOM_TIME_CONFIRM_TAG = "mute-custom-time-confirm"
internal const val MUTE_CUSTOM_TIME_PICKER_TAG = "mute-custom-time-picker"

private const val MUTE_HOUR_MILLIS = 3_600_000L
private const val MUTE_EIGHT_HOURS_MILLIS = 8 * MUTE_HOUR_MILLIS
private const val MUTE_ONE_DAY_MILLIS = 24 * MUTE_HOUR_MILLIS
private const val MUTE_ONE_WEEK_MILLIS = 7 * 24 * MUTE_HOUR_MILLIS

private data class MutePreset(
    @StringRes val labelId: Int,
    val target: MuteTarget,
)

/** The prototype's order; Always closes the preset list ahead of the optional custom picker. */
private val mutePresets =
    listOf(
        MutePreset(R.string.mute_duration_1_hour, MuteTarget.After(MUTE_HOUR_MILLIS)),
        MutePreset(R.string.mute_duration_8_hours, MuteTarget.After(MUTE_EIGHT_HOURS_MILLIS)),
        MutePreset(R.string.mute_duration_1_day, MuteTarget.After(MUTE_ONE_DAY_MILLIS)),
        MutePreset(R.string.mute_duration_1_week, MuteTarget.After(MUTE_ONE_WEEK_MILLIS)),
        MutePreset(R.string.mute_duration_always, MuteTarget.Always),
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

internal fun isDateAllowed(
    utcTimeMillis: Long,
    minimumDate: LocalDate,
): Boolean = utcTimeMillis >= minimumDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

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

/**
 * Mute picker whose presets apply the moment they are chosen; only a custom wall-clock time is
 * staged, through the date and time pickers, until it passes the future-time check.
 *
 * [customTimeAvailable] hides the custom row on surfaces that have no date/time picker to offer.
 */
@Suppress("FunctionNaming", "LongParameterList") // Jetpack Compose functions use UpperCamelCase.
@Composable
internal fun MuteDurationDialog(
    onDismiss: () -> Unit,
    onSelect: (MuteTarget) -> Unit,
    nowMillis: () -> Long = System::currentTimeMillis,
    zoneId: () -> ZoneId = ZoneId::systemDefault,
    initialCustomDateTime: LocalDateTime? = null,
    customTimeAvailable: Boolean = true,
) {
    val initialCustom =
        remember(initialCustomDateTime) {
            initialCustomDateTime ?: defaultCustomDateTime(nowMillis(), zoneId())
        }
    var customDate by remember { mutableStateOf(initialCustom.toLocalDate()) }
    var customStage by remember { mutableStateOf<CustomMutePickerStage?>(null) }
    var customValidationError by remember { mutableStateOf(false) }

    when (customStage) {
        CustomMutePickerStage.DATE ->
            CustomMuteDateDialog(
                initialDate = customDate,
                minimumDate = Instant.ofEpochMilli(nowMillis()).atZone(zoneId()).toLocalDate(),
                onDismiss = { customStage = null },
                onConfirm = { pickedDate ->
                    customDate = pickedDate
                    customValidationError = false
                    customStage = CustomMutePickerStage.TIME
                },
            )
        CustomMutePickerStage.TIME ->
            CustomMuteTimeDialog(
                initialTime = initialCustom.toLocalTime(),
                showValidationError = customValidationError,
                onDismiss = { customStage = null },
                onConfirm = { pickedTime ->
                    val expiryMillis = customMuteExpiryMillis(customDate, pickedTime, zoneId())
                    if (expiryMillis <= nowMillis()) {
                        customValidationError = true
                    } else {
                        customStage = null
                        onSelect(MuteTarget.At(expiryMillis))
                    }
                },
            )
        null ->
            MuteDurationChoiceDialog(
                customTimeAvailable = customTimeAvailable,
                onSelect = onSelect,
                onCustom = {
                    customValidationError = false
                    customStage = CustomMutePickerStage.DATE
                },
                onDismiss = onDismiss,
            )
    }
}

/** Single-select duration list with no body copy and no confirm button, only a Cancel dismiss. */
@Suppress("FunctionNaming")
@Composable
private fun MuteDurationChoiceDialog(
    customTimeAvailable: Boolean,
    onSelect: (MuteTarget) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        modifier = Modifier.testTag(MUTE_DURATION_DIALOG_TAG),
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        title = { Text(stringResource(R.string.mute_for)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                mutePresets.forEach { preset ->
                    MuteDurationChoice(preset.labelId) { onSelect(preset.target) }
                }
                if (customTimeAvailable) {
                    MuteDurationChoice(R.string.mute_duration_custom, onClick = onCustom)
                }
            }
        },
    )
}

/**
 * One duration row. Nothing is ever pre-selected: the picker opens only to start a mute and
 * closes as soon as a choice is made, so no selection outlives the dialog.
 */
@Suppress("FunctionNaming")
@Composable
private fun MuteDurationChoice(
    @StringRes labelId: Int,
    onClick: () -> Unit,
) {
    WhiteNoiseDialogChoiceRow(
        title = stringResource(labelId),
        selected = false,
        onClick = onClick,
    )
}

/** Custom mute step one: a calendar that refuses days already past in the caller's zone. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
private fun CustomMuteDateDialog(
    initialDate: LocalDate,
    minimumDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val selectableDates =
        remember(minimumDate) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = isDateAllowed(utcTimeMillis, minimumDate)
            }
        }
    val dateState =
        rememberDatePickerState(
            initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = selectableDates,
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

/** Custom mute step two: the only confirm in the flow, so it commits the mute instead of staging it. */
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
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(MUTE_CUSTOM_TIME_CONFIRM_TAG),
                onClick = { onConfirm(LocalTime.of(timeState.hour, timeState.minute)) },
            ) { Text(stringResource(R.string.mute)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
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
    )
}

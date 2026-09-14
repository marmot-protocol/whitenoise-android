package dev.ipf.whitenoise.android.ui.group

import android.widget.NumberPicker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDialogChoiceRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val DISAPPEARING_CUSTOM_VALUE_PICKER_TAG = "disappearing_custom_value_picker"
internal const val DISAPPEARING_CUSTOM_UNIT_PICKER_TAG = "disappearing_custom_unit_picker"

// Full-screen retention picker: an explanatory line, a radio list of preset
// windows + Custom, and a Save action. The selection is STAGED — nothing
// changes until Save, so the caller's [onPick] (which routes through the group
// mutation lock + prune confirm) fires once. Custom opens a wheel picker.

/** The prototype's disappearing-messages dialog: explainer, one radio row per preset, Custom time, Save / Cancel. */
@Suppress("FunctionNaming")
@Composable
internal fun DisappearingMessagesPickerDialog(
    currentSecs: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    var selected by remember(currentSecs) { mutableLongStateOf(currentSecs) }
    var showCustom by remember { mutableStateOf(false) }
    val isCustom = selected !in disappearingPresetSecs

    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(DISAPPEARING_PICKER_TAG),
        title = { Text(stringResource(R.string.disappearing_messages)) },
        text = {
            DisappearingPickerChoices(
                selected = selected,
                isCustom = isCustom,
                onSelect = { selected = it },
                onCustom = { showCustom = true },
            )
        },
        confirmButton = {
            TextButton(onClick = { onPick(selected) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
    if (showCustom) {
        val initialSecs =
            if (isCustom && selected > 0L) {
                selected
            } else {
                60L
            }
        DisappearingCustomDialog(
            initialSecs = initialSecs,
            onDismiss = { showCustom = false },
            onConfirm = { secs ->
                selected = secs
                showCustom = false
            },
        )
    }
}

/** Explainer, the preset radio rows and the Custom time action that the prototype's dialog body carries. */
@Suppress("FunctionNaming")
@Composable
private fun DisappearingPickerChoices(
    selected: Long,
    isCustom: Boolean,
    onSelect: (Long) -> Unit,
    onCustom: () -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        Text(stringResource(R.string.disappearing_explainer))
        Column(Modifier.selectableGroup()) {
            disappearingPresetSecs.forEach { secs ->
                WhiteNoiseDialogChoiceRow(
                    title = disappearingMessagesLabel(secs),
                    selected = !isCustom && selected == secs,
                    onClick = { onSelect(secs) },
                )
            }
        }
        TextButton(onClick = onCustom, modifier = Modifier.testTag(DISAPPEARING_CUSTOM_ACTION_TAG)) {
            Text(
                if (isCustom) {
                    stringResource(R.string.disappearing_custom_value, disappearingMessagesLabel(selected))
                } else {
                    stringResource(R.string.disappearing_custom)
                },
            )
        }
    }
}

internal const val DISAPPEARING_PICKER_TAG = "disappearing.picker"
internal const val DISAPPEARING_CUSTOM_ACTION_TAG = "disappearing.custom"

@Composable
internal fun disappearingMessagesLabel(secs: Long): String =
    when (val spec = disappearingLabelSpec(secs)) {
        DisappearingLabelSpec.Off -> stringResource(R.string.disappearing_off)
        is DisappearingLabelSpec.Preset -> stringResource(spec.resId)
        is DisappearingLabelSpec.Seconds ->
            stringResource(R.string.disappearing_seconds_format, spec.count)
        is DisappearingLabelSpec.Minutes ->
            stringResource(R.string.disappearing_minutes_format, spec.count)
        is DisappearingLabelSpec.Hours ->
            stringResource(R.string.disappearing_hours_format, spec.count)
        is DisappearingLabelSpec.Days ->
            stringResource(R.string.disappearing_days_format, spec.count)
        is DisappearingLabelSpec.Weeks ->
            pluralStringResource(R.plurals.disappearing_weeks_count, spec.count.toInt(), spec.count.toInt())
        is DisappearingLabelSpec.Months ->
            pluralStringResource(R.plurals.disappearing_months_count, spec.count.toInt(), spec.count.toInt())
        is DisappearingLabelSpec.Years ->
            pluralStringResource(R.plurals.disappearing_years_count, spec.count.toInt(), spec.count.toInt())
    }

@Composable
private fun DisappearingCustomDialog(
    initialSecs: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    hostInDialog: Boolean = true,
    onPickerCreated: ((NumberPicker) -> Unit)? = null,
) {
    val units = disappearingCustomUnits
    val initialState = remember(initialSecs) { disappearingCustomPickerStateForSeconds(initialSecs) }
    val unitLabels = units.map { stringResource(it.labelRes) }.toTypedArray()
    var unitIndex by remember(initialSecs) { mutableIntStateOf(initialState.unitIndex) }
    var value by remember(initialSecs) { mutableIntStateOf(initialState.value) }
    val unitMax = units[unitIndex].max
    val pickerMax = maxOf(unitMax, value)
    val displayValue = value.coerceIn(1, pickerMax)
    // The selected (center) number is rendered bright by the theme; only the
    // scrolling neighbours expose a public color setter.
    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val content: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.width(300.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 12.dp)) {
                Text(
                    text = stringResource(R.string.disappearing_custom),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AndroidView(
                        factory = { ctx ->
                            NumberPicker(ctx)
                                .apply {
                                    tag = DISAPPEARING_CUSTOM_VALUE_PICKER_TAG
                                    minValue = 1
                                    setOnValueChangedListener { _, _, n -> value = n }
                                }.also { onPickerCreated?.invoke(it) }
                        },
                        update = { picker ->
                            picker.textColor = unselectedColor
                            picker.maxValue = pickerMax
                            picker.value = displayValue
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    AndroidView(
                        factory = { ctx ->
                            NumberPicker(ctx)
                                .apply {
                                    tag = DISAPPEARING_CUSTOM_UNIT_PICKER_TAG
                                    minValue = 0
                                    wrapSelectorWheel = false
                                    setOnValueChangedListener { _, _, n ->
                                        unitIndex = n
                                        value = clampDisappearingCustomValue(value, n)
                                    }
                                }.also { onPickerCreated?.invoke(it) }
                        },
                        update = { picker ->
                            picker.textColor = unselectedColor
                            picker.displayedValues = null
                            picker.maxValue = unitLabels.size - 1
                            picker.displayedValues = unitLabels
                            picker.value = unitIndex
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            onConfirm(disappearingCustomSeconds(displayValue, unitIndex))
                        },
                    ) {
                        Text(stringResource(R.string.disappearing_set))
                    }
                }
            }
        }
    }
    if (hostInDialog) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            content()
        }
    } else {
        content()
    }
}

@Composable
internal fun disappearingCustomDialogTestHost(
    initialSecs: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    onPickerCreated: (NumberPicker) -> Unit,
) {
    DisappearingCustomDialog(
        initialSecs = initialSecs,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        hostInDialog = false,
        onPickerCreated = onPickerCreated,
    )
}

package dev.ipf.whitenoise.android.ui.group

import android.widget.NumberPicker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import dev.ipf.whitenoise.android.R

internal const val DISAPPEARING_CUSTOM_VALUE_PICKER_TAG = "disappearing_custom_value_picker"
internal const val DISAPPEARING_CUSTOM_UNIT_PICKER_TAG = "disappearing_custom_unit_picker"

// Full-screen retention picker: an explanatory line, a radio list of preset
// windows + Custom, and a Save action. The selection is STAGED — nothing
// changes until Save, so the caller's [onPick] (which routes through the group
// mutation lock + prune confirm) fires once. Custom opens a wheel picker.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DisappearingMessagesPickerDialog(
    currentSecs: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    var selected by remember(currentSecs) { mutableStateOf(currentSecs) }
    var showCustom by remember { mutableStateOf(false) }
    val isCustom = selected !in disappearingPresetSecs

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        // The edge-to-edge dialog owns its own window, so tint the status/nav
        // bar icons to match the active surface (light icons on dark themes,
        // dark icons on the light theme) instead of inheriting stale activity
        // appearance.
        val view = LocalView.current
        val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            WindowInsetsControllerCompat(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.disappearing_messages)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        },
                    )
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(onClick = { onPick(selected) }) {
                        Text(stringResource(R.string.save))
                    }
                },
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        stringResource(R.string.disappearing_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier.padding(
                                start = 20.dp,
                                end = 20.dp,
                                top = 8.dp,
                                bottom = 16.dp,
                            ),
                    )
                    disappearingPresetSecs.forEach { secs ->
                        DisappearingOptionRow(
                            label = disappearingMessagesLabel(secs),
                            selected = !isCustom && selected == secs,
                            onClick = { selected = secs },
                        )
                    }
                    DisappearingOptionRow(
                        label = stringResource(R.string.disappearing_custom),
                        selected = isCustom,
                        onClick = { showCustom = true },
                    )
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
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
private fun DisappearingOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

// Wheel picker for an arbitrary duration: a value column + a unit column
// (seconds through years), using the platform NumberPicker for the native
// wheel feel of the reference.
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

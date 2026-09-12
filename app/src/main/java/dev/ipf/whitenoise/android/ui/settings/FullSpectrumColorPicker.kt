package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.HsvColor
import dev.ipf.whitenoise.android.state.opaqueArgbToHsv
import dev.ipf.whitenoise.android.state.parseOpaqueColorHex
import dev.ipf.whitenoise.android.state.tonalBubbleColorPresets
import dev.ipf.whitenoise.android.ui.conversation.messages.colorFromArgb
import java.util.Locale

/**
 * The prototype's colour editor: ten preset swatches, hue / saturation / brightness sliders and a hex field with a
 * live swatch. Every accepted colour is reported through [onColorSelected]; [onValidityChanged] follows the hex
 * field so callers can hold Save while the text is not a colour.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun FullSpectrumColorPicker(
    selectedArgb: Long?,
    fallbackArgb: Long,
    onColorSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onValidityChanged: (Boolean) -> Unit = {},
) {
    val initial = selectedArgb ?: fallbackArgb
    val initialHsv = remember(initial) { opaqueArgbToHsv(initial) }
    var hue by rememberSaveable(initial) { mutableFloatStateOf(initialHsv.hue) }
    var saturation by rememberSaveable(initial) { mutableFloatStateOf(initialHsv.saturation) }
    var brightness by rememberSaveable(initial) { mutableFloatStateOf(initialHsv.value) }
    var hex by rememberSaveable(initial) { mutableStateOf(formatColorHex(initial)) }
    val parsedHex = parseOpaqueColorHex(hex)
    val sliderArgb = HsvColor(hue, saturation, brightness).toOpaqueArgb()

    fun applyHsv(color: Long) {
        val hsv = opaqueArgbToHsv(color)
        hue = hsv.hue
        saturation = hsv.saturation
        brightness = hsv.value
    }

    fun updateFromSliders(
        h: Float = hue,
        s: Float = saturation,
        v: Float = brightness,
    ) {
        val color = HsvColor(h, s, v).toOpaqueArgb()
        hex = formatColorHex(color)
        onValidityChanged(true)
        onColorSelected(color)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ColorPickerDefaults.Gap)) {
        ColorPresetRow(
            selectedArgb = parsedHex,
            onSelect = { argb ->
                applyHsv(argb)
                hex = formatColorHex(argb)
                onValidityChanged(true)
                onColorSelected(argb)
            },
        )
        ColorSlider(
            label = stringResource(R.string.color_hue, hue.toInt()),
            value = hue,
            valueRange = 0f..ColorPickerDefaults.HUE_MAX,
            testTag = "color.hue",
            onValueChange = {
                hue = it
                updateFromSliders(h = it)
            },
        )
        ColorSlider(
            label = stringResource(R.string.color_saturation, (saturation * ColorPickerDefaults.PERCENT).toInt()),
            value = saturation,
            valueRange = 0f..1f,
            testTag = "color.saturation",
            onValueChange = {
                saturation = it
                updateFromSliders(s = it)
            },
        )
        ColorSlider(
            label = stringResource(R.string.color_brightness, (brightness * ColorPickerDefaults.PERCENT).toInt()),
            value = brightness,
            valueRange = 0f..1f,
            testTag = "color.brightness",
            onValueChange = {
                brightness = it
                updateFromSliders(v = it)
            },
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ColorPickerDefaults.Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorSwatch(argb = parsedHex ?: sliderArgb, selected = false, modifier = Modifier.testTag("color.swatch"))
            OutlinedTextField(
                value = hex,
                onValueChange = { value ->
                    hex = value
                    val parsed = parseOpaqueColorHex(value)
                    onValidityChanged(parsed != null)
                    parsed?.let { color ->
                        onColorSelected(color)
                        applyHsv(color)
                    }
                },
                label = { Text(stringResource(R.string.color_hex)) },
                supportingText = if (parsedHex == null) ({ Text(stringResource(R.string.color_hex_error)) }) else null,
                isError = parsedHex == null,
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("color.hex"),
            )
        }
    }
}

/** The ten preset swatches as a wrapping row of 48 dp radio-style circles. */
@Suppress("FunctionNaming")
@Composable
private fun ColorPresetRow(
    selectedArgb: Long?,
    onSelect: (Long) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ColorPickerDefaults.Gap),
        verticalArrangement = Arrangement.spacedBy(ColorPickerDefaults.Gap),
    ) {
        tonalBubbleColorPresets().forEach { argb ->
            val description = stringResource(R.string.color_swatch_description, formatColorHex(argb))
            ColorSwatch(
                argb = argb,
                selected = selectedArgb == argb,
                modifier =
                    Modifier
                        .clickable { onSelect(argb) }
                        .semantics {
                            role = Role.RadioButton
                            selected = selectedArgb == argb
                            contentDescription = description
                        },
            )
        }
    }
}

/** One 48 dp circle; the selected preset carries a 3 dp on-surface ring, the rest a 1 dp outline. */
@Suppress("FunctionNaming")
@Composable
private fun ColorSwatch(
    argb: Long,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(ColorPickerDefaults.SwatchSize)
            .clip(CircleShape)
            .background(colorFromArgb(argb), CircleShape)
            .border(
                if (selected) ColorPickerDefaults.SelectedRing else ColorPickerDefaults.RestingRing,
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                CircleShape,
            ),
    )
}

/** Label above a slider; the label doubles as the slider's accessible name and carries the current value. */
@Suppress("FunctionNaming")
@Composable
private fun ColorSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    testTag: String,
    onValueChange: (Float) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = Modifier.testTag(testTag).semantics { contentDescription = label },
    )
}

/** `#RRGGBB` for an opaque colour. */
internal fun formatColorHex(argb: Long): String = "#%06X".format(Locale.ROOT, argb and ColorPickerDefaults.RGB_MASK)

/** Geometry and ranges of the colour editor. */
internal object ColorPickerDefaults {
    val Gap = 12.dp
    val SwatchSize = 48.dp
    val SelectedRing = 3.dp
    val RestingRing = 1.dp
    const val HUE_MAX = 359f
    const val PERCENT = 100f
    const val RGB_MASK = 0xFFFFFFL
}

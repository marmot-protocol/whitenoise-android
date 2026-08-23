package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.HsvColor
import dev.ipf.whitenoise.android.state.opaqueArgbToHsv
import dev.ipf.whitenoise.android.state.withoutBlueChannel
import dev.ipf.whitenoise.android.ui.conversation.messages.colorFromArgb
import java.util.Locale

private const val HUE_MAX_DEGREES = 360f
private const val HUE_STEP_COUNT = 359
private const val HUE_STOP_COUNT = 7
private const val HUE_STOP_INTERVAL = 60f
private const val CHANNEL_STEP_COUNT = 99
private const val PERCENT_SCALE = 100f
private const val HUE_KEYBOARD_STEP = 1f
private const val CHANNEL_KEYBOARD_STEP = 0.01f
private val HUE_STOPS = List(HUE_STOP_COUNT) { index -> index * HUE_STOP_INTERVAL }
private val CHANNEL_THUMB_RADIUS = 9.dp

private fun Long.blueFreeWhen(enabled: Boolean): Long = if (enabled) withoutBlueChannel() else this

@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun FullSpectrumColorPicker(
    argb: Long,
    onColorChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
    blueFree: Boolean = false,
    isColorAccepted: (Long) -> Boolean = { true },
) {
    val displayedArgb = argb.blueFreeWhen(blueFree)
    val indicatorColor = if (blueFree) MaterialTheme.colorScheme.onSurface else Color.White
    var hsv by remember { mutableStateOf(opaqueArgbToHsv(displayedArgb)) }
    var lastEmittedArgb by remember { mutableLongStateOf(displayedArgb) }
    LaunchedEffect(displayedArgb) {
        if (displayedArgb != lastEmittedArgb) {
            hsv = opaqueArgbToHsv(displayedArgb)
            lastEmittedArgb = displayedArgb
        }
    }

    fun updateHsv(updated: HsvColor): Boolean {
        val updatedArgb = updated.toOpaqueArgb().blueFreeWhen(blueFree)
        if (!isColorAccepted(updatedArgb)) return false
        hsv = updated
        lastEmittedArgb = updatedArgb
        onColorChanged(updatedArgb)
        return true
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorChannel(
            label = stringResource(R.string.color_picker_hue),
            valueLabel = "${hsv.hue.toInt()}°",
            value = hsv.hue,
            valueRange = 0f..HUE_MAX_DEGREES,
            steps = HUE_STEP_COUNT,
            gradientColors =
                HUE_STOPS.map { hue ->
                    HsvColor(hue, 1f, 1f)
                        .toOpaqueArgb()
                        .blueFreeWhen(blueFree)
                        .let(::colorFromArgb)
                },
            keyboardStep = HUE_KEYBOARD_STEP,
            indicatorColor = indicatorColor,
            onValueChange = { updateHsv(hsv.copy(hue = it)) },
        )
        ColorChannel(
            label = stringResource(R.string.color_picker_saturation),
            valueLabel = String.format(Locale.ROOT, "%.0f%%", hsv.saturation * PERCENT_SCALE),
            value = hsv.saturation,
            valueRange = 0f..1f,
            steps = CHANNEL_STEP_COUNT,
            gradientColors =
                listOf(
                    colorFromArgb(
                        hsv.copy(saturation = 0f).toOpaqueArgb().blueFreeWhen(blueFree),
                    ),
                    colorFromArgb(
                        hsv.copy(saturation = 1f).toOpaqueArgb().blueFreeWhen(blueFree),
                    ),
                ),
            keyboardStep = CHANNEL_KEYBOARD_STEP,
            indicatorColor = indicatorColor,
            onValueChange = { updateHsv(hsv.copy(saturation = it)) },
        )
        ColorChannel(
            label = stringResource(R.string.color_picker_brightness),
            valueLabel = String.format(Locale.ROOT, "%.0f%%", hsv.value * PERCENT_SCALE),
            value = hsv.value,
            valueRange = 0f..1f,
            steps = CHANNEL_STEP_COUNT,
            gradientColors =
                listOf(
                    Color.Black,
                    colorFromArgb(
                        hsv.copy(value = 1f).toOpaqueArgb().blueFreeWhen(blueFree),
                    ),
                ),
            keyboardStep = CHANNEL_KEYBOARD_STEP,
            indicatorColor = indicatorColor,
            onValueChange = { updateHsv(hsv.copy(value = it)) },
        )
    }
}

@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun ColorChannel(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    gradientColors: List<Color>,
    keyboardStep: Float,
    indicatorColor: Color,
    onValueChange: (Float) -> Boolean,
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val coercedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val rangeLength = valueRange.endInclusive - valueRange.start

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val delta =
                            when (event.key) {
                                Key.DirectionLeft, Key.DirectionDown -> -keyboardStep
                                Key.DirectionRight, Key.DirectionUp -> keyboardStep
                                else -> return@onKeyEvent false
                            }
                        currentOnValueChange(
                            (coercedValue + delta).coerceIn(valueRange.start, valueRange.endInclusive),
                        )
                    }.focusable()
                    .semantics {
                        contentDescription = label
                        stateDescription = valueLabel
                        progressBarRangeInfo = ProgressBarRangeInfo(coercedValue, valueRange, steps)
                        setProgress { target ->
                            currentOnValueChange(target.coerceIn(valueRange.start, valueRange.endInclusive))
                        }
                    }.pointerInput(valueRange) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()

                            fun updateAt(x: Float) {
                                val inset = CHANNEL_THUMB_RADIUS.toPx()
                                val interactiveWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                                val fraction = ((x - inset) / interactiveWidth).coerceIn(0f, 1f)
                                currentOnValueChange(valueRange.start + fraction * rangeLength)
                            }
                            updateAt(down.position.x)
                            var finished = false
                            while (!finished) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                                change.consume()
                                updateAt(change.position.x)
                                finished = change.changedToUp() || !change.pressed
                            }
                        }
                    },
        ) {
            val trackHeight = 20.dp.toPx()
            val trackTop = (size.height - trackHeight) / 2f
            drawRoundRect(
                brush = Brush.horizontalGradient(gradientColors),
                topLeft = Offset(0f, trackTop),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f),
            )
            val indicatorRadius = CHANNEL_THUMB_RADIUS.toPx()
            val fraction = (coercedValue - valueRange.start) / rangeLength
            val indicatorCenter =
                Offset(
                    indicatorRadius + fraction * (size.width - indicatorRadius * 2f),
                    size.height / 2f,
                )
            drawCircle(indicatorColor, radius = indicatorRadius, center = indicatorCenter)
            drawCircle(
                Color.Black.copy(alpha = 0.7f),
                radius = indicatorRadius,
                center = indicatorCenter,
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}

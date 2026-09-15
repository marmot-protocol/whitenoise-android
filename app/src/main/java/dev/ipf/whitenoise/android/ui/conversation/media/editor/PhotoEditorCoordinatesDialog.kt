@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.media.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.editor.NormalizedPoint
import dev.ipf.whitenoise.android.media.editor.NormalizedRect
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import kotlin.math.roundToInt

private const val PERCENT = 100f
private const val DEFAULT_LINE_START = 0.25f
private const val DEFAULT_LINE_END = 0.75f
private const val DEFAULT_LINE_AXIS = 0.5f

/** Title of the coordinate dialog for the tool now in use. */
@Composable
internal fun coordinateDialogTitle(tool: PhotoEditorTool): String =
    stringResource(
        when (tool) {
            PhotoEditorTool.Crop -> R.string.photo_editor_adjust_crop
            PhotoEditorTool.Draw -> R.string.photo_editor_draw_line
            PhotoEditorTool.Erase -> R.string.photo_editor_erase_line
        },
    )

/**
 * Numeric alternative to dragging on the canvas: four sliders set either the crop edges or the two
 * endpoints of a straight stroke, so the whole editor stays usable with a switch or screen reader.
 */
@Composable
@Suppress("LongParameterList")
internal fun PhotoEditorCoordinatesDialog(
    tool: PhotoEditorTool,
    crop: NormalizedRect,
    minimumFraction: Float,
    onDismiss: () -> Unit,
    onCrop: (NormalizedRect) -> Unit,
    onStroke: (List<NormalizedPoint>) -> Unit,
) {
    val cropMode = tool == PhotoEditorTool.Crop
    var first by rememberSaveable(tool) { mutableFloatStateOf(if (cropMode) crop.left else DEFAULT_LINE_START) }
    var second by rememberSaveable(tool) { mutableFloatStateOf(if (cropMode) crop.top else DEFAULT_LINE_AXIS) }
    var third by rememberSaveable(tool) { mutableFloatStateOf(if (cropMode) crop.right else DEFAULT_LINE_END) }
    var fourth by rememberSaveable(tool) { mutableFloatStateOf(if (cropMode) crop.bottom else DEFAULT_LINE_AXIS) }
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(coordinateDialogTitle(tool)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                EditorCoordinateSlider(
                    label = stringResource(if (cropMode) R.string.photo_editor_left else R.string.photo_editor_start_x),
                    value = first,
                    range = 0f..(if (cropMode) (third - minimumFraction).coerceAtLeast(0f) else 1f),
                ) { first = it }
                EditorCoordinateSlider(
                    label = stringResource(if (cropMode) R.string.photo_editor_top else R.string.photo_editor_start_y),
                    value = second,
                    range = 0f..(if (cropMode) (fourth - minimumFraction).coerceAtLeast(0f) else 1f),
                ) { second = it }
                EditorCoordinateSlider(
                    label =
                        stringResource(if (cropMode) R.string.photo_editor_right else R.string.photo_editor_end_x),
                    value = third,
                    range = (if (cropMode) (first + minimumFraction).coerceAtMost(1f) else 0f)..1f,
                ) { third = it }
                EditorCoordinateSlider(
                    label =
                        stringResource(if (cropMode) R.string.photo_editor_bottom else R.string.photo_editor_end_y),
                    value = fourth,
                    range = (if (cropMode) (second + minimumFraction).coerceAtMost(1f) else 0f)..1f,
                ) { fourth = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (cropMode) {
                        onCrop(
                            NormalizedRect.clamped(
                                NormalizedPoint(first, second),
                                NormalizedPoint(third, fourth),
                                minimumFraction,
                            ),
                        )
                    } else {
                        onStroke(listOf(NormalizedPoint(first, second), NormalizedPoint(third, fourth)))
                    }
                },
                modifier = Modifier.testTag("photo.editor.coordinates.apply"),
            ) { Text(stringResource(R.string.photo_editor_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** One labelled percentage slider; the label doubles as its accessible name. */
@Composable
private fun EditorCoordinateSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Text(
        text = stringResource(R.string.photo_editor_coordinate_value, label, (value * PERCENT).roundToInt()),
        style = MaterialTheme.typography.bodySmall,
    )
    Slider(
        value = value.coerceIn(range),
        onValueChange = onValueChange,
        valueRange = range,
        modifier = Modifier.semantics { contentDescription = label },
    )
}

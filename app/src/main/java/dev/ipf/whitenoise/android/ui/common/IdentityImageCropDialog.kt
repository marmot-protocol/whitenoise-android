package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.IdentityImageCrop
import dev.ipf.whitenoise.android.media.IdentityImageCropShape
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import kotlin.math.roundToInt

/** Sizes and angles the crop surface uses, kept off the call sites detekt reads as magic numbers. */
private object IdentityImageCropDefaults {
    const val QUARTER_TURN_DEGREES = 90f
    val EdgePadding = 20.dp
    val MaskCorner = 28.dp
    val ActionSpacing = 12.dp
    val MaskInset = 8.dp
}

/**
 * Chooses which square of [preview] becomes an identity image.
 *
 * The mask never moves: the picture is panned and pinched beneath it, which is what keeps the
 * result square without asking anyone to match corners. Only the selected region is drawn, so this
 * preview is the published image rather than an approximation of it.
 */
@Suppress("FunctionNaming")
@Composable
internal fun IdentityImageCropDialog(
    preview: ImageBitmap,
    sourceSize: EditorPixelSize,
    shape: IdentityImageCropShape,
    onDismiss: () -> Unit,
    onConfirm: (IdentityImageCrop) -> Unit,
) {
    var crop by remember(preview) { mutableStateOf(IdentityImageCrop.Centered) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .testTag("identity_crop.dialog"),
            ) {
                IdentityImageCropTopBar(onDismiss = onDismiss, onConfirm = { onConfirm(crop) })
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    IdentityImageCropCanvas(
                        preview = preview,
                        sourceSize = sourceSize,
                        crop = crop,
                        shape = shape,
                        onCrop = { transform -> crop = transform(crop) },
                    )
                }
                IdentityImageCropActions(onRotate = { crop = crop.rotatedClockwise() })
            }
        }
    }
}

/**
 * Close, title and accept, laid out the way the conversation photo editor already does it.
 *
 * Accepting is a text action rather than a filled task button: this is an editor being dismissed
 * with a result, not a form being submitted, and the app bar is where its flows put that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
private fun IdentityImageCropTopBar(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.photo_editor_crop)) },
        navigationIcon = {
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("identity_crop.cancel")) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
            }
        },
        actions = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("identity_crop.confirm")) {
                Text(stringResource(R.string.done))
            }
        },
    )
}

/** The masked square that shows exactly the pixels the crop selects. */
@Suppress("FunctionNaming")
@Composable
private fun IdentityImageCropCanvas(
    preview: ImageBitmap,
    sourceSize: EditorPixelSize,
    crop: IdentityImageCrop,
    shape: IdentityImageCropShape,
    onCrop: ((IdentityImageCrop) -> IdentityImageCrop) -> Unit,
) {
    // A gesture sends many deltas before anything recomposes, so it reports how to change the crop
    // rather than what to set it to. Handing over a value would apply every delta of a drag to
    // whichever crop happened to be captured, leaving the picture almost still under the finger.
    val currentOnCrop by rememberUpdatedState(onCrop)
    val maskShape =
        when (shape) {
            IdentityImageCropShape.Circle -> RoundedCornerShape(percent = 50)
            IdentityImageCropShape.RoundedSquare -> RoundedCornerShape(IdentityImageCropDefaults.MaskCorner)
        }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(IdentityImageCropDefaults.EdgePadding)
                .aspectRatio(1f)
                .clip(maskShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .testTag("identity_crop.canvas"),
    ) {
        Canvas(
            modifier =
                Modifier.fillMaxSize().pointerInput(sourceSize) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val viewport = size.width.toFloat()
                        currentOnCrop { previous ->
                            previous.zoomedBy(zoom).pannedBy(pan.x, pan.y, viewport, sourceSize)
                        }
                    }
                },
        ) {
            val rect = crop.rectFor(sourceSize)
            val srcOffset =
                IntOffset(
                    (rect.left * preview.width).roundToInt(),
                    (rect.top * preview.height).roundToInt(),
                )
            val srcSize =
                IntSize(
                    (rect.width * preview.width).roundToInt().coerceAtLeast(1),
                    (rect.height * preview.height).roundToInt().coerceAtLeast(1),
                )
            rotate(IdentityImageCropDefaults.QUARTER_TURN_DEGREES * crop.quarterTurnsClockwise) {
                drawImage(
                    image = preview,
                    srcOffset = srcOffset,
                    srcSize = srcSize,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}

/** Turning sits under the picture, the only control the canvas itself needs. */
@Suppress("FunctionNaming")
@Composable
private fun IdentityImageCropActions(onRotate: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(IdentityImageCropDefaults.EdgePadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onRotate, modifier = Modifier.testTag("identity_crop.rotate")) {
            Icon(
                painterResource(R.drawable.ic_refresh),
                contentDescription = stringResource(R.string.photo_editor_rotate_clockwise),
            )
        }
    }
}

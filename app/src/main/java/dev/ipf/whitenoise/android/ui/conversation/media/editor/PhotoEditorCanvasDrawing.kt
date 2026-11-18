@file:Suppress("MagicNumber", "MaxLineLength")

package dev.ipf.whitenoise.android.ui.conversation.media.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.media.editor.EditorPoint
import dev.ipf.whitenoise.android.media.editor.EditorViewTransform
import dev.ipf.whitenoise.android.media.editor.NormalizedPoint
import dev.ipf.whitenoise.android.media.editor.NormalizedRect
import dev.ipf.whitenoise.android.media.editor.PhotoEditGeometry
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoStrokeMode
import kotlin.math.hypot
import kotlin.math.min

@Suppress("LongMethod") // Drawing all editor layers in one DrawScope prevents transform drift.
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEditorPreview(
    previewBitmap: Bitmap,
    geometry: PhotoEditGeometry,
    viewTransform: EditorViewTransform,
    committedRecipe: PhotoEditRecipe,
    transientCrop: NormalizedRect?,
    transientStroke: List<NormalizedPoint>,
    transientMode: PhotoStrokeMode,
    transientColor: Int,
    transientWidth: Float,
    cropMode: Boolean,
) {
    val affine = geometry.orientedBitmapToOutputAffine(previewBitmap.width, previewBitmap.height)
    val matrix =
        Matrix().apply {
            setValues(
                floatArrayOf(
                    affine.scaleX * viewTransform.scale,
                    affine.skewX * viewTransform.scale,
                    viewTransform.offsetX + affine.translateX * viewTransform.scale,
                    affine.skewY * viewTransform.scale,
                    affine.scaleY * viewTransform.scale,
                    viewTransform.offsetY + affine.translateY * viewTransform.scale,
                    0f,
                    0f,
                    1f,
                ),
            )
        }
    val outputLeft = viewTransform.offsetX
    val outputTop = viewTransform.offsetY
    val outputRight = outputLeft + geometry.outputSize.width * viewTransform.scale
    val outputBottom = outputTop + geometry.outputSize.height * viewTransform.scale
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val checkpoint = native.save()
        native.clipRect(outputLeft, outputTop, outputRight, outputBottom)
        native.drawBitmap(
            previewBitmap,
            matrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        native.restoreToCount(checkpoint)
    }
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val checkpoint = native.saveLayer(outputLeft, outputTop, outputRight, outputBottom, null)
        native.clipRect(outputLeft, outputTop, outputRight, outputBottom)
        committedRecipe.strokes.forEach { stroke ->
            drawPreviewStroke(native, stroke.points, stroke.mode, stroke.colorArgb, stroke.widthFraction, geometry, viewTransform)
        }
        if (transientStroke.isNotEmpty()) {
            drawPreviewStroke(native, transientStroke, transientMode, transientColor, transientWidth, geometry, viewTransform)
        }
        native.restoreToCount(checkpoint)
    }
    if (cropMode) {
        val crop = transientCrop ?: committedRecipe.crop
        val corners = cropCorners(crop).map { geometry.orientedToOutput(it).let(viewTransform::outputToView) }
        val left = corners.minOf { it.x }
        val top = corners.minOf { it.y }
        val right = corners.maxOf { it.x }
        val bottom = corners.maxOf { it.y }
        val scrim = Color.Black.copy(alpha = 0.55f)
        drawRect(
            scrim,
            Offset(0f, 0f),
            androidx.compose.ui.geometry
                .Size(size.width, top.coerceAtLeast(0f)),
        )
        drawRect(
            scrim,
            Offset(0f, bottom),
            androidx.compose.ui.geometry
                .Size(size.width, (size.height - bottom).coerceAtLeast(0f)),
        )
        drawRect(
            scrim,
            Offset(0f, top),
            androidx.compose.ui.geometry
                .Size(left.coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
        )
        drawRect(
            scrim,
            Offset(right, top),
            androidx.compose.ui.geometry
                .Size((size.width - right).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
        )
        val cropOutline =
            androidx.compose.ui.geometry
                .Rect(left, top, right, bottom)
        // Dark underlay stays visible on light photos; white dashes stay visible on dark ones.
        drawRect(
            Color.Black,
            cropOutline.topLeft,
            cropOutline.size,
            style =
                androidx.compose.ui.graphics.drawscope
                    .Stroke(4.dp.toPx()),
        )
        drawRect(
            Color.White,
            cropOutline.topLeft,
            cropOutline.size,
            style =
                androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2.dp.toPx(),
                    pathEffect =
                        androidx.compose.ui.graphics.PathEffect
                            .dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                ),
        )
        val handleLength = minOf(24.dp.toPx(), (right - left) / 4, (bottom - top) / 4)
        corners.forEach { point ->
            val origin = Offset(point.x, point.y)
            val horizontal = if (point.x == left) handleLength else -handleLength
            val vertical = if (point.y == top) handleLength else -handleLength
            for ((color, width) in listOf(Color.Black to 7.dp.toPx(), Color.White to 3.dp.toPx())) {
                drawLine(color, origin, origin + Offset(horizontal, 0f), strokeWidth = width)
                drawLine(color, origin, origin + Offset(0f, vertical), strokeWidth = width)
            }
        }
        if (transientCrop != null) {
            for (fraction in listOf(1f / 3, 2f / 3)) {
                val x = left + (right - left) * fraction
                val y = top + (bottom - top) * fraction
                drawLine(Color.White.copy(alpha = .3f), Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
                drawLine(Color.White.copy(alpha = .3f), Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            }
        }
    }
}

private fun drawPreviewStroke(
    canvas: android.graphics.Canvas,
    points: List<NormalizedPoint>,
    mode: PhotoStrokeMode,
    color: Int,
    widthFraction: Float,
    geometry: PhotoEditGeometry,
    viewTransform: EditorViewTransform,
) {
    if (points.isEmpty()) return
    val mapped = points.map { geometry.orientedToOutput(it).let(viewTransform::outputToView) }
    val cropWidth = geometry.orientedSize.width * geometry.recipe.crop.width
    val naturalWidth =
        if (geometry.recipe.quarterTurnsClockwise % 2 == 0) {
            cropWidth
        } else {
            geometry.orientedSize.height * geometry.recipe.crop.height
        }
    val outputScale = geometry.outputSize.width / naturalWidth.coerceAtLeast(1f)
    val width = widthFraction * min(geometry.orientedSize.width, geometry.orientedSize.height) * outputScale * viewTransform.scale
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width.coerceAtLeast(1f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
            if (mode == PhotoStrokeMode.Erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
    if (mapped.size == 1) {
        canvas.drawCircle(mapped[0].x, mapped[0].y, paint.strokeWidth / 2f, paint)
    } else {
        val lines = FloatArray((mapped.size - 1) * 4)
        for (index in 0 until mapped.lastIndex) {
            val offset = index * 4
            lines[offset] = mapped[index].x
            lines[offset + 1] = mapped[index].y
            lines[offset + 2] = mapped[index + 1].x
            lines[offset + 3] = mapped[index + 1].y
        }
        canvas.drawLines(lines, paint)
    }
}

internal fun nearestCropCorner(
    position: Offset,
    crop: NormalizedRect,
    geometry: PhotoEditGeometry,
    viewTransform: EditorViewTransform,
    thresholdPx: Float,
): Int? {
    val nearest =
        cropCorners(crop)
            .map { geometry.orientedToOutput(it).let(viewTransform::outputToView) }
            .mapIndexed { index, point -> index to hypot(position.x - point.x, position.y - point.y) }
            .minByOrNull { it.second }
            ?.takeIf { it.second <= thresholdPx }
            ?: return null
    val point = geometry.viewToOriented(EditorPoint(position.x, position.y), viewTransform)
    val centerOwnsPress =
        if (crop.contains(point)) {
            val center =
                geometry
                    .orientedToOutput(NormalizedPoint((crop.left + crop.right) / 2f, (crop.top + crop.bottom) / 2f))
                    .let(viewTransform::outputToView)
            // Keep a move region even when enlarged corner targets overlap in a small crop.
            hypot(position.x - center.x, position.y - center.y) <= nearest.second
        } else {
            false
        }
    return nearest.first.takeUnless { centerOwnsPress }
}

internal fun movedCropCorner(
    crop: NormalizedRect,
    corner: Int,
    point: NormalizedPoint,
    minimumFraction: Float,
): NormalizedRect {
    val opposite =
        when (corner) {
            0 -> NormalizedPoint(crop.right, crop.bottom)
            1 -> NormalizedPoint(crop.left, crop.bottom)
            2 -> NormalizedPoint(crop.left, crop.top)
            else -> NormalizedPoint(crop.right, crop.top)
        }
    return NormalizedRect.clamped(point, opposite, minimumFraction)
}

private fun cropCorners(crop: NormalizedRect): List<NormalizedPoint> =
    listOf(
        NormalizedPoint(crop.left, crop.top),
        NormalizedPoint(crop.right, crop.top),
        NormalizedPoint(crop.right, crop.bottom),
        NormalizedPoint(crop.left, crop.bottom),
    )

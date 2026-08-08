package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect

internal const val IMAGE_EDITOR_MAX_EDGE_PX = 2_048
internal const val IMAGE_EDITOR_MAX_PIXELS = IMAGE_EDITOR_MAX_EDGE_PX.toLong() * IMAGE_EDITOR_MAX_EDGE_PX
private const val QUARTER_TURN_COUNT = 4
private const val RIGHT_ANGLE_DEGREES = 90f

internal fun withinImageEditorPixelLimit(
    width: Int,
    height: Int,
): Boolean =
    width in 1..IMAGE_EDITOR_MAX_EDGE_PX &&
        height in 1..IMAGE_EDITOR_MAX_EDGE_PX &&
        width.toLong() <= IMAGE_EDITOR_MAX_PIXELS / height.toLong()

internal object ImageEditorRenderer {
    /**
     * Replays the bounded edit state from the safely sampled, EXIF-oriented
     * source. The source is never mutated or recycled. Callers own the returned
     * bitmap and must render/encode it off the main thread.
     */
    fun render(
        source: Bitmap,
        state: ImageEditState,
    ): Bitmap? {
        if (source.isRecycled || !withinImageEditorPixelLimit(source.width, source.height)) return null
        var rotated: Bitmap? = null
        var overlay: Bitmap? = null
        var output: Bitmap? = null
        return try {
            val quarterTurns = state.quarterTurns.mod(QUARTER_TURN_COUNT)
            val oriented =
                if (quarterTurns == 0) {
                    source
                } else {
                    val created =
                        Bitmap.createBitmap(
                            source,
                            0,
                            0,
                            source.width,
                            source.height,
                            Matrix().apply { postRotate(quarterTurns * RIGHT_ANGLE_DEGREES) },
                            true,
                        )
                    rotated = created
                    created
                }
            val cropBounds = cropBounds(oriented, state.crop)
            val width = cropBounds.width()
            val height = cropBounds.height()
            require(withinImageEditorPixelLimit(width, height))

            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            output.setHasAlpha(oriented.hasAlpha())
            Canvas(output).drawBitmap(
                oriented,
                cropBounds,
                Rect(0, 0, width, height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )

            if (state.strokes.isNotEmpty()) {
                overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                overlay.setHasAlpha(true)
                val overlayCanvas = Canvas(overlay)
                state.strokes.takeLast(IMAGE_EDITOR_MAX_STROKES).forEach { stroke ->
                    drawStroke(overlayCanvas, stroke, width, height)
                }
                Canvas(output).drawBitmap(overlay, 0f, 0f, null)
            }
            output.also { output = null }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            output?.recycle()
            overlay?.recycle()
            rotated?.takeIf { it !== source }?.recycle()
        }
    }

    private fun cropBounds(
        bitmap: Bitmap,
        requested: NormalizedRect,
    ): Rect {
        val crop = requested.bounded()
        val left = (crop.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (crop.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right =
            (crop.right * bitmap.width)
                .toInt()
                .coerceIn(left + 1, bitmap.width)
        val bottom =
            (crop.bottom * bitmap.height)
                .toInt()
                .coerceIn(top + 1, bitmap.height)
        return Rect(left, top, right, bottom)
    }

    private fun drawStroke(
        canvas: Canvas,
        stroke: EditorStroke,
        width: Int,
        height: Int,
    ) {
        val points = stroke.points
        if (points.isEmpty()) return
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.colorArgb
                style = Paint.Style.STROKE
                strokeWidth = stroke.widthFraction * minOf(width, height)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                if (stroke.eraser) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
        if (points.size == 1) {
            val point = points.single()
            canvas.drawPoint(point.x * width, point.y * height, paint)
            return
        }
        val path = Path()
        path.moveTo(points.first().x * width, points.first().y * height)
        points.drop(1).forEach { point -> path.lineTo(point.x * width, point.y * height) }
        canvas.drawPath(path, paint)
    }
}

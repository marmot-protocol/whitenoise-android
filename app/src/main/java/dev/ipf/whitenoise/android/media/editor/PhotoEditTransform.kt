package dev.ipf.whitenoise.android.media.editor

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class EditorPixelSize(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "Editor pixel dimensions must be positive" }
    }

    val pixels: Long
        get() = width.toLong() * height.toLong()
}

internal data class EditorPoint(
    val x: Float,
    val y: Float,
)

internal data class EditorAffineTransform(
    val scaleX: Float,
    val skewX: Float,
    val translateX: Float,
    val skewY: Float,
    val scaleY: Float,
    val translateY: Float,
) {
    fun map(point: EditorPoint): EditorPoint =
        EditorPoint(
            x = scaleX * point.x + skewX * point.y + translateX,
            y = skewY * point.x + scaleY * point.y + translateY,
        )
}

internal data class EditorViewTransform(
    val outputSize: EditorPixelSize,
    val viewWidth: Float,
    val viewHeight: Float,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    fun outputToView(point: EditorPoint): EditorPoint =
        EditorPoint(
            x = offsetX + point.x * scale,
            y = offsetY + point.y * scale,
        )

    fun viewToOutput(point: EditorPoint): EditorPoint =
        EditorPoint(
            x = (point.x - offsetX) / scale,
            y = (point.y - offsetY) / scale,
        )

    companion object {
        fun fit(
            outputSize: EditorPixelSize,
            viewWidth: Float,
            viewHeight: Float,
            zoom: Float = 1f,
            panX: Float = 0f,
            panY: Float = 0f,
        ): EditorViewTransform {
            require(viewWidth.isFinite() && viewWidth > 0f)
            require(viewHeight.isFinite() && viewHeight > 0f)
            require(zoom.isFinite() && zoom >= 1f)
            val fitted =
                min(
                    viewWidth / outputSize.width.toFloat(),
                    viewHeight / outputSize.height.toFloat(),
                )
            val scale = fitted * zoom
            return EditorViewTransform(
                outputSize = outputSize,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                scale = scale,
                offsetX = (viewWidth - outputSize.width * scale) / 2f + panX,
                offsetY = (viewHeight - outputSize.height * scale) / 2f + panY,
            )
        }
    }
}

internal class PhotoEditGeometry private constructor(
    val encodedSize: EditorPixelSize,
    val orientedSize: EditorPixelSize,
    val outputSize: EditorPixelSize,
    val exifOrientation: Int,
    val recipe: PhotoEditRecipe,
) {
    fun encodedToOriented(point: NormalizedPoint): NormalizedPoint =
        when (exifOrientation) {
            EXIF_FLIP_HORIZONTAL -> NormalizedPoint(1f - point.x, point.y)
            EXIF_ROTATE_180 -> NormalizedPoint(1f - point.x, 1f - point.y)
            EXIF_FLIP_VERTICAL -> NormalizedPoint(point.x, 1f - point.y)
            EXIF_TRANSPOSE -> NormalizedPoint(point.y, point.x)
            EXIF_ROTATE_90 -> NormalizedPoint(1f - point.y, point.x)
            EXIF_TRANSVERSE -> NormalizedPoint(1f - point.y, 1f - point.x)
            EXIF_ROTATE_270 -> NormalizedPoint(point.y, 1f - point.x)
            else -> point
        }

    fun orientedToEncoded(point: NormalizedPoint): NormalizedPoint =
        when (exifOrientation) {
            EXIF_FLIP_HORIZONTAL -> NormalizedPoint(1f - point.x, point.y)
            EXIF_ROTATE_180 -> NormalizedPoint(1f - point.x, 1f - point.y)
            EXIF_FLIP_VERTICAL -> NormalizedPoint(point.x, 1f - point.y)
            EXIF_TRANSPOSE -> NormalizedPoint(point.y, point.x)
            EXIF_ROTATE_90 -> NormalizedPoint(point.y, 1f - point.x)
            EXIF_TRANSVERSE -> NormalizedPoint(1f - point.y, 1f - point.x)
            EXIF_ROTATE_270 -> NormalizedPoint(1f - point.y, point.x)
            else -> point
        }

    fun orientedToOutput(point: NormalizedPoint): EditorPoint {
        val crop = recipe.crop
        val cropX = (point.x - crop.left) / crop.width
        val cropY = (point.y - crop.top) / crop.height
        val rotated =
            when (recipe.quarterTurnsClockwise) {
                1 -> NormalizedPoint(1f - cropY, cropX)
                2 -> NormalizedPoint(1f - cropX, 1f - cropY)
                3 -> NormalizedPoint(cropY, 1f - cropX)
                else -> NormalizedPoint(cropX, cropY)
            }
        return EditorPoint(
            x = rotated.x * outputSize.width,
            y = rotated.y * outputSize.height,
        )
    }

    fun outputToOriented(point: EditorPoint): NormalizedPoint {
        val outputX = point.x / outputSize.width
        val outputY = point.y / outputSize.height
        val cropPoint =
            when (recipe.quarterTurnsClockwise) {
                1 -> NormalizedPoint(outputY, 1f - outputX)
                2 -> NormalizedPoint(1f - outputX, 1f - outputY)
                3 -> NormalizedPoint(1f - outputY, outputX)
                else -> NormalizedPoint(outputX, outputY)
            }
        return NormalizedPoint(
            x = recipe.crop.left + cropPoint.x * recipe.crop.width,
            y = recipe.crop.top + cropPoint.y * recipe.crop.height,
        )
    }

    fun viewToOriented(
        point: EditorPoint,
        viewTransform: EditorViewTransform,
    ): NormalizedPoint = outputToOriented(viewTransform.viewToOutput(point))

    fun orientedBitmapToOutputAffine(
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): EditorAffineTransform {
        require(bitmapWidth > 0 && bitmapHeight > 0)
        val origin = orientedToOutput(NormalizedPoint(0f, 0f))
        val horizontal = orientedToOutput(NormalizedPoint(1f, 0f))
        val vertical = orientedToOutput(NormalizedPoint(0f, 1f))
        return EditorAffineTransform(
            scaleX = (horizontal.x - origin.x) / bitmapWidth,
            skewX = (vertical.x - origin.x) / bitmapHeight,
            translateX = origin.x,
            skewY = (horizontal.y - origin.y) / bitmapWidth,
            scaleY = (vertical.y - origin.y) / bitmapHeight,
            translateY = origin.y,
        )
    }

    companion object {
        const val EXIF_NORMAL = 1
        const val EXIF_FLIP_HORIZONTAL = 2
        const val EXIF_ROTATE_180 = 3
        const val EXIF_FLIP_VERTICAL = 4
        const val EXIF_TRANSPOSE = 5
        const val EXIF_ROTATE_90 = 6
        const val EXIF_TRANSVERSE = 7
        const val EXIF_ROTATE_270 = 8

        fun create(
            encodedSize: EditorPixelSize,
            exifOrientation: Int,
            recipe: PhotoEditRecipe,
            maxEdgePx: Int,
            maxPixels: Long,
        ): PhotoEditGeometry {
            require(maxEdgePx > 0)
            require(maxPixels > 0)
            val swapsAxes = exifOrientation in setOf(EXIF_TRANSPOSE, EXIF_ROTATE_90, EXIF_TRANSVERSE, EXIF_ROTATE_270)
            val orientedSize =
                if (swapsAxes) {
                    EditorPixelSize(encodedSize.height, encodedSize.width)
                } else {
                    encodedSize
                }
            val croppedWidth = max(1, (orientedSize.width * recipe.crop.width).roundToInt())
            val croppedHeight = max(1, (orientedSize.height * recipe.crop.height).roundToInt())
            val naturalWidth = if (recipe.quarterTurnsClockwise % 2 == 0) croppedWidth else croppedHeight
            val naturalHeight = if (recipe.quarterTurnsClockwise % 2 == 0) croppedHeight else croppedWidth
            val edgeScale = min(1.0, maxEdgePx.toDouble() / max(naturalWidth, naturalHeight))
            val pixelScale = min(1.0, sqrt(maxPixels.toDouble() / (naturalWidth.toLong() * naturalHeight)))
            val scale = min(edgeScale, pixelScale)
            val outputSize =
                EditorPixelSize(
                    width = max(1, floor(naturalWidth * scale).toInt()),
                    height = max(1, floor(naturalHeight * scale).toInt()),
                )
            return PhotoEditGeometry(
                encodedSize = encodedSize,
                orientedSize = orientedSize,
                outputSize = outputSize,
                exifOrientation = exifOrientation.takeIf { it in EXIF_NORMAL..EXIF_ROTATE_270 } ?: EXIF_NORMAL,
                recipe = recipe,
            )
        }
    }
}

package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import kotlin.math.hypot
import kotlin.math.roundToInt

internal const val IMAGE_EDITOR_MAX_STROKES = 128
internal const val IMAGE_EDITOR_MAX_POINTS_PER_STROKE = 2_048
internal const val IMAGE_EDITOR_MAX_HISTORY = 64
private const val MIN_CROP_SIZE = 0.05f
private const val STROKE_SIMPLIFY_DISTANCE = 0.0025f
private const val MIN_STROKE_WIDTH = 0.002f
private const val MAX_STROKE_WIDTH = 0.08f
private const val QUARTER_TURN_COUNT = 4
private const val FOUR_THREE_RATIO = 4f / 3f
private const val SIXTEEN_NINE_RATIO = 16f / 9f
private const val NORMALIZED_COORDINATE_SCALE = 1_000_000f

internal data class NormalizedPoint(
    val x: Float,
    val y: Float,
)

internal data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun bounded(minSize: Float = MIN_CROP_SIZE): NormalizedRect {
        val safeMin = minSize.coerceIn(0f, 1f)
        var boundedLeft = left.coerceIn(0f, 1f)
        var boundedTop = top.coerceIn(0f, 1f)
        var boundedRight = right.coerceIn(0f, 1f)
        var boundedBottom = bottom.coerceIn(0f, 1f)
        if (boundedRight - boundedLeft < safeMin) {
            if (boundedLeft + safeMin <= 1f) {
                boundedRight = boundedLeft + safeMin
            } else {
                boundedLeft = boundedRight - safeMin
            }
        }
        if (boundedBottom - boundedTop < safeMin) {
            if (boundedTop + safeMin <= 1f) {
                boundedBottom = boundedTop + safeMin
            } else {
                boundedTop = boundedBottom - safeMin
            }
        }
        return NormalizedRect(
            normalizeCoordinate(boundedLeft),
            normalizeCoordinate(boundedTop),
            normalizeCoordinate(boundedRight),
            normalizeCoordinate(boundedBottom),
        )
    }

    companion object {
        val Full = NormalizedRect(0f, 0f, 1f, 1f)
    }
}

internal data class EditorStroke(
    val points: List<NormalizedPoint>,
    val colorArgb: Int,
    val widthFraction: Float,
    val eraser: Boolean,
) {
    companion object {
        fun bounded(
            points: List<NormalizedPoint>,
            colorArgb: Int,
            widthFraction: Float,
            eraser: Boolean,
        ): EditorStroke {
            val clamped = points.map { NormalizedPoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
            val simplified = simplifyPoints(clamped)
            val bounded =
                if (simplified.size <= IMAGE_EDITOR_MAX_POINTS_PER_STROKE) {
                    simplified
                } else {
                    val lastIndex = simplified.lastIndex
                    List(IMAGE_EDITOR_MAX_POINTS_PER_STROKE) { index ->
                        val sourceIndex =
                            (index.toLong() * lastIndex / (IMAGE_EDITOR_MAX_POINTS_PER_STROKE - 1L))
                                .toInt()
                        simplified[sourceIndex]
                    }
                }
            return EditorStroke(
                points = bounded,
                colorArgb = colorArgb,
                widthFraction = widthFraction.coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH),
                eraser = eraser,
            )
        }

        private fun simplifyPoints(points: List<NormalizedPoint>): List<NormalizedPoint> {
            if (points.size <= 2) return points
            val kept = ArrayList<NormalizedPoint>(minOf(points.size, IMAGE_EDITOR_MAX_POINTS_PER_STROKE))
            kept += points.first()
            var previous = points.first()
            for (point in points.subList(1, points.lastIndex)) {
                val distance =
                    hypot(
                        (point.x - previous.x).toDouble(),
                        (point.y - previous.y).toDouble(),
                    )
                if (distance >= STROKE_SIMPLIFY_DISTANCE) {
                    kept += point
                    previous = point
                }
            }
            kept += points.last()
            return kept
        }
    }
}

internal fun appendGesturePoint(
    current: List<NormalizedPoint>,
    point: NormalizedPoint,
): List<NormalizedPoint> {
    val normalized = NormalizedPoint(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
    if (current.size < IMAGE_EDITOR_MAX_POINTS_PER_STROKE) return current + normalized

    val compacted = ArrayList<NormalizedPoint>(IMAGE_EDITOR_MAX_POINTS_PER_STROKE / 2 + 2)
    current.forEachIndexed { index, existing ->
        if (index % 2 == 0) compacted += existing
    }
    if (compacted.lastOrNull() != current.last()) compacted += current.last()
    compacted += normalized
    return compacted
}

internal data class ImageEditState(
    val crop: NormalizedRect = NormalizedRect.Full,
    val quarterTurns: Int = 0,
    val strokes: List<EditorStroke> = emptyList(),
) {
    fun rotateRight(): ImageEditState =
        copy(
            crop =
                NormalizedRect(
                    left = 1f - crop.bottom,
                    top = crop.left,
                    right = 1f - crop.top,
                    bottom = crop.right,
                ).bounded(),
            quarterTurns = (quarterTurns + 1).mod(QUARTER_TURN_COUNT),
            strokes =
                strokes.map { stroke ->
                    stroke.copy(
                        points = stroke.points.map { point -> NormalizedPoint(1f - point.y, point.x).normalized() },
                    )
                },
        )

    fun rotateLeft(): ImageEditState = rotateRight().rotateRight().rotateRight()

    fun withCrop(nextCrop: NormalizedRect): ImageEditState {
        val bounded = nextCrop.bounded()
        if (bounded == crop) return this
        val remapped =
            strokes.map { stroke ->
                stroke.copy(
                    points =
                        stroke.points.map { point ->
                            val sourceX = crop.left + point.x * crop.width
                            val sourceY = crop.top + point.y * crop.height
                            NormalizedPoint(
                                x = (sourceX - bounded.left) / bounded.width,
                                y = (sourceY - bounded.top) / bounded.height,
                            ).normalized(clamp = false)
                        },
                )
            }
        return copy(crop = bounded, strokes = remapped)
    }

    fun addStroke(stroke: EditorStroke): ImageEditState =
        if (stroke.points.isEmpty()) {
            this
        } else {
            copy(strokes = (strokes + stroke).takeLast(IMAGE_EDITOR_MAX_STROKES))
        }

    fun outputDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
    ): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 0 to 0
        val rotatedWidth = if (quarterTurns.mod(2) == 0) sourceWidth else sourceHeight
        val rotatedHeight = if (quarterTurns.mod(2) == 0) sourceHeight else sourceWidth
        return (rotatedWidth * crop.width).roundToInt().coerceAtLeast(1) to
            (rotatedHeight * crop.height).roundToInt().coerceAtLeast(1)
    }
}

internal enum class ImageCropAspect(
    val ratio: Float?,
) {
    Free(null),
    Square(1f),
    FourThree(FOUR_THREE_RATIO),
    SixteenNine(SIXTEEN_NINE_RATIO),
}

internal fun cropRectForAspect(
    sourceWidth: Int,
    sourceHeight: Int,
    quarterTurns: Int,
    aspect: ImageCropAspect,
): NormalizedRect {
    val desiredRatio = aspect.ratio
    return if (desiredRatio == null || sourceWidth <= 0 || sourceHeight <= 0) {
        NormalizedRect.Full
    } else {
        val rotatedWidth = if (quarterTurns.mod(2) == 0) sourceWidth else sourceHeight
        val rotatedHeight = if (quarterTurns.mod(2) == 0) sourceHeight else sourceWidth
        val sourceRatio = rotatedWidth.toFloat() / rotatedHeight.toFloat()
        if (sourceRatio > desiredRatio) {
            val width = desiredRatio / sourceRatio
            NormalizedRect((1f - width) / 2f, 0f, (1f + width) / 2f, 1f).bounded()
        } else {
            val height = sourceRatio / desiredRatio
            NormalizedRect(0f, (1f - height) / 2f, 1f, (1f + height) / 2f).bounded()
        }
    }
}

internal data class ImageEditHistory(
    val current: ImageEditState = ImageEditState(),
    val undoStates: List<ImageEditState> = emptyList(),
    val redoStates: List<ImageEditState> = emptyList(),
) {
    val canUndo: Boolean get() = undoStates.isNotEmpty()
    val canRedo: Boolean get() = redoStates.isNotEmpty()

    fun commit(next: ImageEditState): ImageEditHistory =
        if (next == current) {
            this
        } else {
            ImageEditHistory(
                current = next,
                undoStates = (undoStates + current).takeLast(IMAGE_EDITOR_MAX_HISTORY),
                redoStates = emptyList(),
            )
        }

    fun undo(): ImageEditHistory {
        val previous = undoStates.lastOrNull() ?: return this
        return ImageEditHistory(
            current = previous,
            undoStates = undoStates.dropLast(1),
            redoStates = (redoStates + current).takeLast(IMAGE_EDITOR_MAX_HISTORY),
        )
    }

    fun redo(): ImageEditHistory {
        val next = redoStates.lastOrNull() ?: return this
        return ImageEditHistory(
            current = next,
            undoStates = (undoStates + current).takeLast(IMAGE_EDITOR_MAX_HISTORY),
            redoStates = redoStates.dropLast(1),
        )
    }

    fun reset(): ImageEditHistory = commit(ImageEditState())
}

internal data class ImageEditability(
    val canEdit: Boolean,
    val isUnsupportedImage: Boolean,
)

internal fun imageEditability(
    mime: String,
    isAnimated: Boolean,
): ImageEditability {
    val isImage = mime.startsWith("image/", ignoreCase = true)
    val unsupported = isImage && (isAnimated || mime.equals("image/avif", ignoreCase = true))
    return ImageEditability(
        canEdit = isImage && !unsupported,
        isUnsupportedImage = unsupported,
    )
}

internal fun replaceMediaUriIfCurrent(
    current: List<Uri>,
    index: Int,
    expected: Uri,
    replacement: Uri,
    expectedRevision: String? = null,
    currentRevision: String? = null,
): List<Uri>? =
    if (expectedRevision == currentRevision && index in current.indices && current[index] == expected) {
        current.toMutableList().also { it[index] = replacement }
    } else {
        null
    }

internal fun isOnlyMediaUriReferenceAt(
    current: List<Uri>,
    index: Int,
    expected: Uri,
): Boolean = current.getOrNull(index) == expected && current.count { it == expected } == 1

private fun NormalizedPoint.normalized(clamp: Boolean = true): NormalizedPoint =
    NormalizedPoint(
        x = normalizeCoordinate(if (clamp) x.coerceIn(0f, 1f) else x),
        y = normalizeCoordinate(if (clamp) y.coerceIn(0f, 1f) else y),
    )

private fun normalizeCoordinate(value: Float): Float {
    val scaled = (value * NORMALIZED_COORDINATE_SCALE).roundToInt()
    return scaled / NORMALIZED_COORDINATE_SCALE
}

@file:Suppress("MagicNumber", "ReturnCount") // Values are normalized bounds/turns; guards enforce edit limits.

package dev.ipf.whitenoise.android.media.editor

import kotlin.math.max
import kotlin.math.min

internal data class NormalizedPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Editor points must be finite" }
    }

    fun clamped(): NormalizedPoint =
        NormalizedPoint(
            x = x.coerceIn(0f, 1f),
            y = y.coerceIn(0f, 1f),
        )
}

internal data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Editor crop bounds must be finite"
        }
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "Editor crop bounds must be normalized"
        }
        require(left < right && top < bottom) { "Editor crop bounds must have positive area" }
    }

    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    companion object {
        val Full = NormalizedRect(0f, 0f, 1f, 1f)

        fun clamped(
            first: NormalizedPoint,
            second: NormalizedPoint,
            minimumSize: Float,
        ): NormalizedRect {
            require(minimumSize.isFinite() && minimumSize in 0f..1f)
            val a = first.clamped()
            val b = second.clamped()
            var left = min(a.x, b.x)
            var top = min(a.y, b.y)
            var right = max(a.x, b.x)
            var bottom = max(a.y, b.y)
            if (right - left < minimumSize) {
                val center = (left + right) / 2f
                left = (center - minimumSize / 2f).coerceIn(0f, 1f - minimumSize)
                right = left + minimumSize
            }
            if (bottom - top < minimumSize) {
                val center = (top + bottom) / 2f
                top = (center - minimumSize / 2f).coerceIn(0f, 1f - minimumSize)
                bottom = top + minimumSize
            }
            return NormalizedRect(left, top, right, bottom)
        }
    }
}

internal enum class PhotoStrokeMode {
    Draw,
    Erase,
}

internal data class PhotoEditStroke(
    val id: String,
    val mode: PhotoStrokeMode,
    val widthFraction: Float,
    val colorArgb: Int,
    val points: List<NormalizedPoint>,
) {
    init {
        require(id.isNotBlank()) { "Editor stroke id must not be blank" }
        require(widthFraction.isFinite() && widthFraction in 0f..0.25f && widthFraction > 0f) {
            "Editor stroke width must be positive and bounded"
        }
        require(points.isNotEmpty()) { "Editor stroke must contain at least one point" }
    }
}

internal data class PhotoEditRecipe(
    val crop: NormalizedRect = NormalizedRect.Full,
    val quarterTurnsClockwise: Int = 0,
    val strokes: List<PhotoEditStroke> = emptyList(),
) {
    init {
        require(quarterTurnsClockwise in 0..3) { "Quarter turns must be canonical" }
    }

    val totalPointCount: Int
        get() = strokes.sumOf { it.points.size }

    companion object {
        val Original = PhotoEditRecipe()
    }
}

internal data class PhotoEditLimits(
    val maxUndoStates: Int = 50,
    val maxRedoStates: Int = 50,
    val maxStrokes: Int = 256,
    val maxPointsPerStroke: Int = 2_048,
    val maxTotalPoints: Int = 100_000,
    val minimumPointDistance: Float = 0.0005f,
) {
    init {
        require(maxUndoStates > 0)
        require(maxRedoStates > 0)
        require(maxStrokes > 0)
        require(maxPointsPerStroke > 0)
        require(maxTotalPoints > 0)
        require(minimumPointDistance.isFinite() && minimumPointDistance >= 0f)
    }
}

internal enum class PhotoEditLimit {
    StrokeCount,
    StrokePoints,
    TotalPoints,
}

internal data class PhotoEditMutation(
    val history: PhotoEditHistory,
    val reachedLimit: PhotoEditLimit? = null,
)

internal data class PhotoEditHistory(
    val original: PhotoEditRecipe = PhotoEditRecipe.Original,
    val current: PhotoEditRecipe = original,
    val undoStates: List<PhotoEditRecipe> = emptyList(),
    val redoStates: List<PhotoEditRecipe> = emptyList(),
    val limits: PhotoEditLimits = PhotoEditLimits(),
) {
    val canUndo: Boolean
        get() = undoStates.isNotEmpty()

    val canRedo: Boolean
        get() = redoStates.isNotEmpty()

    fun setCrop(crop: NormalizedRect): PhotoEditHistory = commit(current.copy(crop = crop))

    fun rotateClockwise(): PhotoEditHistory =
        commit(
            current.copy(
                quarterTurnsClockwise = (current.quarterTurnsClockwise + 1) % 4,
            ),
        )

    fun reset(): PhotoEditHistory = commit(original)

    fun addStroke(stroke: PhotoEditStroke): PhotoEditMutation {
        if (current.strokes.size >= limits.maxStrokes) {
            return PhotoEditMutation(this, PhotoEditLimit.StrokeCount)
        }
        val remainingPoints = limits.maxTotalPoints - current.totalPointCount
        if (remainingPoints <= 0) {
            return PhotoEditMutation(this, PhotoEditLimit.TotalPoints)
        }
        val sanitized =
            sanitizeStrokePoints(
                points = stroke.points,
                maxPoints = min(limits.maxPointsPerStroke, remainingPoints),
                minimumDistance = limits.minimumPointDistance,
            )
        val limit =
            when {
                sanitized.size < stroke.points.size && remainingPoints < limits.maxPointsPerStroke ->
                    PhotoEditLimit.TotalPoints
                sanitized.size < stroke.points.size -> PhotoEditLimit.StrokePoints
                else -> null
            }
        val nextStroke = stroke.copy(points = sanitized)
        return PhotoEditMutation(
            history = commit(current.copy(strokes = current.strokes + nextStroke)),
            reachedLimit = limit,
        )
    }

    fun undo(): PhotoEditHistory {
        val previous = undoStates.lastOrNull() ?: return this
        return copy(
            current = previous,
            undoStates = undoStates.dropLast(1),
            redoStates = boundedRedo(redoStates + current),
        )
    }

    fun redo(): PhotoEditHistory {
        val next = redoStates.lastOrNull() ?: return this
        return copy(
            current = next,
            undoStates = boundedUndo(undoStates + current),
            redoStates = redoStates.dropLast(1),
        )
    }

    private fun commit(next: PhotoEditRecipe): PhotoEditHistory {
        if (next == current) return this
        return copy(
            current = next,
            undoStates = boundedUndo(undoStates + current),
            redoStates = emptyList(),
        )
    }

    private fun boundedUndo(states: List<PhotoEditRecipe>): List<PhotoEditRecipe> {
        if (states.size <= limits.maxUndoStates) return states
        if (limits.maxUndoStates == 1) return listOf(states.first())
        return listOf(states.first()) + states.takeLast(limits.maxUndoStates - 1)
    }

    private fun boundedRedo(states: List<PhotoEditRecipe>): List<PhotoEditRecipe> =
        if (states.size <= limits.maxRedoStates) states else states.takeLast(limits.maxRedoStates)
}

internal fun sanitizeStrokePoints(
    points: List<NormalizedPoint>,
    maxPoints: Int,
    minimumDistance: Float,
): List<NormalizedPoint> {
    require(maxPoints > 0)
    require(minimumDistance.isFinite() && minimumDistance >= 0f)
    if (points.isEmpty()) return listOf(NormalizedPoint(0f, 0f))

    val clamped = points.map(NormalizedPoint::clamped)
    val minimumDistanceSquared = minimumDistance * minimumDistance
    val coalesced = ArrayList<NormalizedPoint>(min(clamped.size, maxPoints))
    clamped.forEachIndexed { index, point ->
        val previous = coalesced.lastOrNull()
        val isLast = index == clamped.lastIndex
        if (previous == null || isLast || squaredDistance(previous, point) >= minimumDistanceSquared) {
            if (point != previous) coalesced += point
        }
    }
    if (coalesced.isEmpty()) coalesced += clamped.first()
    if (coalesced.size <= maxPoints) return coalesced
    if (maxPoints == 1) return listOf(coalesced.first())

    val sampled = ArrayList<NormalizedPoint>(maxPoints)
    repeat(maxPoints) { index ->
        val sourceIndex = ((index.toLong() * (coalesced.lastIndex)) / (maxPoints - 1)).toInt()
        sampled += coalesced[sourceIndex]
    }
    return sampled
}

private fun squaredDistance(
    first: NormalizedPoint,
    second: NormalizedPoint,
): Float {
    val dx = first.x - second.x
    val dy = first.y - second.y
    return dx * dx + dy * dy
}

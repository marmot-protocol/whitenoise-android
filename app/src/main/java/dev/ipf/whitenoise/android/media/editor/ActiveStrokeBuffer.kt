package dev.ipf.whitenoise.android.media.editor

/**
 * The points of the stroke under the finger. Each move costs the same however long the gesture runs. Points are kept by
 * the same rule [sanitizeStrokePoints] applies when the stroke is committed: a sample is kept once it is at least
 * [PhotoEditLimits.minimumPointDistance] from the last kept point, and the latest sample is always the stroke's end. A
 * buffer that reaches twice the per-stroke budget keeps every other point. Thinning can merge points the full gesture
 * kept apart, so [exceededPointBudget] carries the stroke limit to [PhotoEditHistory.addStroke] instead.
 */
internal class ActiveStrokeBuffer(
    limits: PhotoEditLimits = PhotoEditLimits(),
) {
    private val budget = limits.maxPointsPerStroke
    private val capacity = budget * 2
    private val minimumDistanceSquared = limits.minimumPointDistance * limits.minimumPointDistance
    private val retained = ArrayList<NormalizedPoint>(INITIAL_CAPACITY)

    /** Whether the last retained point is only the latest sample, closer than the minimum distance to a kept point. */
    private var hasPendingEnd = false
    private var samples = 0
    private var thinned = false

    /** A read-only view for drawing; copy it with [toStroke] before the next gesture reuses the buffer. */
    val points: List<NormalizedPoint> get() = retained

    /**
     * Whether the whole gesture outgrew the per-stroke budget: it had more samples than the budget and at least as many
     * kept points. This is the stroke limit [PhotoEditHistory.addStroke] would report for every sample.
     */
    val exceededPointBudget: Boolean get() = thinned || (samples > budget && retained.size >= budget)

    fun isEmpty(): Boolean = retained.isEmpty()

    fun start(point: NormalizedPoint) {
        clear()
        retained += point
        samples = 1
    }

    fun add(point: NormalizedPoint) {
        if (retained.isEmpty()) return start(point)
        samples++
        // The previous sample was only the stroke's end; the new sample replaces it.
        if (hasPendingEnd) retained.removeAt(retained.lastIndex)
        val lastKept = retained.last().clamped()
        val candidate = point.clamped()
        hasPendingEnd = candidate != lastKept && squaredDistance(lastKept, candidate) < minimumDistanceSquared
        if (candidate == lastKept) return
        retained += point
        if (!hasPendingEnd && retained.size >= capacity) halve()
    }

    fun toStroke(): List<NormalizedPoint> = retained.toList()

    fun clear() {
        retained.clear()
        hasPendingEnd = false
        samples = 0
        thinned = false
    }

    private fun halve() {
        thinned = true
        val last = retained.last()
        var write = 0
        for (read in 0 until retained.size step 2) {
            retained[write++] = retained[read]
        }
        retained.subList(write, retained.size).clear()
        if (retained.last() != last) retained += last
    }

    private fun squaredDistance(
        first: NormalizedPoint,
        second: NormalizedPoint,
    ): Float {
        val dx = first.x - second.x
        val dy = first.y - second.y
        return dx * dx + dy * dy
    }

    private companion object {
        const val INITIAL_CAPACITY = 256
    }
}

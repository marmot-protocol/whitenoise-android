package dev.ipf.whitenoise.android.media.editor

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Issue #3701: the stroke under the finger stays bounded however long the gesture runs. */
class ActiveStrokeBufferTest {
    private val limits = PhotoEditLimits()

    @Test
    fun longGestureStaysBoundedAndEndsUnderTheFinger() {
        val buffer = ActiveStrokeBuffer(limits)
        val moves = limits.maxPointsPerStroke * 20
        buffer.start(point(0))
        var maxSize = buffer.points.size
        for (index in 1..moves) {
            buffer.add(point(index))
            maxSize = maxOf(maxSize, buffer.points.size)
        }

        assertTrue("held $maxSize points", maxSize < limits.maxPointsPerStroke * 2)
        assertEquals(point(0), buffer.points.first())
        assertEquals(point(moves), buffer.points.last())
    }

    @Test
    fun overflowingStrokeStillReportsTheStrokeLimit() {
        val buffer = ActiveStrokeBuffer(limits)
        val moves = limits.maxPointsPerStroke * 3
        buffer.start(point(0))
        for (index in 1..moves) buffer.add(point(index))

        val mutation = PhotoEditHistory(limits = limits).addBufferedStroke(buffer)

        assertEquals(PhotoEditLimit.StrokePoints, mutation.reachedLimit)
        val strokes = mutation.history.current.strokes
        val committed = strokes.single().points
        assertEquals(limits.maxPointsPerStroke, committed.size)
        assertEquals(point(moves), committed.last())
    }

    @Test
    fun strokeThatDoublesBackKeepsTheTurn() {
        val buffer = ActiveStrokeBuffer(limits)
        val a = NormalizedPoint(0.2f, 0.5f)
        val b = NormalizedPoint(0.21f, 0.5f)
        buffer.start(a)
        buffer.add(b)
        buffer.add(a)

        assertEquals(listOf(a, b, a), buffer.toStroke())
    }

    /** Thinning a back-and-forth scribble merges its points, but the stroke limit is still reported. */
    @Test
    fun thinnedScribbleStillReportsTheStrokeLimit() {
        val buffer = ActiveStrokeBuffer(limits)
        val a = NormalizedPoint(0.2f, 0.5f)
        val b = NormalizedPoint(0.8f, 0.5f)
        buffer.start(a)
        for (index in 1 until limits.maxPointsPerStroke * 2) buffer.add(if (index % 2 == 0) a else b)

        assertTrue(buffer.exceededPointBudget)
        val mutation = PhotoEditHistory(limits = limits).addBufferedStroke(buffer)
        assertEquals(PhotoEditLimit.StrokePoints, mutation.reachedLimit)
    }

    @Test
    fun shortStrokeKeepsEveryDistinctPointAndNoLimit() {
        val buffer = ActiveStrokeBuffer(limits)
        buffer.start(point(0))
        repeat(9) { buffer.add(point(it + 1)) }

        assertEquals(10, buffer.points.size)
        assertEquals(null, PhotoEditHistory(limits = limits).addStroke(stroke(buffer.toStroke())).reachedLimit)
    }

    @Test
    fun jitterBelowTheMinimumDistanceMovesTheEndInsteadOfGrowing() {
        val buffer = ActiveStrokeBuffer(limits)
        val tiny = limits.minimumPointDistance / 10f
        buffer.start(NormalizedPoint(0.5f, 0.5f))
        buffer.add(NormalizedPoint(0.6f, 0.5f))
        repeat(JITTER_MOVES) { buffer.add(NormalizedPoint(0.6f + tiny * (it % 3), 0.5f)) }

        assertTrue("held ${buffer.points.size} points", buffer.points.size <= 3)
        assertEquals(NormalizedPoint(0.6f, 0.5f), buffer.points.last())
    }

    @Test
    fun committedStrokeMatchesSimplifyingEveryRawSample() {
        val random = Random(SEED)
        repeat(WALKS) {
            val buffer = ActiveStrokeBuffer(limits)
            val raw = randomWalk(random)
            raw.forEachIndexed { step, sample -> if (step == 0) buffer.start(sample) else buffer.add(sample) }

            val max = limits.maxPointsPerStroke
            val min = limits.minimumPointDistance
            assertEquals(sanitizeStrokePoints(raw, max, min), sanitizeStrokePoints(buffer.toStroke(), max, min))
        }
    }

    /** Small budgets thin the buffer, so only the reported limit must match committing every raw sample. */
    @Test
    fun reportedLimitMatchesCommittingEveryRawSample() {
        val budgets =
            listOf(
                limits,
                PhotoEditLimits(maxPointsPerStroke = 1_024),
                PhotoEditLimits(maxPointsPerStroke = 128),
                PhotoEditLimits(maxPointsPerStroke = 512, maxTotalPoints = 300),
            )
        val random = Random(SEED)
        val reported = mutableSetOf<PhotoEditLimit?>()
        for (budget in budgets) {
            repeat(WALKS) {
                val raw = randomWalk(random)
                val buffer = ActiveStrokeBuffer(budget)
                raw.forEachIndexed { step, sample -> if (step == 0) buffer.start(sample) else buffer.add(sample) }

                val history = PhotoEditHistory(limits = budget)
                val expected = history.addStroke(stroke(raw)).reachedLimit
                assertEquals(expected, history.addBufferedStroke(buffer).reachedLimit)
                reported += expected
            }
        }
        assertEquals(setOf(null, PhotoEditLimit.StrokePoints, PhotoEditLimit.TotalPoints), reported)
    }

    /** Mostly sub-minimum jitter, with larger moves and excursions past the image edge. */
    private fun randomWalk(random: Random): List<NormalizedPoint> {
        var x = 0.5f
        var y = 0.5f
        return List(WALK_LENGTH) {
            val scale = if (random.nextInt(4) == 0) 0.01f else limits.minimumPointDistance * 1.5f
            x += (random.nextFloat() - 0.5f) * scale
            y += (random.nextFloat() - 0.5f) * scale
            if (random.nextInt(50) == 0) x = -0.1f
            NormalizedPoint(x, y)
        }
    }

    @Test
    fun copiedStrokeSurvivesTheNextGesture() {
        val buffer = ActiveStrokeBuffer(limits)
        buffer.start(point(0))
        buffer.add(point(1))
        val committed = buffer.toStroke()
        buffer.clear()
        buffer.start(point(5))

        assertEquals(listOf(point(0), point(1)), committed)
        assertFalse(buffer.isEmpty())
    }

    @Test
    fun editorCanvasAppendsThroughTheBufferWithoutCopyingEachMove() {
        val screen = appModuleSource("$EDITOR_SOURCES/PhotoEditorScreen.kt")

        assertTrue(screen.contains("ActiveStrokeBuffer("))
        assertTrue(screen.contains("activeStroke.add("))
        assertFalse(screen.contains("transientStroke = transientStroke"))
        assertFalse(screen.contains("strokePoints.toList()"))
    }

    /** A cancelled drag drops the long gesture: nothing is committed and the next stroke starts clean. */
    @Test
    fun cancelledLongGestureIsDiscarded() {
        val buffer = ActiveStrokeBuffer(limits)
        buffer.start(point(0))
        for (index in 1..limits.maxPointsPerStroke * 3) buffer.add(point(index))
        assertTrue(buffer.exceededPointBudget)

        buffer.clear()

        assertTrue(buffer.isEmpty())
        assertFalse(buffer.exceededPointBudget)
        val screen = appModuleSource("$EDITOR_SOURCES/PhotoEditorScreen.kt")
        val marker = "onDragCancel = {"
        val markerStart = screen.indexOf(marker)
        assertTrue("onDragCancel marker missing", markerStart >= 0)
        val onCancel = screen.kotlinBlockFrom(markerStart + marker.length - 1, "onDragCancel callback")
        assertTrue(onCancel.contains("activeStroke.clear()"))
        assertFalse(onCancel.contains("onStroke("))
    }

    /** A raster sweep whose neighbouring samples sit [ROW_LENGTH]ths of the width apart, above the minimum distance. */
    private fun point(index: Int): NormalizedPoint {
        val x = (index % ROW_LENGTH).toFloat() / ROW_LENGTH
        return NormalizedPoint(x, (index / ROW_LENGTH) * ROW_SPACING)
    }

    private fun PhotoEditHistory.addBufferedStroke(buffer: ActiveStrokeBuffer): PhotoEditMutation =
        addStroke(stroke(buffer.toStroke()), buffer.exceededPointBudget)

    private fun stroke(points: List<NormalizedPoint>) =
        PhotoEditStroke(
            id = "stroke",
            mode = PhotoStrokeMode.Draw,
            widthFraction = 0.01f,
            colorArgb = 0xFFFFFFFF.toInt(),
            points = points,
        )

    private companion object {
        const val EDITOR_SOURCES = "src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/editor"
        const val ROW_LENGTH = 1_000
        const val ROW_SPACING = 0.02f
        const val JITTER_MOVES = 100
        const val SEED = 3701
        const val WALKS = 20
        const val WALK_LENGTH = 1_500
    }
}

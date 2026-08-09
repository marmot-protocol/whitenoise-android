package dev.ipf.whitenoise.android.media.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoEditRecipeTest {
    @Test
    fun cropAndRotateAreUndoableInChronologicalOrder() {
        val crop = NormalizedRect(0.1f, 0.2f, 0.9f, 0.8f)
        val history = PhotoEditHistory().setCrop(crop).rotateClockwise()

        assertEquals(1, history.current.quarterTurnsClockwise)
        assertEquals(crop, history.current.crop)
        val afterUndoRotate = history.undo()
        assertEquals(0, afterUndoRotate.current.quarterTurnsClockwise)
        assertEquals(crop, afterUndoRotate.current.crop)
        assertEquals(PhotoEditRecipe.Original, afterUndoRotate.undo().current)
    }

    @Test
    fun redoRestoresEditAndNewEditClearsRedoBranch() {
        val rotated = PhotoEditHistory().rotateClockwise()
        val undone = rotated.undo()

        assertTrue(undone.canRedo)
        assertEquals(rotated.current, undone.redo().current)
        assertFalse(undone.setCrop(NormalizedRect(0f, 0f, 0.5f, 1f)).canRedo)
    }

    @Test
    fun resetIsNonDestructiveAndUndoable() {
        val edited = PhotoEditHistory().rotateClockwise()
        val reset = edited.reset()

        assertEquals(PhotoEditRecipe.Original, reset.current)
        assertEquals(edited.current, reset.undo().current)
    }

    @Test
    fun undoBoundRetainsOriginalBaseline() {
        var history =
            PhotoEditHistory(
                limits = PhotoEditLimits(maxUndoStates = 3),
            )
        repeat(8) {
            history = history.rotateClockwise()
            history = history.setCrop(NormalizedRect(0f, 0f, 1f, 0.9f - it * 0.01f))
        }

        assertEquals(3, history.undoStates.size)
        assertEquals(PhotoEditRecipe.Original, history.undoStates.first())
    }

    @Test
    fun drawAndEraseStrokesShareTheBoundedHistory() {
        val draw = stroke("draw", PhotoStrokeMode.Draw, points(3))
        val erase = stroke("erase", PhotoStrokeMode.Erase, points(2))
        val afterDraw = PhotoEditHistory().addStroke(draw).history
        val afterErase = afterDraw.addStroke(erase).history

        assertEquals(listOf(draw, erase), afterErase.current.strokes)
        assertEquals(listOf(draw), afterErase.undo().current.strokes)
        assertTrue(
            afterErase
                .undo()
                .undo()
                .current.strokes
                .isEmpty(),
        )
    }

    @Test
    fun strokePointsAreClampedCoalescedAndCapped() {
        val limits =
            PhotoEditLimits(
                maxPointsPerStroke = 3,
                minimumPointDistance = 0.1f,
            )
        val input =
            listOf(
                NormalizedPoint(-1f, -1f),
                NormalizedPoint(0.01f, 0.01f),
                NormalizedPoint(0.5f, 0.5f),
                NormalizedPoint(0.75f, 0.75f),
                NormalizedPoint(2f, 2f),
            )
        val mutation = PhotoEditHistory(limits = limits).addStroke(stroke("s", PhotoStrokeMode.Draw, input))
        val result =
            mutation.history.current.strokes
                .single()
                .points

        assertEquals(PhotoEditLimit.StrokePoints, mutation.reachedLimit)
        assertEquals(3, result.size)
        assertEquals(NormalizedPoint(0f, 0f), result.first())
        assertEquals(NormalizedPoint(1f, 1f), result.last())
    }

    @Test
    fun totalPointAndStrokeLimitsDoNotGrowState() {
        val limits =
            PhotoEditLimits(
                maxStrokes = 1,
                maxPointsPerStroke = 3,
                maxTotalPoints = 2,
            )
        val first = PhotoEditHistory(limits = limits).addStroke(stroke("one", PhotoStrokeMode.Draw, points(3)))
        val second = first.history.addStroke(stroke("two", PhotoStrokeMode.Draw, points(2)))

        assertEquals(PhotoEditLimit.TotalPoints, first.reachedLimit)
        assertEquals(2, first.history.current.totalPointCount)
        assertEquals(PhotoEditLimit.StrokeCount, second.reachedLimit)
        assertEquals(first.history, second.history)
    }

    @Test
    fun clampedCropHonorsMinimumSizeAtEdges() {
        val crop =
            NormalizedRect.clamped(
                first = NormalizedPoint(0.99f, 0.99f),
                second = NormalizedPoint(1f, 1f),
                minimumSize = 0.1f,
            )

        assertEquals(0.9f, crop.left)
        assertEquals(0.9f, crop.top)
        assertEquals(1f, crop.right)
        assertEquals(1f, crop.bottom)
    }

    @Test
    fun unchangedRecipeDoesNotCreateHistoryEntry() {
        val history = PhotoEditHistory()

        assertEquals(history, history.setCrop(NormalizedRect.Full))
        assertNotEquals(history, history.rotateClockwise())
    }

    private fun stroke(
        id: String,
        mode: PhotoStrokeMode,
        points: List<NormalizedPoint>,
    ) = PhotoEditStroke(
        id = id,
        mode = mode,
        widthFraction = 0.01f,
        colorArgb = 0xff00ff00.toInt(),
        points = points,
    )

    private fun points(count: Int): List<NormalizedPoint> =
        List(count) { index ->
            val position = if (count == 1) 0f else index.toFloat() / (count - 1)
            NormalizedPoint(position, position)
        }
}

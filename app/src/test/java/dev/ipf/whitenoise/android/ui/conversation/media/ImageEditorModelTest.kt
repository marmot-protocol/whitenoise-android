package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImageEditorModelTest {
    @Test
    fun rotateRightComposesCropAndDrawingCoordinates() {
        val state =
            ImageEditState(
                crop = NormalizedRect(0.1f, 0.2f, 0.7f, 0.8f),
                strokes =
                    listOf(
                        EditorStroke(
                            points = listOf(NormalizedPoint(0.25f, 0.75f)),
                            colorArgb = 0xff112233.toInt(),
                            widthFraction = 0.02f,
                            eraser = false,
                        ),
                    ),
            )

        val rotated = state.rotateRight()

        assertEquals(1, rotated.quarterTurns)
        assertEquals(NormalizedRect(0.2f, 0.1f, 0.8f, 0.7f), rotated.crop)
        assertEquals(
            NormalizedPoint(0.25f, 0.25f),
            rotated.strokes
                .single()
                .points
                .single(),
        )
        assertEquals(300 to 240, rotated.outputDimensions(sourceWidth = 400, sourceHeight = 500))
    }

    @Test
    fun changingCropKeepsDrawingAnchoredToTheSameSourcePoint() {
        val initial =
            ImageEditState(
                crop = NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f),
                strokes =
                    listOf(
                        EditorStroke(
                            points = listOf(NormalizedPoint(0.5f, 0.5f)),
                            colorArgb = 0xff000000.toInt(),
                            widthFraction = 0.01f,
                            eraser = false,
                        ),
                    ),
            )

        val cropped = initial.withCrop(NormalizedRect(0.4f, 0.4f, 0.8f, 0.8f))

        assertEquals(
            NormalizedPoint(0.25f, 0.25f),
            cropped.strokes
                .single()
                .points
                .single(),
        )
    }

    @Test
    fun strokeInputIsSimplifiedAndBounded() {
        val noisy = (0..5_000).map { index -> NormalizedPoint(index / 5_000f, 0.5f) }

        val stroke =
            EditorStroke.bounded(
                points = noisy,
                colorArgb = 0xffffffff.toInt(),
                widthFraction = 0.02f,
                eraser = false,
            )

        assertTrue(stroke.points.size <= IMAGE_EDITOR_MAX_POINTS_PER_STROKE)
        assertEquals(NormalizedPoint(0f, 0.5f), stroke.points.first())
        assertEquals(NormalizedPoint(1f, 0.5f), stroke.points.last())
    }

    @Test
    fun historyIsBoundedAndClearsRedoOnNewEdit() {
        var history = ImageEditHistory()
        repeat(IMAGE_EDITOR_MAX_HISTORY + 4) {
            history = history.commit(history.current.rotateRight())
        }
        assertEquals(IMAGE_EDITOR_MAX_HISTORY, history.undoStates.size)

        val undone = history.undo()
        assertTrue(undone.canRedo)
        val replaced = undone.commit(undone.current.withCrop(NormalizedRect(0f, 0f, 0.5f, 0.5f)))
        assertFalse(replaced.canRedo)
    }

    @Test
    fun longDrawingGestureStaysBoundedAndKeepsItsLatestPoint() {
        var points = emptyList<NormalizedPoint>()
        val inputCount = IMAGE_EDITOR_MAX_POINTS_PER_STROKE * 3

        repeat(inputCount) { index ->
            points =
                appendGesturePoint(
                    current = points,
                    point =
                        NormalizedPoint(
                            x = index.toFloat() / (inputCount - 1),
                            y = if (index % 2 == 0) 0.25f else 0.75f,
                        ),
                )
        }

        assertTrue(points.size <= IMAGE_EDITOR_MAX_POINTS_PER_STROKE)
        assertEquals(0f, points.first().x)
        assertEquals(1f, points.last().x)
    }

    @Test
    fun aspectPresetAccountsForSourceOrientation() {
        val landscapeSquare = cropRectForAspect(400, 200, 0, ImageCropAspect.Square)
        val rotatedSquare = cropRectForAspect(400, 200, 1, ImageCropAspect.Square)

        assertEquals(NormalizedRect(0.25f, 0f, 0.75f, 1f), landscapeSquare)
        assertEquals(NormalizedRect(0f, 0.25f, 1f, 0.75f), rotatedSquare)
    }

    @Test
    fun staleEditorResultCannotReplaceANewerAttachmentRevision() {
        val old = Uri.parse("content://test/old")
        val newer = Uri.parse("content://test/newer")
        val edited = Uri.parse("content://test/edited")

        assertNull(replaceMediaUriIfCurrent(listOf(newer), index = 0, expected = old, replacement = edited))
        assertEquals(
            listOf(edited),
            replaceMediaUriIfCurrent(listOf(old), index = 0, expected = old, replacement = edited),
        )
    }

    @Test
    fun staleEditorResultCannotReplaceAfterAttachmentAba() {
        val original = Uri.parse("content://test/original")
        val edited = Uri.parse("content://test/edited")

        assertNull(
            replaceMediaUriIfCurrent(
                current = listOf(original),
                index = 0,
                expected = original,
                replacement = edited,
                expectedRevision = "revision-before-aba",
                currentRevision = "revision-after-aba",
            ),
        )
    }

    @Test
    fun editedArtifactIsDeletedOnlyAfterItsLastAttachmentReferenceIsRemoved() {
        val edited = Uri.parse("content://test/edited")
        val other = Uri.parse("content://test/other")

        assertTrue(isOnlyMediaUriReferenceAt(listOf(edited, other), index = 0, expected = edited))
        assertFalse(isOnlyMediaUriReferenceAt(listOf(edited, edited), index = 0, expected = edited))
        assertFalse(isOnlyMediaUriReferenceAt(listOf(other), index = 0, expected = edited))
    }
}

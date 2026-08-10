package dev.ipf.whitenoise.android.media.editor

import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoCropPreset
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorStateHolder
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorTool
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoStrokeWidth
import dev.ipf.whitenoise.android.ui.conversation.media.editor.cropForPreset
import dev.ipf.whitenoise.android.ui.conversation.media.editor.minimumCropFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PhotoEditorStateTest {
    @Test
    fun cropPresetsKeepTheirOutputAspectAcrossQuarterTurns() {
        val size = EditorPixelSize(400, 300)

        for (turns in 0..3) {
            for (preset in PhotoCropPreset.entries.filter { it.outputAspectRatio != null }) {
                val crop = requireNotNull(cropForPreset(size, turns, preset))
                val recipe = PhotoEditRecipe(crop = crop, quarterTurnsClockwise = turns)
                val geometry =
                    requireNotNull(
                        PhotoEditGeometry.create(
                            encodedSize = size,
                            exifOrientation = 1,
                            recipe = recipe,
                            maxEdgePx = 4096,
                            maxPixels = 12_000_000,
                        ),
                    )
                val actual = geometry.outputSize.width.toFloat() / geometry.outputSize.height
                assertTrue("$preset after $turns turns was $actual", abs(actual - preset.outputAspectRatio!!) < 0.01f)
            }
        }
    }

    @Test
    fun mixedOperationsResetUndoAndRedoChronologically() {
        val holder = holder()
        holder.rotateClockwise()
        holder.selectTool(PhotoEditorTool.Draw)
        holder.selectStrokeWidth(PhotoStrokeWidth.Large)
        holder.commitStroke(listOf(NormalizedPoint(0.2f, 0.3f), NormalizedPoint(0.8f, 0.7f)))
        val beforeReset = holder.state.recipe

        holder.reset()
        assertEquals(PhotoEditRecipe.Original, holder.state.recipe)
        assertTrue(holder.state.canUndo)

        holder.undo()
        assertEquals(beforeReset, holder.state.recipe)
        assertTrue(holder.state.canRedo)

        holder.redo()
        assertEquals(PhotoEditRecipe.Original, holder.state.recipe)
    }

    @Test
    fun newOperationAfterUndoDropsRedoBranch() {
        val holder = holder()
        holder.rotateClockwise()
        holder.rotateClockwise()
        holder.undo()
        assertTrue(holder.state.canRedo)

        holder.selectTool(PhotoEditorTool.Erase)
        holder.commitStroke(listOf(NormalizedPoint(0.5f, 0.5f)))

        assertFalse(holder.state.canRedo)
        assertEquals(
            PhotoStrokeMode.Erase,
            holder.state.recipe.strokes
                .single()
                .mode,
        )
    }

    @Test
    fun savingFreezesMutations() {
        val holder = holder()
        holder.beginSaving()
        holder.rotateClockwise()
        holder.commitStroke(listOf(NormalizedPoint(0.5f, 0.5f)))

        assertEquals(MediaQuality.Standard, holder.state.quality)
        assertEquals(PhotoEditRecipe.Original, holder.state.recipe)
        assertFalse(holder.state.canUndo)
    }

    @Test
    fun minimumCropIsAtLeastThirtyTwoPixelsOrOnePercent() {
        assertEquals(0.32f, minimumCropFraction(EditorPixelSize(100, 200)), 0.0001f)
        assertEquals(0.01f, minimumCropFraction(EditorPixelSize(4000, 4000)), 0.0001f)
        assertEquals(1f, minimumCropFraction(EditorPixelSize(1, 1)), 0.0001f)
    }

    @Test
    fun toolSwitchesPreserveTheCropAndOneContinuousHistory() {
        val holder = holder()
        holder.selectCropPreset(PhotoCropPreset.Square)
        holder.rotateClockwise()
        val acceptedCrop = holder.state.recipe
        assertTrue(holder.state.canUndo)

        assertEquals(acceptedCrop, holder.state.recipe)
        holder.selectTool(PhotoEditorTool.Draw)
        assertEquals(acceptedCrop, holder.state.recipe)
        holder.commitStroke(listOf(NormalizedPoint(0.1f, 0.1f), NormalizedPoint(0.9f, 0.9f)))
        holder.commitStroke(listOf(NormalizedPoint(0.2f, 0.2f), NormalizedPoint(0.8f, 0.8f)))
        val acceptedDraw = holder.state.recipe

        holder.undo()
        assertEquals(1, holder.state.recipe.strokes.size)
        holder.undo()
        assertEquals(acceptedCrop, holder.state.recipe)
        holder.redo()
        assertEquals(1, holder.state.recipe.strokes.size)
        holder.redo()
        assertEquals(acceptedDraw, holder.state.recipe)
    }

    @Test
    fun switchingToolsNeverDiscardsAcceptedEdits() {
        val holder = holder()
        holder.rotateClockwise()
        val accepted = holder.state.recipe

        holder.selectTool(PhotoEditorTool.Draw)
        holder.commitStroke(listOf(NormalizedPoint(0.5f, 0.5f)))
        holder.undo()

        assertEquals(accepted, holder.state.recipe)
        assertTrue(holder.state.canUndo)
        assertTrue(holder.state.canRedo)
    }

    @Test
    fun originalQualityIsPreservedUntilAnEditAndRestoredByUndo() {
        val holder =
            PhotoEditorStateHolder(
                initialRecipe = PhotoEditRecipe.Original,
                initialQuality = MediaQuality.Original,
                orientedSize = EditorPixelSize(400, 300),
            )

        holder.rotateClockwise()

        assertEquals(MediaQuality.High, holder.state.quality)
        assertTrue(holder.hasUnsavedChanges)
        holder.undo()
        assertEquals(MediaQuality.Original, holder.state.quality)
        assertFalse(holder.hasUnsavedChanges)
        holder.redo()
        assertEquals(MediaQuality.High, holder.state.quality)
    }

    private fun holder() =
        PhotoEditorStateHolder(
            initialRecipe = PhotoEditRecipe.Original,
            initialQuality = MediaQuality.Standard,
            orientedSize = EditorPixelSize(400, 300),
            newStrokeId = { "stroke" },
        )
}

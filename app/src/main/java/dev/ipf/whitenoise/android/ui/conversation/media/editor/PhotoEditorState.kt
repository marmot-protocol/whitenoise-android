@file:Suppress("MagicNumber", "ReturnCount") // Ratios, normalized bounds, and quarter turns are editor-domain values.

package dev.ipf.whitenoise.android.ui.conversation.media.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.media.editor.NormalizedPoint
import dev.ipf.whitenoise.android.media.editor.NormalizedRect
import dev.ipf.whitenoise.android.media.editor.PhotoEditHistory
import dev.ipf.whitenoise.android.media.editor.PhotoEditLimit
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoEditStroke
import dev.ipf.whitenoise.android.media.editor.PhotoStrokeMode
import dev.ipf.whitenoise.android.state.MediaQuality
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

internal enum class PhotoEditorTool {
    Crop,
    Draw,
    Erase,
}

internal enum class PhotoEditorOperation {
    Crop,
    Draw,
}

internal enum class PhotoEditorQualityTier {
    Standard,
    Hd,
}

internal enum class PhotoCropPreset(
    val outputAspectRatio: Float?,
) {
    Free(null),
    Original(null),
    Square(1f),
    FourThree(4f / 3f),
    ThreeFour(3f / 4f),
    SixteenNine(16f / 9f),
    NineSixteen(9f / 16f),
}

internal enum class PhotoStrokeWidth(
    val fraction: Float,
) {
    Small(0.004f),
    Medium(0.008f),
    Large(0.016f),
    ExtraLarge(0.032f),
}

internal enum class PhotoEditorAnnouncement {
    ToolSelected,
    CropChanged,
    Rotated,
    DrawingAdded,
    EraserAdded,
    Undo,
    Redo,
    Reset,
    QualityChanged,
}

internal data class PhotoEditorUiState(
    val history: PhotoEditHistory,
    val quality: MediaQuality,
    val activeOperation: PhotoEditorOperation? = null,
    val activeTool: PhotoEditorTool = PhotoEditorTool.Draw,
    val cropPreset: PhotoCropPreset = PhotoCropPreset.Free,
    val drawColorArgb: Int = 0xFFFF3B30.toInt(),
    val strokeWidth: PhotoStrokeWidth = PhotoStrokeWidth.Medium,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val lastLimit: PhotoEditLimit? = null,
    val announcement: PhotoEditorAnnouncement? = null,
) {
    val recipe: PhotoEditRecipe
        get() = history.current

    val canUndo: Boolean
        get() = history.canUndo && !isSaving

    val canRedo: Boolean
        get() = history.canRedo && !isSaving

    val qualityTier: PhotoEditorQualityTier
        get() =
            when (quality) {
                MediaQuality.Low,
                MediaQuality.Standard,
                -> PhotoEditorQualityTier.Standard
                MediaQuality.High,
                MediaQuality.Original,
                -> PhotoEditorQualityTier.Hd
            }
}

@Suppress("TooManyFunctions") // Intent-style commands form one cohesive state-holder API.
internal class PhotoEditorStateHolder(
    initialRecipe: PhotoEditRecipe,
    initialQuality: MediaQuality,
    private val orientedSize: EditorPixelSize,
    private val newStrokeId: () -> String = { UUID.randomUUID().toString() },
) {
    val initialRecipe: PhotoEditRecipe = initialRecipe
    val initialQuality: MediaQuality = initialQuality.toEditorQuality()
    private var operationEntryHistory: PhotoEditHistory? = null

    var state by
        mutableStateOf(
            PhotoEditorUiState(
                history = PhotoEditHistory(original = initialRecipe, current = initialRecipe),
                quality = this.initialQuality,
            ),
        )
        private set

    val hasUnsavedChanges: Boolean
        get() = state.recipe != initialRecipe || state.quality != initialQuality

    val hasUncommittedOperationChanges: Boolean
        get() = operationEntryHistory?.current?.let { it != state.recipe } == true

    fun beginCropOperation() {
        beginOperation(PhotoEditorOperation.Crop, PhotoEditorTool.Crop)
    }

    fun beginDrawOperation() {
        beginOperation(PhotoEditorOperation.Draw, PhotoEditorTool.Draw)
    }

    fun commitOperation() {
        if (state.isSaving || state.activeOperation == null) return
        val entryHistory = operationEntryHistory ?: return
        val committedHistory = entryHistory.applyRecipe(state.recipe)
        operationEntryHistory = null
        state =
            state.copy(
                history = committedHistory,
                activeOperation = null,
                activeTool = PhotoEditorTool.Draw,
                cropPreset = PhotoCropPreset.Free,
                errorMessage = null,
            )
    }

    fun discardOperation() {
        if (state.isSaving || state.activeOperation == null) return
        val entry = operationEntryHistory
        operationEntryHistory = null
        state =
            state.copy(
                history = entry ?: state.history,
                activeOperation = null,
                activeTool = PhotoEditorTool.Draw,
                cropPreset = PhotoCropPreset.Free,
                errorMessage = null,
                lastLimit = null,
            )
    }

    private fun beginOperation(
        operation: PhotoEditorOperation,
        tool: PhotoEditorTool,
    ) {
        if (state.isSaving || state.activeOperation != null) return
        operationEntryHistory = state.history
        state =
            state.copy(
                history =
                    PhotoEditHistory(
                        original = state.recipe,
                        current = state.recipe,
                        limits = state.history.limits,
                    ),
                activeOperation = operation,
                activeTool = tool,
                errorMessage = null,
                lastLimit = null,
                announcement = PhotoEditorAnnouncement.ToolSelected,
            )
    }

    fun selectTool(tool: PhotoEditorTool) {
        if (!state.isSaving && state.activeOperation == PhotoEditorOperation.Draw) {
            state =
                state.copy(
                    activeTool = tool,
                    errorMessage = null,
                    announcement = PhotoEditorAnnouncement.ToolSelected,
                )
        }
    }

    fun selectQuality(quality: MediaQuality) {
        if (!state.isSaving && state.activeOperation == null) {
            state =
                state.copy(
                    quality = quality,
                    errorMessage = null,
                    announcement = PhotoEditorAnnouncement.QualityChanged,
                )
        }
    }

    fun selectQualityTier(tier: PhotoEditorQualityTier) {
        selectQuality(
            when (tier) {
                PhotoEditorQualityTier.Standard -> MediaQuality.Standard
                PhotoEditorQualityTier.Hd -> MediaQuality.High
            },
        )
    }

    fun selectColor(colorArgb: Int) {
        if (!state.isSaving && state.activeOperation == PhotoEditorOperation.Draw) {
            state = state.copy(drawColorArgb = colorArgb, errorMessage = null)
        }
    }

    fun selectStrokeWidth(width: PhotoStrokeWidth) {
        if (!state.isSaving && state.activeOperation == PhotoEditorOperation.Draw) {
            state = state.copy(strokeWidth = width, errorMessage = null)
        }
    }

    fun selectCropPreset(preset: PhotoCropPreset) {
        if (state.isSaving || state.activeOperation != PhotoEditorOperation.Crop) return
        val crop = cropForPreset(orientedSize, state.recipe.quarterTurnsClockwise, preset, state.recipe.crop)
        state =
            state.copy(
                history = if (crop == null) state.history else state.history.setCrop(crop),
                activeTool = PhotoEditorTool.Crop,
                cropPreset = preset,
                errorMessage = null,
                announcement = PhotoEditorAnnouncement.CropChanged,
            )
    }

    fun commitFreeCrop(crop: NormalizedRect) {
        if (!state.isSaving && state.activeOperation == PhotoEditorOperation.Crop) {
            state =
                state.copy(
                    history = state.history.setCrop(crop),
                    activeTool = PhotoEditorTool.Crop,
                    cropPreset = PhotoCropPreset.Free,
                    errorMessage = null,
                    announcement = PhotoEditorAnnouncement.CropChanged,
                )
        }
    }

    fun rotateClockwise() {
        if (!state.isSaving && state.activeOperation == PhotoEditorOperation.Crop) {
            state =
                state.copy(
                    history = state.history.rotateClockwise(),
                    activeTool = PhotoEditorTool.Crop,
                    cropPreset = PhotoCropPreset.Free,
                    errorMessage = null,
                    announcement = PhotoEditorAnnouncement.Rotated,
                )
        }
    }

    fun commitStroke(points: List<NormalizedPoint>) {
        if (state.isSaving || state.activeOperation != PhotoEditorOperation.Draw || points.isEmpty()) return
        val mutation =
            state.history.addStroke(
                PhotoEditStroke(
                    id = newStrokeId(),
                    mode =
                        if (state.activeTool == PhotoEditorTool.Erase) {
                            PhotoStrokeMode.Erase
                        } else {
                            PhotoStrokeMode.Draw
                        },
                    widthFraction = state.strokeWidth.fraction,
                    colorArgb = state.drawColorArgb,
                    points = points,
                ),
            )
        state =
            state.copy(
                history = mutation.history,
                lastLimit = mutation.reachedLimit,
                errorMessage = null,
                announcement =
                    if (state.activeTool == PhotoEditorTool.Erase) {
                        PhotoEditorAnnouncement.EraserAdded
                    } else {
                        PhotoEditorAnnouncement.DrawingAdded
                    },
            )
    }

    fun undo() {
        if (state.canUndo) {
            state =
                state.copy(
                    history = state.history.undo(),
                    cropPreset = PhotoCropPreset.Free,
                    announcement = PhotoEditorAnnouncement.Undo,
                )
        }
    }

    fun redo() {
        if (state.canRedo) {
            state =
                state.copy(
                    history = state.history.redo(),
                    cropPreset = PhotoCropPreset.Free,
                    announcement = PhotoEditorAnnouncement.Redo,
                )
        }
    }

    fun reset() {
        if (!state.isSaving && state.activeOperation == null) {
            state =
                state.copy(
                    history = state.history.reset(),
                    cropPreset = PhotoCropPreset.Original,
                    errorMessage = null,
                    lastLimit = null,
                    announcement = PhotoEditorAnnouncement.Reset,
                )
        }
    }

    fun beginSaving() {
        if (state.activeOperation == null) {
            state = state.copy(isSaving = true, errorMessage = null, announcement = null)
        }
    }

    fun finishSaving(errorMessage: String? = null) {
        state = state.copy(isSaving = false, errorMessage = errorMessage)
    }
}

private fun MediaQuality.toEditorQuality(): MediaQuality =
    when (this) {
        MediaQuality.Low,
        MediaQuality.Standard,
        -> MediaQuality.Standard
        MediaQuality.High,
        MediaQuality.Original,
        -> MediaQuality.High
    }

internal fun cropForPreset(
    orientedSize: EditorPixelSize,
    quarterTurnsClockwise: Int,
    preset: PhotoCropPreset,
    currentCrop: NormalizedRect = NormalizedRect.Full,
): NormalizedRect? {
    if (preset == PhotoCropPreset.Free) return null
    if (preset == PhotoCropPreset.Original) return NormalizedRect.Full
    val outputRatio = requireNotNull(preset.outputAspectRatio)
    val cropPixelRatio = if (quarterTurnsClockwise % 2 == 0) outputRatio else 1f / outputRatio
    val sourceRatio = orientedSize.width.toFloat() / orientedSize.height
    val centerX = (currentCrop.left + currentCrop.right) / 2f
    val centerY = (currentCrop.top + currentCrop.bottom) / 2f
    val widthFraction: Float
    val heightFraction: Float
    if (sourceRatio >= cropPixelRatio) {
        widthFraction = cropPixelRatio / sourceRatio
        heightFraction = 1f
    } else {
        widthFraction = 1f
        heightFraction = sourceRatio / cropPixelRatio
    }
    val left = (centerX - widthFraction / 2f).coerceIn(0f, 1f - widthFraction)
    val top = (centerY - heightFraction / 2f).coerceIn(0f, 1f - heightFraction)
    return NormalizedRect(
        left = left,
        top = top,
        right = min(1f, left + widthFraction),
        bottom = min(1f, top + heightFraction),
    )
}

internal fun minimumCropFraction(orientedSize: EditorPixelSize): Float =
    max(
        0.01f,
        32f / min(orientedSize.width, orientedSize.height).coerceAtLeast(1),
    ).coerceAtMost(1f)

@file:Suppress("FunctionNaming", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package dev.ipf.whitenoise.android.ui.conversation.media.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.editor.EditorPoint
import dev.ipf.whitenoise.android.media.editor.EditorViewTransform
import dev.ipf.whitenoise.android.media.editor.NormalizedPoint
import dev.ipf.whitenoise.android.media.editor.NormalizedRect
import dev.ipf.whitenoise.android.media.editor.PhotoEditGeometry
import dev.ipf.whitenoise.android.media.editor.PhotoEditLimit
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoEditorSourceInfo
import dev.ipf.whitenoise.android.media.editor.PhotoStrokeMode
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog
import kotlin.math.hypot
import kotlin.math.min

@Composable
@Suppress("LongParameterList")
internal fun PhotoEditorDialog(
    previewBitmap: Bitmap,
    sourceInfo: PhotoEditorSourceInfo,
    stateHolder: PhotoEditorStateHolder,
    onCancel: () -> Unit,
    onSave: (PhotoEditRecipe, MediaQuality) -> Unit,
    frameIndex: Int = 0,
    frameCount: Int = 1,
) {
    Dialog(
        // Back is handled by [PhotoEditorScreen] so a dirty recipe always goes
        // through the discard confirmation instead of bypassing it here.
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        PhotoEditorScreen(
            previewBitmap = previewBitmap,
            sourceInfo = sourceInfo,
            stateHolder = stateHolder,
            onCancel = onCancel,
            onSave = onSave,
            frameIndex = frameIndex,
            frameCount = frameCount,
        )
    }
}

/** Photo editor: canvas, tools and the save flow for a staged photo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "LongParameterList") // Screen-level orchestration keeps save/back semantics together.
internal fun PhotoEditorScreen(
    previewBitmap: Bitmap,
    sourceInfo: PhotoEditorSourceInfo,
    stateHolder: PhotoEditorStateHolder,
    onCancel: () -> Unit,
    onSave: (PhotoEditRecipe, MediaQuality) -> Unit,
    modifier: Modifier = Modifier,
    frameIndex: Int = 0,
    frameCount: Int = 1,
) {
    val state = stateHolder.state
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showCoordinates by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }

    fun requestCancel() {
        if (state.isSaving) return
        if (stateHolder.hasUnsavedChanges) showDiscardDialog = true else onCancel()
    }

    BackHandler(enabled = true, onBack = ::requestCancel)
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("photo.editor"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            PhotoEditorTopBar(
                saving = state.isSaving,
                frameIndex = frameIndex,
                frameCount = frameCount,
                onCancel = ::requestCancel,
                onSave = {
                    stateHolder.beginSaving()
                    onSave(state.recipe, state.quality)
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PhotoEditorHistoryActions(state, stateHolder, stateHolder.hasUnsavedChanges)
            if (state.isSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 600.dp || maxHeight < 360.dp
                val sidePanelWidth = minOf(280.dp, maxWidth / 2)
                val photo: @Composable (Modifier) -> Unit = { canvasModifier ->
                    PhotoEditorCanvas(
                        previewBitmap = previewBitmap,
                        sourceInfo = sourceInfo,
                        state = state,
                        onFreeCrop = stateHolder::commitFreeCrop,
                        onStroke = stateHolder::commitStroke,
                        modifier = canvasModifier.padding(8.dp),
                    )
                }
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        photo(Modifier.weight(1f).fillMaxHeight())
                        val controlsWidth = sidePanelWidth
                        PhotoEditorControls(
                            state = state,
                            stateHolder = stateHolder,
                            onCoordinates = { showCoordinates = true },
                            onQuality = { showQuality = true },
                            modifier = Modifier.width(controlsWidth).fillMaxHeight(),
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        photo(Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp))
                        PhotoEditorControls(
                            state = state,
                            stateHolder = stateHolder,
                            onCoordinates = { showCoordinates = true },
                            onQuality = { showQuality = true },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        )
                    }
                }
            }
            EditorStatus(state)
        }
    }
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.photo_editor_discard_title)) },
            text = { Text(stringResource(R.string.photo_editor_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onCancel()
                    },
                ) { Text(stringResource(R.string.photo_editor_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.photo_editor_keep_editing))
                }
            },
        )
    }
    if (showCoordinates) {
        PhotoEditorCoordinatesDialog(
            tool = state.activeTool,
            crop = state.recipe.crop,
            minimumFraction = minimumCropFraction(sourceInfo.orientedSize),
            onDismiss = { showCoordinates = false },
            onCrop = { crop ->
                showCoordinates = false
                stateHolder.commitFreeCrop(crop)
            },
            onStroke = { points ->
                showCoordinates = false
                stateHolder.commitStroke(points)
            },
        )
    }
    if (showQuality) {
        // ChoiceDialog labels are plain strings, so resolve each level's name up front.
        val qualityLabels = MediaQuality.entries.associateWith { level -> photoQualityLevelLabel(level) }
        ChoiceDialog(
            title = stringResource(R.string.photo_editor_quality),
            values = MediaQuality.entries,
            selected = state.quality,
            label = { level -> qualityLabels.getValue(level) },
            onDismiss = { showQuality = false },
            onSelect = { level ->
                showQuality = false
                stateHolder.selectQuality(level)
            },
            supportingText = stringResource(R.string.photo_editor_quality_explanation),
        )
    }
}

/** Keeps cancel/save on the prototype app bar while the native holder owns save acceptance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoEditorTopBar(
    saving: Boolean,
    frameIndex: Int,
    frameCount: Int,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.photo_editor_title))
                if (frameCount > 1) {
                    Text(
                        stringResource(R.string.photo_editor_frame, frameIndex + 1),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onCancel, enabled = !saving) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
            }
        },
        actions = {
            EditorTextAction(
                stringResource(if (saving) R.string.photo_editor_saving_action else R.string.save),
                !saving,
                onSave,
            )
        },
    )
}

/** Exposes every existing history command as a labeled, horizontally scrollable action. */
@Composable
private fun PhotoEditorHistoryActions(
    state: PhotoEditorUiState,
    holder: PhotoEditorStateHolder,
    dirty: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EditorTextAction(stringResource(R.string.photo_editor_undo), state.canUndo, holder::undo)
        EditorTextAction(stringResource(R.string.photo_editor_redo), state.canRedo, holder::redo)
        EditorTextAction(stringResource(R.string.photo_editor_reset), !state.isSaving && dirty, holder::reset)
        EditorTextAction(
            stringResource(R.string.photo_editor_rotate_clockwise),
            !state.isSaving,
            holder::rotateClockwise,
        )
    }
}

/** Retains the named native action and its accessible minimum touch target. */
@Composable
private fun EditorTextAction(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
    ) { Text(description) }
}

/** Arranges the native tool/preset/color/width commands in the prototype labeled chip rows. */
@Composable
@Suppress("LongParameterList")
private fun PhotoEditorControls(
    state: PhotoEditorUiState,
    stateHolder: PhotoEditorStateHolder,
    onCoordinates: () -> Unit,
    onQuality: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhotoEditorTool.entries.forEach { tool ->
                EditorChoiceChip(toolLabel(tool), state.activeTool == tool, !state.isSaving) {
                    stateHolder.selectTool(tool)
                }
            }
        }
        if (state.activeTool == PhotoEditorTool.Crop) {
            CropControls(state, stateHolder)
        } else {
            DrawControls(state, stateHolder, showColors = state.activeTool == PhotoEditorTool.Draw)
        }
        Text(
            stringResource(
                if (state.activeTool == PhotoEditorTool.Crop) {
                    R.string.photo_editor_crop_hint
                } else {
                    R.string.photo_editor_draw_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        EditorTextAction(coordinateDialogTitle(state.activeTool), !state.isSaving, onCoordinates)
        PhotoEditorQualityAction(state.quality, !state.isSaving, onQuality)
        if (state.quality == MediaQuality.Original && state.recipe != stateHolder.initialRecipe) {
            Text(
                stringResource(R.string.photo_editor_original_edited),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The output-quality control the prototype keeps inside the editor rather than in the preview chrome. */
@Composable
private fun PhotoEditorQualityAction(
    quality: MediaQuality,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(R.string.photo_editor_quality) + ": " + photoQualityLevelLabel(quality)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = label }
                .testTag("photo.editor.quality"),
    ) { Text(label) }
}

/** Name of one selectable output level, as offered in the editor's quality dialog. */
@Composable
internal fun photoQualityLevelLabel(quality: MediaQuality): String =
    stringResource(
        when (quality) {
            MediaQuality.Low -> R.string.photo_editor_quality_low
            MediaQuality.Standard -> R.string.photo_editor_quality_standard
            MediaQuality.High -> R.string.photo_editor_quality_high
            MediaQuality.Original -> R.string.photo_editor_quality_original
        },
    )

/** Keeps all native crop presets available without requiring a canvas gesture. */
@Composable
private fun CropControls(
    state: PhotoEditorUiState,
    stateHolder: PhotoEditorStateHolder,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhotoCropPreset.entries.forEach { preset ->
            EditorChoiceChip(cropPresetLabel(preset), state.cropPreset == preset, !state.isSaving) {
                stateHolder.selectCropPreset(preset)
            }
        }
    }
}

/** Keeps drawing colors and stroke widths on distinct rows with native selection state. */
@Composable
private fun DrawControls(
    state: PhotoEditorUiState,
    stateHolder: PhotoEditorStateHolder,
    showColors: Boolean,
) {
    if (showColors) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            editorColors().forEach { color ->
                val selected = state.drawColorArgb == color.argb
                FilterChip(
                    selected = selected,
                    onClick = { stateHolder.selectColor(color.argb) },
                    label = { Text(color.name) },
                    enabled = !state.isSaving,
                    leadingIcon = {
                        Canvas(Modifier.size(20.dp)) {
                            drawCircle(Color(color.argb))
                            drawCircle(Color.Gray, style = Stroke(1.dp.toPx()))
                        }
                    },
                    modifier =
                        Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = if (selected) color.selectedDescription else color.name
                        },
                )
            }
        }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhotoStrokeWidth.entries.forEach { width ->
            EditorChoiceChip(strokeWidthLabel(width), state.strokeWidth == width, !state.isSaving) {
                stateHolder.selectStrokeWidth(width)
            }
        }
    }
}

/** Presents one state-holder choice with an explicit label and unchanged selected semantics. */
@Composable
private fun EditorChoiceChip(
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(description) },
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
    )
}

/** Error or limit status line of the editor. */
@Composable
private fun EditorStatus(state: PhotoEditorUiState) {
    val status = state.errorMessage ?: limitMessage(state.lastLimit)
    when {
        status != null ->
            Text(
                text = status,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            )
        state.isSaving ->
            Text(
                text = stringResource(R.string.photo_editor_saving),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        else ->
            announcementMessage(state)?.let { announcement ->
                Box(
                    Modifier
                        .size(1.dp)
                        .semantics {
                            contentDescription = announcement
                            liveRegion = LiveRegionMode.Polite
                        },
                )
            }
    }
}

/** Editing canvas with crop, rotation and drawing overlays. */
@Composable
@Suppress("LongMethod") // Pointer input, crop handles, and transformed preview share one coordinate space.
private fun PhotoEditorCanvas(
    previewBitmap: Bitmap,
    sourceInfo: PhotoEditorSourceInfo,
    state: PhotoEditorUiState,
    onFreeCrop: (NormalizedRect) -> Unit,
    onStroke: (List<NormalizedPoint>) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { 24.dp.toPx() }
    var activeCropCorner by remember { mutableStateOf<Int?>(null) }
    // A drag that took hold of the rectangle's inside rather than a corner, with where it began, so
    // the move is measured from the press and not accumulated frame by frame.
    var cropDragOrigin by remember { mutableStateOf<Pair<NormalizedRect, NormalizedPoint>?>(null) }
    var transientCrop by remember { mutableStateOf<NormalizedRect?>(null) }
    var transientStroke by remember { mutableStateOf<List<NormalizedPoint>>(emptyList()) }
    val canvasDescription = stringResource(R.string.photo_editor_image_description)

    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        val viewWidth = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val viewHeight = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val cropMode = state.activeTool == PhotoEditorTool.Crop
        val displayRecipe =
            if (cropMode) {
                state.recipe.copy(crop = NormalizedRect.Full)
            } else {
                state.recipe
            }
        val geometry =
            remember(sourceInfo, displayRecipe) {
                PhotoEditGeometry.create(
                    encodedSize = sourceInfo.encodedSize,
                    exifOrientation = sourceInfo.exifOrientation,
                    recipe = displayRecipe,
                    maxEdgePx = 1536,
                    maxPixels = 4_000_000L,
                )
            }
        val viewTransform =
            remember(geometry.outputSize, viewWidth, viewHeight) {
                EditorViewTransform.fit(geometry.outputSize, viewWidth, viewHeight)
            }
        val pointerModifier =
            if (state.isSaving) {
                Modifier
            } else {
                Modifier.pointerInput(state.activeTool, geometry, viewTransform, state.recipe.crop) {
                    val strokePoints = mutableListOf<NormalizedPoint>()
                    detectDragGestures(
                        onDragStart = { position ->
                            if (cropMode) {
                                activeCropCorner =
                                    nearestCropCorner(position, state.recipe.crop, geometry, viewTransform, handleRadiusPx)
                                // Missing a corner used to leave the drag doing nothing at all. A press
                                // inside the rectangle moves it instead, so every crop drag has an effect.
                                cropDragOrigin =
                                    if (activeCropCorner == null) {
                                        val start =
                                            geometry.viewToOriented(EditorPoint(position.x, position.y), viewTransform).clamped()
                                        state.recipe.crop
                                            .takeIf { it.contains(start) }
                                            ?.let { it to start }
                                    } else {
                                        null
                                    }
                            } else {
                                strokePoints.clear()
                                strokePoints += geometry.viewToOriented(EditorPoint(position.x, position.y), viewTransform).clamped()
                                transientStroke = strokePoints.toList()
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val point = geometry.viewToOriented(EditorPoint(change.position.x, change.position.y), viewTransform).clamped()
                            if (cropMode) {
                                activeCropCorner?.let { corner ->
                                    transientCrop =
                                        movedCropCorner(
                                            state.recipe.crop,
                                            corner,
                                            point,
                                            minimumCropFraction(sourceInfo.orientedSize),
                                        )
                                }
                                cropDragOrigin?.let { (startCrop, startPoint) ->
                                    transientCrop = startCrop.translated(point.x - startPoint.x, point.y - startPoint.y)
                                }
                            } else {
                                strokePoints += point
                                transientStroke = strokePoints.toList()
                            }
                        },
                        onDragEnd = {
                            transientCrop?.let(onFreeCrop)
                            if (strokePoints.isNotEmpty()) onStroke(strokePoints)
                            activeCropCorner = null
                            cropDragOrigin = null
                            transientCrop = null
                            transientStroke = emptyList()
                            strokePoints.clear()
                        },
                        onDragCancel = {
                            activeCropCorner = null
                            cropDragOrigin = null
                            transientCrop = null
                            transientStroke = emptyList()
                            strokePoints.clear()
                        },
                    )
                }
            }
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(pointerModifier)
                    // Android clips this rect to the system gesture insets, so
                    // only the narrow screen-edge overlap is excluded. That
                    // keeps crop/draw drags from becoming Back gestures without
                    // reserving unrelated parts of the editor.
                    .systemGestureExclusion()
                    .semantics {
                        role = Role.Image
                        contentDescription = canvasDescription
                    },
        ) {
            drawEditorPreview(
                previewBitmap = previewBitmap,
                geometry = geometry,
                viewTransform = viewTransform,
                committedRecipe = state.recipe,
                transientCrop = transientCrop,
                transientStroke = transientStroke,
                transientMode = if (state.activeTool == PhotoEditorTool.Erase) PhotoStrokeMode.Erase else PhotoStrokeMode.Draw,
                transientColor = state.drawColorArgb,
                transientWidth = state.strokeWidth.fraction,
                cropMode = cropMode,
            )
        }
    }
}

@Suppress("LongMethod") // Drawing all editor layers in one DrawScope prevents transform drift.
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEditorPreview(
    previewBitmap: Bitmap,
    geometry: PhotoEditGeometry,
    viewTransform: EditorViewTransform,
    committedRecipe: PhotoEditRecipe,
    transientCrop: NormalizedRect?,
    transientStroke: List<NormalizedPoint>,
    transientMode: PhotoStrokeMode,
    transientColor: Int,
    transientWidth: Float,
    cropMode: Boolean,
) {
    val affine = geometry.orientedBitmapToOutputAffine(previewBitmap.width, previewBitmap.height)
    val matrix =
        Matrix().apply {
            setValues(
                floatArrayOf(
                    affine.scaleX * viewTransform.scale,
                    affine.skewX * viewTransform.scale,
                    viewTransform.offsetX + affine.translateX * viewTransform.scale,
                    affine.skewY * viewTransform.scale,
                    affine.scaleY * viewTransform.scale,
                    viewTransform.offsetY + affine.translateY * viewTransform.scale,
                    0f,
                    0f,
                    1f,
                ),
            )
        }
    val outputLeft = viewTransform.offsetX
    val outputTop = viewTransform.offsetY
    val outputRight = outputLeft + geometry.outputSize.width * viewTransform.scale
    val outputBottom = outputTop + geometry.outputSize.height * viewTransform.scale
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val checkpoint = native.save()
        native.clipRect(outputLeft, outputTop, outputRight, outputBottom)
        native.drawBitmap(
            previewBitmap,
            matrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        native.restoreToCount(checkpoint)
    }
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val checkpoint = native.saveLayer(outputLeft, outputTop, outputRight, outputBottom, null)
        native.clipRect(outputLeft, outputTop, outputRight, outputBottom)
        committedRecipe.strokes.forEach { stroke ->
            drawPreviewStroke(native, stroke.points, stroke.mode, stroke.colorArgb, stroke.widthFraction, geometry, viewTransform)
        }
        if (transientStroke.isNotEmpty()) {
            drawPreviewStroke(native, transientStroke, transientMode, transientColor, transientWidth, geometry, viewTransform)
        }
        native.restoreToCount(checkpoint)
    }
    if (cropMode) {
        val crop = transientCrop ?: committedRecipe.crop
        val corners = cropCorners(crop).map { geometry.orientedToOutput(it).let(viewTransform::outputToView) }
        val left = corners.minOf { it.x }
        val top = corners.minOf { it.y }
        val right = corners.maxOf { it.x }
        val bottom = corners.maxOf { it.y }
        val scrim = Color.Black.copy(alpha = 0.55f)
        drawRect(
            scrim,
            Offset(0f, 0f),
            androidx.compose.ui.geometry
                .Size(size.width, top.coerceAtLeast(0f)),
        )
        drawRect(
            scrim,
            Offset(0f, bottom),
            androidx.compose.ui.geometry
                .Size(size.width, (size.height - bottom).coerceAtLeast(0f)),
        )
        drawRect(
            scrim,
            Offset(0f, top),
            androidx.compose.ui.geometry
                .Size(left.coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
        )
        drawRect(
            scrim,
            Offset(right, top),
            androidx.compose.ui.geometry
                .Size((size.width - right).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
        )
        drawRect(
            Color.White,
            Offset(left, top),
            androidx.compose.ui.geometry
                .Size(right - left, bottom - top),
            style =
                androidx.compose.ui.graphics.drawscope
                    .Stroke(2.dp.toPx()),
        )
        corners.forEach { drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(it.x, it.y)) }
    }
}

private fun drawPreviewStroke(
    canvas: android.graphics.Canvas,
    points: List<NormalizedPoint>,
    mode: PhotoStrokeMode,
    color: Int,
    widthFraction: Float,
    geometry: PhotoEditGeometry,
    viewTransform: EditorViewTransform,
) {
    if (points.isEmpty()) return
    val mapped = points.map { geometry.orientedToOutput(it).let(viewTransform::outputToView) }
    val cropWidth = geometry.orientedSize.width * geometry.recipe.crop.width
    val naturalWidth =
        if (geometry.recipe.quarterTurnsClockwise % 2 == 0) {
            cropWidth
        } else {
            geometry.orientedSize.height * geometry.recipe.crop.height
        }
    val outputScale = geometry.outputSize.width / naturalWidth.coerceAtLeast(1f)
    val width = widthFraction * min(geometry.orientedSize.width, geometry.orientedSize.height) * outputScale * viewTransform.scale
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width.coerceAtLeast(1f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
            if (mode == PhotoStrokeMode.Erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
    if (mapped.size == 1) {
        canvas.drawCircle(mapped[0].x, mapped[0].y, paint.strokeWidth / 2f, paint)
    } else {
        val lines = FloatArray((mapped.size - 1) * 4)
        for (index in 0 until mapped.lastIndex) {
            val offset = index * 4
            lines[offset] = mapped[index].x
            lines[offset + 1] = mapped[index].y
            lines[offset + 2] = mapped[index + 1].x
            lines[offset + 3] = mapped[index + 1].y
        }
        canvas.drawLines(lines, paint)
    }
}

private fun nearestCropCorner(
    position: Offset,
    crop: NormalizedRect,
    geometry: PhotoEditGeometry,
    viewTransform: EditorViewTransform,
    thresholdPx: Float,
): Int? =
    cropCorners(crop)
        .map { geometry.orientedToOutput(it).let(viewTransform::outputToView) }
        .mapIndexed { index, point -> index to hypot(position.x - point.x, position.y - point.y) }
        .minByOrNull { it.second }
        ?.takeIf { it.second <= thresholdPx }
        ?.first

private fun movedCropCorner(
    crop: NormalizedRect,
    corner: Int,
    point: NormalizedPoint,
    minimumFraction: Float,
): NormalizedRect {
    val opposite =
        when (corner) {
            0 -> NormalizedPoint(crop.right, crop.bottom)
            1 -> NormalizedPoint(crop.left, crop.bottom)
            2 -> NormalizedPoint(crop.left, crop.top)
            else -> NormalizedPoint(crop.right, crop.top)
        }
    return NormalizedRect.clamped(point, opposite, minimumFraction)
}

private fun cropCorners(crop: NormalizedRect): List<NormalizedPoint> =
    listOf(
        NormalizedPoint(crop.left, crop.top),
        NormalizedPoint(crop.right, crop.top),
        NormalizedPoint(crop.right, crop.bottom),
        NormalizedPoint(crop.left, crop.bottom),
    )

private data class EditorColor(
    val argb: Int,
    val name: String,
    val selectedDescription: String,
)

/** The drawing colours the editor offers. */
@Composable
private fun editorColors(): List<EditorColor> {
    @Composable
    fun color(
        argb: Int,
        nameResource: Int,
    ): EditorColor {
        val name = stringResource(nameResource)
        return EditorColor(argb, name, stringResource(R.string.photo_editor_color_selected, name))
    }
    return listOf(
        color(0xFFFF3B30.toInt(), R.string.photo_editor_color_red),
        color(0xFFFFCC00.toInt(), R.string.photo_editor_color_yellow),
        color(0xFF34C759.toInt(), R.string.photo_editor_color_green),
        color(0xFF007AFF.toInt(), R.string.photo_editor_color_blue),
        color(0xFFFFFFFF.toInt(), R.string.photo_editor_color_white),
    )
}

@Composable
private fun cropPresetLabel(preset: PhotoCropPreset): String =
    stringResource(
        when (preset) {
            PhotoCropPreset.Free -> R.string.photo_editor_crop_free
            PhotoCropPreset.Original -> R.string.photo_editor_crop_original
            PhotoCropPreset.Square -> R.string.photo_editor_crop_square
            PhotoCropPreset.FourThree -> R.string.photo_editor_crop_four_three
            PhotoCropPreset.ThreeFour -> R.string.photo_editor_crop_three_four
            PhotoCropPreset.SixteenNine -> R.string.photo_editor_crop_sixteen_nine
            PhotoCropPreset.NineSixteen -> R.string.photo_editor_crop_nine_sixteen
        },
    )

@Composable
private fun strokeWidthLabel(width: PhotoStrokeWidth): String =
    stringResource(
        when (width) {
            PhotoStrokeWidth.Small -> R.string.photo_editor_width_small
            PhotoStrokeWidth.Medium -> R.string.photo_editor_width_medium
            PhotoStrokeWidth.Large -> R.string.photo_editor_width_large
            PhotoStrokeWidth.ExtraLarge -> R.string.photo_editor_width_extra_large
        },
    )

@Composable
private fun toolLabel(tool: PhotoEditorTool): String =
    stringResource(
        when (tool) {
            PhotoEditorTool.Crop -> R.string.photo_editor_crop
            PhotoEditorTool.Draw -> R.string.photo_editor_draw
            PhotoEditorTool.Erase -> R.string.photo_editor_erase
        },
    )

@Composable
private fun announcementMessage(state: PhotoEditorUiState): String? =
    when (state.announcement) {
        PhotoEditorAnnouncement.ToolSelected ->
            stringResource(R.string.photo_editor_announcement_tool, toolLabel(state.activeTool))
        PhotoEditorAnnouncement.CropChanged ->
            stringResource(R.string.photo_editor_announcement_crop, cropPresetLabel(state.cropPreset))
        PhotoEditorAnnouncement.Rotated -> stringResource(R.string.photo_editor_announcement_rotated)
        PhotoEditorAnnouncement.DrawingAdded -> stringResource(R.string.photo_editor_announcement_drawing)
        PhotoEditorAnnouncement.EraserAdded -> stringResource(R.string.photo_editor_announcement_eraser)
        PhotoEditorAnnouncement.Undo -> stringResource(R.string.photo_editor_announcement_undo)
        PhotoEditorAnnouncement.Redo -> stringResource(R.string.photo_editor_announcement_redo)
        PhotoEditorAnnouncement.Reset -> stringResource(R.string.photo_editor_announcement_reset)
        null -> null
    }

@Composable
private fun limitMessage(limit: PhotoEditLimit?): String? =
    when (limit) {
        PhotoEditLimit.StrokeCount -> stringResource(R.string.photo_editor_limit_strokes)
        PhotoEditLimit.StrokePoints,
        PhotoEditLimit.TotalPoints,
        -> stringResource(R.string.photo_editor_limit_points)
        null -> null
    }

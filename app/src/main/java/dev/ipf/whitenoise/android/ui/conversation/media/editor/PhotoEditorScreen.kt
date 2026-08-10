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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.hypot
import kotlin.math.min

@Composable
internal fun PhotoEditorDialog(
    previewBitmap: Bitmap,
    sourceInfo: PhotoEditorSourceInfo,
    stateHolder: PhotoEditorStateHolder,
    onCancel: () -> Unit,
    onSave: (PhotoEditRecipe, MediaQuality) -> Unit,
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
        )
    }
}

@Composable
@Suppress("LongMethod") // Screen-level orchestration keeps save/back semantics alongside editor state.
internal fun PhotoEditorScreen(
    previewBitmap: Bitmap,
    sourceInfo: PhotoEditorSourceInfo,
    stateHolder: PhotoEditorStateHolder,
    onCancel: () -> Unit,
    onSave: (PhotoEditRecipe, MediaQuality) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = stateHolder.state
    var showDiscardDialog by remember { mutableStateOf(false) }

    fun requestCancel() {
        if (state.isSaving) return
        if (stateHolder.hasUnsavedChanges) showDiscardDialog = true else onCancel()
    }

    BackHandler(enabled = true, onBack = ::requestCancel)
    Surface(modifier = modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.fillMaxSize()) {
            PhotoEditorTopBar(
                saving = state.isSaving,
                onCancel = ::requestCancel,
                canUndo = state.canUndo,
                onUndo = stateHolder::undo,
                canRedo = state.canRedo,
                onRedo = stateHolder::redo,
                onReset = stateHolder::reset,
                onSave = {
                    stateHolder.beginSaving()
                    onSave(state.recipe, state.quality)
                },
            )
            PhotoEditorCanvas(
                previewBitmap = previewBitmap,
                sourceInfo = sourceInfo,
                state = state,
                onFreeCrop = stateHolder::commitFreeCrop,
                onStroke = stateHolder::commitStroke,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            PhotoEditorControls(
                state = state,
                stateHolder = stateHolder,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            )
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
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
@Suppress("LongMethod") // The compact top bar keeps all icon actions and saving state in one accessibility group.
private fun PhotoEditorTopBar(
    saving: Boolean,
    onCancel: () -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorIconButton(
            description = stringResource(R.string.cancel),
            enabled = !saving,
            onClick = onCancel,
        ) {
            Icon(Icons.Default.Close, contentDescription = null)
        }
        Spacer(Modifier.weight(1f))
        EditorIconButton(
            description = stringResource(R.string.photo_editor_undo),
            enabled = canUndo,
            onClick = onUndo,
        ) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
        }
        EditorIconButton(
            description = stringResource(R.string.photo_editor_redo),
            enabled = canRedo,
            onClick = onRedo,
        ) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = null)
        }
        EditorIconButton(
            description = stringResource(R.string.photo_editor_reset),
            enabled = !saving,
            onClick = onReset,
        ) {
            Icon(Icons.Default.RestartAlt, contentDescription = null)
        }
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = if (saving) Color.White.copy(alpha = 0.1f) else MaterialTheme.colorScheme.primary,
            contentColor = if (saving) Color.White.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onPrimary,
        ) {
            val saveDescription = stringResource(R.string.save)
            IconButton(
                onClick = onSave,
                enabled = !saving,
                modifier = Modifier.semantics { contentDescription = saveDescription },
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun PhotoEditorControls(
    state: PhotoEditorUiState,
    stateHolder: PhotoEditorStateHolder,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF111315),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (state.activeTool) {
                    PhotoEditorTool.Crop -> CropControls(state, stateHolder)
                    PhotoEditorTool.Draw -> DrawControls(state, stateHolder, showColors = true)
                    PhotoEditorTool.Erase -> DrawControls(state, stateHolder, showColors = false)
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorToolButton(
                    selected = state.activeTool == PhotoEditorTool.Crop,
                    description = stringResource(R.string.photo_editor_crop),
                    enabled = !state.isSaving,
                    onClick = { stateHolder.selectTool(PhotoEditorTool.Crop) },
                ) {
                    Icon(Icons.Default.Crop, contentDescription = null)
                }
                EditorToolButton(
                    selected = state.activeTool == PhotoEditorTool.Draw,
                    description = stringResource(R.string.photo_editor_draw),
                    enabled = !state.isSaving,
                    onClick = { stateHolder.selectTool(PhotoEditorTool.Draw) },
                ) {
                    Icon(Icons.Default.Brush, contentDescription = null)
                }
                EditorToolButton(
                    selected = state.activeTool == PhotoEditorTool.Erase,
                    description = stringResource(R.string.photo_editor_erase),
                    enabled = !state.isSaving,
                    onClick = { stateHolder.selectTool(PhotoEditorTool.Erase) },
                ) {
                    EraserIcon()
                }
            }
            EditorStatus(state)
        }
    }
}

@Suppress("DEPRECATION") // A mirrored icon would promise counter-clockwise rotation in RTL.
@Composable
private fun CropControls(
    state: PhotoEditorUiState,
    stateHolder: PhotoEditorStateHolder,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorIconButton(
            description = stringResource(R.string.photo_editor_rotate_clockwise),
            enabled = !state.isSaving,
            onClick = stateHolder::rotateClockwise,
        ) { Icon(Icons.Default.RotateRight, contentDescription = null) }
        PhotoCropPreset.entries.forEach { preset ->
            CropPresetButton(
                preset = preset,
                selected = state.cropPreset == preset,
                enabled = !state.isSaving,
                onClick = { stateHolder.selectCropPreset(preset) },
            )
        }
    }
}

@Composable
private fun DrawControls(
    state: PhotoEditorUiState,
    stateHolder: PhotoEditorStateHolder,
    showColors: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showColors) {
            editorColors().forEach { color ->
                val selected = state.drawColorArgb == color.argb
                ColorButton(
                    color = color,
                    selected = selected,
                    enabled = !state.isSaving,
                    onClick = { stateHolder.selectColor(color.argb) },
                )
            }
            Spacer(Modifier.width(4.dp))
            VerticalDivider(
                modifier = Modifier.width(1.dp).height(28.dp),
                color = Color.White.copy(alpha = 0.16f),
            )
            Spacer(Modifier.width(4.dp))
        }
        PhotoStrokeWidth.entries.forEach { width ->
            StrokeWidthButton(
                width = width,
                selected = state.strokeWidth == width,
                enabled = !state.isSaving,
                onClick = { stateHolder.selectStrokeWidth(width) },
            )
        }
    }
}

@Composable
private fun EditorToolButton(
    selected: Boolean,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val contentColor =
        when {
            !enabled -> Color.White.copy(alpha = 0.35f)
            selected -> MaterialTheme.colorScheme.primary
            else -> Color.White.copy(alpha = 0.82f)
        }
    Surface(
        modifier =
            Modifier
                .size(48.dp)
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onClick,
                ).semantics {
                    contentDescription = description
                    this.selected = selected
                },
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent,
        contentColor = contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
private fun EraserIcon() {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        rotate(-40f, pivot = center) {
            val left = size.width * 0.18f
            val top = size.height * 0.32f
            val width = size.width * 0.64f
            val height = size.height * 0.36f
            drawRoundRect(
                color = tint,
                topLeft = Offset(left, top),
                size =
                    androidx.compose.ui.geometry
                        .Size(width, height),
                cornerRadius =
                    androidx.compose.ui.geometry
                        .CornerRadius(2.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawLine(
                color = tint,
                start = Offset(left + width * 0.58f, top),
                end = Offset(left + width * 0.58f, top + height),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

@Composable
private fun EditorIconButton(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = Color.White.copy(alpha = if (enabled) 0.08f else 0.03f),
            contentColor = Color.White.copy(alpha = if (enabled) 0.82f else 0.32f),
        ) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
    }
}

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
                color = Color.White.copy(alpha = 0.8f),
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

@Composable
private fun CropPresetButton(
    preset: PhotoCropPreset,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description = cropPresetLabel(preset)
    val tint =
        when {
            !enabled -> Color.White.copy(alpha = 0.3f)
            selected -> MaterialTheme.colorScheme.primary
            else -> Color.White.copy(alpha = 0.78f)
        }
    Surface(
        modifier =
            Modifier
                .size(48.dp)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .semantics {
                    contentDescription = description
                    this.selected = selected
                },
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CropPresetGlyph(preset = preset, tint = tint)
        }
    }
}

@Composable
private fun CropPresetGlyph(
    preset: PhotoCropPreset,
    tint: Color,
) {
    Canvas(Modifier.size(28.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        if (preset == PhotoCropPreset.Free) {
            val inset = size.minDimension * 0.18f
            val arm = size.minDimension * 0.25f
            listOf(
                Offset(inset, inset) to Offset(inset + arm, inset),
                Offset(inset, inset) to Offset(inset, inset + arm),
                Offset(size.width - inset, inset) to Offset(size.width - inset - arm, inset),
                Offset(size.width - inset, inset) to Offset(size.width - inset, inset + arm),
                Offset(inset, size.height - inset) to Offset(inset + arm, size.height - inset),
                Offset(inset, size.height - inset) to Offset(inset, size.height - inset - arm),
                Offset(size.width - inset, size.height - inset) to Offset(size.width - inset - arm, size.height - inset),
                Offset(size.width - inset, size.height - inset) to Offset(size.width - inset, size.height - inset - arm),
            ).forEach { (start, end) -> drawLine(tint, start, end, strokeWidth = strokeWidth) }
        } else {
            val ratio = preset.outputAspectRatio ?: (4f / 3f)
            val maxWidth = size.width * 0.78f
            val maxHeight = size.height * 0.7f
            val width = min(maxWidth, maxHeight * ratio)
            val height = min(maxHeight, maxWidth / ratio)
            val topLeft = Offset((size.width - width) / 2f, (size.height - height) / 2f)
            drawRect(
                color = tint,
                topLeft = topLeft,
                size =
                    androidx.compose.ui.geometry
                        .Size(width, height),
                style = Stroke(width = strokeWidth),
            )
            if (preset == PhotoCropPreset.Original) {
                drawCircle(
                    color = tint,
                    radius = 1.7.dp.toPx(),
                    center = Offset(topLeft.x + width - 4.dp.toPx(), topLeft.y + 4.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun ColorButton(
    color: EditorColor,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .semantics {
                    this.selected = selected
                    contentDescription = if (selected) color.selectedDescription else color.name
                }.padding(6.dp)
                .border(
                    width = if (selected) 2.5.dp else 1.dp,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.35f),
                    shape = CircleShape,
                ).padding(4.dp)
                .background(Color(color.argb), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = contrastingColor(color.argb),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun StrokeWidthButton(
    width: PhotoStrokeWidth,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description = strokeWidthLabel(width)
    val tint =
        when {
            !enabled -> Color.White.copy(alpha = 0.3f)
            selected -> MaterialTheme.colorScheme.primary
            else -> Color.White.copy(alpha = 0.78f)
        }
    Surface(
        modifier =
            Modifier
                .size(44.dp)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .semantics {
                    contentDescription = description
                    this.selected = selected
                },
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
    ) {
        Canvas(Modifier.padding(10.dp)) {
            val lineWidth =
                when (width) {
                    PhotoStrokeWidth.Small -> 1.5.dp
                    PhotoStrokeWidth.Medium -> 3.dp
                    PhotoStrokeWidth.Large -> 5.dp
                    PhotoStrokeWidth.ExtraLarge -> 7.dp
                }.toPx()
            drawLine(
                color = tint,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = lineWidth,
            )
        }
    }
}

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
    var transientCrop by remember { mutableStateOf<NormalizedRect?>(null) }
    var transientStroke by remember { mutableStateOf<List<NormalizedPoint>>(emptyList()) }
    val canvasDescription = stringResource(R.string.photo_editor_image_description)

    BoxWithConstraints(modifier.background(Color(0xFF161616), RoundedCornerShape(12.dp))) {
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
                                activeCropCorner = nearestCropCorner(position, state.recipe.crop, geometry, viewTransform, handleRadiusPx)
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
                                    transientCrop = movedCropCorner(state.recipe.crop, corner, point, minimumCropFraction(sourceInfo.orientedSize))
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
                            transientCrop = null
                            transientStroke = emptyList()
                            strokePoints.clear()
                        },
                        onDragCancel = {
                            activeCropCorner = null
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

private fun contrastingColor(argb: Int): Color = if (android.graphics.Color.luminance(argb) > 0.6f) Color.Black else Color.White

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

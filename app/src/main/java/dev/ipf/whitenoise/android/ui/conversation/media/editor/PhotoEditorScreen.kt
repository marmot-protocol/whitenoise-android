@file:Suppress("FunctionNaming", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package dev.ipf.whitenoise.android.ui.conversation.media.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.editor.ActiveStrokeBuffer
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
import dev.ipf.whitenoise.android.ui.common.TopNoticeSlot
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.common.scrollEdgeFade
import dev.ipf.whitenoise.android.ui.conversation.PlaybackDialog
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import kotlin.math.hypot
import kotlin.math.min

/** Keeps transport controls available while the photo editor retains sole ownership of dirty-recipe dismissal. */
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
    PlaybackDialog(
        // Back is handled by [PhotoEditorScreen] so a dirty recipe always goes
        // through the discard confirmation instead of bypassing it here.
        onDismissRequest = {},
        onSourceDismiss = null,
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
    var showHelp by remember { mutableStateOf(false) }

    fun requestCancel() {
        if (state.isSaving) return
        if (stateHolder.hasUnsavedChanges) showDiscardDialog = true else onCancel()
    }

    BackHandler(enabled = true, onBack = ::requestCancel)
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("photo.editor").exposePerformanceTestTags(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            // App banners sit below this screen's bar rather than over it (#3312).
            Column {
                PhotoEditorTopBar(
                    saving = state.isSaving,
                    frameIndex = frameIndex,
                    frameCount = frameCount,
                    onReset = stateHolder::reset,
                    canReset = stateHolder.hasUnsavedChanges,
                    onHelp = { showHelp = true },
                    onCancel = ::requestCancel,
                    onSave = {
                        stateHolder.beginSaving()
                        onSave(state.recipe, state.quality)
                    },
                )
                TopNoticeSlot()
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PhotoEditorHistoryActions(state, stateHolder) { showQuality = true }
            if (state.isSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 600.dp || maxHeight < 360.dp
                val sidePanelWidth = minOf(144.dp, maxWidth / 2)
                val photo: @Composable (Modifier) -> Unit = { canvasModifier ->
                    PhotoEditorCanvas(
                        previewBitmap = previewBitmap,
                        sourceInfo = sourceInfo,
                        state = state,
                        onFreeCrop = stateHolder::commitFreeCrop,
                        onStroke = stateHolder::commitStroke,
                        modifier = canvasModifier,
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
                            wide = true,
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
                            wide = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            EditorStatus(state)
        }
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(stringResource(R.string.help)) },
            text = {
                Column(Modifier.fadingVerticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.photo_editor_crop_hint))
                    Text(stringResource(R.string.photo_editor_draw_hint))
                    Text(stringResource(R.string.photo_editor_original_edited))
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.done)) } },
        )
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
            supportingText =
                buildString {
                    append(stringResource(R.string.photo_editor_quality_explanation))
                    if (state.quality == MediaQuality.Original && stateHolder.hasUnsavedChanges) {
                        append("\n\n")
                        append(stringResource(R.string.photo_editor_original_edited))
                    }
                },
        )
    }
}

/** Keeps cancel/save on the app bar while the existing holder owns save acceptance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
private fun PhotoEditorTopBar(
    saving: Boolean,
    frameIndex: Int,
    frameCount: Int,
    onReset: () -> Unit,
    canReset: Boolean,
    onHelp: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val resetLabel = stringResource(R.string.photo_editor_reset)
    val menuScrollState = rememberScrollState()
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.photo_editor_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Box {
                IconButton(onClick = { expanded = true }, enabled = !saving) {
                    Icon(painterResource(R.drawable.ic_more_horiz), stringResource(R.string.more_options))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    scrollState = menuScrollState,
                    modifier = Modifier.scrollEdgeFade(menuScrollState, stableRenderTarget = true),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.photo_editor_reset)) },
                        enabled = canReset && !saving,
                        modifier = Modifier.semantics { contentDescription = resetLabel },
                        onClick = {
                            expanded = false
                            onReset()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.help)) },
                        onClick = {
                            expanded = false
                            onHelp()
                        },
                    )
                }
            }
            EditorTextAction(
                stringResource(if (saving) R.string.photo_editor_saving_action else R.string.save),
                !saving,
                onSave,
            )
        },
    )
}

/** Icon history actions share a compact row with the existing quality dialog. */
@Composable
private fun PhotoEditorHistoryActions(
    state: PhotoEditorUiState,
    holder: PhotoEditorStateHolder,
    onQuality: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        EditorIconAction(R.drawable.ic_editor_undo, stringResource(R.string.photo_editor_undo), state.canUndo, holder::undo)
        EditorIconAction(R.drawable.ic_editor_redo, stringResource(R.string.photo_editor_redo), state.canRedo, holder::redo)
        PhotoEditorQualityAction(state.quality, !state.isSaving, onQuality, Modifier.weight(1f))
    }
}

@Composable
private fun EditorIconAction(
    icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription = label)
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

/** Two compact bands: active-tool options and the four main icon-labelled actions. */
@Composable
@Suppress("LongMethod")
private fun PhotoEditorControls(
    state: PhotoEditorUiState,
    stateHolder: PhotoEditorStateHolder,
    onCoordinates: () -> Unit,
    wide: Boolean,
    modifier: Modifier,
) {
    if (wide) {
        Row(modifier) {
            Column(Modifier.weight(1f).fadingVerticalScroll(rememberScrollState())) {
                EditorOptions(state, stateHolder, vertical = true)
                EditorIconAction(R.drawable.ic_tune, coordinateDialogTitle(state.activeTool), !state.isSaving, onCoordinates)
            }
            Column(Modifier.weight(1f).fadingVerticalScroll(rememberScrollState())) {
                PhotoEditorTool.entries.forEach { tool ->
                    EditorToolButton(toolLabel(tool), toolIcon(tool), state.activeTool == tool, !state.isSaving, Modifier.fillMaxWidth()) {
                        stateHolder.selectTool(tool)
                    }
                }
                EditorToolButton(
                    stringResource(R.string.photo_editor_rotate),
                    R.drawable.ic_editor_rotate,
                    false,
                    !state.isSaving,
                    Modifier.fillMaxWidth(),
                ) {
                    stateHolder.rotateClockwise()
                }
            }
        }
    } else {
        Column(modifier) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    EditorOptions(state, stateHolder, vertical = false)
                }
                EditorIconAction(R.drawable.ic_tune, coordinateDialogTitle(state.activeTool), !state.isSaving, onCoordinates)
            }
            Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PhotoEditorTool.entries.forEach { tool ->
                    EditorToolButton(toolLabel(tool), toolIcon(tool), state.activeTool == tool, !state.isSaving, Modifier.weight(1f)) {
                        stateHolder.selectTool(tool)
                    }
                }
                EditorToolButton(
                    stringResource(R.string.photo_editor_rotate),
                    R.drawable.ic_editor_rotate,
                    false,
                    !state.isSaving,
                    Modifier.weight(1f),
                ) {
                    stateHolder.rotateClockwise()
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun EditorOptions(
    state: PhotoEditorUiState,
    holder: PhotoEditorStateHolder,
    vertical: Boolean,
) {
    val selectionColor = MaterialTheme.colorScheme.onSurface
    val choices: @Composable () -> Unit = {
        if (state.activeTool == PhotoEditorTool.Crop) {
            PhotoCropPreset.entries.forEach { preset ->
                EditorTextChoice(cropPresetLabel(preset), state.cropPreset == preset, !state.isSaving) { holder.selectCropPreset(preset) }
            }
        } else {
            if (state.activeTool == PhotoEditorTool.Draw) {
                editorColors().forEach { color ->
                    val selected = state.drawColorArgb == color.argb
                    TextButton(
                        onClick = { holder.selectColor(color.argb) },
                        enabled = !state.isSaving,
                        modifier =
                            Modifier.size(48.dp).semantics {
                                this.selected = selected
                                contentDescription = if (selected) color.selectedDescription else color.name
                            },
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        Canvas(Modifier.size(24.dp)) {
                            drawCircle(Color(color.argb), radius = 8.dp.toPx())
                            drawCircle(selectionColor.copy(alpha = .5f), radius = 8.dp.toPx(), style = Stroke(1.dp.toPx()))
                            if (selected) drawCircle(selectionColor, radius = 11.dp.toPx(), style = Stroke(2.dp.toPx()))
                        }
                    }
                }
            }
            PhotoStrokeWidth.entries.forEach { width ->
                EditorTextChoice(strokeWidthLabel(width), state.strokeWidth == width, !state.isSaving) { holder.selectStrokeWidth(width) }
            }
        }
    }
    if (vertical) {
        FlowRow(Modifier.selectableGroup(), content = { choices() })
    } else {
        Row(Modifier.selectableGroup(), content = { choices() })
    }
}

@Composable
private fun EditorTextChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier.heightIn(min = 48.dp).semantics {
                this.selected = selected
                contentDescription = label
            },
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(label, fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null)
    }
}

private fun toolIcon(tool: PhotoEditorTool): Int =
    when (tool) {
        PhotoEditorTool.Crop -> R.drawable.ic_editor_crop
        PhotoEditorTool.Draw -> R.drawable.ic_edit
        PhotoEditorTool.Erase -> R.drawable.ic_editor_erase
    }

@Composable
@Suppress("LongParameterList")
private fun EditorToolButton(
    label: String,
    icon: Int,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val amoled = isAmoledSurfaceTheme()
    val description = if (icon == R.drawable.ic_editor_rotate) stringResource(R.string.photo_editor_rotate_clockwise) else label
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier.heightIn(min = 48.dp).semantics {
                this.selected = selected
                contentDescription = description
            },
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        border = if (amoled && !selected) null else BorderStroke(1.dp, colors.onSurface.copy(alpha = if (amoled) .44f else .2f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected && !amoled) colors.secondaryContainer else colors.surface),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Output quality remains available beside the history actions. */
@Composable
private fun PhotoEditorQualityAction(
    quality: MediaQuality,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val label = stringResource(R.string.photo_editor_quality) + ": " + photoQualityLevelLabel(quality)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = label }
                .testTag("photo.editor.quality"),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, modifier = Modifier.size(18.dp))
    }
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
    onStroke: (points: List<NormalizedPoint>, exceededPointBudget: Boolean) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { 24.dp.toPx() }
    var activeCropCorner by remember { mutableStateOf<Int?>(null) }
    // A drag that took hold of the rectangle's inside rather than a corner, with where it began, so
    // the move is measured from the press and not accumulated frame by frame.
    var cropDragOrigin by remember { mutableStateOf<Pair<NormalizedRect, NormalizedPoint>?>(null) }
    var transientCrop by remember { mutableStateOf<NormalizedRect?>(null) }
    // The live stroke is drawn straight from its bounded buffer; the revision invalidates the canvas per move
    // without copying every point the gesture has made so far.
    val activeStroke = remember(state.history.limits) { ActiveStrokeBuffer(state.history.limits) }
    var activeStrokeRevision by remember { mutableIntStateOf(0) }
    val canvasDescription = stringResource(R.string.photo_editor_image_description)

    BoxWithConstraints(modifier.background(Color.Black)) {
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
                                activeStroke.start(
                                    geometry.viewToOriented(EditorPoint(position.x, position.y), viewTransform).clamped(),
                                )
                                activeStrokeRevision++
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
                                activeStroke.add(point)
                                activeStrokeRevision++
                            }
                        },
                        onDragEnd = {
                            transientCrop?.let(onFreeCrop)
                            if (!activeStroke.isEmpty()) onStroke(activeStroke.toStroke(), activeStroke.exceededPointBudget)
                            activeCropCorner = null
                            cropDragOrigin = null
                            transientCrop = null
                            activeStroke.clear()
                            activeStrokeRevision++
                        },
                        onDragCancel = {
                            activeCropCorner = null
                            cropDragOrigin = null
                            transientCrop = null
                            activeStroke.clear()
                            activeStrokeRevision++
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
            // Read so each stroke move redraws; the points themselves live in the buffer.
            @Suppress("UNUSED_EXPRESSION")
            activeStrokeRevision
            drawEditorPreview(
                previewBitmap = previewBitmap,
                geometry = geometry,
                viewTransform = viewTransform,
                committedRecipe = state.recipe,
                transientCrop = transientCrop,
                transientStroke = activeStroke.points,
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
        val handleLength = minOf(16.dp.toPx(), (right - left) / 4, (bottom - top) / 4)
        corners.forEach { point ->
            val x = point.x
            val y = point.y
            val horizontal = if (x == left) handleLength else -handleLength
            val vertical = if (y == top) handleLength else -handleLength
            drawLine(Color.White, Offset(x, y), Offset(x + horizontal, y), strokeWidth = 3.dp.toPx())
            drawLine(Color.White, Offset(x, y), Offset(x, y + vertical), strokeWidth = 3.dp.toPx())
        }
        if (transientCrop != null) {
            for (fraction in listOf(1f / 3, 2f / 3)) {
                val x = left + (right - left) * fraction
                val y = top + (bottom - top) * fraction
                drawLine(Color.White.copy(alpha = .3f), Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
                drawLine(Color.White.copy(alpha = .3f), Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            }
        }
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

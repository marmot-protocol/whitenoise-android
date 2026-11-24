@file:Suppress("FunctionNaming", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package dev.ipf.whitenoise.android.ui.conversation.media.editor

import android.graphics.Bitmap
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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.unit.sp
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
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.common.nativePageBody
import dev.ipf.whitenoise.android.ui.common.scrollEdgeFade
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPlaybackDialog
import dev.ipf.whitenoise.android.ui.conversation.media.AttachmentViewerChrome
import dev.ipf.whitenoise.android.ui.conversation.media.AttachmentViewerChromeDefaults
import dev.ipf.whitenoise.android.ui.conversation.media.AttachmentViewerTitle
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

/** Keeps transport controls available while the photo editor retains sole ownership of dirty-recipe dismissal. */
@Composable
@Suppress("LongParameterList")
internal fun PhotoEditorDialog(
    previewBitmap: Bitmap,
    sourceInfo: PhotoEditorSourceInfo,
    stateHolder: PhotoEditorStateHolder,
    onCancel: () -> Unit,
    onSave: (PhotoEditRecipe, MediaQuality, () -> Unit) -> Unit,
    origin: Rect? = null,
    ownerKey: Any = stateHolder,
    frameIndex: Int = 0,
    frameCount: Int = 1,
) {
    MeasuredPlaybackDialog(
        origin = origin,
        ownerKey = ownerKey,
        dismissEnabled = { false },
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
            onCancel = { close(onCancel) },
            onSave = { recipe, quality -> onSave(recipe, quality) { close(onCancel) } },
            inputEnabled = { acceptsInput },
            frameIndex = frameIndex,
            frameCount = frameCount,
        )
    }
}

/** Photo editor: canvas, tools and the save flow for a staged photo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod") // Screen-level orchestration keeps save/back semantics together.
internal fun PhotoEditorScreen(
    previewBitmap: Bitmap,
    sourceInfo: PhotoEditorSourceInfo,
    stateHolder: PhotoEditorStateHolder,
    onCancel: () -> Unit,
    onSave: (PhotoEditRecipe, MediaQuality) -> Unit,
    modifier: Modifier = Modifier,
    inputEnabled: () -> Boolean = { true },
    frameIndex: Int = 0,
    frameCount: Int = 1,
) {
    val state = stateHolder.state
    var showDiscardDialog by remember(stateHolder) { mutableStateOf(false) }
    var showCoordinates by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    // Edits are encoded at High, so Original only keeps its meaning while the recipe is untouched.
    val originalEdited = state.quality == MediaQuality.Original && state.recipe != stateHolder.initialRecipe

    fun requestCancel() {
        if (state.isSaving || !inputEnabled()) return
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
                    state = state,
                    holder = stateHolder,
                    frameIndex = frameIndex,
                    frameCount = frameCount,
                    onQuality = { showQuality = true },
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
        Column(Modifier.fillMaxSize().nativePageBody(padding).padding(padding)) {
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
            if (originalEdited) {
                Text(
                    text = stringResource(R.string.photo_editor_original_edited),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            EditorStatus(state)
        }
    }
    val currentOnCancel by rememberUpdatedState(onCancel)
    val discardEnabled by rememberUpdatedState(!state.isSaving && inputEnabled())
    if (showHelp) {
        WhiteNoiseAlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(stringResource(R.string.help)) },
            text = {
                Column(Modifier.fadingVerticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.photo_editor_crop_hint))
                    Text(stringResource(R.string.photo_editor_draw_hint))
                    Text(stringResource(R.string.photo_editor_original_edited))
                }
            },
            confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.done)) } },
        )
    }
    if (showDiscardDialog) {
        key(stateHolder, discardEnabled) {
            WhiteNoiseAlertDialog(
                onDismissRequest = { showDiscardDialog = false },
                title = { Text(stringResource(R.string.photo_editor_discard_title)) },
                text = { Text(stringResource(R.string.photo_editor_discard_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            close {
                                if (discardEnabled) {
                                    showDiscardDialog = false
                                    currentOnCancel()
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.photo_editor_discard)) }
                },
                dismissButton = {
                    TextButton(onClick = dismiss) {
                        Text(stringResource(R.string.photo_editor_keep_editing))
                    }
                },
            )
        }
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
                    if (originalEdited) {
                        append("\n\n")
                        append(stringResource(R.string.photo_editor_original_edited))
                    }
                },
        )
    }
}

/** The editor's viewer chrome: Close, title with history and quality, Save, then the existing More menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "LongParameterList")
private fun PhotoEditorTopBar(
    state: PhotoEditorUiState,
    holder: PhotoEditorStateHolder,
    frameIndex: Int,
    frameCount: Int,
    onQuality: () -> Unit,
    onHelp: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val saving = state.isSaving
    var expanded by remember { mutableStateOf(false) }
    val resetLabel = stringResource(R.string.photo_editor_reset)
    val menuScrollState = rememberScrollState()
    AttachmentViewerChrome(
        title = stringResource(R.string.photo_editor_title),
        onClose = onCancel,
        closeEnabled = !saving,
        windowInsets = AttachmentViewerChromeDefaults.topInsets(),
        titleContent = {
            // The title slot receives only the width left after Close, More and Save, so the
            // history and quality actions sit here to give up the title's space before their own.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // A lone ellipsis beside More reads as another control, so the title stays
                // in semantics but is not drawn until at least one whole word fits.
                Column(
                    Modifier.weight(1f).layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val wordFits = constraints.maxWidth >= measurable.minIntrinsicWidth(constraints.maxHeight)
                        layout(placeable.width, placeable.height) {
                            if (wordFits) placeable.place(0, 0) else placeable.placeWithLayer(0, 0) { alpha = 0f }
                        }
                    },
                ) {
                    AttachmentViewerTitle(
                        title = stringResource(R.string.photo_editor_title),
                        subtitle =
                            if (frameCount > 1) stringResource(R.string.photo_editor_frame, frameIndex + 1) else null,
                    )
                }
                EditorIconAction(R.drawable.ic_editor_undo, stringResource(R.string.photo_editor_undo), state.canUndo, holder::undo)
                EditorIconAction(R.drawable.ic_editor_redo, stringResource(R.string.photo_editor_redo), state.canRedo, holder::redo)
                PhotoEditorQualityAction(state.quality, !saving, onQuality)
            }
        },
        actions = {
            EditorIconAction(
                R.drawable.ic_check,
                stringResource(if (saving) R.string.photo_editor_saving_action else R.string.save),
                !saving,
                onSave,
            )
        },
        overflow = {
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
                        enabled = holder.hasUnsavedChanges && !saving,
                        modifier = Modifier.semantics { contentDescription = resetLabel },
                        onClick = {
                            expanded = false
                            holder.reset()
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
        },
    )
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
        Column(modifier) {
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                Row(Modifier.fillMaxWidth()) {
                    PhotoEditorTool.entries.take(2).forEach { tool ->
                        EditorToolButton(toolLabel(tool), toolIcon(tool), state.activeTool == tool, !state.isSaving, Modifier.weight(1f)) {
                            stateHolder.selectTool(tool)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    val tool = PhotoEditorTool.Erase
                    EditorToolButton(toolLabel(tool), toolIcon(tool), state.activeTool == tool, !state.isSaving, Modifier.weight(1f)) {
                        stateHolder.selectTool(tool)
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
            Column(Modifier.weight(1f).fillMaxWidth().fadingVerticalScroll(rememberScrollState())) {
                EditorOptions(state, stateHolder, vertical = true)
                EditorIconAction(R.drawable.ic_tune, coordinateDialogTitle(state.activeTool), !state.isSaving, onCoordinates)
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

/**
 * Output quality beside the history actions. The chip shows only the level; its accessible name keeps
 * the full "Photo quality: level" label. At large font scales the level text shrinks to fit the app
 * bar instead of clipping.
 */
@Composable
private fun PhotoEditorQualityAction(
    quality: MediaQuality,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val level = photoQualityLevelLabel(quality)
    val label = stringResource(R.string.photo_editor_quality) + ": " + level
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier =
            Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = label }
                .testTag("photo.editor.quality"),
    ) {
        Text(
            level,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = LocalTextStyle.current.fontSize),
            modifier = Modifier.weight(1f, fill = false),
        )
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
    val handleRadiusPx = with(density) { 40.dp.toPx() }
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
                        orientationLock = null,
                        onDragStart = { down, slopTrigger, _ ->
                            // Select from the original press, before touch slop can move it away.
                            if (cropMode) {
                                val position = down.position
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
                                val position = slopTrigger.position
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
                        onDragEnd = { _ ->
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

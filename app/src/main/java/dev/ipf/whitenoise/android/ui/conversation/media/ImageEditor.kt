@file:Suppress("FunctionNaming", "TooManyFunctions")

package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

private const val CROP_HANDLE_RADIUS_DP = 9f
private const val CROP_HANDLE_HIT_RADIUS_DP = 28f
internal val ImageEditorGestureExclusionKey = SemanticsPropertyKey<Boolean>("ImageEditorGestureExclusion")
private var SemanticsPropertyReceiver.imageEditorGestureExclusion by ImageEditorGestureExclusionKey

/**
 * Keep producer ownership across a dispatcher handoff. If cancellation wins
 * after native work creates a resource but before the caller resumes, the
 * producer still has a reference and reclaims it non-cancellably.
 */
internal suspend fun <T : Any> createOwnedResource(
    workContext: CoroutineContext,
    create: suspend () -> T?,
    cleanup: (T) -> Unit,
): T? {
    var producerOwned: T? = null
    return try {
        withContext(workContext) {
            create().also { producerOwned = it }
        }.also { producerOwned = null }
    } finally {
        val abandoned = producerOwned
        if (abandoned != null) {
            withContext(NonCancellable + workContext) { cleanup(abandoned) }
        }
    }
}

internal suspend fun <T : Any> createSerializedOwnedResource(
    mutex: Mutex,
    workContext: CoroutineContext,
    create: suspend () -> T?,
    cleanup: (T) -> Unit,
): T? =
    mutex.withLock {
        createOwnedResource(
            workContext = workContext,
            create = create,
            cleanup = cleanup,
        )
    }

internal suspend fun <T : Any> releaseSerializedOwnedResource(
    mutex: Mutex,
    resource: T,
    workContext: CoroutineContext = Dispatchers.Default,
    cleanup: (T) -> Unit,
) {
    withContext(NonCancellable + workContext) {
        mutex.withLock { cleanup(resource) }
    }
}

private enum class ImageEditorTool { Crop, Draw }

private enum class CropCorner { TopLeft, TopRight, BottomLeft, BottomRight }

private sealed interface EditorDecodeState {
    data object Loading : EditorDecodeState

    data class Ready(
        val bitmap: Bitmap,
    ) : EditorDecodeState

    data object Failed : EditorDecodeState
}

@Composable
@Suppress("LongMethod")
internal fun ImageEditorScreen(
    uri: Uri,
    onDismiss: () -> Unit,
    onSaved: (Uri) -> Unit,
    onFailure: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val renderMutex = remember(uri) { Mutex() }
    var decodeState by remember(uri) { mutableStateOf<EditorDecodeState>(EditorDecodeState.Loading) }
    var saving by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val decoded =
            createOwnedResource(
                workContext = Dispatchers.IO,
                create = {
                    MediaPipeline.decodeSampledFromUri(
                        contentResolver = context.contentResolver,
                        uri = uri,
                        maxEdgePx = IMAGE_EDITOR_MAX_EDGE_PX,
                    )
                },
                cleanup = Bitmap::recycle,
            )
        if (decoded == null) {
            decodeState = EditorDecodeState.Failed
            return@LaunchedEffect
        }
        decodeState = EditorDecodeState.Ready(decoded)
        try {
            awaitCancellation()
        } finally {
            releaseSerializedOwnedResource(
                mutex = renderMutex,
                resource = decoded,
                cleanup = Bitmap::recycle,
            )
        }
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        when (val decoded = decodeState) {
            EditorDecodeState.Loading -> EditorLoadingContent()
            EditorDecodeState.Failed -> EditorFailureContent(onDismiss)
            is EditorDecodeState.Ready ->
                ImageEditorContent(
                    source = decoded.bitmap,
                    renderMutex = renderMutex,
                    enabled = !saving,
                    onCancel = onDismiss,
                    onSave = { state ->
                        if (saving) return@ImageEditorContent
                        saving = true
                        scope.launch {
                            var rendered: Bitmap? = null
                            var outputFile: java.io.File? = null
                            try {
                                rendered =
                                    createSerializedOwnedResource(
                                        mutex = renderMutex,
                                        workContext = Dispatchers.Default,
                                        create = { ImageEditorRenderer.render(decoded.bitmap, state) },
                                        cleanup = Bitmap::recycle,
                                    ) ?: run {
                                        onFailure()
                                        return@launch
                                    }
                                outputFile =
                                    createOwnedResource(
                                        workContext = Dispatchers.IO,
                                        create = { writeEditedBitmap(context.cacheDir, rendered) },
                                        cleanup = { file -> runCatching { file.delete() } },
                                    ) ?: run {
                                        onFailure()
                                        return@launch
                                    }
                                currentCoroutineContext().ensureActive()
                                val editedUri = fileProviderUri(context, outputFile)
                                onSaved(editedUri)
                                outputFile = null
                            } finally {
                                rendered?.recycle()
                                val abandoned = outputFile
                                if (abandoned != null) {
                                    withContext(NonCancellable + Dispatchers.IO) {
                                        runCatching { abandoned.delete() }
                                    }
                                }
                                saving = false
                            }
                        }
                    },
                )
        }
    }
}

@Composable
private fun EditorLoadingContent() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun EditorFailureContent(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.image_editor_decode_failed), color = Color.White)
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
    }
}

@Composable
@Suppress("LongMethod")
internal fun ImageEditorContent(
    source: Bitmap,
    onCancel: () -> Unit,
    onSave: (ImageEditState) -> Unit,
    enabled: Boolean = true,
    renderMutex: Mutex? = null,
) {
    val sessionRenderMutex = remember(source, renderMutex) { renderMutex ?: Mutex() }
    var history by remember(source) { mutableStateOf(ImageEditHistory()) }
    var tool by remember(source) { mutableStateOf(ImageEditorTool.Crop) }
    var selectedAspect by remember(source) { mutableStateOf(ImageCropAspect.Free) }
    var cropDraft by remember(source) { mutableStateOf(history.current.crop) }
    var penColor by remember(source) { mutableStateOf(Color.Red) }
    var strokeWidth by remember(source) { mutableFloatStateOf(0.018f) }
    var eraser by remember(source) { mutableStateOf(false) }
    var activePoints by remember(source) { mutableStateOf<List<NormalizedPoint>>(emptyList()) }
    var rendered by remember(source) { mutableStateOf<Bitmap?>(null) }
    var announcement by remember(source) { mutableStateOf("") }

    val undoLabel = stringResource(R.string.image_editor_undo)
    val redoLabel = stringResource(R.string.image_editor_redo)
    val resetLabel = stringResource(R.string.image_editor_reset)

    LaunchedEffect(history.current.crop) { cropDraft = history.current.crop }
    LaunchedEffect(source, history.current, tool) {
        var next: Bitmap? = null
        try {
            next =
                createSerializedOwnedResource(
                    mutex = sessionRenderMutex,
                    workContext = Dispatchers.Default,
                    create = {
                        val previewState =
                            if (tool == ImageEditorTool.Crop) {
                                ImageEditState(quarterTurns = history.current.quarterTurns)
                            } else {
                                history.current
                            }
                        ImageEditorRenderer.render(source, previewState)
                    },
                    cleanup = Bitmap::recycle,
                )
            currentCoroutineContext().ensureActive()
            rendered = next
            next = null
        } finally {
            next?.recycle()
        }
    }
    DisposableEffect(rendered) {
        val bitmap = rendered
        onDispose { bitmap?.recycle() }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel, enabled = enabled) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), tint = Color.White)
            }
            Text(
                text = stringResource(R.string.image_editor_title),
                modifier = Modifier.weight(1f),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = { onSave(history.current) }, enabled = enabled) {
                Text(stringResource(R.string.save))
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            val bitmap = rendered
            if (bitmap == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            } else {
                ImageEditorCanvas(
                    bitmap = bitmap.asImageBitmap(),
                    tool = tool,
                    crop = cropDraft,
                    committedCrop = history.current.crop,
                    activePoints = activePoints,
                    penColor = penColor,
                    eraser = eraser,
                    enabled = enabled,
                    onCropChanged = {
                        cropDraft = it
                        selectedAspect = ImageCropAspect.Free
                    },
                    onCropCommitted = {
                        history = history.commit(history.current.withCrop(it))
                    },
                    onActivePointsChanged = { activePoints = it },
                    onStrokeCommitted = { points ->
                        val stroke =
                            EditorStroke.bounded(
                                points = points,
                                colorArgb = penColor.toArgb(),
                                widthFraction = strokeWidth,
                                eraser = eraser,
                            )
                        history = history.commit(history.current.addStroke(stroke))
                        activePoints = emptyList()
                    },
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorToolButton(
                    selected = tool == ImageEditorTool.Crop,
                    label = stringResource(R.string.image_editor_crop),
                    enabled = enabled,
                    onClick = { tool = ImageEditorTool.Crop },
                ) { Icon(Icons.Default.Crop, contentDescription = null) }
                EditorToolButton(
                    selected = tool == ImageEditorTool.Draw,
                    label = stringResource(R.string.image_editor_draw),
                    enabled = enabled,
                    onClick = { tool = ImageEditorTool.Draw },
                ) { Icon(Icons.Default.Draw, contentDescription = null) }
                EditorActionButton(
                    icon = { Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = null) },
                    label = stringResource(R.string.image_editor_rotate_left),
                    enabled = enabled,
                ) { history = history.commit(history.current.rotateLeft()) }
                EditorActionButton(
                    icon = { Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null) },
                    label = stringResource(R.string.image_editor_rotate_right),
                    enabled = enabled,
                ) { history = history.commit(history.current.rotateRight()) }
                EditorActionButton(
                    icon = { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null) },
                    label = undoLabel,
                    enabled = enabled && history.canUndo,
                ) {
                    history = history.undo()
                    announcement = nextEditorAnnouncement(undoLabel, announcement)
                }
                EditorActionButton(
                    icon = { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = null) },
                    label = redoLabel,
                    enabled = enabled && history.canRedo,
                ) {
                    history = history.redo()
                    announcement = nextEditorAnnouncement(redoLabel, announcement)
                }
                EditorActionButton(
                    icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                    label = resetLabel,
                    enabled = enabled && history.current != ImageEditState(),
                ) {
                    history = history.reset()
                    selectedAspect = ImageCropAspect.Free
                    announcement = nextEditorAnnouncement(resetLabel, announcement)
                }
            }

            if (tool == ImageEditorTool.Crop) {
                AspectControls(
                    selected = selectedAspect,
                    enabled = enabled,
                    onSelect = { aspect ->
                        selectedAspect = aspect
                        if (aspect != ImageCropAspect.Free) {
                            val crop =
                                cropRectForAspect(
                                    sourceWidth = source.width,
                                    sourceHeight = source.height,
                                    quarterTurns = history.current.quarterTurns,
                                    aspect = aspect,
                                )
                            cropDraft = crop
                            history = history.commit(history.current.withCrop(crop))
                        }
                    },
                )
            } else {
                DrawingControls(
                    penColor = penColor,
                    strokeWidth = strokeWidth,
                    eraser = eraser,
                    enabled = enabled,
                    onColor = {
                        penColor = it
                        eraser = false
                    },
                    onWidth = { strokeWidth = it },
                    onEraser = { eraser = !eraser },
                )
            }
            Text(
                text = announcement,
                modifier = Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = Color.Transparent,
            )
        }
    }
}

@Composable
private fun EditorToolButton(
    selected: Boolean,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .size(48.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                .semantics {
                    contentDescription = label
                    this.selected = selected
                },
    ) {
        Box(modifier = Modifier.semantics(mergeDescendants = true) {}, contentAlignment = Alignment.Center) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides
                    if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
            ) { icon() }
        }
    }
}

@Composable
private fun EditorActionButton(
    icon: @Composable () -> Unit,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides if (enabled) Color.White else Color.Gray,
        ) { icon() }
    }
}

@Composable
private fun AspectControls(
    selected: ImageCropAspect,
    enabled: Boolean,
    onSelect: (ImageCropAspect) -> Unit,
) {
    val labels =
        listOf(
            ImageCropAspect.Free to stringResource(R.string.image_editor_aspect_free),
            ImageCropAspect.Square to stringResource(R.string.image_editor_aspect_square),
            ImageCropAspect.FourThree to stringResource(R.string.image_editor_aspect_four_three),
            ImageCropAspect.SixteenNine to stringResource(R.string.image_editor_aspect_sixteen_nine),
        )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEach { (aspect, label) ->
            FilterChip(
                selected = selected == aspect,
                onClick = { onSelect(aspect) },
                enabled = enabled,
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun DrawingControls(
    penColor: Color,
    strokeWidth: Float,
    eraser: Boolean,
    enabled: Boolean,
    onColor: (Color) -> Unit,
    onWidth: (Float) -> Unit,
    onEraser: () -> Unit,
) {
    val widthLabel = stringResource(R.string.image_editor_width)
    val colors =
        listOf(
            Color.Red to stringResource(R.string.image_editor_color_red),
            Color.Yellow to stringResource(R.string.image_editor_color_yellow),
            Color.Green to stringResource(R.string.image_editor_color_green),
            Color.Blue to stringResource(R.string.image_editor_color_blue),
            Color.White to stringResource(R.string.image_editor_color_white),
        )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEach { (color, label) ->
            IconButton(
                onClick = { onColor(color) },
                enabled = enabled,
                modifier =
                    Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = label
                            selected = !eraser && penColor == color
                        },
            ) {
                Box(
                    Modifier
                        .size(if (!eraser && penColor == color) 30.dp else 24.dp)
                        .background(color, CircleShape),
                )
            }
        }
        EditorToolButton(
            selected = eraser,
            label = stringResource(R.string.image_editor_eraser),
            enabled = enabled,
            onClick = onEraser,
        ) { Icon(Icons.Default.Brush, contentDescription = null) }
        Slider(
            value = strokeWidth,
            onValueChange = onWidth,
            valueRange = 0.006f..0.06f,
            enabled = enabled,
            modifier =
                Modifier.size(width = 140.dp, height = 48.dp).semantics {
                    contentDescription = widthLabel
                },
        )
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun ImageEditorCanvas(
    bitmap: ImageBitmap,
    tool: ImageEditorTool,
    crop: NormalizedRect,
    committedCrop: NormalizedRect,
    activePoints: List<NormalizedPoint>,
    penColor: Color,
    eraser: Boolean,
    enabled: Boolean,
    onCropChanged: (NormalizedRect) -> Unit,
    onCropCommitted: (NormalizedRect) -> Unit,
    onActivePointsChanged: (List<NormalizedPoint>) -> Unit,
    onStrokeCommitted: (List<NormalizedPoint>) -> Unit,
) {
    val hint =
        stringResource(
            if (tool == ImageEditorTool.Crop) R.string.image_editor_crop_hint else R.string.image_editor_draw_hint,
        )
    val latestCrop by rememberUpdatedState(crop)
    val latestCommittedCrop by rememberUpdatedState(committedCrop)
    Canvas(
        modifier =
            Modifier
                .fillMaxSize()
                .systemGestureExclusion()
                .semantics {
                    contentDescription = hint
                    imageEditorGestureExclusion = true
                }.pointerInput(bitmap.width, bitmap.height, tool, enabled) {
                    if (!enabled) return@pointerInput
                    var activeCorner: CropCorner? = null
                    var gestureCrop = crop
                    var gesturePoints = emptyList<NormalizedPoint>()
                    detectDragGestures(
                        onDragStart = { position ->
                            val imageRect =
                                fittedImageRect(
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                    bitmap.width,
                                    bitmap.height,
                                )
                            if (tool == ImageEditorTool.Crop) {
                                gestureCrop = latestCrop
                                activeCorner =
                                    closestCropCorner(
                                        point = position,
                                        imageRect = imageRect,
                                        crop = gestureCrop,
                                        hitRadiusPx = CROP_HANDLE_HIT_RADIUS_DP * density,
                                    )
                            } else {
                                gesturePoints = emptyList()
                                normalizedPoint(position, imageRect)?.let {
                                    gesturePoints = appendGesturePoint(gesturePoints, it)
                                    onActivePointsChanged(gesturePoints)
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val imageRect =
                                fittedImageRect(
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                    bitmap.width,
                                    bitmap.height,
                                )
                            val point = normalizedPoint(change.position, imageRect) ?: return@detectDragGestures
                            if (tool == ImageEditorTool.Crop) {
                                activeCorner?.let { corner ->
                                    gestureCrop = moveCropCorner(gestureCrop, corner, point)
                                    onCropChanged(gestureCrop)
                                }
                            } else {
                                gesturePoints = appendGesturePoint(gesturePoints, point)
                                onActivePointsChanged(gesturePoints)
                            }
                        },
                        onDragEnd = {
                            if (tool == ImageEditorTool.Crop) {
                                if (activeCorner != null && gestureCrop != latestCommittedCrop) {
                                    onCropCommitted(gestureCrop)
                                }
                            } else if (gesturePoints.isNotEmpty()) {
                                onStrokeCommitted(gesturePoints)
                            }
                            gesturePoints = emptyList()
                            activeCorner = null
                        },
                        onDragCancel = {
                            if (tool == ImageEditorTool.Crop) {
                                onCropChanged(latestCommittedCrop)
                            } else {
                                onActivePointsChanged(emptyList())
                            }
                            gesturePoints = emptyList()
                            activeCorner = null
                        },
                    )
                },
    ) {
        val imageRect = fittedImageRect(size.width, size.height, bitmap.width, bitmap.height)
        drawRect(Color.White, imageRect.topLeft, imageRect.size)
        drawImage(
            image = bitmap,
            dstOffset =
                androidx.compose.ui.unit
                    .IntOffset(imageRect.left.toInt(), imageRect.top.toInt()),
            dstSize =
                androidx.compose.ui.unit
                    .IntSize(imageRect.width.toInt(), imageRect.height.toInt()),
        )
        if (tool == ImageEditorTool.Crop) {
            drawCropOverlay(imageRect, crop)
        } else if (activePoints.isNotEmpty()) {
            val path = Path()
            activePoints.forEachIndexed { index, point ->
                val position =
                    Offset(
                        imageRect.left + point.x * imageRect.width,
                        imageRect.top + point.y * imageRect.height,
                    )
                if (index == 0) path.moveTo(position.x, position.y) else path.lineTo(position.x, position.y)
            }
            drawPath(
                path = path,
                color = if (eraser) Color.White.copy(alpha = 0.65f) else penColor,
                style = Stroke(width = 4f, cap = StrokeCap.Round),
            )
        }
    }
}

private fun fittedImageRect(
    canvasWidth: Float,
    canvasHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): Rect {
    val canvasIsEmpty = canvasWidth <= 0f || canvasHeight <= 0f
    val imageIsEmpty = imageWidth <= 0 || imageHeight <= 0
    if (canvasIsEmpty || imageIsEmpty) return Rect.Zero
    val scale = minOf(canvasWidth / imageWidth, canvasHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (canvasWidth - width) / 2f
    val top = (canvasHeight - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun normalizedPoint(
    point: Offset,
    imageRect: Rect,
): NormalizedPoint? {
    if (imageRect.width <= 0f || imageRect.height <= 0f || !imageRect.contains(point)) return null
    return NormalizedPoint(
        x = ((point.x - imageRect.left) / imageRect.width).coerceIn(0f, 1f),
        y = ((point.y - imageRect.top) / imageRect.height).coerceIn(0f, 1f),
    )
}

private fun closestCropCorner(
    point: Offset,
    imageRect: Rect,
    crop: NormalizedRect,
    hitRadiusPx: Float,
): CropCorner? {
    val corners =
        mapOf(
            CropCorner.TopLeft to Offset(crop.left, crop.top),
            CropCorner.TopRight to Offset(crop.right, crop.top),
            CropCorner.BottomLeft to Offset(crop.left, crop.bottom),
            CropCorner.BottomRight to Offset(crop.right, crop.bottom),
        )
    return corners
        .mapValues { (_, normalized) ->
            val actual =
                Offset(
                    imageRect.left + normalized.x * imageRect.width,
                    imageRect.top + normalized.y * imageRect.height,
                )
            (actual - point).getDistance()
        }.minByOrNull { it.value }
        ?.takeIf { it.value <= hitRadiusPx }
        ?.key
}

private fun moveCropCorner(
    crop: NormalizedRect,
    corner: CropCorner,
    point: NormalizedPoint,
): NormalizedRect =
    when (corner) {
        CropCorner.TopLeft -> crop.copy(left = point.x, top = point.y)
        CropCorner.TopRight -> crop.copy(right = point.x, top = point.y)
        CropCorner.BottomLeft -> crop.copy(left = point.x, bottom = point.y)
        CropCorner.BottomRight -> crop.copy(right = point.x, bottom = point.y)
    }.bounded()

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropOverlay(
    imageRect: Rect,
    crop: NormalizedRect,
) {
    val cropRect =
        Rect(
            left = imageRect.left + crop.left * imageRect.width,
            top = imageRect.top + crop.top * imageRect.height,
            right = imageRect.left + crop.right * imageRect.width,
            bottom = imageRect.top + crop.bottom * imageRect.height,
        )
    val shade = Color.Black.copy(alpha = 0.6f)
    drawRect(
        shade,
        imageRect.topLeft,
        Size(imageRect.width, (cropRect.top - imageRect.top).coerceAtLeast(0f)),
    )
    drawRect(
        shade,
        Offset(imageRect.left, cropRect.bottom),
        Size(imageRect.width, (imageRect.bottom - cropRect.bottom).coerceAtLeast(0f)),
    )
    drawRect(
        shade,
        Offset(imageRect.left, cropRect.top),
        Size((cropRect.left - imageRect.left).coerceAtLeast(0f), cropRect.height),
    )
    drawRect(
        shade,
        Offset(cropRect.right, cropRect.top),
        Size((imageRect.right - cropRect.right).coerceAtLeast(0f), cropRect.height),
    )
    drawRect(Color.White, cropRect.topLeft, cropRect.size, style = Stroke(width = 3f))
    val handleRadius = CROP_HANDLE_RADIUS_DP * density
    listOf(cropRect.topLeft, cropRect.topRight, cropRect.bottomLeft, cropRect.bottomRight).forEach { corner ->
        drawCircle(Color.White, radius = handleRadius, center = corner)
        drawCircle(Color.Black, radius = handleRadius / 2f, center = corner)
    }
}

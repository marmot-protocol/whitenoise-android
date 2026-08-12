package dev.ipf.whitenoise.android.ui.profile

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.AvatarDragDismissResult
import dev.ipf.whitenoise.android.ui.common.AvatarDragDismissState
import dev.ipf.whitenoise.android.ui.common.SwipeDismissibleSnackbar
import dev.ipf.whitenoise.android.ui.common.VIEWER_MIN_SCALE
import dev.ipf.whitenoise.android.ui.common.ViewerTransform
import dev.ipf.whitenoise.android.ui.common.applyAvatarDownwardDrag
import dev.ipf.whitenoise.android.ui.common.applyViewerTransformGesture
import dev.ipf.whitenoise.android.ui.common.resetViewerTransform
import dev.ipf.whitenoise.android.ui.common.viewerOneToOneScale
import dev.ipf.whitenoise.android.ui.conversation.media.saveImageToGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val AVATAR_VIEWER_MAX_BYTES = 8 * 1024 * 1024

@Composable
internal fun rememberAvatarImageAvailable(pictureUrl: String?): Boolean {
    val available by produceState(
        initialValue = pictureUrl?.let { AvatarImageLoader.peek(it) != null } == true,
        key1 = pictureUrl,
    ) {
        value = pictureUrl?.let { AvatarImageLoader.load(it) != null } == true
    }
    return available
}

@Composable
internal fun AvatarFullScreenViewer(
    title: String,
    seed: String,
    pictureUrl: String? = null,
    picture: ImageBitmap? = null,
    onDismiss: () -> Unit,
    editActionLabel: String? = null,
    onEditPicture: (() -> Unit)? = null,
    securePolicy: SecureFlagPolicy = SecureFlagPolicy.Inherit,
) {
    val safePictureUrl = remember(pictureUrl) { ProfileSanitizer.imageUrl(pictureUrl) }
    if (safePictureUrl == null && picture == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.media_saved)
    val saveFailedMessage = stringResource(R.string.media_save_failed)
    val fileName = remember(safePictureUrl) { safePictureUrl?.let(::avatarViewerFileName) ?: "avatar.jpg" }
    var menuOpen by remember { mutableStateOf(false) }
    var scale by remember(safePictureUrl, picture) { mutableStateOf(1f) }
    var offset by remember(safePictureUrl, picture) { mutableStateOf(Offset.Zero) }
    val dismissThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }

    val imageState = rememberAvatarViewerImageState(safePictureUrl, picture)

    val remote = imageState as? AvatarViewerImageState.Remote
    val readyBitmap = remote?.bitmap
    DisposableEffect(readyBitmap) {
        onDispose { readyBitmap?.recycle() }
    }
    LaunchedEffect(imageState) {
        if (imageState is AvatarViewerImageState.Failed) onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                securePolicy = securePolicy,
            ),
    ) {
        AvatarViewerFrame(
            scale = scale,
            dismissThresholdPx = dismissThresholdPx,
            onDismiss = onDismiss,
            menuOpen = menuOpen,
            onMenuOpenChange = { menuOpen = it },
            saveEnabled = remote != null,
            editActionLabel = editActionLabel,
            onEditPicture = onEditPicture,
            onSave = {
                val bytes = remote?.bytes ?: return@AvatarViewerFrame
                scope.launch {
                    val ok =
                        withContext(Dispatchers.IO) {
                            saveImageToGallery(context, bytes, fileName, avatarViewerMimeType(bytes, fileName))
                        }
                    snackbarHostState.showSnackbar(if (ok) savedMessage else saveFailedMessage)
                }
            },
            snackbarHostState = snackbarHostState,
        ) {
            AvatarViewerImageContent(
                state = imageState,
                title = title,
                seed = seed,
                scale = scale,
                offset = offset,
                onScaleChange = { scale = it },
                onOffsetChange = { offset = it },
            )
        }
    }
}

@Composable
private fun rememberAvatarViewerImageState(
    safePictureUrl: String?,
    picture: ImageBitmap?,
): AvatarViewerImageState {
    val state by produceState<AvatarViewerImageState>(
        initialValue = picture?.let(AvatarViewerImageState::Local) ?: AvatarViewerImageState.Loading,
        safePictureUrl,
        picture,
    ) {
        if (picture != null) {
            value = AvatarViewerImageState.Local(picture)
            return@produceState
        }
        val remoteUrl = safePictureUrl
        if (remoteUrl == null) {
            value = AvatarViewerImageState.Failed
            return@produceState
        }
        value = AvatarViewerImageState.Loading
        val bytes =
            withContext(Dispatchers.IO) {
                runCatching {
                    AvatarImageLoader.fetchBytes(remoteUrl, AVATAR_VIEWER_MAX_BYTES)
                }.getOrNull()
            }
        val bitmap =
            bytes?.let { data ->
                withContext(Dispatchers.Default) {
                    MediaPipeline.decodeSampledBitmap(data, MediaPipeline.VIEWER_MAX_EDGE_PX)
                }
            }
        value =
            if (bytes != null && bitmap != null) {
                AvatarViewerImageState.Remote(bytes, bitmap)
            } else {
                AvatarViewerImageState.Failed
            }
    }
    return state
}

@Composable
@Suppress("FunctionNaming")
private fun BoxScope.AvatarViewerImageContent(
    state: AvatarViewerImageState,
    title: String,
    seed: String,
    scale: Float,
    offset: Offset,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
) {
    when (state) {
        AvatarViewerImageState.Loading ->
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        AvatarViewerImageState.Failed ->
            Avatar(
                title = title,
                seed = seed,
                size = 112.dp,
                pictureUrl = null,
            )
        is AvatarViewerImageState.Local ->
            ZoomableAvatarImage(
                image = state.image,
                bitmapWidth = state.image.width,
                bitmapHeight = state.image.height,
                contentDescription = title,
                scale = scale,
                offset = offset,
                onScaleChange = onScaleChange,
                onOffsetChange = onOffsetChange,
                modifier = Modifier.fillMaxSize(),
            )
        is AvatarViewerImageState.Remote -> {
            val image = remember(state.bitmap) { state.bitmap.asImageBitmap() }
            ZoomableAvatarImage(
                image = image,
                bitmapWidth = state.bitmap.width,
                bitmapHeight = state.bitmap.height,
                contentDescription = title,
                scale = scale,
                offset = offset,
                onScaleChange = onScaleChange,
                onOffsetChange = onOffsetChange,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun AvatarViewerFrame(
    scale: Float,
    dismissThresholdPx: Float,
    onDismiss: () -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    saveEnabled: Boolean,
    editActionLabel: String?,
    onEditPicture: (() -> Unit)?,
    onSave: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    body: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(scale, dismissThresholdPx) {
                    if (scale <= VIEWER_MIN_SCALE) {
                        var draggedDown = 0f
                        detectVerticalDragGestures(
                            onDragEnd = { draggedDown = 0f },
                            onDragCancel = { draggedDown = 0f },
                            onVerticalDrag = { change, dragAmount ->
                                when (
                                    val result =
                                        applyAvatarDownwardDrag(
                                            scale = scale,
                                            state = AvatarDragDismissState(draggedDown),
                                            dragAmount = dragAmount,
                                            dismissThresholdPx = dismissThresholdPx,
                                        )
                                ) {
                                    AvatarDragDismissResult.Ignored -> Unit
                                    is AvatarDragDismissResult.Tracking -> {
                                        draggedDown = result.state.draggedDownPx
                                        change.consume()
                                    }
                                    is AvatarDragDismissResult.Dismiss -> {
                                        draggedDown = result.state.draggedDownPx
                                        change.consume()
                                        onDismiss()
                                    }
                                }
                            },
                        )
                    }
                },
    ) {
        body()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
            }
            Box {
                IconButton(onClick = { onMenuOpenChange(true) }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.actions),
                        tint = Color.White,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenuOpenChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.media_save)) },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        enabled = saveEnabled,
                        onClick = {
                            onMenuOpenChange(false)
                            onSave()
                        },
                    )
                    if (editActionLabel != null && onEditPicture != null) {
                        DropdownMenuItem(
                            text = { Text(editActionLabel) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                onMenuOpenChange(false)
                                onEditPicture()
                            },
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            snackbar = { SwipeDismissibleSnackbar(it) },
        )
    }
}

@Composable
private fun ZoomableAvatarImage(
    image: ImageBitmap,
    bitmapWidth: Int,
    bitmapHeight: Int,
    contentDescription: String,
    scale: Float,
    offset: Offset,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestScale by rememberUpdatedState(scale)
    val latestOffset by rememberUpdatedState(offset)
    val latestOnScaleChange by rememberUpdatedState(onScaleChange)
    val latestOnOffsetChange by rememberUpdatedState(onOffsetChange)

    Image(
        bitmap = image,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier =
            modifier
                .pointerInput(image) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (latestScale > VIEWER_MIN_SCALE) {
                                val reset = resetViewerTransform()
                                latestOnScaleChange(reset.scale)
                                latestOnOffsetChange(reset.offset)
                            } else {
                                val viewportW = size.width.toFloat()
                                val viewportH = size.height.toFloat()
                                val oneToOneScale =
                                    viewerOneToOneScale(
                                        viewportWidth = viewportW,
                                        viewportHeight = viewportH,
                                        imageWidth = bitmapWidth,
                                        imageHeight = bitmapHeight,
                                    )
                                latestOnScaleChange(oneToOneScale)
                                latestOnOffsetChange(Offset.Zero)
                            }
                        },
                    )
                }.pointerInput(image, bitmapWidth, bitmapHeight) {
                    awaitEachGesture {
                        do {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }
                            if (pressedCount == 0) break
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val currentScale = latestScale
                            val currentOffset = latestOffset
                            val handleAsTransform = pressedCount >= 2 || currentScale > VIEWER_MIN_SCALE
                            if (!handleAsTransform) continue

                            val next =
                                applyViewerTransformGesture(
                                    current = ViewerTransform(currentScale, currentOffset),
                                    zoomFactor = zoom,
                                    panDelta = pan,
                                    viewportWidth = size.width.toFloat(),
                                    viewportHeight = size.height.toFloat(),
                                    imageWidth = bitmapWidth,
                                    imageHeight = bitmapHeight,
                                )
                            if (next.scale != currentScale) latestOnScaleChange(next.scale)
                            if (next.offset != currentOffset) latestOnOffsetChange(next.offset)
                            event.changes.forEach { it.consume() }
                        } while (true)
                    }
                }.graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
    )
}

private fun avatarViewerFileName(url: String): String {
    val parsed = runCatching { android.net.Uri.parse(url) }.getOrNull()
    val lastSegment =
        parsed
            ?.lastPathSegment
            ?.takeIf { it.isNotBlank() }
            ?: "avatar.jpg"
    val safe = MediaPipeline.safeDisplayName(lastSegment)
    return if (safe.contains('.')) safe else "$safe.jpg"
}

// Sniff the real content type from the bytes being saved — the URL filename is
// only a fallback, since extensionless or mislabeled URLs would otherwise store
// PNG/WebP bytes under JPEG metadata in MediaStore.
private fun avatarViewerMimeType(
    bytes: ByteArray,
    fallbackFileName: String,
): String {
    val options =
        android.graphics.BitmapFactory
            .Options()
            .apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    return options.outMimeType ?: avatarViewerMimeType(fallbackFileName)
}

private fun avatarViewerMimeType(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        else -> MediaPipeline.RECOMPRESSED_MIME
    }

private sealed interface AvatarViewerImageState {
    data object Loading : AvatarViewerImageState

    data object Failed : AvatarViewerImageState

    data class Local(
        val image: ImageBitmap,
    ) : AvatarViewerImageState

    data class Remote(
        val bytes: ByteArray,
        val bitmap: Bitmap,
    ) : AvatarViewerImageState
}

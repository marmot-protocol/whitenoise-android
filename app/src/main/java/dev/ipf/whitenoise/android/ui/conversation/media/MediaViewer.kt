package dev.ipf.whitenoise.android.ui.conversation.media

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.MediaReferenceParser
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Resolve the decrypted bytes for an attachment, preferring the retained
 * plaintext in `pendingAttachmentsList` for own optimistic sends so the
 * viewer / save / share paths don't spin while waiting for the projection
 * to reconcile. Falls back to the standard FFI download for everything else.
 */
private suspend fun attachmentBytes(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
): ByteArray {
    if (mine) {
        controller
            .pendingAttachmentsList(messageIdHex)
            .getOrNull(attachmentIndex)
            ?.plaintextBytes
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
    }
    return controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
}

// One page of the full-screen media viewer. Unlike the original single-album
// viewer (one fixed messageIdHex + mine for the whole pager), each page now
// carries its own message context so the pager can span attachments from
// different messages — the cross-message gallery the shared-media grids open.
// The save/share/decrypt paths read the CURRENT page's descriptor.
internal data class MediaViewerPage(
    val messageIdHex: String,
    val attachmentIndex: Int,
    val reference: MediaAttachmentReferenceFfi,
    val mine: Boolean,
    val sender: String,
    val recordedAt: ULong,
)

// Album wrapper preserving the original call shape: a single message's
// attachments, one `mine` flag. The three conversation bubble callsites use
// this; it just projects the album onto per-page descriptors.
@Composable
internal fun FullScreenImageViewer(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    messageIdHex: String,
    attachments: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    startIndex: Int,
    onDismiss: () -> Unit,
    sender: String,
    recordedAt: ULong,
    mine: Boolean = false,
) {
    val pages =
        remember(messageIdHex, attachments, mine, sender, recordedAt) {
            attachments.map { entry ->
                MediaViewerPage(messageIdHex, entry.index, entry.value, mine, sender, recordedAt)
            }
        }
    FullScreenMediaViewer(
        controller = controller,
        appState = appState,
        pages = pages,
        startIndex = startIndex,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun FullScreenMediaViewer(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    pages: List<MediaViewerPage>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    if (pages.isEmpty()) {
        // Defensive — callers shouldn't open an empty viewer, but guard so the
        // pager doesn't NPE on a vanished album.
        onDismiss()
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.media_saved)
    val saveFailedMessage = stringResource(R.string.media_save_failed)
    val pagerState =
        rememberPagerState(
            initialPage = startIndex.coerceIn(0, pages.lastIndex),
            pageCount = { pages.size },
        )
    // pagerState outlives a shrinking pages list (album reconcile): currentPage
    // isn't re-clamped to the new lastIndex for a frame, so clamp at the read.
    val currentPage = pages[pagerState.currentPage.coerceIn(0, pages.lastIndex)]
    val currentReference = currentPage.reference
    val currentAttachmentIndex = currentPage.attachmentIndex
    val currentMessageIdHex = currentPage.messageIdHex
    val currentMine = currentPage.mine
    // Zoom state is hoisted to the viewer scope (not per-page) so the pager
    // can read it to gate horizontal swipe. Without this gate, the page's
    // `detectTransformGestures` claims every horizontal drag and the pager
    // never moves. Page change resets to identity below.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // Disable pager swipe while the visible page is zoomed in —
                // otherwise the pan gesture and the pager's swipe both want
                // the horizontal drag. At scale 1× the pager wins.
                userScrollEnabled = scale <= 1f,
            ) { page ->
                val pageDescriptor = pages[page.coerceIn(0, pages.lastIndex)]
                if (MediaReferenceParser.isVideoMedia(pageDescriptor.reference)) {
                    VideoViewerPage(
                        controller = controller,
                        messageIdHex = pageDescriptor.messageIdHex,
                        attachmentIndex = pageDescriptor.attachmentIndex,
                        reference = pageDescriptor.reference,
                        isCurrent = page == pagerState.currentPage,
                        mine = pageDescriptor.mine,
                    )
                } else {
                    ViewerPage(
                        controller = controller,
                        messageIdHex = pageDescriptor.messageIdHex,
                        attachmentIndex = pageDescriptor.attachmentIndex,
                        reference = pageDescriptor.reference,
                        scale = if (page == pagerState.currentPage) scale else 1f,
                        offset = if (page == pagerState.currentPage) offset else Offset.Zero,
                        onScaleChange = { if (page == pagerState.currentPage) scale = it },
                        onOffsetChange = { if (page == pagerState.currentPage) offset = it },
                        mine = pageDescriptor.mine,
                    )
                }
            }
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
                if (pages.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${pages.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Row {
                    IconButton(
                        onClick = {
                            val ref = currentReference
                            val attachmentIndex = currentAttachmentIndex
                            val msgId = currentMessageIdHex
                            val owned = currentMine
                            scope.launch {
                                val data =
                                    runCatching {
                                        attachmentBytes(controller, msgId, attachmentIndex, ref, owned)
                                    }.getOrNull()
                                val ok =
                                    data != null &&
                                        withContext(Dispatchers.IO) {
                                            if (MediaReferenceParser.isVideoMedia(ref)) {
                                                saveVideoToGallery(context, data, ref.fileName, ref.mediaType)
                                            } else {
                                                saveImageToGallery(context, data, ref.fileName, ref.mediaType)
                                            }
                                        }
                                snackbarHostState.showSnackbar(if (ok) savedMessage else saveFailedMessage)
                            }
                        },
                    ) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.media_save), tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            val ref = currentReference
                            val attachmentIndex = currentAttachmentIndex
                            val msgId = currentMessageIdHex
                            val owned = currentMine
                            scope.launch {
                                runCatching {
                                    attachmentBytes(controller, msgId, attachmentIndex, ref, owned)
                                }.getOrNull()?.let { shareImage(context, it, ref.fileName, ref.mediaType) }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share), tint = Color.White)
                    }
                }
            }
            // Sender + send-time caption for the visible page, over a bottom
            // scrim so it stays readable on bright photos. Reads the current
            // page so it tracks swipes.
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                            ),
                        ).navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = appState.displayName(currentPage.sender),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        DateUtils.formatDateTime(
                            context,
                            currentPage.recordedAt.toLong() * 1000L,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
                        ),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
            )
        }
    }
}

@Composable
internal fun ViewerPage(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    scale: Float,
    offset: Offset,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    mine: Boolean,
) {
    // `pointerInput(pageKey)` only restarts when the key changes — its
    // coroutine outlives any single gesture. Function parameters
    // (`scale`, `offset`, the callbacks) captured directly inside that
    // coroutine would stay at their initial values for the lifetime of
    // the gesture, causing jumpy zoom/pan and stale callback dispatch.
    // `rememberUpdatedState` snapshots each parameter into a stable
    // State<T> whose `.value` reads inside the coroutine always reflect
    // the most recent recomposition's value.
    val latestScale by rememberUpdatedState(scale)
    val latestOffset by rememberUpdatedState(offset)
    val latestOnScaleChange by rememberUpdatedState(onScaleChange)
    val latestOnOffsetChange by rememberUpdatedState(onOffsetChange)
    // `sourceEpoch` is folded into the page key so a viewer that failed
    // its first decrypt at epoch 0 (typed reference not yet loaded) re-keys
    // and retries when the real reference arrives.
    val pageKey = "$messageIdHex#$attachmentIndex#${reference.sourceEpoch}"
    var presentation by remember(pageKey) { mutableStateOf<DecodedAttachmentPresentation?>(null) }
    var viewerFailed by remember(pageKey) { mutableStateOf(false) }
    var viewerReloadToken by remember(pageKey) { mutableStateOf(0) }
    val imageWidth =
        when (val current = presentation) {
            is DecodedAttachmentPresentation.Static -> current.bitmap.width
            is DecodedAttachmentPresentation.Animated -> current.drawable.intrinsicWidth
            null -> 0
        }
    val imageHeight =
        when (val current = presentation) {
            is DecodedAttachmentPresentation.Static -> current.bitmap.height
            is DecodedAttachmentPresentation.Animated -> current.drawable.intrinsicHeight
            null -> 0
        }
    LaunchedEffect(pageKey, viewerReloadToken) {
        viewerFailed = false
        try {
            val data = attachmentBytes(controller, messageIdHex, attachmentIndex, reference, mine)
            val decoded =
                decodeMessageAttachmentImage(
                    bytes = data,
                    mediaType = reference.mediaType,
                    staticMaxEdgePx = MediaPipeline.VIEWER_MAX_EDGE_PX,
                )
            if (decoded != null) {
                presentation = decoded
            } else {
                viewerFailed = true
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            viewerFailed = true
        }
    }
    DisposableEffect(pageKey, presentation) {
        val owned = presentation
        onDispose {
            when (owned) {
                is DecodedAttachmentPresentation.Static -> owned.bitmap.recycle()
                is DecodedAttachmentPresentation.Animated ->
                    (owned.drawable as? android.graphics.drawable.AnimatedImageDrawable)?.stop()
                null -> Unit
            }
        }
    }

    val viewerGestureModifier =
        Modifier
            .fillMaxSize()
            .pointerInput(pageKey) {
                detectTapGestures(onDoubleTap = {
                    latestOnScaleChange(1f)
                    latestOnOffsetChange(Offset.Zero)
                })
            }.pointerInput(pageKey) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount =
                            event.changes.count { it.pressed }
                        if (pressedCount == 0) break
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val currentScale = latestScale
                        val currentOffset = latestOffset
                        val handleAsTransform =
                            pressedCount >= 2 || currentScale > 1f
                        if (!handleAsTransform) {
                            continue
                        }
                        val nextScale = (currentScale * zoom).coerceIn(1f, 5f)
                        if (nextScale != currentScale) latestOnScaleChange(nextScale)
                        if (nextScale > 1f) {
                            val viewportW = size.width.toFloat()
                            val viewportH = size.height.toFloat()
                            val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
                            val viewportAspect = viewportW / viewportH
                            val baseWidth: Float
                            val baseHeight: Float
                            if (imageAspect > viewportAspect) {
                                baseWidth = viewportW
                                baseHeight = viewportW / imageAspect
                            } else {
                                baseHeight = viewportH
                                baseWidth = viewportH * imageAspect
                            }
                            val maxX = ((baseWidth * nextScale) - viewportW).coerceAtLeast(0f) / 2f
                            val maxY = ((baseHeight * nextScale) - viewportH).coerceAtLeast(0f) / 2f
                            latestOnOffsetChange(
                                Offset(
                                    (currentOffset.x + pan.x).coerceIn(-maxX, maxX),
                                    (currentOffset.y + pan.y).coerceIn(-maxY, maxY),
                                ),
                            )
                        } else if (currentOffset != Offset.Zero) {
                            latestOnOffsetChange(Offset.Zero)
                        }
                        event.changes.forEach { it.consume() }
                    } while (true)
                }
            }.graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            )

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = presentation) {
            is DecodedAttachmentPresentation.Static ->
                Image(
                    bitmap = current.toImageBitmap(),
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.Fit,
                    modifier = viewerGestureModifier,
                )
            is DecodedAttachmentPresentation.Animated ->
                AnimatedDrawableAttachmentImage(
                    drawable = current.drawable,
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.Fit,
                    modifier = viewerGestureModifier,
                )
            null ->
                when {
                    viewerFailed ->
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.BrokenImage,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                stringResource(R.string.media_save_failed),
                                color = Color.White,
                            )
                            TextButton(onClick = { viewerReloadToken += 1 }) {
                                Text(stringResource(R.string.media_tap_to_retry), color = Color.White)
                            }
                        }
                    else ->
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Color.White,
                        )
                }
        }
    }
}

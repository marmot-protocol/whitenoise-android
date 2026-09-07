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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.SwipeDismissibleSnackbar
import dev.ipf.whitenoise.android.ui.common.ViewerTransform
import dev.ipf.whitenoise.android.ui.common.applyViewerTransformGesture
import dev.ipf.whitenoise.android.ui.common.clampViewerPageIndex
import dev.ipf.whitenoise.android.ui.common.resetViewerTransform
import dev.ipf.whitenoise.android.ui.common.viewerPagerScrollEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Upgrade receive-side compatibility references before a transfer. Timeline
 * fallback records can temporarily carry sourceEpoch=0; passing that value to
 * MarmotKit can never succeed and previously left visual media spinning.
 */
internal suspend fun authoritativeVisualMediaReference(
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
    resolve: suspend () -> MediaAttachmentReferenceFfi,
): MediaAttachmentReferenceFfi =
    if (mine || reference.sourceEpoch != 0uL) {
        reference
    } else {
        resolve()
    }

/**
 * Resolve the decrypted bytes for an attachment, preferring the retained
 * plaintext in `pendingAttachmentsList` for own optimistic sends so the
 * viewer / save / share paths don't spin while waiting for the projection
 * to reconcile. Falls back to the standard FFI download for everything else.
 */
internal suspend fun attachmentBytes(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
    priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Interactive,
): ByteArray {
    if (mine) {
        controller
            .pendingAttachmentsList(messageIdHex)
            .getOrNull(attachmentIndex)
            ?.plaintextBytes
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
    }
    val resolvedReference =
        authoritativeVisualMediaReference(reference, mine) {
            controller.authoritativeAttachmentReference(messageIdHex, attachmentIndex, reference)
        }
    return controller.downloadAttachment(messageIdHex, attachmentIndex, resolvedReference, priority)
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

/** Exact visible-page identity handed to the external Android sharing boundary. */
internal data class MediaViewerShareRequest(
    val messageIdHex: String,
    val attachmentIndex: Int,
    val reference: MediaAttachmentReferenceFfi,
    val mine: Boolean,
)

internal data class MediaViewerGallery(
    val pages: List<MediaViewerPage>,
    val startIndex: Int,
)

private data class MediaViewerPageKey(
    val messageIdHex: String,
    val attachmentIndex: Int,
)

private fun MediaViewerPage.key(): MediaViewerPageKey = MediaViewerPageKey(messageIdHex, attachmentIndex)

private fun MediaViewerPage.saveableKey(): String = "${messageIdHex.length}:$messageIdHex:$attachmentIndex"

internal data class MediaViewerPagerSelection(
    val pagerState: PagerState,
    val currentPageIndex: Int,
    val currentPage: MediaViewerPage,
)

/** Preserves the selected logical attachment while an authoritative gallery replaces its references. */
@Composable
internal fun rememberMediaViewerPagerSelection(
    pages: List<MediaViewerPage>,
    startIndex: Int,
): MediaViewerPagerSelection {
    require(pages.isNotEmpty()) { "Media viewer pages must not be empty" }
    val initialPageIndex = clampViewerPageIndex(startIndex, pages.size)
    var visiblePageKey by remember { mutableStateOf(pages[initialPageIndex].key()) }
    var visiblePageIndex by remember { mutableIntStateOf(initialPageIndex) }
    val preservedIndex = pages.indexOfFirst { it.key() == visiblePageKey }
    val restoredIndex =
        if (preservedIndex >= 0) {
            preservedIndex
        } else {
            clampViewerPageIndex(visiblePageIndex, pages.size)
        }
    // Shared-media projection is asynchronous. Recreate the pager at the
    // preserved attachment as part of the same composition where the fallback
    // list expands, so neither content nor metadata can bind to the old numeric
    // index for a frame.
    val pagerState =
        key(pages) {
            rememberPagerState(
                initialPage = restoredIndex,
                pageCount = { pages.size },
            )
        }
    LaunchedEffect(pagerState, pages) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val settledIndex = clampViewerPageIndex(page, pages.size)
                visiblePageIndex = settledIndex
                visiblePageKey = pages[settledIndex].key()
            }
    }
    val pagerPageIndex = clampViewerPageIndex(pagerState.currentPage, pages.size)
    val currentPageIndex =
        pages
            .indexOfFirst { it.key() == visiblePageKey }
            .takeIf { it >= 0 }
            ?: pagerPageIndex
    return MediaViewerPagerSelection(
        pagerState = pagerState,
        currentPageIndex = currentPageIndex,
        currentPage = pages[currentPageIndex],
    )
}

/** Renders pager pages with stable logical keys and exposes the settled page to accessibility. */
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
@Composable
internal fun StableMediaViewerPager(
    pages: List<MediaViewerPage>,
    selection: MediaViewerPagerSelection,
    modifier: Modifier,
    pagePositionDescription: String?,
    userScrollEnabled: Boolean,
    pageContent: @Composable (page: MediaViewerPage, isCurrent: Boolean) -> Unit,
) {
    val currentPageKey = selection.currentPage.key()
    val pagerModifier =
        if (pagePositionDescription == null) {
            modifier
        } else {
            modifier.semantics { stateDescription = pagePositionDescription }
        }
    HorizontalPager(
        state = selection.pagerState,
        modifier = pagerModifier,
        key = { page -> pages[clampViewerPageIndex(page, pages.size)].saveableKey() },
        userScrollEnabled = userScrollEnabled,
    ) { page ->
        val pageDescriptor = pages[clampViewerPageIndex(page, pages.size)]
        pageContent(pageDescriptor, pageDescriptor.key() == currentPageKey)
    }
}

/**
 * Select the gallery opened by an inline visual attachment.
 *
 * Visual messages use the conversation's shared-media image/video order
 * (newest first). The current message pages are merged when the asynchronous
 * shared projection has not caught up yet, which keeps optimistic own sends
 * openable.
 */
internal fun visualMediaViewerGallery(
    conversationVisualPages: List<MediaViewerPage>,
    messagePages: List<MediaViewerPage>,
    tappedAttachmentIndex: Int,
): MediaViewerGallery {
    val tappedPage =
        messagePages.firstOrNull { it.attachmentIndex == tappedAttachmentIndex }
            ?: messagePages.firstOrNull()
    return when {
        tappedPage == null -> MediaViewerGallery(conversationVisualPages, 0)
        else -> {
            val currentMessageId = tappedPage.messageIdHex
            val projectedKeys = conversationVisualPages.mapTo(HashSet()) { it.messageIdHex to it.attachmentIndex }
            val currentMessageFullyProjected =
                messagePages.all { (it.messageIdHex to it.attachmentIndex) in projectedKeys }
            val pages =
                if (currentMessageFullyProjected) {
                    conversationVisualPages
                } else {
                    // buildTiles() reverses the flattened timeline projection, so an
                    // album's attachment order is reversed in the shared-media grid.
                    val currentPages = messagePages.asReversed()
                    val otherPages = conversationVisualPages.filterNot { it.messageIdHex == currentMessageId }
                    val insertAt =
                        otherPages
                            .indexOfFirst { it.recordedAt < tappedPage.recordedAt }
                            .takeIf { it >= 0 }
                            ?: otherPages.size
                    otherPages.subList(0, insertAt) + currentPages + otherPages.subList(insertAt, otherPages.size)
                }
            val startIndex =
                pages
                    .indexOfFirst {
                        it.messageIdHex == currentMessageId && it.attachmentIndex == tappedAttachmentIndex
                    }.coerceAtLeast(0)
            MediaViewerGallery(pages, startIndex)
        }
    }
}

/**
 * Resolves an inline attachment into the conversation-wide gallery while preserving the
 * caller-owned logical selection across reference refreshes and viewport changes.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ConversationMediaViewer(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    conversationVisualPages: List<MediaViewerPage>,
    messageIdHex: String,
    attachments: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    tappedAttachmentIndex: Int,
    sender: String,
    recordedAt: ULong,
    mine: Boolean,
    onDismiss: () -> Unit,
    onShareRequest: (suspend (MediaViewerShareRequest) -> Result<Unit>)? = null,
    selectedAttachment: ConversationMediaViewerAttachmentId? = null,
    onSelectedAttachmentChange: (ConversationMediaViewerAttachmentId) -> Unit = {},
    onVideoPlayerChanged: (androidx.media3.exoplayer.ExoPlayer?) -> Unit = {},
    videoFileResolver: VideoViewerFileResolver = ::resolveVideoViewerFile,
) {
    val messagePages =
        remember(messageIdHex, attachments, mine, sender, recordedAt) {
            attachments.map { entry ->
                MediaViewerPage(messageIdHex, entry.index, entry.value, mine, sender, recordedAt)
            }
        }
    val gallery =
        remember(conversationVisualPages, messagePages, tappedAttachmentIndex) {
            visualMediaViewerGallery(conversationVisualPages, messagePages, tappedAttachmentIndex)
        }
    val selectedStartIndex =
        selectedAttachment
            ?.let { selected ->
                gallery.pages.indexOfFirst {
                    it.messageIdHex == selected.messageIdHex && it.attachmentIndex == selected.attachmentIndex
                }
            }?.takeIf { it >= 0 }
            ?: gallery.startIndex
    FullScreenMediaViewer(
        controller = controller,
        appState = appState,
        pages = gallery.pages,
        startIndex = selectedStartIndex,
        onDismiss = onDismiss,
        onShareRequest = onShareRequest,
        onCurrentPageChange = { page ->
            onSelectedAttachmentChange(
                ConversationMediaViewerAttachmentId(page.messageIdHex, page.attachmentIndex),
            )
        },
        onVideoPlayerChanged = onVideoPlayerChanged,
        videoFileResolver = videoFileResolver,
    )
}

/**
 * Presents the media pager and reports its settled logical page without owning conversation
 * lifetime. The caller decides when a viewer generation is created or dismissed.
 */
@Composable
internal fun FullScreenMediaViewer(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    pages: List<MediaViewerPage>,
    startIndex: Int,
    onDismiss: () -> Unit,
    onShareRequest: (suspend (MediaViewerShareRequest) -> Result<Unit>)? = null,
    onCurrentPageChange: (MediaViewerPage) -> Unit = {},
    onVideoPlayerChanged: (androidx.media3.exoplayer.ExoPlayer?) -> Unit = {},
    videoFileResolver: VideoViewerFileResolver = ::resolveVideoViewerFile,
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

    val pagerSelection = rememberMediaViewerPagerSelection(pages, startIndex)
    val currentPageIndex = pagerSelection.currentPageIndex
    val currentPage = pagerSelection.currentPage
    val latestOnCurrentPageChange by rememberUpdatedState(onCurrentPageChange)
    LaunchedEffect(currentPage.messageIdHex, currentPage.attachmentIndex) {
        latestOnCurrentPageChange(currentPage)
    }
    val pagePositionDescription =
        if (pages.size > 1) {
            stringResource(R.string.media_viewer_page_position, currentPageIndex + 1, pages.size)
        } else {
            null
        }
    val currentReference = currentPage.reference
    val currentAttachmentIndex = currentPage.attachmentIndex
    val currentMessageIdHex = currentPage.messageIdHex
    val currentMine = currentPage.mine
    // Zoom state is hoisted to the viewer scope (not per-page) so the pager
    // can read it to gate horizontal swipe. Without this gate, the page's
    // `detectTransformGestures` claims every horizontal drag and the pager
    // never moves. Page change resets to identity below.
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(currentPage.messageIdHex, currentPage.attachmentIndex) {
        val reset = resetViewerTransform()
        scale = reset.scale
        offset = reset.offset
    }

    val currentRecordedAtLabel =
        DateUtils.formatDateTime(
            context,
            currentPage.recordedAt.toLong() * 1000L,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
        )

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        MediaViewerFrame(
            senderLabel = appState.displayName(currentPage.sender),
            recordedAtLabel = currentRecordedAtLabel,
            onDismiss = onDismiss,
            onSave = {
                val ref = currentReference
                val attachmentIndex = currentAttachmentIndex
                val msgId = currentMessageIdHex
                val owned = currentMine
                scope.launch {
                    val outcome =
                        runCatchingCancellable {
                            val saved =
                                if (MediaReferenceSupport.isVideoMedia(ref)) {
                                    val file =
                                        materializeVideoAttachment(
                                            context,
                                            controller,
                                            msgId,
                                            attachmentIndex,
                                            ref,
                                            owned,
                                        )
                                    withContext(Dispatchers.IO) {
                                        saveVideoToGallery(context, file, ref.fileName, ref.mediaType)
                                    }
                                } else {
                                    val data = attachmentBytes(controller, msgId, attachmentIndex, ref, owned)
                                    withContext(Dispatchers.IO) {
                                        saveImageToGallery(context, data, ref.fileName, ref.mediaType)
                                    }
                                }
                            check(saved) { "MediaStore save returned false" }
                        }
                    snackbarHostState.showSnackbar(
                        mediaSaveSnackbarVisuals(
                            context = context,
                            outcome = outcome,
                            successTitleRes = R.string.media_saved,
                            failureTitleRes = R.string.media_save_failed,
                            operationCode = "MEDIA_VIEWER_SAVE",
                        ),
                    )
                }
            },
            onShare = {
                val request =
                    MediaViewerShareRequest(
                        messageIdHex = currentMessageIdHex,
                        attachmentIndex = currentAttachmentIndex,
                        reference = currentReference,
                        mine = currentMine,
                    )
                scope.launch {
                    runCatchingCancellable {
                        when {
                            onShareRequest != null -> onShareRequest(request).getOrThrow()
                            MediaReferenceSupport.isVideoMedia(request.reference) -> {
                                val file =
                                    materializeVideoAttachment(
                                        context,
                                        controller,
                                        request.messageIdHex,
                                        request.attachmentIndex,
                                        request.reference,
                                        request.mine,
                                    )
                                shareVideo(
                                    context,
                                    file,
                                    request.reference.fileName,
                                    request.reference.mediaType,
                                ).getOrThrow()
                            }
                            else -> {
                                attachmentBytes(
                                    controller,
                                    request.messageIdHex,
                                    request.attachmentIndex,
                                    request.reference,
                                    request.mine,
                                ).let {
                                    shareImage(
                                        context,
                                        it,
                                        request.reference.fileName,
                                        request.reference.mediaType,
                                    ).getOrThrow()
                                }
                            }
                        }
                    }.onFailure { error ->
                        if (MediaReferenceSupport.isVideoMedia(request.reference)) {
                            appState.presentMediaLaunchFailure(
                                R.string.media_couldnt_open,
                                "MEDIA_VIEWER_VIDEO_SHARE",
                                error,
                            )
                        } else {
                            appState.presentMediaLaunchFailure(
                                R.string.media_couldnt_open,
                                "MEDIA_VIEWER_IMAGE_SHARE",
                                error,
                            )
                        }
                    }
                }
            },
            snackbarHostState = snackbarHostState,
        ) {
            StableMediaViewerPager(
                pages = pages,
                selection = pagerSelection,
                modifier = Modifier.fillMaxSize(),
                pagePositionDescription = pagePositionDescription,
                // Disable pager swipe while the visible page is zoomed in —
                // otherwise the pan gesture and the pager's swipe both want
                // the horizontal drag. At scale 1× the pager wins.
                userScrollEnabled = viewerPagerScrollEnabled(scale),
            ) { pageDescriptor, isCurrent ->
                if (MediaReferenceSupport.isVideoMedia(pageDescriptor.reference)) {
                    VideoViewerPage(
                        controller = controller,
                        messageIdHex = pageDescriptor.messageIdHex,
                        attachmentIndex = pageDescriptor.attachmentIndex,
                        reference = pageDescriptor.reference,
                        isCurrent = isCurrent,
                        mine = pageDescriptor.mine,
                        onPlayerChanged = onVideoPlayerChanged,
                        videoFileResolver = videoFileResolver,
                    )
                } else {
                    ViewerPage(
                        controller = controller,
                        messageIdHex = pageDescriptor.messageIdHex,
                        attachmentIndex = pageDescriptor.attachmentIndex,
                        reference = pageDescriptor.reference,
                        scale = if (isCurrent) scale else 1f,
                        offset = if (isCurrent) offset else Offset.Zero,
                        onScaleChange = { if (isCurrent) scale = it },
                        onOffsetChange = { if (isCurrent) offset = it },
                        mine = pageDescriptor.mine,
                        isCurrent = isCurrent,
                    )
                }
            }
        }
    }
}

/**
 * Draws edge-to-edge media while keeping interactive chrome inside the supplied safe insets.
 * The injectable inset value also makes cutout and large-type layouts deterministic in tests.
 */
@Composable
internal fun MediaViewerFrame(
    senderLabel: String,
    recordedAtLabel: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    snackbarHostState: SnackbarHostState,
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
    modifier: Modifier = Modifier,
    body: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        body()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(
                        contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    ).padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
            }
            Row {
                IconButton(onClick = onSave) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = stringResource(R.string.media_save),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share), tint = Color.White)
                }
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                        ),
                    ).windowInsetsPadding(
                        contentWindowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = senderLabel,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = recordedAtLabel,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(
                        contentWindowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ),
            snackbar = { SwipeDismissibleSnackbar(it) },
        )
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
    isCurrent: Boolean,
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
    var viewerReloadToken by remember(pageKey) { mutableIntStateOf(0) }
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
    LaunchedEffect(pageKey, viewerReloadToken, isCurrent) {
        // Mirror the video page's gating: neighbour pages composed during a
        // swipe must not each run a viewer-resolution decode (with its ~4×
        // transient) concurrently with the current page's. A page decodes
        // once it becomes current; an already-decoded bitmap is kept.
        if (!isCurrent || presentation != null) return@LaunchedEffect
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
                    val reset = resetViewerTransform()
                    latestOnScaleChange(reset.scale)
                    latestOnOffsetChange(reset.offset)
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
                        val next =
                            applyViewerTransformGesture(
                                current = ViewerTransform(currentScale, currentOffset),
                                zoomFactor = zoom,
                                panDelta = pan,
                                viewportWidth = size.width.toFloat(),
                                viewportHeight = size.height.toFloat(),
                                imageWidth = imageWidth,
                                imageHeight = imageHeight,
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
                        MediaViewerLoadFailed(
                            onRetry = { viewerReloadToken += 1 },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    else ->
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Color.White,
                        )
                }
        }
    }
}

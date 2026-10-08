package dev.ipf.whitenoise.android.ui.conversation.media

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.AttachmentTooLargeToPresentException
import dev.ipf.whitenoise.android.media.IDENTITY_IMAGE_SOURCE_MAX_BYTES
import dev.ipf.whitenoise.android.media.IdentityImageCropShape
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.media.loadGroupAttachmentCropSource
import dev.ipf.whitenoise.android.media.renderIdentityImageDraft
import dev.ipf.whitenoise.android.state.ATTACHMENT_EXPLICIT_READ_MAX_BYTES
import dev.ipf.whitenoise.android.state.ATTACHMENT_PRESENTATION_MAX_BYTES
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.state.ScopedGroupImageMutation
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.retryAttachmentTransfer
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.IdentityImageCropDialog
import dev.ipf.whitenoise.android.ui.common.SwipeDismissibleSnackbar
import dev.ipf.whitenoise.android.ui.common.ViewerTransform
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.common.applyViewerTransformGesture
import dev.ipf.whitenoise.android.ui.common.clampViewerPageIndex
import dev.ipf.whitenoise.android.ui.common.resetViewerTransform
import dev.ipf.whitenoise.android.ui.common.viewerPagerScrollEnabled
import dev.ipf.whitenoise.android.ui.conversation.PlaybackDialog
import dev.ipf.whitenoise.android.ui.group.GroupImageFailureScope
import dev.ipf.whitenoise.android.ui.group.groupImageFailureDetail
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
 * The array never exceeds [maxBytes]: previews keep the default presentation
 * budget, an explicit Save or Share passes [ATTACHMENT_EXPLICIT_READ_MAX_BYTES].
 */
internal suspend fun attachmentBytes(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
    priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Interactive,
    maxBytes: Long = ATTACHMENT_PRESENTATION_MAX_BYTES,
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
    return controller.downloadAttachment(messageIdHex, attachmentIndex, resolvedReference, priority, maxBytes)
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
    val pageContentLayoutDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        HorizontalPager(
            state = selection.pagerState,
            modifier = pagerModifier,
            key = { page -> pages[clampViewerPageIndex(page, pages.size)].saveableKey() },
            userScrollEnabled = userScrollEnabled,
        ) { page ->
            val pageDescriptor = pages[clampViewerPageIndex(page, pages.size)]
            CompositionLocalProvider(LocalLayoutDirection provides pageContentLayoutDirection) {
                pageContent(pageDescriptor, pageDescriptor.key() == currentPageKey)
            }
        }
    }
}

/**
 * Select the gallery opened by an inline visual attachment.
 *
 * Visual messages use chronological message traversal with authored attachment slots.
 * The current message pages are merged when the asynchronous
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
                    val currentPages = messagePages
                    val otherPages = conversationVisualPages.filterNot { it.messageIdHex == currentMessageId }
                    val insertAt =
                        otherPages
                            .indexOfFirst { it.recordedAt > tappedPage.recordedAt }
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
@Suppress("FunctionNaming", "LongParameterList")
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
    onGoToMessage: ((MediaViewerPage) -> Unit)? = null,
    forwardActions: MediaViewerForwardActions? = null,
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
        onGoToMessage = onGoToMessage,
        forwardActions = forwardActions,
    )
}

/**
 * Presents the media pager and reports its settled logical page without owning conversation
 * lifetime. The caller decides when a viewer generation is created or dismissed.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
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
    onGoToMessage: ((MediaViewerPage) -> Unit)? = null,
    forwardActions: MediaViewerForwardActions? = null,
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
    val actionOwner =
        remember(controller) {
            ConversationMediaViewerOwner(
                controller.boundAccountRef,
                controller.group.groupIdHex,
                appState.runtimeGeneration,
            )
        }
    val actionGate = remember(actionOwner) { MediaViewerActionGate(actionOwner) }

    /** Owner token binding viewer actions to the account and conversation that opened it. */
    fun currentActionOwner() =
        ConversationMediaViewerOwner(
            appState.activeAccountRef,
            controller.group.groupIdHex,
            appState.runtimeGeneration,
        )
    var pictureNotice by remember(actionOwner) {
        mutableStateOf<dev.ipf.whitenoise.android.state.ToastMessage?>(null)
    }
    ViewerGroupPictureFailureNotice(pictureNotice, snackbarHostState)
    val pictureFailures =
        remember(actionOwner) {
            GroupImageFailureScope({ appState.toast }, { appState.clearToast(it) })
        }
    val pictureSession =
        remember(actionOwner) {
            ViewerGroupPictureSession(
                ownerIsCurrent = {
                    currentActionOwner() == actionOwner && controller.boundAccountRef == actionOwner.accountRef
                },
                permitted = {
                    controller.isSelfMember &&
                        controller.isSelfAdmin &&
                        !controller.isDm &&
                        !controller.group.unrecoverable &&
                        !controller.group.pendingConfirmation &&
                        !controller.group.leaveRequestPending &&
                        !controller.group.disbanding &&
                        !controller.group.disbanded
                },
            )
        }
    DisposableEffect(pictureSession) {
        onDispose {
            pictureSession.close()
            pictureFailures.dispose()
        }
    }
    LaunchedEffect(currentActionOwner(), controller.isSelfMember, controller.isSelfAdmin) {
        if (!pictureSession.isCurrent()) {
            pictureSession.close()
            pictureFailures.clear()
            pictureNotice = null
        }
    }

    /** Prepare and commit within the captured viewer's account/group; retries reconcile native image bytes. */
    suspend fun commitPicture(
        draft: dev.ipf.whitenoise.android.media.ImageUploadDraft,
        reconcile: Boolean,
    ): Boolean {
        val attempt = pictureFailures.begin()
        val updated =
            controller.updateGroupImage(
                ScopedGroupImageMutation(draft) {
                    pictureSession.isCurrent() && pictureFailures.isCurrent(attempt)
                }.forViewer(reconcile),
            )

        if (!updated && controller.lastMutationError != null && pictureSession.isCurrent()) {
            pictureFailures.captureFailure(attempt)
            pictureNotice = appState.toast
        }
        if (updated) pictureNotice = null
        return updated
    }

    /** Read/render errors use the existing group-image failure surface while this viewer owns the attempt. */
    fun pictureFailure(error: Throwable) {
        if (!pictureSession.isCurrent()) return
        val attempt = pictureFailures.begin()
        appState.presentFailure(
            R.string.group_photo_error,
            "VIEWER_GROUP_IMAGE",
            error,
            detail = groupImageFailureDetail(error),
        )
        pictureFailures.captureFailure(attempt)
        pictureNotice = appState.toast
    }
    actionGate.currentPage = currentPage
    DisposableEffect(actionGate) { onDispose { actionGate.close() } }
    val latestGoToMessage by rememberUpdatedState(onGoToMessage)
    val latestForwardActions by rememberUpdatedState(forwardActions)
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
    val currentPageIsVideo = MediaReferenceSupport.isVideoMedia(currentReference)
    var chromeVisible by remember { mutableStateOf(true) }
    // Zoom state is hoisted to the viewer scope (not per-page) so the pager
    // can read it to gate horizontal swipe. Without this gate, the page's
    // `detectTransformGestures` claims every horizontal drag and the pager
    // never moves. Page change resets to identity below.
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(currentPage.messageIdHex, currentPage.attachmentIndex, currentPageIsVideo) {
        val reset = resetViewerTransform()
        scale = reset.scale
        offset = reset.offset
        chromeVisible = mediaViewerChromeVisibilityAfterPageChange(chromeVisible, currentPageIsVideo)
    }

    val currentRecordedAtLabel =
        DateUtils.formatDateTime(
            context,
            currentPage.recordedAt.toLong() * 1000L,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
        )

    PlaybackDialog(
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
            currentPosition = currentPageIndex + 1,
            pageCount = pages.size,
            actionOwner = currentPage,
            onGoToMessage =
                onGoToMessage?.let {
                    { actionGate.dispatch(currentPage, currentActionOwner()) { latestGoToMessage?.invoke(it) } }
                },
            onForwardMessage =
                forwardActions?.takeIf { it.canForward(currentPage) }?.let {
                    {
                        actionGate.dispatch(currentPage, currentActionOwner()) { page ->
                            if (latestForwardActions?.forward(page) == true) onDismiss()
                        }
                    }
                },
            onSetGroupPicture =
                if (canSetViewerGroupPicture(
                        controller.isDm,
                        controller.isSelfMember,
                        controller.isSelfAdmin,
                        pictureSession.isCurrent(),
                        currentPage,
                    )
                ) {
                    {
                        actionGate.dispatch(currentPage, currentActionOwner()) { page ->
                            scope.launch {
                                pictureNotice = null
                                pictureSession.choose(page, read = { selected ->
                                    val bytes =
                                        attachmentBytes(
                                            controller,
                                            selected.messageIdHex,
                                            selected.attachmentIndex,
                                            selected.reference,
                                            selected.mine,
                                            maxBytes = IDENTITY_IMAGE_SOURCE_MAX_BYTES.toLong(),
                                        )
                                    loadGroupAttachmentCropSource(bytes, selected.reference.mediaType)
                                }, failure = ::pictureFailure)
                            }
                        }
                    }
                } else {
                    null
                },
            groupPictureBusy = pictureSession.busy,
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
                                    // An explicit Save hands the whole verified image to MediaStore, so it is not
                                    // bounded by the preview budget that gates decoding.
                                    val data =
                                        attachmentBytes(
                                            controller,
                                            msgId,
                                            attachmentIndex,
                                            ref,
                                            owned,
                                            maxBytes = ATTACHMENT_EXPLICIT_READ_MAX_BYTES,
                                        )
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
                                // An explicit Share stages the whole verified image for the chooser, so it is not
                                // bounded by the preview budget that gates decoding.
                                attachmentBytes(
                                    controller,
                                    request.messageIdHex,
                                    request.attachmentIndex,
                                    request.reference,
                                    request.mine,
                                    maxBytes = ATTACHMENT_EXPLICIT_READ_MAX_BYTES,
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
            chromeVisible = chromeVisible,
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
                        onChromeToggle = { if (isCurrent) chromeVisible = !chromeVisible },
                    )
                }
            }
        }
    }
    pictureSession.source?.takeIf { pictureSession.isCurrent() }?.let { source ->
        IdentityImageCropDialog(
            source.preview.asImageBitmap(),
            source.orientedSize,
            IdentityImageCropShape.RoundedSquare,
            onDismiss = {
                pictureSession.cancel()
                pictureFailures.clear()
                pictureNotice = null
            },
            onConfirm = { crop ->
                scope.launch {
                    pictureSession.apply(
                        crop,
                        render = { bytes, chosen -> renderIdentityImageDraft(bytes, chosen, MediaQuality.Standard) },
                        commit = ::commitPicture,
                        failure = ::pictureFailure,
                    )
                }
            },
        )
    }
    if (pictureSession.failedDraft != null && pictureSession.isCurrent() && !pictureSession.busy) {
        AlertDialog(
            onDismissRequest = {
                pictureSession.cancel()
                pictureFailures.clear()
                pictureNotice = null
            },
            title = { Text(stringResource(R.string.toast_couldnt_update_group)) },
            text = { Text(stringResource(R.string.error_try_again)) },
            confirmButton = {
                TextButton(onClick = { scope.launch { pictureSession.retry(::commitPicture) } }) {
                    Text(stringResource(R.string.retry))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pictureSession.cancel()
                    pictureFailures.clear()
                    pictureNotice = null
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Draws native edge-to-edge media with prototype chrome inside the supplied cutout/system safe insets. */
@Composable
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
internal fun MediaViewerFrame(
    senderLabel: String,
    recordedAtLabel: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    snackbarHostState: SnackbarHostState,
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
    modifier: Modifier = Modifier,
    currentPosition: Int = 1,
    pageCount: Int = 1,
    actionOwner: Any? = null,
    onGoToMessage: (() -> Unit)? = null,
    onForwardMessage: (() -> Unit)? = null,
    chromeVisible: Boolean = true,
    onSetGroupPicture: (() -> Unit)? = null,
    groupPictureBusy: Boolean = false,
    body: @Composable BoxScope.() -> Unit,
) {
    var moreExpanded by remember(actionOwner) { mutableStateOf(false) }
    var bottomChromeHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val snackbarInsetSides =
        if (chromeVisible) {
            WindowInsetsSides.Horizontal
        } else {
            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
        }
    LaunchedEffect(chromeVisible) {
        if (!chromeVisible) moreExpanded = false
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        body()
        if (groupPictureBusy) {
            Surface(
                Modifier.align(Alignment.Center).testTag("conversation.media.viewer.group-picture-progress"),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.group_preparing_photo))
                }
            }
        }
        if (chromeVisible) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .testTag(MEDIA_VIEWER_TOP_CHROME_TAG),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                Row(
                    modifier =
                        Modifier
                            .windowInsetsPadding(
                                contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                            ).fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            text = senderLabel,
                            modifier = Modifier.testTag("conversation.media.viewer.sender"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text =
                                stringResource(
                                    R.string.media_viewer_metadata,
                                    recordedAtLabel,
                                    currentPosition,
                                    pageCount,
                                ),
                            modifier = Modifier.testTag("conversation.media.viewer.position"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box {
                        IconButton(onClick = { moreExpanded = true }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                contentDescription = stringResource(R.string.more_options),
                            )
                        }
                        WhiteNoiseDropdownMenu(
                            expanded = moreExpanded,
                            onDismissRequest = { moreExpanded = false },
                            items =
                                buildList {
                                    add(
                                        WhiteNoiseMenuItem(
                                            label = stringResource(R.string.media_save),
                                            icon = R.drawable.ic_download,
                                            onClick = onSave,
                                        ),
                                    )
                                    if (onSetGroupPicture != null) {
                                        add(
                                            WhiteNoiseMenuItem(
                                                label = stringResource(R.string.media_set_group_picture),
                                                icon = R.drawable.ic_image,
                                                onClick = onSetGroupPicture,
                                                enabled = !groupPictureBusy,
                                            ),
                                        )
                                    }
                                    if (onGoToMessage != null) {
                                        add(
                                            WhiteNoiseMenuItem(
                                                label = stringResource(R.string.shared_content_go_to_message),
                                                icon = R.drawable.ic_settings_chat_bubble_outline,
                                                onClick = onGoToMessage,
                                            ),
                                        )
                                    }
                                },
                        )
                    }
                }
            }
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .testTag(MEDIA_VIEWER_BOTTOM_CHROME_TAG)
                        .onSizeChanged { bottomChromeHeight = with(density) { it.height.toDp() } },
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                Row(
                    modifier =
                        Modifier
                            .windowInsetsPadding(
                                contentWindowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                            ).fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier.size(48.dp).testTag("conversation.media.viewer.share"),
                    ) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.share))
                    }
                    if (onForwardMessage != null) {
                        IconButton(
                            onClick = onForwardMessage,
                            modifier = Modifier.size(48.dp).testTag("conversation.media.viewer.forward"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_forward),
                                contentDescription = stringResource(R.string.media_viewer_forward_message),
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (chromeVisible) bottomChromeHeight else 0.dp)
                    .windowInsetsPadding(contentWindowInsets.only(snackbarInsetSides)),
            snackbar = { SwipeDismissibleSnackbar(it) },
        )
    }
}

/** One page of the media viewer for a message attachment. */
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
    onChromeToggle: () -> Unit,
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
    val latestOnChromeToggle by rememberUpdatedState(onChromeToggle)
    // `sourceEpoch` is folded into the page key so a viewer that failed
    // its first decrypt at epoch 0 (typed reference not yet loaded) re-keys
    // and retries when the real reference arrives.
    val pageKey = "$messageIdHex#$attachmentIndex#${reference.sourceEpoch}"
    val cachedThumbnail =
        remember(pageKey) {
            controller
                .thumbnailFor(messageIdHex, attachmentIndex)
                ?.takeIf { MediaPipeline.canSeedStaticThumbnailFromMediaType(reference.mediaType) }
                ?.asImageBitmap()
        }
    val thumbhashImage = rememberThumbhashImage(reference.thumbhash)
    var presentation by remember(pageKey) { mutableStateOf<DecodedAttachmentPresentation?>(null) }
    var viewerFailed by remember(pageKey) { mutableStateOf(false) }
    // A verified image above the preview budget is not a failure: Retry cannot shrink it, Save and Share still work.
    var viewerTooLarge by remember(pageKey) { mutableStateOf(false) }
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
        viewerTooLarge = false
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
        } catch (_: AttachmentTooLargeToPresentException) {
            viewerTooLarge = true
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

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .viewerTapGestureModifier(
                    gestureKey = pageKey,
                    onSingleTap = { latestOnChromeToggle() },
                    onDoubleTap = {
                        val reset = resetViewerTransform()
                        latestOnScaleChange(reset.scale)
                        latestOnOffsetChange(reset.offset)
                    },
                ).testTag(MEDIA_VIEWER_PAGE_GESTURE_TAG),
    ) {
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
                MediaViewerPendingFrame(
                    cachedThumbnail = cachedThumbnail,
                    thumbhashImage = thumbhashImage,
                    displayName = MediaPipeline.safeDisplayName(reference.fileName),
                    failed = viewerFailed,
                    tooLarge = viewerTooLarge,
                    onRetry = {
                        controller.retryAttachmentTransfer(
                            messageIdHex,
                            attachmentIndex,
                            onAccepted = { viewerReloadToken += 1 },
                            onFailure = { viewerFailed = true },
                        )
                    },
                )
        }
    }
}

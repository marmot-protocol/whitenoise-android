package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.offset
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.AttachmentTooLargeToPresentException
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.retryAttachmentTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** One viewer page; prepares its current decoded still for display before publishing the pixels. */
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
    imageBytes: (suspend () -> ByteArray)? = null,
    runtimeGeneration: Int = 0,
) {
    val latestImageBytes by rememberUpdatedState(imageBytes)
    // `sourceEpoch` is folded into the page key so a viewer that failed
    // its first decrypt at epoch 0 (typed reference not yet loaded) re-keys
    // and retries when the real reference arrives.
    val pageKey = listOf(controller, messageIdHex, attachmentIndex, reference.sourceEpoch, runtimeGeneration)
    var original by remember(pageKey) { mutableStateOf<ViewerOriginalImage?>(null) }
    var originalInspection by remember(pageKey) {
        mutableStateOf<dev.ipf.whitenoise.android.media.sandbox.ImageInspection?>(null)
    }
    var sourcePrepared by remember(pageKey) { mutableStateOf(false) }
    val animationsEnabled =
        dev.ipf.whitenoise.android.ui.common
            .rememberSystemAnimationsEnabled()
    val viewerLifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsState()
    val detailCurrent = isCurrent && viewerLifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    val animateCurrent = detailCurrent && animationsEnabled
    val cachedThumbnail =
        remember(pageKey) {
            controller
                .thumbnailFor(messageIdHex, attachmentIndex)
                ?.takeIf { MediaPipeline.canSeedStaticThumbnailFromMediaType(reference.mediaType) }
                ?.asImageBitmap()
        }
    val thumbhashImage = rememberThumbhashImage(reference.thumbhash)
    val presentationState = remember(pageKey) { mutableStateOf<DecodedAttachmentPresentation?>(null) }
    var presentation by presentationState
    OwnLatestAttachmentPresentation(pageKey, presentationState)
    var viewerFailed by remember(pageKey) { mutableStateOf(false) }
    // A verified image above the preview budget is not a failure: Retry cannot shrink it, Save and Share still work.
    var viewerTooLarge by remember(pageKey) { mutableStateOf(false) }
    val animationRecovery = remember(pageKey) { ViewerAnimationRecovery() }
    var animationFailure by remember(pageKey) {
        mutableStateOf<dev.ipf.whitenoise.android.media.AnimationTermination?>(null)
    }
    var animationRestartRequested by remember(pageKey) { mutableStateOf(false) }
    var viewerReloadToken by remember(pageKey) { mutableIntStateOf(0) }
    ObserveViewerAnimationTermination(
        sourceKey = pageKey,
        drawable = (presentation as? DecodedAttachmentPresentation.Animated)?.drawable,
        active = animateCurrent,
    ) { terminated, reason ->
        if ((presentation as? DecodedAttachmentPresentation.Animated)?.drawable === terminated) {
            animationFailure = reason
            if (animationRecovery.claim(reason)) {
                animationRestartRequested = true
                sourcePrepared = false
                viewerReloadToken++
            }
        }
    }
    val imageWidth =
        originalInspection?.width
            ?: when (val current = presentation) {
                is DecodedAttachmentPresentation.Static -> current.bitmap.width
                is DecodedAttachmentPresentation.Animated -> current.drawable.intrinsicWidth
                null -> 0
            }
    val imageHeight =
        originalInspection?.height
            ?: when (val current = presentation) {
                is DecodedAttachmentPresentation.Static -> current.bitmap.height
                is DecodedAttachmentPresentation.Animated -> current.drawable.intrinsicHeight
                null -> 0
            }
    LaunchedEffect(pageKey, viewerReloadToken, detailCurrent, animateCurrent) {
        // Mirror the video page's gating: neighbour pages composed during a
        // swipe must not each run a viewer-resolution decode (with its ~4×
        // transient) concurrently with the current page's. A page decodes
        // once it becomes current; an already-decoded bitmap is kept.
        val promoteAnimation =
            animateCurrent && (presentation as? DecodedAttachmentPresentation.Static)?.animationDeferred == true
        if (!detailCurrent) {
            original = null
            sourcePrepared = false
            return@LaunchedEffect
        }
        val replaceTerminatedAnimation = animationRestartRequested
        if (
            reusableViewerPresentation(presentation, promoteAnimation, replaceTerminatedAnimation, sourcePrepared)
        ) {
            return@LaunchedEffect
        }
        viewerFailed = false
        viewerTooLarge = false
        try {
            withContext(
                dev.ipf.whitenoise.android.media.sandbox.MediaTrustScope
                    .element(controller.mediaTrustDomain),
            ) {
                withViewerImageWork {
                    val data =
                        latestImageBytes?.invoke()
                            ?: attachmentBytes(controller, messageIdHex, attachmentIndex, reference, mine)
                    if (data.size > dev.ipf.whitenoise.android.media.sandbox.MediaDecodePolicy.MAX_INPUT_BYTES) {
                        throw AttachmentTooLargeToPresentException(
                            dev.ipf.whitenoise.android.media.sandbox.MediaDecodePolicy.MAX_INPUT_BYTES
                                .toLong(),
                            data.size.toLong(),
                        )
                    }
                    val inspectedSource = inspectViewerSource(data)
                    val inspected = inspectedSource?.image
                    val isVector = inspected?.mime == "image/svg+xml"
                    val staticSource = inspectedSource?.staticSource == true
                    val source =
                        inspected?.takeIf { staticSource }?.let {
                            ViewerOriginalImage(data, it, controller.mediaTrustDomain)
                        }
                    if (presentation != null && !promoteAnimation && !replaceTerminatedAnimation) {
                        originalInspection = inspected
                        original = source
                        sourcePrepared = true
                        return@withViewerImageWork
                    }
                    val decoded =
                        if (isVector && source != null) {
                            dev.ipf.whitenoise.android.media.decodeOwnedMedia(
                                decode = {
                                    dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
                                        .svgRegion(
                                            data,
                                            dev.ipf.whitenoise.android.media.sandbox.ViewerSvgRegion(
                                                0.0,
                                                0.0,
                                                source.inspection.width.toDouble(),
                                                source.inspection.height.toDouble(),
                                            ),
                                            VIEWER_DETAIL_EDGE,
                                        )?.let { DecodedAttachmentPresentation.Static(it) }
                                },
                                discard = { it.releaseOwnedPresentation() },
                            )
                        } else {
                            decodeMessageAttachmentImage(
                                bytes = data,
                                animationAllowed = animateCurrent,
                                mediaType = reference.mediaType,
                                staticMaxEdgePx = MediaPipeline.VIEWER_MAX_EDGE_PX,
                                viewerOriginalAnimation = true,
                                domain = controller.mediaTrustDomain,
                            )
                        }
                    if (replaceTerminatedAnimation) animationRestartRequested = false
                    if (decoded != null) animationFailure = null
                    originalInspection = inspected
                    original = source.takeIf { decoded != null }
                    sourcePrepared = decoded != null
                    if (decoded != null) {
                        if (decoded is DecodedAttachmentPresentation.Static) {
                            // Queue the asynchronous texture upload before the next draw needs these pixels.
                            decoded.bitmap.prepareToDraw()
                        }
                        presentation = decoded
                    } else if (!promoteAnimation) {
                        viewerFailed = true
                    }
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: AttachmentTooLargeToPresentException) {
            animationRestartRequested = false
            viewerTooLarge = !promoteAnimation
        } catch (_: Throwable) {
            animationRestartRequested = false
            viewerFailed = !promoteAnimation
        }
    }
    DisposableEffect(pageKey, presentation) {
        val owned = presentation
        onDispose {
            owned?.releaseOwnedPresentation()
        }
    }

    ImageViewerViewport(
        sourceKey = pageKey,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        scale = scale,
        offset = offset,
        onScaleChange = onScaleChange,
        onOffsetChange = onOffsetChange,
        onChromeToggle = onChromeToggle,
        imageLayer = { geometry, preview ->
            ViewerImageDetailLayer(original, geometry, detailCurrent, preview = preview)
        },
    ) { imageModifier ->
        when (val current = presentation) {
            is DecodedAttachmentPresentation.Static ->
                Image(
                    bitmap = current.toImageBitmap(),
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.FillBounds,
                    modifier = imageModifier,
                )
            is DecodedAttachmentPresentation.Animated -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AnimatedDrawableAttachmentImage(
                        drawable = current.drawable,
                        active = animateCurrent && animationFailure == null,
                        contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                        contentScale = ContentScale.FillBounds,
                        modifier = imageModifier,
                    )
                    if (animationFailure != null && !animationRestartRequested) {
                        MediaViewerLoadFailed(onRetry = {
                            if (!animationRestartRequested) {
                                animationRestartRequested = true
                                sourcePrepared = false
                                viewerReloadToken++
                            }
                        })
                    }
                }
            }
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

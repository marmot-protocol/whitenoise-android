package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import dev.ipf.whitenoise.android.media.AnimationSourceAdmission
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.decodeAdmittedAttachmentImage

/** Decoded pixels for a chat message image attachment. */
sealed interface DecodedAttachmentPresentation {
    data class Static(
        val bitmap: Bitmap,
        val animationDeferred: Boolean = false,
    ) : DecodedAttachmentPresentation

    data class Animated(
        val drawable: Drawable,
    ) : DecodedAttachmentPresentation
}

/**
 * Decode attachment bytes for in-bubble or viewer rendering. Content-based
 * animation admission picks the decoder: a refused GIF/WebP returns null (the
 * caller's failed placeholder) without reaching either native decoder, and only
 * an admitted animation may fall back to a sampled still.
 */
@Suppress("UNUSED_PARAMETER") // Callers pass the advertised MIME; admission deliberately trusts content only.
suspend fun decodeMessageAttachmentImage(
    bytes: ByteArray,
    mediaType: String,
    staticMaxEdgePx: Int,
    animatedMaxEdgePx: Int = MediaPipeline.ANIMATED_IMAGE_MAX_EDGE_PX,
    animationAllowed: Boolean = false,
    viewerOriginalAnimation: Boolean = false,
): DecodedAttachmentPresentation? =
    dev.ipf.whitenoise.android.media.decodeOwnedMedia(decode = {
        val admission =
            dev.ipf.whitenoise.android.media
                .admitAnimationSource(bytes)
        val animationDeferred =
            !animationAllowed && admission is dev.ipf.whitenoise.android.media.AnimationSourceAdmission.Admitted
        decodeAdmittedAttachmentImage<DecodedAttachmentPresentation>(
            bytes = bytes,
            admission = admission,
            decodeAnimated = {
                val drawable =
                    when {
                        !animationAllowed -> null
                        viewerOriginalAnimation -> MediaPipeline.decodeViewerAnimatedDrawable(bytes)
                        else -> MediaPipeline.decodeAnimatedDrawable(bytes, animatedMaxEdgePx)
                    }
                drawable?.let {
                    DecodedAttachmentPresentation.Animated(it)
                }
            },
            decodeStill = {
                val fallbackEdge =
                    if (viewerOriginalAnimation && admission is AnimationSourceAdmission.Admitted) {
                        maxOf(admission.canvasWidth, admission.canvasHeight)
                    } else {
                        staticMaxEdgePx
                    }
                decodeChatAttachmentStill(bytes, fallbackEdge)?.let {
                    DecodedAttachmentPresentation.Static(it, animationDeferred)
                }
            },
        )
    }, discard = { presentation ->
        when (presentation) {
            is DecodedAttachmentPresentation.Static -> presentation.bitmap.recycle()
            is DecodedAttachmentPresentation.Animated -> (presentation.drawable as? java.io.Closeable)?.close()
        }
    })

@Composable
fun AnimatedDrawableAttachmentImage(
    drawable: Drawable,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    DisposableEffect(drawable, active) {
        val animated = drawable as? Animatable
        if (active) animated?.start() else animated?.stop()
        onDispose {
            animated?.stop()
        }
    }
    DisposableEffect(drawable) {
        onDispose { (drawable as? java.io.Closeable)?.close() }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                adjustViewBounds = true
            }
        },
        update = { view ->
            view.scaleType = contentScale.toImageViewScaleType()
            view.setImageDrawable(drawable)
            view.contentDescription = contentDescription
        },
    )
}

internal fun DecodedAttachmentPresentation.Static.toImageBitmap() = bitmap.asImageBitmap()

/** The page owns assigned resources even if their drawable has not reached a rendering composition. */
@Composable
@Suppress("FunctionNaming") // A lifecycle owner follows Compose component naming.
internal fun OwnLatestAttachmentPresentation(
    pageKey: Any,
    presentation: androidx.compose.runtime.State<DecodedAttachmentPresentation?>,
) {
    DisposableEffect(pageKey, presentation) {
        onDispose { presentation.value?.releaseOwnedPresentation() }
    }
}

internal fun DecodedAttachmentPresentation.releaseOwnedPresentation() {
    when (this) {
        is DecodedAttachmentPresentation.Static -> bitmap.recycle()
        is DecodedAttachmentPresentation.Animated -> {
            (drawable as? Animatable)?.stop()
            (drawable as? java.io.Closeable)?.close()
        }
    }
}

private fun ContentScale.toImageViewScaleType(): ImageView.ScaleType =
    when (this) {
        ContentScale.Crop -> ImageView.ScaleType.CENTER_CROP
        ContentScale.Fit -> ImageView.ScaleType.FIT_CENTER
        ContentScale.FillBounds -> ImageView.ScaleType.FIT_XY
        else -> ImageView.ScaleType.CENTER_CROP
    }

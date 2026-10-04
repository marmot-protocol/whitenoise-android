package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.decodeAdmittedAttachmentImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded pixels for a chat message image attachment. */
sealed interface DecodedAttachmentPresentation {
    data class Static(
        val bitmap: Bitmap,
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
): DecodedAttachmentPresentation? =
    withContext(Dispatchers.Default) {
        decodeAdmittedAttachmentImage<DecodedAttachmentPresentation>(
            bytes = bytes,
            decodeAnimated = {
                MediaPipeline.decodeAnimatedDrawable(bytes, animatedMaxEdgePx)?.let {
                    DecodedAttachmentPresentation.Animated(it)
                }
            },
            decodeStill = {
                MediaPipeline.decodeSampledBitmap(bytes, staticMaxEdgePx)?.let {
                    DecodedAttachmentPresentation.Static(it)
                }
            },
        )
    }

@Composable
fun AnimatedDrawableAttachmentImage(
    drawable: Drawable,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(drawable) {
        val animated = drawable as? AnimatedImageDrawable
        animated?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        animated?.start()
        onDispose {
            animated?.stop()
        }
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

private fun ContentScale.toImageViewScaleType(): ImageView.ScaleType =
    when (this) {
        ContentScale.Crop -> ImageView.ScaleType.CENTER_CROP
        ContentScale.Fit -> ImageView.ScaleType.FIT_CENTER
        ContentScale.FillBounds -> ImageView.ScaleType.FIT_XY
        else -> ImageView.ScaleType.CENTER_CROP
    }

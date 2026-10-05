package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * Shows useful local pixels before viewer-resolution decode, falling back to real loading/error states. A page
 * that is [tooLarge] to preview keeps its thumbnail or thumbhash underneath the explanation and offers no Retry.
 */
@Composable
@Suppress("FunctionNaming")
internal fun BoxScope.MediaViewerPendingFrame(
    cachedThumbnail: ImageBitmap?,
    thumbhashImage: ImageBitmap?,
    displayName: String,
    failed: Boolean,
    onRetry: () -> Unit,
    tooLarge: Boolean = false,
) {
    when {
        failed -> MediaViewerLoadFailed(onRetry, Modifier.align(Alignment.Center))
        tooLarge -> {
            val underlay = cachedThumbnail ?: thumbhashImage
            if (underlay != null) {
                Image(
                    bitmap = underlay,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            MediaViewerTooLargeToPreview(Modifier.align(Alignment.Center))
        }
        cachedThumbnail != null ->
            Image(
                bitmap = cachedThumbnail,
                contentDescription = displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        thumbhashImage != null ->
            Image(
                bitmap = thumbhashImage,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        else ->
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onBackground,
            )
    }
}

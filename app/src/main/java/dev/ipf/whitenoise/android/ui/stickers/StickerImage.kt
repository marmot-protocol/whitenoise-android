package dev.ipf.whitenoise.android.ui.stickers

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import dev.ipf.marmotkit.StickerRefFfi
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.media.AnimatedDrawableAttachmentImage
import dev.ipf.whitenoise.android.ui.conversation.media.DecodedAttachmentPresentation
import dev.ipf.whitenoise.android.ui.conversation.media.decodeMessageAttachmentImage
import dev.ipf.whitenoise.android.ui.conversation.media.toImageBitmap
import kotlinx.coroutines.CancellationException

@Composable
fun StickerImage(
    appState: WhiteNoiseAppState,
    stickerRef: StickerRefFfi,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    var presentation by remember(stickerRef) { mutableStateOf<DecodedAttachmentPresentation?>(null) }
    var failed by remember(stickerRef) { mutableStateOf(false) }
    var resolvedContentDescription by remember(stickerRef, contentDescription) { mutableStateOf(contentDescription) }

    LaunchedEffect(stickerRef, contentDescription) {
        failed = false
        presentation = null
        resolvedContentDescription = contentDescription
        try {
            val asset = appState.stickerAsset(stickerRef)
            resolvedContentDescription = asset.sticker.alt?.takeIf { it.isNotBlank() } ?: contentDescription
            presentation =
                decodeMessageAttachmentImage(
                    bytes = asset.bytes,
                    mediaType = asset.sticker.mime,
                    staticMaxEdgePx = 768,
                    animatedMaxEdgePx = 768,
                ) ?: run {
                    failed = true
                    null
                }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            failed = true
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (val current = presentation) {
            is DecodedAttachmentPresentation.Static ->
                Image(
                    bitmap = current.toImageBitmap(),
                    contentDescription = resolvedContentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )

            is DecodedAttachmentPresentation.Animated ->
                AnimatedDrawableAttachmentImage(
                    drawable = current.drawable,
                    contentDescription = resolvedContentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )

            null ->
                if (failed) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = resolvedContentDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
        }
    }
}

@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.ui.conversation.media.ImageViewerViewport
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerFrame
import kotlinx.coroutines.CancellationException

/** Uses the existing bounded MDK public-image fetch path, only after an explicit tap. */
@Composable
internal fun NostrEventImagePane(
    url: String,
    loadImage: suspend (String, Int) -> ImageBitmap? = AvatarImageLoader::retryBanner,
    compact: Boolean = false,
    initiallyRequested: Boolean = false,
    onFullscreenDismiss: (() -> Unit)? = null,
) {
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    // A link tap belongs to its original runtime; cache invalidation must not replay that consent.
    val initialRequestLifetime = remember(url, initiallyRequested) { lifetime }
    val requestInitially = initiallyRequested && initialRequestLifetime == lifetime
    var attempt by remember(url, lifetime, initiallyRequested) { mutableIntStateOf(if (requestInitially) 1 else 0) }
    var image by remember(url, lifetime) { mutableStateOf(AvatarImageLoader.peekBanner(url, IMAGE_WIDTH_PX)) }
    var loading by remember(url, lifetime) { mutableStateOf(false) }
    var failed by remember(url, lifetime) { mutableStateOf(false) }
    var fullscreen by remember(url, lifetime, initiallyRequested) { mutableStateOf(requestInitially) }
    LaunchedEffect(url, lifetime, attempt) {
        val selectedCachedImage = requestInitially && attempt == 1 && image != null
        if (attempt == 0 || selectedCachedImage) return@LaunchedEffect
        loading = true
        failed = false
        image =
            try {
                safeNostrMediaUrl(url)?.let { safeUrl -> loadImage(safeUrl, IMAGE_WIDTH_PX) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        failed = image == null
        loading = false
    }
    val viewDescription = stringResource(R.string.nostr_event_view_image)
    NostrImageLayout(
        presentation = NostrImagePresentation(image, loading, failed, attempt),
        description = viewDescription,
        compact = compact,
        onOpen = { fullscreen = true },
        onAction = { if (image == null) attempt++ else fullscreen = true },
    )

    if (fullscreen) {
        image?.let { bitmap ->
            NostrFullscreenImage(bitmap = bitmap, onDismiss = {
                fullscreen = false
                onFullscreenDismiss?.invoke()
            })
        }
    }
}

private data class NostrImagePresentation(
    val image: ImageBitmap?,
    val loading: Boolean,
    val failed: Boolean,
    val attempt: Int,
) {
    val requested: Boolean get() = attempt > 0 || image != null
}

@Composable
private fun NostrImageLayout(
    presentation: NostrImagePresentation,
    description: String,
    compact: Boolean,
    onOpen: () -> Unit,
    onAction: () -> Unit,
) {
    if (!presentation.requested) {
        NostrImageAction(
            presentation = presentation,
            description = description,
            modifier = Modifier.fillMaxWidth().height(48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
            onClick = onAction,
        )
    } else if (compact) {
        Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.CenterVertically) {
            NostrImageViewport(
                presentation = presentation,
                description = description,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) { onOpen() }
            NostrImageAction(
                presentation = presentation,
                description = description,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = onAction,
            )
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            NostrImageViewport(
                presentation = presentation,
                description = description,
                modifier = Modifier.fillMaxWidth().height(240.dp),
            ) { onOpen() }
            NostrImageAction(
                presentation = presentation,
                description = description,
                modifier = Modifier.fillMaxWidth().height(48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                onClick = onAction,
            )
        }
    }
}

@Composable
private fun NostrImageViewport(
    presentation: NostrImagePresentation,
    description: String,
    modifier: Modifier,
    onOpen: () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        presentation.image?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clickable(onClickLabel = description, onClick = onOpen),
            )
        }
        if (presentation.loading) CircularProgressIndicator(color = LocalContentColor.current)
        if (presentation.failed) {
            Text(stringResource(R.string.nostr_event_image_failed), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NostrImageAction(
    presentation: NostrImagePresentation,
    description: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    TextButton(
        enabled = !presentation.loading,
        onClick = onClick,
        colors =
            ButtonDefaults.textButtonColors(
                contentColor = LocalContentColor.current,
                disabledContentColor = LocalContentColor.current.copy(alpha = NOSTR_IMAGE_DISABLED_ACTION_ALPHA),
            ),
        modifier = modifier,
    ) {
        Text(
            if (presentation.failed) stringResource(R.string.retry) else description,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Public images enter the same chrome and viewport as chat, without attachment-only actions. */
@Composable
private fun NostrFullscreenImage(
    bitmap: ImageBitmap,
    onDismiss: () -> Unit,
) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var chromeVisible by remember(bitmap) { mutableStateOf(true) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        MediaViewerFrame(
            senderLabel = stringResource(R.string.nostr_event_type_image),
            recordedAtLabel = "",
            onDismiss = onDismiss,
            snackbarHostState = remember { SnackbarHostState() },
            actionOwner = bitmap,
            chromeVisible = chromeVisible,
        ) {
            ImageViewerViewport(
                sourceKey = bitmap,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                scale = scale,
                offset = offset,
                onScaleChange = { scale = it },
                onOffsetChange = { offset = it },
                onChromeToggle = { chromeVisible = !chromeVisible },
            ) { imageModifier ->
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.nostr_event_type_image),
                    contentScale = ContentScale.FillBounds,
                    modifier = imageModifier,
                )
            }
        }
    }
}

private const val IMAGE_WIDTH_PX = 1440

private const val NOSTR_IMAGE_DISABLED_ACTION_ALPHA = 0.38f

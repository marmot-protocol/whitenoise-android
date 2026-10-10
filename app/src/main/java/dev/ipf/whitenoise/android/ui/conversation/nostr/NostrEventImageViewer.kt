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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.CancellationException

/** Uses the existing bounded MDK public-image fetch path, only after an explicit tap. */
@Composable
internal fun NostrEventImagePane(
    url: String,
    loadImage: suspend (String, Int) -> ImageBitmap? = AvatarImageLoader::retryBanner,
    compact: Boolean = false,
) {
    var attempt by remember(url) { mutableIntStateOf(0) }
    var image by remember(url) { mutableStateOf(AvatarImageLoader.peekBanner(url, IMAGE_WIDTH_PX)) }
    var loading by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    var fullscreen by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url, attempt) {
        if (attempt == 0) return@LaunchedEffect
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
        presentation = NostrImagePresentation(image, loading, failed),
        description = viewDescription,
        compact = compact,
        onOpen = { fullscreen = true },
        onAction = { if (image == null) attempt++ else fullscreen = true },
    )

    if (fullscreen) {
        image?.let { bitmap ->
            NostrFullscreenImage(bitmap = bitmap, onDismiss = { fullscreen = false })
        }
    }
}

private data class NostrImagePresentation(
    val image: ImageBitmap?,
    val loading: Boolean,
    val failed: Boolean,
)

@Composable
private fun NostrImageLayout(
    presentation: NostrImagePresentation,
    description: String,
    compact: Boolean,
    onOpen: () -> Unit,
    onAction: () -> Unit,
) {
    if (compact) {
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
        if (presentation.loading) CircularProgressIndicator()
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
    TextButton(enabled = !presentation.loading, onClick = onClick, modifier = modifier) {
        Text(
            if (presentation.failed) stringResource(R.string.retry) else description,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NostrFullscreenImage(
    bitmap: ImageBitmap,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.nostr_event_type_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.close))
                }
            }
        }
    }
}

private const val IMAGE_WIDTH_PX = 1440

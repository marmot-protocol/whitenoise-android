package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RemoteGiphyMedia
import dev.ipf.whitenoise.android.core.SafeHttpsGet
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationMessageMetrics
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationRichContentShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

private const val GIPHY_MAX_BODY_BYTES = 5 * 1024 * 1024
private const val GIPHY_CONNECT_TIMEOUT_MILLIS = 10_000
private const val GIPHY_READ_TIMEOUT_MILLIS = 15_000

/** Process-wide cap shared by GIPHY fetch/decode work to bound animated-media pressure. */
private val giphyFetchSlots = Semaphore(6)
private val giphyPlaybackSlots = Semaphore(6)

/** Lets an explicit load bypass automatic-download policy while still pausing unrequested work. */
internal fun shouldLoadRemoteGiphyMedia(
    automaticDownloadsPaused: Boolean,
    automaticAllowed: Boolean,
    manualRequest: Boolean,
): Boolean = manualRequest || (!automaticDownloadsPaused && automaticAllowed)

/** Loads and renders one iOS-originated GIPHY envelope through Android's media policy. */
@Composable
@Suppress("FunctionNaming")
internal fun RemoteGiphyMediaBubble(
    media: RemoteGiphyMedia,
    appState: WhiteNoiseAppState,
    onLongPress: () -> Unit,
) {
    var presentation by remember(media.url) { mutableStateOf<DecodedAttachmentPresentation?>(null) }
    var failed by remember(media.url) { mutableStateOf(false) }
    var manualRequest by remember(media.url) { mutableStateOf(false) }
    var retryToken by remember(media.url) { mutableIntStateOf(0) }
    var playbackGranted by remember(media.url) { mutableStateOf(false) }
    val automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused()
    val automaticAllowed = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image)
    val shouldLoad =
        shouldLoadRemoteGiphyMedia(
            automaticDownloadsPaused = automaticDownloadsPaused,
            automaticAllowed = automaticAllowed,
            manualRequest = manualRequest,
        )
    val playbackAllowed = manualRequest || !automaticDownloadsPaused

    LaunchedEffect(media.url, shouldLoad, retryToken) {
        if (!shouldLoad || presentation != null) return@LaunchedEffect
        failed = false
        try {
            val decoded = fetchAndDecodeRemoteGiphyMedia(media)
            currentCoroutineContext().ensureActive()
            if (decoded == null) failed = true else presentation = decoded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failed = true
        }
    }

    LaunchedEffect(media.url, presentation, playbackAllowed) {
        if (presentation == null || !playbackAllowed) {
            playbackGranted = false
            return@LaunchedEffect
        }
        giphyPlaybackSlots.withPermit {
            playbackGranted = true
            try {
                awaitCancellation()
            } finally {
                playbackGranted = false
            }
        }
    }

    RemoteGiphyMediaCard(
        media = media,
        presentation = presentation.takeIf { playbackGranted },
        loading = shouldLoad && (presentation == null || !playbackGranted) && !failed,
        failed = failed,
        onLoad = {
            manualRequest = true
            failed = false
            retryToken++
        },
        onLongPress = onLongPress,
    )
}

/** Stateless GIPHY card used by production state ownership and deterministic screenshot tests. */
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun RemoteGiphyMediaCard(
    media: RemoteGiphyMedia,
    presentation: DecodedAttachmentPresentation?,
    loading: Boolean,
    failed: Boolean,
    onLoad: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val attribution = media.attribution?.let { "via GIPHY · $it" } ?: stringResource(R.string.giphy_attribution)
    val openLabel = stringResource(R.string.link_confirm_open)
    val copyLabel = stringResource(R.string.copy)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = ConversationRichContentShape,
        modifier = Modifier.width(ConversationMessageMetrics.RichContentCanvasWidth),
    ) {
        Column {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(ConversationMessageMetrics.GifHeight),
            ) {
                when (presentation) {
                    is DecodedAttachmentPresentation.Static ->
                        Image(
                            bitmap = presentation.toImageBitmap(),
                            contentDescription = stringResource(R.string.giphy_media_preview),
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .combinedClickable(onClick = {}, onLongClick = onLongPress),
                        )
                    is DecodedAttachmentPresentation.Animated ->
                        AnimatedDrawableAttachmentImage(
                            drawable = presentation.drawable,
                            contentDescription = stringResource(R.string.giphy_media_preview),
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .combinedClickable(onClick = {}, onLongClick = onLongPress),
                        )
                    null ->
                        when {
                            failed ->
                                MediaCircleAction(
                                    icon = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.media_tap_to_retry),
                                    onClick = onLoad,
                                )
                            loading -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                            else ->
                                MediaCircleAction(
                                    icon = Icons.Default.ArrowDownward,
                                    contentDescription = stringResource(R.string.media_tap_to_download),
                                    onClick = onLoad,
                                )
                        }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            ) {
                Text(
                    text = attribution,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(media.url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = openLabel },
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                }
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(media.url)) },
                    modifier = Modifier.semantics { contentDescription = copyLabel },
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                }
            }
        }
    }
}

/** Fetches one validated rendition with bounded transport, MIME, decode, and concurrency. */
@Suppress("ReturnCount") // Each invalid request/fetch stage terminates without passing bytes to the decoder.
private suspend fun fetchAndDecodeRemoteGiphyMedia(media: RemoteGiphyMedia): DecodedAttachmentPresentation? {
    val request = media.imageRequest() ?: return null
    return giphyFetchSlots.withPermit {
        val bytes =
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine<ByteArray?> { continuation ->
                    val result =
                        SafeHttpsGet.get(
                            url = request.url,
                            maxBodyBytes = GIPHY_MAX_BODY_BYTES,
                            connectTimeoutMillis = GIPHY_CONNECT_TIMEOUT_MILLIS,
                            readTimeoutMillis = GIPHY_READ_TIMEOUT_MILLIS,
                            hostAllowed = { RemoteGiphyMedia.isAllowedMediaUrl(it.toString()) },
                            contentTypeAllowed = { value ->
                                val mime = value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                                mime == request.mediaType || mime == "application/octet-stream"
                            },
                            registerCancellation = { cancelRequest ->
                                continuation.invokeOnCancellation { cancelRequest() }
                            },
                        )
                    continuation.resume(result)
                }
            } ?: return null
        decodeMessageAttachmentImage(
            bytes = bytes,
            mediaType = request.mediaType,
            staticMaxEdgePx = MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
        )
    }
}

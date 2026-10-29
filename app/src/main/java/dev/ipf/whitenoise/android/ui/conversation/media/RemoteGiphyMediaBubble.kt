package dev.ipf.whitenoise.android.ui.conversation.media

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RemoteGiphyMedia
import dev.ipf.whitenoise.android.core.SafeHttpsGet
import dev.ipf.whitenoise.android.media.ByteSizeLruCache
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationMessageMetrics
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationRichContentShape
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageInlineFooter
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.ArrowDownward
import dev.ipf.whitenoise.android.ui.icons.filled.Refresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

internal const val GIPHY_MAX_BODY_BYTES = 5 * 1024 * 1024
private const val GIPHY_MEMORY_CACHE_MAX_BYTES = 20L * 1024 * 1024
private const val GIPHY_CONNECT_TIMEOUT_MILLIS = 10_000
private const val GIPHY_READ_TIMEOUT_MILLIS = 15_000

private val giphyByteCache = RemoteGiphyByteCache(GIPHY_MEMORY_CACHE_MAX_BYTES)

/** Trims encoded presentation bytes without creating or retiring the shared acquisition controller. */
internal fun trimRemoteGiphyMemory(trim: dev.ipf.whitenoise.android.media.PresentationMemoryTrim) {
    giphyByteCache.trimToSize(trim.targetBytes(GIPHY_MEMORY_CACHE_MAX_BYTES))
}

/** Creates the supervised transport only for an actual row; pure policy/cache reads need no Android dispatcher. */
private val giphyAcquisition by lazy {
    RemoteGiphyAcquisition(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        cache = giphyByteCache,
        fetch = ::fetchRemoteGiphyBytes,
    )
}

/** The message's existing metadata, placed once inside the GIPHY card footer. */
internal class GiphyMessageFooter(
    val timeText: String,
    val showStatus: Boolean,
    val status: MessageStatus,
    val editedLabel: String?,
    val onEditedClick: (() -> Unit)?,
)

/** Thread-safe, byte-bounded cache that survives conversation-row disposal within this process. */
internal class RemoteGiphyByteCache(
    maxBytes: Long,
) {
    private val delegate =
        ByteSizeLruCache<String, ByteArray>(
            maxBytes = maxBytes,
            sizeOf = ByteArray::size,
            maxEntryBytes = GIPHY_MAX_BODY_BYTES.toLong(),
        )

    /** Returns and promotes the cached bytes for [url], if present. */
    @Synchronized
    fun get(url: String): ByteArray? = delegate.get(url)

    /** Stores validated [bytes] for [url], evicting older entries to remain within the byte cap. */
    @Synchronized
    fun put(
        url: String,
        bytes: ByteArray,
    ) {
        delegate.put(url, bytes)
    }

    /** Drops LRU references without cancelling coalesced downloads or changing the insertion budget. */
    @Synchronized
    fun trimToSize(targetBytes: Long): Long = delegate.trimToSize(targetBytes)

    /** Removes bytes that no longer decode successfully. */
    @Synchronized
    fun remove(url: String) {
        delegate.remove(url)
    }
}

/** Reuses cached media and lets explicit loads bypass policy while pausing unrequested network work. */
internal fun shouldLoadRemoteGiphyMedia(
    automaticDownloadsPaused: Boolean,
    automaticAllowed: Boolean,
    manualRequest: Boolean,
    cachedAvailable: Boolean = false,
    ownerEligible: Boolean = true,
): Boolean = ownerEligible && (cachedAvailable || manualRequest || (!automaticDownloadsPaused && automaticAllowed))

/** Reads live consent at slot admission, even before a policy change has recomposed its row. */
private fun remoteGiphyNetworkAllowed(
    controller: ConversationController,
    manualRequest: Boolean,
): Boolean =
    shouldLoadRemoteGiphyMedia(
        automaticDownloadsPaused = controller.appState.automaticAttachmentDownloadsPaused(),
        automaticAllowed = controller.appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image),
        manualRequest = manualRequest,
        ownerEligible = controller.ownsRetainedForegroundPresentation,
    )

/** Loads and renders one iOS-originated GIPHY envelope through Android's media policy. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList") // One row owns decoding and playback policy.
internal fun RemoteGiphyMediaBubble(
    media: RemoteGiphyMedia,
    footer: GiphyMessageFooter,
    controller: ConversationController,
    messageIdHex: String,
    onLongPress: () -> Unit,
) {
    val appState = controller.appState
    val identity = remember(controller, messageIdHex, media.url) { Any() }
    val presentationState = remember(identity) { mutableStateOf<DecodedAttachmentPresentation?>(null) }
    var presentation by presentationState
    OwnLatestAttachmentPresentation(identity, presentationState)
    var failed by remember(identity) { mutableStateOf(false) }
    var manualRequest by remember(identity) { mutableStateOf(false) }
    var retryToken by remember(identity) { mutableIntStateOf(0) }
    val imageRequest = remember(identity) { media.imageRequest() }
    val cachedBytes = remember(imageRequest?.url, retryToken) { imageRequest?.url?.let(giphyByteCache::get) }
    val automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused()
    val automaticAllowed = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image)
    val ownerEligible = controller.ownsRetainedForegroundPresentation
    val networkAllowed = remoteGiphyNetworkAllowed(controller, manualRequest)
    val shouldLoad =
        shouldLoadRemoteGiphyMedia(
            automaticDownloadsPaused = automaticDownloadsPaused,
            automaticAllowed = automaticAllowed,
            manualRequest = manualRequest,
            cachedAvailable = cachedBytes != null,
            ownerEligible = ownerEligible,
        )
    val animationSource =
        rememberInlineAnimationSource(identity, appState, controller.boundAccountRef, controller.group.groupIdHex) {
            imageRequest?.url?.let(giphyByteCache::get)
        }

    LaunchedEffect(identity, shouldLoad, networkAllowed, retryToken) {
        if (!shouldLoad || presentation != null) return@LaunchedEffect
        failed = false
        try {
            val request = imageRequest ?: return@LaunchedEffect
            val decoded =
                giphyAcquisition.useBytes(
                    controller,
                    request,
                    mayDownload = { remoteGiphyNetworkAllowed(controller, manualRequest) },
                ) { bytes ->
                    decodeOwnedGiphyPresentation(controller, request, bytes)
                }
            try {
                currentCoroutineContext().ensureActive()
                when {
                    !controller.ownsRetainedForegroundPresentation -> decoded?.releaseOwnedPresentation()
                    decoded == null -> failed = true
                    else -> presentation = decoded
                }
            } catch (cancelled: CancellationException) {
                decoded?.releaseOwnedPresentation()
                throw cancelled
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failed = true
        }
    }

    RemoteGiphyMediaCard(
        media = media,
        footer = footer,
        presentation = presentation,
        animationSource = animationSource,
        loading = shouldLoad && presentation == null && !failed,
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
@Suppress("FunctionNaming", "LongMethod", "LongParameterList") // Stateless inputs include a local playback source.
internal fun RemoteGiphyMediaCard(
    media: RemoteGiphyMedia,
    footer: GiphyMessageFooter,
    presentation: DecodedAttachmentPresentation?,
    loading: Boolean,
    failed: Boolean,
    onLoad: () -> Unit,
    onLongPress: () -> Unit = {},
    animationSource: InlineAnimationSource? = null,
) {
    val attribution = media.attribution?.let { "via GIPHY · $it" } ?: stringResource(R.string.giphy_attribution)
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val openMedia = {
        try {
            uriHandler.openUri(media.url)
        } catch (_: IllegalArgumentException) {
            Toast.makeText(context, R.string.media_no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }
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
                        InlineAttachmentImage(
                            still = presentation,
                            source = animationSource,
                            contentDescription = stringResource(R.string.giphy_media_preview),
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .combinedClickable(onClick = openMedia, onLongClick = onLongPress),
                        )
                    is DecodedAttachmentPresentation.Animated ->
                        AnimatedDrawableAttachmentImage(
                            drawable = presentation.drawable,
                            contentDescription = stringResource(R.string.giphy_media_preview),
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .combinedClickable(onClick = openMedia, onLongClick = onLongPress),
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
            GiphyCardFooter(attribution, footer, openMedia)
        }
    }
}

/** Reserves the visible GIPHY credit and timestamp first, then ellipsizes optional edit metadata. */
@Composable
@Suppress("FunctionNaming")
private fun GiphyCardFooter(
    attribution: String,
    footer: GiphyMessageFooter,
    onOpen: () -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val attributionStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
    val timestampStyle = MaterialTheme.typography.labelSmall
    val attributionPixels =
        textMeasurer
            .measure(
                AnnotatedString(stringResource(R.string.giphy_attribution)),
                style = attributionStyle,
                maxLines = 1,
            ).size.width
    val attributionMinimum = with(density) { attributionPixels.toDp() }
    val timestampPixels =
        textMeasurer.measure(AnnotatedString(footer.timeText), style = timestampStyle, maxLines = 1).size.width
    val timestampWidth = with(density) { timestampPixels.toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        val mandatoryMetadataWidth = timestampWidth + if (footer.showStatus) 17.dp else 0.dp
        val metadataLimit =
            (maxWidth - attributionMinimum - 8.dp)
                .coerceAtLeast(mandatoryMetadataWidth)
                .coerceAtMost(maxWidth)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = attribution,
                style = attributionStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f)
                        .alignByBaseline()
                        .testTag("giphy.attribution")
                        .clickable(onClick = onOpen)
                        .semantics { contentDescription = attribution },
            )
            GiphyMetadataRow(footer, timestampStyle, metadataLimit)
        }
    }
}

/** Gives the shared timestamp/status footer its measured width before the optional edited label. */
@Composable
@Suppress("FunctionNaming")
private fun RowScope.GiphyMetadataRow(
    footer: GiphyMessageFooter,
    timestampStyle: TextStyle,
    maxWidth: Dp,
) {
    Row(
        modifier = Modifier.widthIn(max = maxWidth).alignByBaseline().testTag("giphy.message-footer"),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        footer.editedLabel?.let { label ->
            Text(
                text = label,
                style = timestampStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .alignByBaseline()
                        .then(footer.onEditedClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
            )
        }
        MessageInlineFooter(
            timeText = footer.timeText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            showStatus = footer.showStatus,
            status = footer.status,
            editedLabel = null,
            onEditedClick = null,
            modifier = Modifier.alignByBaseline(),
            statusContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            allowWrap = false,
        )
    }
}

/** Rejects retired owners before decoding and releases any result that finishes after owner retirement. */
private suspend fun decodeOwnedGiphyPresentation(
    controller: ConversationController,
    request: RemoteGiphyMedia.ImageRequest,
    bytes: ByteArray,
): DecodedAttachmentPresentation? {
    if (!controller.ownsRetainedForegroundPresentation) throw CancellationException("GIPHY owner retired")
    val result =
        decodeMessageAttachmentImage(
            bytes,
            request.mediaType,
            MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
            // GIPHY serves these bytes from an allow-listed host with no digest a member pinned, so single-use.
            domain = dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
        )
    if (!controller.ownsRetainedForegroundPresentation) {
        result?.releaseOwnedPresentation()
        throw CancellationException("GIPHY owner retired during decode")
    }
    return result
}

/** Fetches bounded encoded bytes through the existing HTTPS, host, redirect and MIME validation. */
private suspend fun fetchRemoteGiphyBytes(request: RemoteGiphyMedia.ImageRequest): ByteArray? =
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
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
    }

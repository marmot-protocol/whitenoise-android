@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.core.HostSafety
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import kotlinx.coroutines.CancellationException
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

@Composable
internal fun NostrArticleReaderDialog(
    card: NostrEventCardModel,
    authorDisplayName: (String) -> String,
    mentionDisplayName: (String) -> String?,
    onNostrProfileTap: (String) -> Unit,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    onDismiss: () -> Unit,
) {
    var document by remember(card.eventIdHex) { mutableStateOf<MarkdownDocumentFfi?>(null) }
    var parsing by remember(card.eventIdHex) { mutableStateOf(true) }
    LaunchedEffect(card.eventIdHex, card.readerBody) {
        document =
            try {
                parseMarkdown(card.readerBody.orEmpty())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        parsing = false
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        NostrArticleReaderScreen(
            card = card,
            document = document,
            parsing = parsing,
            authorDisplayName = authorDisplayName,
            mentionDisplayName = mentionDisplayName,
            onNostrProfileTap = onNostrProfileTap,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun NostrArticleReaderScreen(
    card: NostrEventCardModel,
    document: MarkdownDocumentFfi?,
    parsing: Boolean,
    authorDisplayName: (String) -> String,
    mentionDisplayName: (String) -> String?,
    onNostrProfileTap: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            NostrArticleReaderHeader(onDismiss)
            HorizontalDivider()
            NostrArticleReaderBody(
                card = card,
                document = document,
                parsing = parsing,
                authorDisplayName = authorDisplayName,
                mentionDisplayName = mentionDisplayName,
                onNostrProfileTap = onNostrProfileTap,
            )
        }
    }
}

@Composable
private fun NostrArticleReaderHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.close),
            )
        }
        Text(
            text = stringResource(R.string.nostr_event_type_article),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun NostrArticleReaderBody(
    card: NostrEventCardModel,
    document: MarkdownDocumentFfi?,
    parsing: Boolean,
    authorDisplayName: (String) -> String,
    mentionDisplayName: (String) -> String?,
    onNostrProfileTap: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        card.title?.takeIf(String::isNotBlank)?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = eventByline(card, authorDisplayName),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            parsing ->
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            document != null && document.blocks.isNotEmpty() ->
                MarkdownMessageBody(
                    document = document,
                    mentionDisplayName = mentionDisplayName,
                    onNostrProfileTap = onNostrProfileTap,
                    useDecorativeBackgrounds = true,
                )
            else ->
                Text(
                    text = card.readerBody ?: card.summary.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun NostrVideoPlayerDialog(
    mediaUrl: String,
    mediaMimeType: String?,
    onDismiss: () -> Unit,
) {
    var playbackFailed by remember(mediaUrl) { mutableStateOf(false) }
    val player = rememberNostrVideoPlayer(mediaUrl, mediaMimeType) { playbackFailed = true }
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        NostrVideoPlayerSurface(
            player = player,
            playbackFailed = playbackFailed,
            onRetry = {
                playbackFailed = false
                player.prepare()
                player.playWhenReady = true
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun rememberNostrVideoPlayer(
    mediaUrl: String,
    mediaMimeType: String?,
    onPlaybackFailed: () -> Unit,
): ExoPlayer {
    val context = LocalContext.current
    val player =
        remember(mediaUrl, mediaMimeType) {
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(nostrMediaHttpClient)))
                .build()
                .apply {
                    setAudioAttributes(nostrVideoAudioAttributes, true)
                    addListener(
                        object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) = onPlaybackFailed()
                        },
                    )
                    setMediaItem(
                        MediaItem
                            .Builder()
                            .setUri(mediaUrl)
                            .apply { mediaMimeType?.let(::setMimeType) }
                            .build(),
                    )
                }
        }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) {
        VoicePlaybackController.pause()
        player.prepare()
        player.playWhenReady = true
    }
    return player
}

@Composable
private fun NostrVideoPlayerSurface(
    player: ExoPlayer,
    playbackFailed: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    controllerShowTimeoutMs = VIDEO_CONTROLS_TIMEOUT_MILLIS
                }
            },
            onRelease = { playerView -> playerView.player = null },
        )
        if (playbackFailed) NostrVideoPlaybackFailure(onRetry)
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.close),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun NostrVideoPlaybackFailure(onRetry: () -> Unit) {
    Column(
        modifier =
            Modifier
                .padding(24.dp)
                .background(Color.Black.copy(alpha = 0.78f), MaterialTheme.shapes.large)
                .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.nostr_video_playback_failed),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.retry), color = Color.White)
        }
    }
}

private val nostrVideoAudioAttributes =
    AudioAttributes
        .Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .setUsage(C.USAGE_MEDIA)
        .build()

/** Rejects internal-network destinations for the initial request and every redirect. */
private val nostrMediaHttpClient: OkHttpClient by lazy {
    OkHttpClient
        .Builder()
        .dns(PublicMediaDns)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (!url.isSafeMediaDestination()) {
                throw IOException("Unsafe media destination")
            }
            chain.proceed(chain.request())
        }.build()
}

private fun HttpUrl.isSafeMediaDestination(): Boolean =
    when {
        !isHttps -> false
        port != HTTPS_PORT -> false
        encodedUsername.isNotEmpty() || encodedPassword.isNotEmpty() -> false
        else -> !HostSafety.isPrivateOrLoopbackHost(host)
    }

private object PublicMediaDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (HostSafety.isPrivateOrLoopbackHost(hostname)) throw UnknownHostException(hostname)
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any(HostSafety::isPrivateOrLoopbackAddress)) {
            throw UnknownHostException(hostname)
        }
        return addresses
    }
}

private const val HTTPS_PORT = 443
private const val VIDEO_CONTROLS_TIMEOUT_MILLIS = 2_500

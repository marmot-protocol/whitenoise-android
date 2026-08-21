package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.media.AttachmentCachePublication
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val voiceMaterializations = SingleFlight<String, java.io.File>()

internal fun voicePlaybackKey(
    messageIdHex: String,
    attachmentIndex: Int,
    sourceEpoch: ULong,
): String = AttachmentCachePublication.attachmentKey(messageIdHex, attachmentIndex, sourceEpoch)

@Composable
internal fun MediaVoiceBubble(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onLongPress: () -> Unit = {},
    attachedToCaption: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val epoch = reference.sourceEpoch
    val pillKey = voicePlaybackKey(messageIdHex, attachmentIndex, epoch)

    var localFile by
        rememberCachedVoiceAttachmentFileState(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        )
    val cachedPlaintextOnEntry =
        remember(pillKey, epoch, reference.mediaType) {
            controller.hasCachedAttachment(messageIdHex, attachmentIndex)
        }
    val retainedPlaintextOnEntry =
        mine && controller.pendingAttachmentsList(messageIdHex).getOrNull(attachmentIndex) != null
    var totalDurationMs by remember(pillKey, epoch) { mutableStateOf(0) }
    var loading by remember(pillKey, epoch) { mutableStateOf(false) }
    var failed by remember(pillKey, epoch) { mutableStateOf(false) }
    // Auto-download gate (#407): retained/cached own clips always materialize;
    // a cache-missing own clip waits during an explicit backlog pause. Incoming
    // clips honor the Audio matrix row unless the attachment is already local.
    // A cached voice file or controller plaintext
    // cache means re-entering the chat should start at Play instead of showing
    // a fake Download affordance. Re-keyed on the matrix so flipping a toggle
    // re-gates an un-fetched clip. A tap on the bubble flips this to true so
    // manual fetch/playback is always available even when auto-download is off.
    val automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused()
    var startDownload by remember(
        pillKey,
        epoch,
        appState.mediaAutoDownloadMatrix,
        automaticDownloadsPaused,
    ) {
        mutableStateOf(
            shouldStartVoiceAttachmentDownload(
                mine = mine,
                audioAutoDownload = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Audio),
                automaticDownloadsPaused = automaticDownloadsPaused,
                hasCachedAttachment = cachedPlaintextOnEntry,
                hasCachedFile = localFile != null,
                hasRetainedPlaintext = retainedPlaintextOnEntry,
            ),
        )
    }
    var interactiveDownloadRequested by remember(pillKey) { mutableStateOf(false) }
    var reloadToken by remember(pillKey, epoch) { mutableStateOf(0) }

    val playback by remember(pillKey) {
        dev.ipf.whitenoise.android.audio.VoicePlaybackController.state
            .map { state -> state.takeIf { it.key == pillKey } }
            .distinctUntilChanged()
    }.collectAsState(null)
    val isThis = playback != null
    val isPlayingThis = playback?.isPlaying == true
    val isPausedThis = playback?.let { !it.isPlaying && it.positionMs > 0 } == true
    val activeDurationMs =
        playback?.durationMs?.takeIf { it > 0 } ?: totalDurationMs
    val activePositionMs = playback?.positionMs ?: 0
    val progressFraction =
        if (activeDurationMs > 0) {
            (activePositionMs.toFloat() / activeDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val pseudoWaveform: FloatArray =
        remember(pillKey) {
            val bytes =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(pillKey.toByteArray())
            FloatArray(dev.ipf.whitenoise.android.audio.AudioWaveformExtractor.BARS) { i ->
                val byte = bytes[i % bytes.size].toInt() and 0xFF
                0.3f + (byte / 255f) * 0.7f
            }
        }
    var realWaveform by remember(pillKey, epoch) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(localFile, pillKey, epoch) {
        val file = localFile ?: return@LaunchedEffect
        if (realWaveform != null) return@LaunchedEffect
        realWaveform =
            dev.ipf.whitenoise.android.audio.AudioWaveformExtractor
                .decode(file)
    }
    val waveform: FloatArray = realWaveform ?: pseudoWaveform

    suspend fun clearBadVoiceCache(reason: String) {
        Log.w(
            "MediaVoiceBubble",
            "$reason for cached voice msg=${messageIdHex.take(8)}#$attachmentIndex; clearing cache",
        )
        clearVoiceAttachmentCacheAfterPlaybackFailure(
            context = context,
            controller = controller,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        )
        localFile = null
        realWaveform = null
        totalDurationMs = 0
        failed = true
        startDownload =
            shouldStartVoiceAttachmentDownload(
                mine = mine,
                audioAutoDownload = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Audio),
                automaticDownloadsPaused = automaticDownloadsPaused,
                hasCachedAttachment = false,
                hasCachedFile = false,
                hasRetainedPlaintext = retainedPlaintextOnEntry,
            )
    }

    suspend fun playReadyVoice(file: java.io.File) {
        val playableFile =
            withContext(Dispatchers.IO) {
                validatedAttachmentCacheFile(file)
            }
        if (playableFile == null) {
            localFile = null
            realWaveform = null
            totalDurationMs = 0
            controller.requestAttachmentOpen(messageIdHex, attachmentIndex)
            return
        }
        localFile = playableFile
        val playbackResult =
            dev.ipf.whitenoise.android.audio.VoicePlaybackController
                .play(pillKey, playableFile, ownerKey = controller.group.groupIdHex)
        if (shouldInvalidateVoiceAttachmentCache(playbackResult)) {
            clearBadVoiceCache("playback start failed")
        }
    }

    LaunchedEffect(pillKey, epoch, reference.mediaType) {
        VoicePlaybackController.failures.collect { failure ->
            if (failure.key == pillKey && failure.invalidatesCache) {
                clearBadVoiceCache("playback error")
            }
        }
    }

    LaunchedEffect(pillKey, epoch, startDownload, interactiveDownloadRequested, reloadToken) {
        if (localFile != null) return@LaunchedEffect
        // Honor the auto-download gate: when Audio is off for the active
        // connection the clip waits behind a Download affordance until the
        // user opts in (tap flips startDownload=true). Manual playback below
        // stays available regardless.
        if (!startDownload) return@LaunchedEffect
        // Receive-side imeta-parsed refs start with sourceEpoch=0 until the
        // controller's listMedia FFI lands the real epoch; the FFI download
        // path errors with "missing encrypted media secret for epoch 0".
        // Skip + retry once the projection rebinds the bubble with a real
        // epoch. Own sends keep epoch 0 valid (retained bytes short-circuit).
        if (!mine && reference.sourceEpoch == 0uL) return@LaunchedEffect
        val instant = retainedPlaintextOnEntry || controller.hasCachedAttachment(messageIdHex, attachmentIndex)
        if (!instant) loading = true
        runCatching {
            materializeVoiceAttachment(
                context = context,
                controller = controller,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
                mine = mine,
                priority =
                    if (interactiveDownloadRequested) {
                        AttachmentDownloadPriority.Interactive
                    } else {
                        AttachmentDownloadPriority.Automatic
                    },
            )
        }.onSuccess { file ->
            localFile = file
            failed = false
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w("MediaVoiceBubble", "auto-materialize failed for msg=${messageIdHex.take(8)}#$attachmentIndex", it)
            failed = true
        }
        loading = false
    }

    persistedAttachmentOpenEffect(
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        sourceEpoch = epoch,
        controller = controller,
        appState = appState,
        isReady = { localFile != null },
        ensureMaterialization = {
            if (failed) {
                failed = false
                reloadToken++
            }
            interactiveDownloadRequested = true
            startDownload = true
        },
        dispatchOpen = {
            val file = localFile
            if (file == null) {
                controller.requestAttachmentOpen(messageIdHex, attachmentIndex)
            } else {
                playReadyVoice(file)
            }
        },
    )

    // Surface a cached duration as soon as the file is materialized so the
    // bubble shows "0:12" instead of "0:00" before the user taps Play.
    LaunchedEffect(pillKey, epoch, localFile) {
        val file = localFile ?: return@LaunchedEffect
        if (totalDurationMs == 0) {
            val probed =
                dev.ipf.whitenoise.android.audio.VoicePlaybackController
                    .probeDuration(file)
            if (probed > 0) totalDurationMs = probed
        }
    }

    val onSurfaceMuted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = if (attachedToCaption) RectangleShape else RoundedCornerShape(18.dp),
        border = if (attachedToCaption) null else amoledSurfaceBorderStroke(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            // Circular play/pause button. Anchors the bubble and is the
            // primary tap target — sized generously (48dp) so it reads as
            // the focal control.
            Surface(
                color = accent,
                shape = androidx.compose.foundation.shape.CircleShape,
                modifier =
                    Modifier
                        .size(48.dp)
                        .combinedClickable(
                            onLongClick = onLongPress,
                            onClick = {
                                if (loading || localFile == null) {
                                    controller.requestAttachmentOpen(messageIdHex, attachmentIndex)
                                    return@combinedClickable
                                }
                                failed = false
                                if (isPlayingThis) {
                                    dev.ipf.whitenoise.android.audio.VoicePlaybackController
                                        .pause()
                                    return@combinedClickable
                                }
                                scope.launch {
                                    val readyFile = localFile ?: return@launch
                                    playReadyVoice(readyFile)
                                }
                            },
                        ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        loading ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = onAccent,
                            )
                        failed ->
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.voice_message_failed),
                                tint = onAccent,
                                modifier = Modifier.size(26.dp),
                            )
                        !startDownload && localFile == null ->
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = stringResource(R.string.media_tap_to_download),
                                tint = onAccent,
                                modifier = Modifier.size(26.dp),
                            )
                        isPlayingThis ->
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = stringResource(R.string.voice_message_pause),
                                tint = onAccent,
                                modifier = Modifier.size(28.dp),
                            )
                        else ->
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.voice_message_play),
                                tint = onAccent,
                                modifier = Modifier.size(28.dp),
                            )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                VoiceWaveform(
                    bars = waveform,
                    progress = progressFraction,
                    playedColor = accent,
                    remainingColor = onSurfaceMuted,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(28.dp),
                    onSeek =
                        if (isThis && activeDurationMs > 0) {
                            { fraction ->
                                dev.ipf.whitenoise.android.audio.VoicePlaybackController
                                    .seekTo(pillKey, (fraction * activeDurationMs).toInt())
                            }
                        } else {
                            null
                        },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val timeText =
                        when {
                            isPlayingThis || isPausedThis ->
                                "${formatVoiceTime(activePositionMs)} / ${formatVoiceTime(activeDurationMs)}"
                            totalDurationMs > 0 -> formatVoiceTime(totalDurationMs)
                            else -> "0:00"
                        }
                    Text(
                        timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // Speed pill: only shown once playback has been engaged
                    // for this clip, so an unplayed bubble stays uncluttered.
                    playback?.let { activePlayback ->
                        VoiceSpeedPill(currentSpeed = activePlayback.speed)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceSpeedPill(currentSpeed: Float) {
    val label =
        when {
            currentSpeed >= 1.95f -> "2×"
            currentSpeed >= 1.45f -> "1.5×"
            else -> "1×"
        }
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        shape = RoundedCornerShape(10.dp),
        modifier =
            Modifier.clickable {
                dev.ipf.whitenoise.android.audio.VoicePlaybackController
                    .cycleSpeed()
            },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * Voice attachments need a file on disk for MediaPlayer; reuse the
 * downloaded plaintext to populate a stable per-message cache file so
 * subsequent plays are instant. Own outgoing sends short-circuit through
 * the still-retained source bytes from the pending-attachments list while
 * the Blossom upload is in flight.
 */
internal suspend fun materializeVoiceAttachment(
    context: android.content.Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
    priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Interactive,
): java.io.File =
    materializeVoiceAttachment(
        context = context,
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = reference,
        resolveBytes = {
            val retained =
                if (mine) {
                    controller
                        .pendingAttachmentsList(messageIdHex)
                        .getOrNull(attachmentIndex)
                        ?.plaintextBytes
                } else {
                    null
                }
            retained ?: controller.downloadAttachment(messageIdHex, attachmentIndex, reference, priority)
        },
    )

@VisibleForTesting
internal suspend fun materializeVoiceAttachment(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    resolveBytes: suspend () -> ByteArray,
): java.io.File {
    val file =
        voiceAttachmentCacheFile(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        )
    val attachmentKey =
        AttachmentCachePublication.attachmentKey(
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            sourceEpoch = reference.sourceEpoch,
        )

    return voiceMaterializations.run(file.absolutePath) {
        materializeVoiceAttachmentOnce(attachmentKey, file, resolveBytes)
    }
}

private suspend fun materializeVoiceAttachmentOnce(
    attachmentKey: String,
    file: java.io.File,
    resolveBytes: suspend () -> ByteArray,
): java.io.File {
    val cachedFile =
        withContext(Dispatchers.IO) {
            file
                .takeIf { it.isFile && it.length() > 0L }
                ?.also(AttachmentPlaintextCache::touch)
        }
    if (cachedFile != null) return cachedFile
    val published =
        AttachmentCachePublication.publishAfterLoad(
            attachmentKey = attachmentKey,
            finalFile = file,
            loadBytes = resolveBytes,
        )
    if (!published) {
        throw java.io.IOException("attachment cache publication aborted for ${file.name}")
    }
    return file
}

internal fun cachedVoiceAttachmentFile(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): java.io.File? =
    validatedAttachmentCacheFile(
        voiceAttachmentCacheFile(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        ),
    )

@Composable
private fun rememberCachedVoiceAttachmentFileState(
    context: Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): MutableState<java.io.File?> {
    val cachedFile =
        remember(messageIdHex, attachmentIndex, reference.sourceEpoch, reference.mediaType) {
            mutableStateOf<java.io.File?>(null)
        }
    LaunchedEffect(messageIdHex, attachmentIndex, reference.sourceEpoch, reference.mediaType) {
        val file =
            withContext(Dispatchers.IO) {
                cachedVoiceAttachmentFile(
                    context = context,
                    messageIdHex = messageIdHex,
                    attachmentIndex = attachmentIndex,
                    reference = reference,
                )
            }
        if (cachedFile.value == null) cachedFile.value = file
    }
    return cachedFile
}

internal fun shouldStartVoiceAttachmentDownload(
    mine: Boolean,
    audioAutoDownload: Boolean,
    automaticDownloadsPaused: Boolean = false,
    hasCachedAttachment: Boolean,
    hasCachedFile: Boolean,
    hasRetainedPlaintext: Boolean = false,
): Boolean =
    shouldMaterializeAttachmentAutomatically(
        mine = mine,
        mediaAutoDownloadAllowed = audioAutoDownload,
        automaticDownloadsPaused = automaticDownloadsPaused,
        hasCachedAttachment = hasCachedAttachment,
        hasMaterializedFile = hasCachedFile,
        hasRetainedPlaintext = hasRetainedPlaintext,
    )

internal fun shouldInvalidateVoiceAttachmentCache(playbackResult: VoicePlaybackController.PlaybackStartResult): Boolean =
    playbackResult == VoicePlaybackController.PlaybackStartResult.PrepareFailed ||
        playbackResult == VoicePlaybackController.PlaybackStartResult.StartFailed

internal suspend fun clearVoiceAttachmentCacheAfterPlaybackFailure(
    context: android.content.Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
) {
    val attachmentKey =
        AttachmentCachePublication.attachmentKey(
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            sourceEpoch = reference.sourceEpoch,
        )
    val cacheFile =
        voiceAttachmentCacheFile(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        )
    withContext(NonCancellable) {
        AttachmentCachePublication.invalidateAttachmentCache(
            attachmentKey = attachmentKey,
            finalFile = cacheFile,
            evictPlaintext = { controller.evictCachedAttachment(messageIdHex, attachmentIndex) },
        )
    }
}

private fun voiceAttachmentCacheFile(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): java.io.File {
    val cacheDir = java.io.File(context.cacheDir, MediaCacheDirs.VOICE)
    return java.io.File(
        cacheDir,
        "$messageIdHex-$attachmentIndex-${reference.sourceEpoch}.${voiceAttachmentExtension(reference)}",
    )
}

private fun voiceAttachmentExtension(reference: MediaAttachmentReferenceFfi): String =
    when {
        reference.mediaType.contains("mp4", ignoreCase = true) -> "m4a"
        reference.mediaType.contains("aac", ignoreCase = true) -> "aac"
        reference.mediaType.contains("ogg", ignoreCase = true) -> "ogg"
        reference.mediaType.contains("wav", ignoreCase = true) -> "wav"
        else -> "bin"
    }

/** mm:ss formatter; durations cap below an hour for voice notes. */
internal fun formatVoiceTime(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
}

@Composable
internal fun VoiceWaveform(
    bars: FloatArray,
    progress: Float,
    playedColor: Color,
    remainingColor: Color,
    modifier: Modifier = Modifier,
    onSeek: ((fraction: Float) -> Unit)? = null,
) {
    var widthPx by remember { mutableStateOf(0f) }
    val seekModifier =
        if (onSeek != null) {
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Consume so the bubble's parent swipe-to-reply gesture
                    // doesn't snatch a rightward drag mid-scrub.
                    down.consume()
                    // Before the first onSizeChanged, widthPx is 0 → x/0 = NaN → a
                    // stray seek-to-zero. Skip the gesture until the size is known.
                    if (widthPx <= 0f) return@awaitEachGesture
                    onSeek((down.position.x / widthPx).coerceIn(0f, 1f))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        onSeek((change.position.x / widthPx).coerceIn(0f, 1f))
                        if (change.changedToUp() || !change.pressed) break
                    }
                }
            }
        } else {
            Modifier
        }
    Canvas(
        modifier =
            modifier
                .then(seekModifier)
                .onSizeChanged { widthPx = it.width.toFloat() },
    ) {
        val barCount = bars.size
        if (barCount == 0) return@Canvas
        val totalWidth = size.width
        val totalHeight = size.height
        val barSlot = totalWidth / barCount
        val barWidth = barSlot * 0.55f
        val cornerRadius =
            androidx.compose.ui.geometry
                .CornerRadius(barWidth / 2f, barWidth / 2f)
        val playedBars = (progress * barCount).toInt()
        for (i in 0 until barCount) {
            val barHeight = totalHeight * bars[i]
            val x = i * barSlot + (barSlot - barWidth) / 2f
            val y = (totalHeight - barHeight) / 2f
            val color = if (i < playedBars) playedColor else remainingColor
            drawRoundRect(
                color = color,
                topLeft =
                    androidx.compose.ui.geometry
                        .Offset(x, y),
                size =
                    androidx.compose.ui.geometry
                        .Size(barWidth, barHeight),
                cornerRadius = cornerRadius,
            )
        }
    }
}

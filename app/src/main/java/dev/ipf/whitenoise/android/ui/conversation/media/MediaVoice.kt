package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.VoicePlaybackController.PlaybackStartResult
import dev.ipf.whitenoise.android.media.AttachmentCachePublication
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.KeyedMutexPool
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.downloadAttachmentSource
import dev.ipf.whitenoise.android.state.evictCachedAttachment
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationMessageMetrics
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationRichContentShape
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val voiceMaterializations = SingleFlight<VoiceMaterializationFlightKey, java.io.File>()

/** Serializes stable cache publication across owner-scoped presentation flights. */
private val voiceMaterializationPathLocks = KeyedMutexPool()

/** Keeps in-flight source work isolated to the controller that authorized it. */
private class VoiceMaterializationFlightKey(
    private val filePath: String,
    private val presentationOwner: Any?,
) {
    override fun equals(other: Any?): Boolean =
        other is VoiceMaterializationFlightKey &&
            filePath == other.filePath &&
            presentationOwner == other.presentationOwner

    override fun hashCode(): Int = 31 * filePath.hashCode() + presentationOwner.hashCode()
}

/** Referential screen owner plus its account/runtime generations. */
private class VoicePresentationOwnerKey(
    private val controller: ConversationController,
    private val appState: WhiteNoiseAppState,
    private val accountRef: String?,
    private val runtimeGeneration: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is VoicePresentationOwnerKey &&
            controller === other.controller &&
            appState === other.appState &&
            accountRef == other.accountRef &&
            runtimeGeneration == other.runtimeGeneration

    override fun hashCode(): Int {
        var result = System.identityHashCode(controller)
        result = 31 * result + System.identityHashCode(appState)
        result = 31 * result + accountRef.hashCode()
        return 31 * result + runtimeGeneration
    }
}

/** Stable identity for one voice attachment within an owner-keyed Compose subtree. */
internal data class VoicePresentationAttachmentKey(
    val messageIdHex: String,
    val attachmentIndex: Int,
    val sourceEpoch: ULong,
)

/** Captures one exact controller/app/account/runtime generation for state and source ownership. */
private fun voicePresentationOwnerKey(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    accountRef: String?,
    runtimeGeneration: Int,
): Any = VoicePresentationOwnerKey(controller, appState, accountRef, runtimeGeneration)

/** Remembers the exact presentation owner that must bound one voice row subtree. */
@Composable
internal fun rememberVoicePresentationOwner(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
): Any {
    val accountRef = controller.boundAccountRef
    val runtimeGeneration = appState.runtimeGeneration
    return remember(controller, appState, accountRef, runtimeGeneration) {
        voicePresentationOwnerKey(
            controller = controller,
            appState = appState,
            accountRef = accountRef,
            runtimeGeneration = runtimeGeneration,
        )
    }
}

/** Inputs needed to materialize one voice attachment for presentation. */
internal data class VoiceAttachmentMaterializationRequest(
    val context: Context,
    val controller: ConversationController,
    val presentationOwner: Any,
    val messageIdHex: String,
    val attachmentIndex: Int,
    val reference: MediaAttachmentReferenceFfi,
    val mine: Boolean,
    val priority: AttachmentDownloadPriority,
)

/** External file, codec, and platform-playback boundary used by voice-note presentation. */
internal interface VoiceAttachmentPresentationRuntime {
    /** Process-wide playback state observed by voice rows. */
    val playbackState: kotlinx.coroutines.flow.StateFlow<VoicePlaybackController.PlaybackState>

    /** Playback failures that require a corrupt cache entry to be invalidated. */
    val playbackFailures: kotlinx.coroutines.flow.SharedFlow<VoicePlaybackController.PlaybackFailure>

    /** Resolves or publishes the stable local file for one attachment. */
    suspend fun materialize(request: VoiceAttachmentMaterializationRequest): java.io.File

    /** Probes the playable duration of [file] in milliseconds. */
    suspend fun durationMs(file: java.io.File): Int

    /** Starts or resumes the keyed voice clip. */
    suspend fun play(
        key: String,
        file: java.io.File,
        ownerKey: String,
    ): VoicePlaybackController.PlaybackStartResult

    /** Pauses the active voice clip. */
    fun pause()

    /** Seeks the keyed voice clip to [positionMs]. */
    fun seekTo(
        key: String,
        positionMs: Int,
    )

    /** Advances the process-wide voice speed preference. */
    fun cycleSpeed()
}

private object DefaultVoiceAttachmentPresentationRuntime : VoiceAttachmentPresentationRuntime {
    override val playbackState = VoicePlaybackController.state
    override val playbackFailures = VoicePlaybackController.failures

    /** Materializes the voice attachment through the shared attachment cache. */
    override suspend fun materialize(request: VoiceAttachmentMaterializationRequest): java.io.File =
        materializeVoiceAttachment(
            context = request.context,
            controller = request.controller,
            messageIdHex = request.messageIdHex,
            attachmentIndex = request.attachmentIndex,
            reference = request.reference,
            mine = request.mine,
            priority = request.priority,
            materializationOwner = request.presentationOwner,
        )

    override suspend fun durationMs(file: java.io.File): Int = VoicePlaybackController.probeDuration(file)

    override suspend fun play(
        key: String,
        file: java.io.File,
        ownerKey: String,
    ): VoicePlaybackController.PlaybackStartResult = VoicePlaybackController.play(key, file, ownerKey)

    override fun pause() {
        VoicePlaybackController.pause()
    }

    override fun seekTo(
        key: String,
        positionMs: Int,
    ) {
        VoicePlaybackController.seekTo(key, positionMs)
    }

    override fun cycleSpeed() {
        VoicePlaybackController.cycleSpeed()
    }
}

/** Test-injectable external-work boundary; production always uses the default runtime. */
internal val LocalVoiceAttachmentPresentationRuntime =
    staticCompositionLocalOf<VoiceAttachmentPresentationRuntime> { DefaultVoiceAttachmentPresentationRuntime }

internal fun voicePlaybackKey(
    messageIdHex: String,
    attachmentIndex: Int,
    sourceEpoch: ULong,
): String = AttachmentCachePublication.attachmentKey(messageIdHex, attachmentIndex, sourceEpoch)

/**
 * Renders one stable-size voice attachment row whose download and playback
 * state changes must not alter its outer lazy-list geometry.
 */
@Composable
internal fun MediaVoiceBubble(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    presentationOwner: Any,
    mine: Boolean,
    onLongPress: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val presentationRuntime = LocalVoiceAttachmentPresentationRuntime.current
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
    var totalDurationMs by remember(pillKey, epoch) { mutableIntStateOf(0) }
    var loading by remember(pillKey, epoch) { mutableStateOf(false) }
    var failed by remember(pillKey, epoch) { mutableStateOf(false) }
    // Auto-download gate (#407): retained/cached own clips always materialize;
    // a cache-missing own clip waits during an explicit backlog pause. Incoming
    // clips honor the Audio matrix row unless the attachment is already local.
    // A cached voice file or controller plaintext
    // cache means re-entering the chat should start at Play instead of showing
    // a fake Download affordance. Policy can grant an idle intent but cannot
    // revoke accepted work; a tap promotes the intent so manual fetch/playback
    // remains available even when auto-download is off.
    val automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused()
    val policyAllowsMaterialization =
        shouldStartVoiceAttachmentDownload(
            mine = mine,
            audioAutoDownload = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Audio),
            automaticDownloadsPaused = automaticDownloadsPaused,
            hasCachedAttachment = cachedPlaintextOnEntry,
            hasCachedFile = localFile != null,
            hasRetainedPlaintext = retainedPlaintextOnEntry,
        )
    var materializationIntent by
        rememberAttachmentMaterializationIntent(
            identity = "$messageIdHex#$attachmentIndex",
            policyAllowsMaterialization = policyAllowsMaterialization,
        )
    val startDownload = materializationIntent.shouldMaterialize
    var reloadToken by remember(pillKey, epoch) { mutableIntStateOf(0) }

    val playback by remember(pillKey, presentationRuntime) {
        presentationRuntime.playbackState
            .map { state -> state.takeIf { it.key == pillKey } }
            .distinctUntilChanged()
    }.collectAsState(null)
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

    /** Drops a corrupt cached voice file so the next play re-downloads it. */
    suspend fun clearBadVoiceCache(reason: String) {
        Log.w("MediaVoiceBubble", "voice_cache_cleared reason=${reason.replace(' ', '_')}")
        clearVoiceAttachmentCacheAfterPlaybackFailure(
            context = context,
            controller = controller,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        )
        localFile = null
        totalDurationMs = 0
        failed = true
        val retryAllowedByPolicy =
            shouldStartVoiceAttachmentDownload(
                mine = mine,
                audioAutoDownload = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Audio),
                automaticDownloadsPaused = automaticDownloadsPaused,
                hasCachedAttachment = false,
                hasCachedFile = false,
                hasRetainedPlaintext = retainedPlaintextOnEntry,
            )
        materializationIntent =
            AttachmentMaterializationIntent.Idle.withPolicyAllowed(retryAllowedByPolicy)
    }

    /** Plays a materialized voice file after validating the cache entry. */
    suspend fun playReadyVoice(file: java.io.File) {
        val playableFile =
            withContext(Dispatchers.IO) {
                validatedAttachmentCacheFile(file)
            }
        if (playableFile == null) {
            localFile = null
            totalDurationMs = 0
            controller.requestAttachmentOpen(messageIdHex, attachmentIndex)
            return
        }
        localFile = playableFile
        val playbackResult = presentationRuntime.play(pillKey, playableFile, controller.group.groupIdHex)
        if (shouldInvalidateVoiceAttachmentCache(playbackResult)) {
            clearBadVoiceCache("playback start failed")
        }
    }

    LaunchedEffect(pillKey, epoch, reference.mediaType, presentationRuntime) {
        presentationRuntime.playbackFailures.collect { failure ->
            if (failure.key == pillKey && failure.invalidatesCache) {
                clearBadVoiceCache("playback error")
            }
        }
    }

    LaunchedEffect(pillKey, epoch, materializationIntent, reloadToken, presentationRuntime) {
        if (localFile != null) return@LaunchedEffect
        // Honor the auto-download gate: when Audio is off for the active
        // connection the clip waits behind a Download affordance until the
        // user opts in (tap promotes the intent). Manual playback below
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
            presentationRuntime.materialize(
                VoiceAttachmentMaterializationRequest(
                    context = context,
                    controller = controller,
                    presentationOwner = presentationOwner,
                    messageIdHex = messageIdHex,
                    attachmentIndex = attachmentIndex,
                    reference = reference,
                    mine = mine,
                    priority = materializationIntent.priority,
                ),
            )
        }.onSuccess { file ->
            localFile = file
            failed = false
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) {
                materializationIntent = materializationIntent.afterProducerCancellation(it)
            } else {
                Log.w("MediaVoiceBubble", "voice_auto_materialize_failed")
                failed = true
            }
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
            materializationIntent = materializationIntent.afterInteractiveRequest()
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
    LaunchedEffect(pillKey, epoch, localFile, presentationRuntime) {
        val file = localFile ?: return@LaunchedEffect
        if (totalDurationMs == 0) {
            val probed = presentationRuntime.durationMs(file)
            if (probed > 0) totalDurationMs = probed
        }
    }

    VoiceAttachmentContent(
        loading = loading,
        failed = failed,
        startDownload = startDownload,
        localFileAvailable = localFile != null,
        isPlaying = isPlayingThis,
        isPaused = isPausedThis,
        activePositionMs = activePositionMs,
        activeDurationMs = activeDurationMs,
        totalDurationMs = totalDurationMs,
        progressFraction = progressFraction,
        outgoing = mine,
        // The speed pill only makes sense for the clip that owns playback; a
        // seek needs a known duration to turn the track fraction into a position.
        playbackSpeed = playback?.speed?.takeIf { isPlayingThis || isPausedThis },
        onSeek =
            if ((isPlayingThis || isPausedThis) && activeDurationMs > 0) {
                { fraction -> presentationRuntime.seekTo(pillKey, (fraction * activeDurationMs).toInt()) }
            } else {
                null
            },
        onCycleSpeed = presentationRuntime::cycleSpeed,
        onLongPress = onLongPress,
        onActionClick = {
            when {
                loading || localFile == null ->
                    controller.requestAttachmentOpen(messageIdHex, attachmentIndex)
                isPlayingThis -> {
                    failed = false
                    presentationRuntime.pause()
                }
                else -> {
                    failed = false
                    scope.launch {
                        val readyFile = localFile ?: return@launch
                        playReadyVoice(readyFile)
                    }
                }
            }
        },
    )
}

/**
 * Stable voice-row presentation shared by production materialization and the
 * real-device LazyColumn geometry regression.
 */
@Composable
// Compose naming and one stateless layout keep every visual phase geometry-identical.
@Suppress(
    "FunctionNaming", // Jetpack Compose functions use UpperCamelCase.
    "LongMethod",
    "LongParameterList",
)
/**
 * Voice bubble content: the 48dp play target, a progress track that doubles as the seek surface,
 * the time label, and a compact speed pill that appears only while this clip is playing or paused.
 * [onSeek] receives the tapped or dragged track fraction (0..1) and is null when seeking is unavailable.
 */
internal fun VoiceAttachmentContent(
    loading: Boolean,
    failed: Boolean,
    startDownload: Boolean,
    localFileAvailable: Boolean,
    isPlaying: Boolean,
    isPaused: Boolean,
    activePositionMs: Int,
    activeDurationMs: Int,
    totalDurationMs: Int,
    progressFraction: Float,
    outgoing: Boolean,
    onLongPress: () -> Unit,
    onActionClick: () -> Unit,
    playbackSpeed: Float? = null,
    onSeek: ((Float) -> Unit)? = null,
    onCycleSpeed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val bubbleContent = LocalContentColor.current
    val tinted = outgoing && !isAmoledSurfaceTheme()
    val container =
        if (tinted) bubbleContent.copy(alpha = VOICE_CARD_TINT_ALPHA) else MaterialTheme.colorScheme.surfaceContainer
    val content = if (tinted) bubbleContent else MaterialTheme.colorScheme.onSurface
    val secondaryContent =
        if (tinted) {
            bubbleContent.copy(alpha = VOICE_CARD_SECONDARY_ALPHA)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val actionVisual = voiceActionVisual(loading, failed, startDownload, localFileAvailable, isPlaying)
    val actionDescription = stringResource(actionVisual.descriptionResource)
    Surface(
        color = container,
        contentColor = content,
        shape = ConversationRichContentShape,
        modifier = modifier.width(ConversationMessageMetrics.RichContentCanvasWidth),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(ConversationMessageMetrics.RichComponentInset),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .semantics(mergeDescendants = true) { contentDescription = actionDescription }
                        .combinedClickable(onLongClick = onLongPress, onClick = onActionClick),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) { VoiceActionGlyph(actionVisual) }
                }
            }
            VoiceSeekTrack(
                progressFraction = progressFraction,
                color = if (tinted) bubbleContent else MaterialTheme.colorScheme.primary,
                trackColor = content.copy(alpha = VOICE_TRACK_ALPHA),
                onSeek = onSeek,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = voiceTimeText(isPlaying, isPaused, activePositionMs, activeDurationMs, totalDurationMs),
                style = MaterialTheme.typography.labelMedium,
                color = secondaryContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (playbackSpeed != null) {
                VoiceSpeedPill(
                    currentSpeed = playbackSpeed,
                    accent = if (tinted) bubbleContent else MaterialTheme.colorScheme.primary,
                    onCycleSpeed = onCycleSpeed,
                )
            }
        }
    }
}

internal const val VOICE_SEEK_TRACK_TAG = "voice-seek-track"

/**
 * The progress bar is the seek surface: the whole 48dp-tall strip accepts a tap or a horizontal drag
 * and reports the pointer's position as a 0..1 fraction. Without [onSeek] it is a plain indicator.
 */
@Suppress("FunctionNaming")
@Composable
private fun VoiceSeekTrack(
    progressFraction: Float,
    color: Color,
    trackColor: Color,
    onSeek: ((Float) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableFloatStateOf(0f) }
    val currentOnSeek by rememberUpdatedState(onSeek)
    Box(
        modifier =
            modifier
                .height(VoiceSeekTargetHeight)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .voiceSeekGesture(enabled = onSeek != null, widthPx = { widthPx }) { fraction ->
                    currentOnSeek?.invoke(fraction)
                }.testTag(VOICE_SEEK_TRACK_TAG),
        contentAlignment = Alignment.Center,
    ) {
        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier.fillMaxWidth(),
            color = color,
            trackColor = trackColor,
            drawStopIndicator = {},
        )
    }
}

/** Compact speed action: reads the current multiplier and advances to the next preset on tap. */
@Suppress("FunctionNaming")
@Composable
private fun VoiceSpeedPill(
    currentSpeed: Float,
    accent: Color,
    onCycleSpeed: () -> Unit,
) {
    val label = voiceSpeedLabel(currentSpeed)
    val description = stringResource(R.string.voice_message_speed, label)
    Surface(
        onClick = onCycleSpeed,
        color = accent.copy(alpha = VOICE_SPEED_PILL_ALPHA),
        contentColor = accent,
        shape = RoundedCornerShape(VoiceSpeedPillCorner),
        modifier =
            Modifier.semantics {
                contentDescription = description
                role = Role.Button
            },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier =
                Modifier.padding(
                    horizontal = VoiceSpeedPillHorizontalPadding,
                    vertical = VoiceSpeedPillVerticalPadding,
                ),
        )
    }
}

/** Formats a playback multiplier as the pill label, snapping to the three presets: 1×, 1.5× and 2×. */
internal fun voiceSpeedLabel(speed: Float): String =
    when {
        speed >= VOICE_SPEED_DOUBLE_THRESHOLD -> "2×"
        speed >= VOICE_SPEED_FAST_THRESHOLD -> "1.5×"
        else -> "1×"
    }

/**
 * Maps the pointer's horizontal position over this surface to a 0..1 fraction for as long as it is
 * pressed. The down is consumed so the bubble's swipe-to-reply cannot snatch a rightward scrub, and
 * the gesture is skipped until [widthPx] is known so x/0 never produces a stray seek to zero.
 */
private fun Modifier.voiceSeekGesture(
    enabled: Boolean,
    widthPx: () -> Float,
    onSeek: (Float) -> Unit,
): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val width = widthPx()
                if (width <= 0f) return@awaitEachGesture
                onSeek((down.position.x / width).coerceIn(0f, 1f))
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    onSeek((change.position.x / width).coerceIn(0f, 1f))
                    if (change.changedToUp() || !change.pressed) break
                }
            }
        }
    }

/** The 24dp glyph inside the play button for each action variant. */
@Suppress("FunctionNaming")
@Composable
private fun VoiceActionGlyph(actionVisual: VoiceActionVisual) {
    when (actionVisual) {
        VoiceActionVisual.LOADING ->
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
        VoiceActionVisual.FAILED ->
            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(24.dp))
        VoiceActionVisual.DOWNLOAD ->
            Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(24.dp))
        VoiceActionVisual.PAUSE ->
            Icon(
                painter = painterResource(R.drawable.ic_pause),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        VoiceActionVisual.PLAY ->
            Icon(
                painter = painterResource(R.drawable.ic_play_arrow),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
    }
}

private const val VOICE_CARD_TINT_ALPHA = 0.12f
private const val VOICE_CARD_SECONDARY_ALPHA = 0.78f
private const val VOICE_TRACK_ALPHA = 0.24f
private const val VOICE_SPEED_PILL_ALPHA = 0.15f
private const val VOICE_SPEED_DOUBLE_THRESHOLD = 1.95f
private const val VOICE_SPEED_FAST_THRESHOLD = 1.45f
private val VoiceSeekTargetHeight = 48.dp
private val VoiceSpeedPillCorner = 10.dp
private val VoiceSpeedPillHorizontalPadding = 8.dp
private val VoiceSpeedPillVerticalPadding = 4.dp

/** Stable action variants keep accessibility copy and glyph selection in lockstep. */
private enum class VoiceActionVisual(
    val descriptionResource: Int,
) {
    LOADING(R.string.media_downloading),
    FAILED(R.string.voice_message_failed),
    DOWNLOAD(R.string.media_tap_to_download),
    PAUSE(R.string.voice_message_pause),
    PLAY(R.string.voice_message_play),
}

/** Resolves one action variant without coupling presentation to materialization owners. */
private fun voiceActionVisual(
    loading: Boolean,
    failed: Boolean,
    startDownload: Boolean,
    localFileAvailable: Boolean,
    isPlaying: Boolean,
): VoiceActionVisual =
    when {
        loading -> VoiceActionVisual.LOADING
        failed -> VoiceActionVisual.FAILED
        !startDownload && !localFileAvailable -> VoiceActionVisual.DOWNLOAD
        isPlaying -> VoiceActionVisual.PAUSE
        else -> VoiceActionVisual.PLAY
    }

/** Formats the fixed-height time label for download, ready, and active playback phases. */
private fun voiceTimeText(
    isPlaying: Boolean,
    isPaused: Boolean,
    activePositionMs: Int,
    activeDurationMs: Int,
    totalDurationMs: Int,
): String =
    when {
        isPlaying || isPaused -> "${formatVoiceTime(activePositionMs)} / ${formatVoiceTime(activeDurationMs)}"
        totalDurationMs > 0 -> formatVoiceTime(totalDurationMs)
        else -> "0:00"
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
    materializationOwner: Any =
        voicePresentationOwnerKey(
            controller = controller,
            appState = controller.appState,
            accountRef = controller.boundAccountRef,
            runtimeGeneration = controller.appState.runtimeGeneration,
        ),
): java.io.File =
    materializeVoiceAttachmentSource(
        context = context,
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = reference,
        materializationOwner = materializationOwner,
        resolveSource = {
            val retained =
                if (mine) {
                    controller
                        .pendingAttachmentsList(messageIdHex)
                        .getOrNull(attachmentIndex)
                        ?.plaintextBytes
                } else {
                    null
                }
            retained?.let(AttachmentPlaintext::Bytes)
                ?: controller.downloadAttachmentSource(messageIdHex, attachmentIndex, reference, priority)
        },
    )

/**
 * Publishes one closeable voice source behind a path-wide recheck. Replacement owners reuse a
 * completed file, but resolve independently after a preceding owner fails.
 */
@VisibleForTesting
internal suspend fun materializeVoiceAttachmentSource(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    materializationOwner: Any? = null,
    resolveSource: suspend () -> AttachmentPlaintext,
): java.io.File {
    val file = voiceAttachmentCacheFile(context, messageIdHex, attachmentIndex, reference)
    val attachmentKey = AttachmentCachePublication.attachmentKey(messageIdHex, attachmentIndex, reference.sourceEpoch)
    return voiceMaterializations.run(VoiceMaterializationFlightKey(file.absolutePath, materializationOwner)) {
        voiceMaterializationPathLocks.withLock(file.absolutePath) {
            withContext(Dispatchers.IO) {
                file.takeIf { it.isFile && it.length() > 0L }?.also(AttachmentPlaintextCache::touch)
            } ?: run {
                val published = AttachmentCachePublication.publishSourceAfterLoad(attachmentKey, file, resolveSource)
                if (!published) throw java.io.IOException("attachment cache publication aborted for ${file.name}")
                file
            }
        }
    }
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

internal fun shouldInvalidateVoiceAttachmentCache(playbackResult: PlaybackStartResult): Boolean =
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

/** Recording-review waveform whose bars double as a seek surface when [onSeek] is provided. */
@Suppress("FunctionNaming")
@Composable
internal fun VoiceWaveform(
    bars: FloatArray,
    progress: Float,
    playedColor: Color,
    remainingColor: Color,
    modifier: Modifier = Modifier,
    onSeek: ((fraction: Float) -> Unit)? = null,
) {
    var widthPx by remember { mutableFloatStateOf(0f) }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val seekModifier =
        Modifier.voiceSeekGesture(enabled = onSeek != null, widthPx = { widthPx }) { fraction ->
            currentOnSeek?.invoke(fraction)
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

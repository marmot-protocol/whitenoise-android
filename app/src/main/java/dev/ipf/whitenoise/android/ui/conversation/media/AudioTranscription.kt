@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.AttachmentSpeechException
import dev.ipf.whitenoise.android.audio.AttachmentSpeechFailure
import dev.ipf.whitenoise.android.audio.transcribeAttachment
import dev.ipf.whitenoise.android.media.holdCacheFileInUse
import dev.ipf.whitenoise.android.media.releaseCacheFileInUse
import dev.ipf.whitenoise.android.translation.MessageSideDisc
import dev.ipf.whitenoise.android.translation.MessageSideDiscProgress
import dev.ipf.whitenoise.android.ui.conversation.messages.BubbleFooterLayout
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationMessageMetrics
import dev.ipf.whitenoise.android.ui.conversation.messages.bubbleFooterLastLineWidth
import dev.ipf.whitenoise.android.ui.settings.openOfflineSpeechToTextListing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.coroutineContext

internal const val AUDIO_TRANSCRIBE_TEST_TAG = "audio.transcribe"
internal const val AUDIO_TRANSCRIPT_TEST_TAG = "audio.transcription.body"

internal enum class AudioTranscriptPhase { WORKING, COMPLETE, FAILED }

internal data class AudioTranscriptUi(
    val phase: AudioTranscriptPhase,
    val text: String = "",
    val failure: AttachmentSpeechFailure = AttachmentSpeechFailure.RECOGNITION_FAILED,
)

/** One attachment's contribution to its message's transcribe disc. */
private data class AudioTranscriptSlot(
    val ready: Boolean,
    val working: Boolean,
    val failedAutomatically: Boolean,
)

/**
 * The per-message switch behind the side transcribe disc. Each received recording registers whether its file is
 * ready and whether work is in flight; the disc shows when any recording is registered and drives them all.
 * The conversation's [VoiceTranscriptionScope] retains the visible choice while scrolling. Outside a
 * conversation the toggle keeps its own scope.
 */
@Stable
internal class AudioTranscriptionToggleState(
    private val messageIdHex: String = "",
) {
    private val ownScope by lazy {
        VoiceTranscriptionScope(ReceivedVoiceTranscripts(capacity = OWN_SCOPE_CAPACITY), "", automatic = false)
    }
    private val slots = mutableStateMapOf<Any, AudioTranscriptSlot>()

    val present: Boolean get() = slots.isNotEmpty()
    val enabled: Boolean get() = slots.values.any { it.ready }
    val working: Boolean get() = slots.values.any { it.working }
    val automaticFailed: Boolean get() = enabled && slots.values.none { it.ready && !it.failedAutomatically }

    fun scope(provided: VoiceTranscriptionScope?): VoiceTranscriptionScope = provided ?: ownScope

    fun requested(provided: VoiceTranscriptionScope?): Boolean = scope(provided).shown(messageIdHex)

    fun explicitlyRequested(provided: VoiceTranscriptionScope?) = scope(provided).requestedExplicitly(messageIdHex)

    fun setRequested(
        provided: VoiceTranscriptionScope?,
        value: Boolean,
    ) = scope(provided).setShown(messageIdHex, value)

    /** Returns the cached transcript for this recording while the message's transcript is shown. */
    fun completedText(
        provided: VoiceTranscriptionScope?,
        index: Int,
        epoch: ULong,
    ): String? {
        val key = voicePlaybackKey(messageIdHex, index, epoch)
        return scope(provided).result(key)?.text.takeIf { requested(provided) }
    }

    /** Offers the menu action once a recording is ready, using the same request as the side disc. */
    fun menuAction(provided: VoiceTranscriptionScope?): (() -> Unit)? {
        if (!enabled) return null
        return { setRequested(provided, true) }
    }

    fun register(
        key: Any,
        ready: Boolean,
        working: Boolean,
        failedAutomatically: Boolean,
    ) {
        slots[key] = AudioTranscriptSlot(ready, working, failedAutomatically)
    }

    fun unregister(key: Any) {
        slots.remove(key)
    }
}

/** A received voice message has one transcription control shared by its recordings. */
@Composable
internal fun rememberReceivedAudioTranscription(
    messageIdHex: String,
    mine: Boolean,
): AudioTranscriptionToggleState? {
    if (mine) return null
    return remember(messageIdHex) { AudioTranscriptionToggleState(messageIdHex) }
}

/**
 * Transcribes the received recording on this device while its message's transcript is shown, either because the
 * automatic setting is on or because the person turned the side disc on, and reveals the text beneath the voice
 * row once it is ready. Turning the disc off hides it and cancels pending work. Leaving the foreground clears
 * the conversation cache and cancels work. An automatic attempt that
 * fails stays quiet. An explicit request retries and can show guidance. Transcripts never enter the message
 * store or composer.
 */
@Composable
internal fun ReceivedAudioTranscription(
    file: File?,
    attachmentKey: String,
    toggle: AudioTranscriptionToggleState,
    completedInFooter: Boolean = false,
) {
    val context = LocalContext.current
    val provided = LocalVoiceTranscription.current
    val scope = toggle.scope(provided)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val slotKey = remember { Any() }
    val shown = toggle.requested(provided)
    val explicit = toggle.explicitlyRequested(provided)
    val complete = scope.result(attachmentKey)
    var pending by remember(scope, attachmentKey, file) { mutableStateOf<AudioTranscriptUi?>(null) }
    var attempt by remember(scope, attachmentKey, file) { mutableIntStateOf(0) }
    var listingFailed by remember { mutableStateOf(false) }
    val failedAutomatically = shown && !explicit && pending?.phase == AudioTranscriptPhase.FAILED
    val ui = if (shown) complete ?: pending.takeUnless { failedAutomatically } else null
    val working = ui?.phase == AudioTranscriptPhase.WORKING

    val ready = file != null || complete != null
    LaunchedEffect(toggle, ready, working, failedAutomatically) {
        toggle.register(slotKey, ready, working, failedAutomatically)
    }
    DisposableEffect(toggle) { onDispose { toggle.unregister(slotKey) } }

    val needsRecognition = shown && complete == null
    LaunchedEffect(scope, attachmentKey, needsRecognition, explicit, file, started, attempt) {
        listingFailed = false
        if (!needsRecognition || file == null || !started) {
            pending = null
            return@LaunchedEffect
        }
        pending = AudioTranscriptUi(AudioTranscriptPhase.WORKING)
        val result = loadAudioTranscript(context, file, scope.transcripts.gate)
        coroutineContext.ensureActive()
        if (result.phase == AudioTranscriptPhase.COMPLETE) {
            scope.storeResult(attachmentKey, result)
            pending = null
        } else {
            pending = result
        }
    }

    AudioTranscriptBody(
        state = ui.takeUnless { completedInFooter && it?.phase == AudioTranscriptPhase.COMPLETE },
        listingError = if (listingFailed) stringResource(R.string.dictation_ostt_recommendation_open_failed) else null,
        onRetry = {
            listingFailed = false
            attempt++
        },
        onGetProvider = { listingFailed = !openOfflineSpeechToTextListing(context) },
    )
}

/** The cache lease covers decoding and recognition, including cancellation cleanup. */
private suspend fun loadAudioTranscript(
    context: Context,
    file: File,
    gate: Mutex,
): AudioTranscriptUi {
    holdCacheFileInUse(file)
    return try {
        gate.withLock { AudioTranscriptUi(AudioTranscriptPhase.COMPLETE, transcribeAttachment(context, file)) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: AttachmentSpeechException) {
        coroutineContext.ensureActive()
        AudioTranscriptUi(AudioTranscriptPhase.FAILED, failure = error.failure)
    } catch (_: Exception) {
        coroutineContext.ensureActive()
        AudioTranscriptUi(AudioTranscriptPhase.FAILED, failure = AttachmentSpeechFailure.AUDIO_REJECTED)
    } finally {
        releaseCacheFileInUse(file)
    }
}

/** The message's side transcribe disc; it emits nothing until a received recording registers with [toggle]. */
@Composable
internal fun AudioTranscriptionToggle(
    toggle: AudioTranscriptionToggleState?,
    modifier: Modifier = Modifier,
) {
    if (toggle == null || !toggle.present) return
    val provided = LocalVoiceTranscription.current
    val on = toggle.requested(provided) && !toggle.automaticFailed
    AudioTranscribeDisc(
        on = on,
        working = on && toggle.working,
        // A requested switch stays tappable so it can cancel a request whose recording became unavailable.
        enabled = on || toggle.enabled,
        onToggle = { toggle.setRequested(provided, it) },
        modifier = modifier,
    )
}

/** Stateless transcribe disc: the translate disc's shape and colors, with a spinner while work is in flight. */
@Composable
internal fun AudioTranscribeDisc(
    on: Boolean,
    working: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    MessageSideDisc(
        on = on,
        description = stringResource(R.string.transcribe_audio),
        onToggle = onToggle,
        enabled = enabled,
        stateDescription = if (working) stringResource(R.string.audio_transcription_working) else null,
        modifier = modifier.heightIn(min = 28.dp).testTag(AUDIO_TRANSCRIBE_TEST_TAG),
        discSize = 24.dp,
    ) {
        if (working) {
            MessageSideDiscProgress()
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Stateless in-bubble result showing plain body text or compact failure guidance. Nothing renders while the
 * disc is off or work is in flight, so the voice row stays as tight as before;
 * a result that arrives while the bubble is on screen expands into place instead of jumping in.
 */
@Composable
internal fun AudioTranscriptBody(
    state: AudioTranscriptUi?,
    onRetry: () -> Unit,
    onGetProvider: () -> Unit,
    modifier: Modifier = Modifier,
    listingError: String? = null,
) {
    val visible = state?.takeIf { it.phase != AudioTranscriptPhase.WORKING }
    // The last result stays composed while the body collapses away.
    val retained = remember { arrayOfNulls<AudioTranscriptUi>(1) }
    if (visible != null) retained[0] = visible
    val generated = stringResource(R.string.audio_transcription_generated)
    AnimatedVisibility(
        visible = visible != null,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        val shown = retained[0] ?: return@AnimatedVisibility
        Column(
            modifier =
                modifier
                    .widthIn(max = ConversationMessageMetrics.RichContentCanvasWidth)
                    .padding(
                        start = ConversationMessageMetrics.RichTextHorizontalAdjustment,
                        end = ConversationMessageMetrics.RichTextHorizontalAdjustment,
                        top = ConversationMessageMetrics.RichContentSpacing,
                        bottom = ConversationMessageMetrics.RichTextBottomAdjustment,
                    ).semantics {
                        liveRegion = LiveRegionMode.Polite
                        if (shown.phase == AudioTranscriptPhase.COMPLETE) stateDescription = generated
                    }.testTag(AUDIO_TRANSCRIPT_TEST_TAG),
        ) {
            AudioTranscriptResult(shown, listingError, onRetry, onGetProvider)
        }
    }
}

/** Keeps the timestamp and transcript in one animated content area. */
internal fun Modifier.animateTranscriptFooter(enabled: Boolean): Modifier {
    if (!enabled) return this
    return animateContentSize(alignment = Alignment.TopStart)
}

/** Uses the same last-line footer placement as a regular message caption. */
@Composable
internal fun AudioTranscriptWithFooter(
    text: String,
    footer: @Composable () -> Unit,
) {
    val generated = stringResource(R.string.audio_transcription_generated)
    val direction = LocalLayoutDirection.current
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }

    BubbleFooterLayout(
        footer = footer,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = generated
                }.testTag(AUDIO_TRANSCRIPT_TEST_TAG),
        lastLineWidth = { layout?.bubbleFooterLastLineWidth(direction) },
        lastLineBaseline = {
            layout?.takeIf { it.lineCount > 0 }?.let { it.getLineBaseline(it.lineCount - 1).toInt() }
        },
    ) {
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodyLarge, onTextLayout = { layout = it })
        }
    }
}

private const val OWN_SCOPE_CAPACITY = 8

@Composable
private fun AudioTranscriptResult(
    state: AudioTranscriptUi,
    listingError: String?,
    onRetry: () -> Unit,
    onGetProvider: () -> Unit,
) {
    when (state.phase) {
        AudioTranscriptPhase.COMPLETE -> {
            SelectionContainer { Text(state.text, style = MaterialTheme.typography.bodyLarge) }
        }
        AudioTranscriptPhase.FAILED -> {
            Text(
                stringResource(transcriptionFailureText(state.failure)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            val needsProvider =
                state.failure == AttachmentSpeechFailure.PROVIDER_MISSING ||
                    state.failure == AttachmentSpeechFailure.PROVIDER_OUTDATED
            val action =
                when {
                    needsProvider -> R.string.dictation_ostt_recommendation_title to onGetProvider
                    state.failure != AttachmentSpeechFailure.ANDROID_VERSION -> R.string.retry to onRetry
                    else -> null
                }
            action?.let { (label, onClick) ->
                TextButton(
                    onClick = onClick,
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    // A pill clips wrapped labels that start flush with the transcript text.
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.heightIn(min = 40.dp),
                ) { Text(stringResource(label)) }
            }
            if (listingError != null) {
                Text(listingError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        AudioTranscriptPhase.WORKING -> Unit
    }
}

private fun transcriptionFailureText(failure: AttachmentSpeechFailure): Int =
    when (failure) {
        AttachmentSpeechFailure.ANDROID_VERSION -> R.string.audio_transcription_android_version
        AttachmentSpeechFailure.PROVIDER_MISSING -> R.string.audio_transcription_provider_missing
        AttachmentSpeechFailure.PROVIDER_OUTDATED -> R.string.audio_transcription_provider_outdated
        AttachmentSpeechFailure.PROVIDER_UNAVAILABLE -> R.string.audio_transcription_provider_unavailable
        AttachmentSpeechFailure.PERMISSION_DENIED -> R.string.audio_transcription_permission_denied
        AttachmentSpeechFailure.AUDIO_REJECTED -> R.string.audio_transcription_audio_rejected
        AttachmentSpeechFailure.RECOGNITION_FAILED -> R.string.audio_transcription_failed
        AttachmentSpeechFailure.TIMEOUT -> R.string.audio_transcription_timeout
    }

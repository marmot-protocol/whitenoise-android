@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.AttachmentSpeechException
import dev.ipf.whitenoise.android.audio.AttachmentSpeechFailure
import dev.ipf.whitenoise.android.audio.transcribeAttachment
import dev.ipf.whitenoise.android.media.holdCacheFileInUse
import dev.ipf.whitenoise.android.media.releaseCacheFileInUse
import dev.ipf.whitenoise.android.translation.MessageSideDisc
import dev.ipf.whitenoise.android.translation.MessageSideDiscIcon
import dev.ipf.whitenoise.android.translation.MessageSideDiscProgress
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationMessageMetrics
import dev.ipf.whitenoise.android.ui.settings.openOfflineSpeechToTextListing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
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
)

/**
 * The per-message switch behind the side transcribe disc. Each received recording registers whether its file is
 * ready and whether work is in flight; the disc shows when any recording is registered and drives them all.
 */
@Stable
internal class AudioTranscriptionToggleState {
    var requested by mutableStateOf(false)
    private val slots = mutableStateMapOf<Any, AudioTranscriptSlot>()

    val present: Boolean get() = slots.isNotEmpty()
    val enabled: Boolean get() = slots.values.any { it.ready }
    val working: Boolean get() = slots.values.any { it.working }

    fun register(
        key: Any,
        ready: Boolean,
        working: Boolean,
    ) {
        slots[key] = AudioTranscriptSlot(ready, working)
    }

    fun unregister(key: Any) {
        slots.remove(key)
    }
}

/**
 * Transcribes the received recording on this device while the message's side disc is on, and shows the text
 * directly beneath the voice row; turning the disc off hides it and cancels pending work. Transcripts belong to
 * the mounted attachment and never enter the message store or composer.
 */
@Composable
internal fun ReceivedAudioTranscription(
    file: File?,
    toggle: AudioTranscriptionToggleState,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val slotKey = remember { Any() }
    var ui by remember(file) { mutableStateOf<AudioTranscriptUi?>(null) }
    var request by remember(file) { mutableStateOf<Job?>(null) }
    var listingFailed by remember { mutableStateOf(false) }
    val working = ui?.phase == AudioTranscriptPhase.WORKING

    LaunchedEffect(toggle, file != null, working) { toggle.register(slotKey, file != null, working) }
    DisposableEffect(toggle) { onDispose { toggle.unregister(slotKey) } }

    LaunchedEffect(toggle.requested, file) {
        listingFailed = false
        if (!toggle.requested) {
            request?.cancel()
            ui = null
        } else if (file != null && ui == null) {
            ui = AudioTranscriptUi(AudioTranscriptPhase.WORKING)
        }
    }

    DisposableEffect(lifecycle, file) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    request?.cancel()
                    ui = null
                    toggle.requested = false
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            request?.cancel()
            lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(working, file) {
        if (!working || file == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return@LaunchedEffect
        }
        request = coroutineContext.job
        val result = loadAudioTranscript(context, file)
        coroutineContext.ensureActive()
        ui = result
    }

    AudioTranscriptBody(
        state = ui,
        listingError = if (listingFailed) stringResource(R.string.dictation_ostt_recommendation_open_failed) else null,
        onRetry = {
            listingFailed = false
            ui = AudioTranscriptUi(AudioTranscriptPhase.WORKING)
        },
        onGetProvider = { listingFailed = !openOfflineSpeechToTextListing(context) },
    )
}

/** The cache lease covers decoding and recognition, including cancellation cleanup. */
private suspend fun loadAudioTranscript(
    context: Context,
    file: File,
): AudioTranscriptUi {
    holdCacheFileInUse(file)
    return try {
        AudioTranscriptUi(AudioTranscriptPhase.COMPLETE, transcribeAttachment(context, file))
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
    AudioTranscribeDisc(
        on = toggle.requested,
        working = toggle.requested && toggle.working,
        // A requested switch stays tappable so it can cancel a request whose recording became unavailable.
        enabled = toggle.requested || toggle.enabled,
        onToggle = { toggle.requested = it },
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
        modifier = modifier.testTag(AUDIO_TRANSCRIBE_TEST_TAG),
    ) {
        if (working) MessageSideDiscProgress() else MessageSideDiscIcon(R.drawable.ic_subtitles)
    }
}

/**
 * Stateless in-bubble result: the transcript as plain body text, or compact failure guidance. Nothing renders
 * while the disc is off or work is in flight, so the voice row stays as tight as before.
 */
@Composable
internal fun AudioTranscriptBody(
    state: AudioTranscriptUi?,
    onRetry: () -> Unit,
    onGetProvider: () -> Unit,
    modifier: Modifier = Modifier,
    listingError: String? = null,
) {
    if (state == null || state.phase == AudioTranscriptPhase.WORKING) return
    val generated = stringResource(R.string.audio_transcription_generated)
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
                    if (state.phase == AudioTranscriptPhase.COMPLETE) stateDescription = generated
                }.testTag(AUDIO_TRANSCRIPT_TEST_TAG),
    ) {
        AudioTranscriptResult(state, listingError, onRetry, onGetProvider)
    }
}

@Composable
private fun AudioTranscriptResult(
    state: AudioTranscriptUi,
    listingError: String?,
    onRetry: () -> Unit,
    onGetProvider: () -> Unit,
) {
    when (state.phase) {
        AudioTranscriptPhase.COMPLETE ->
            SelectionContainer { Text(state.text, style = MaterialTheme.typography.bodyLarge) }
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

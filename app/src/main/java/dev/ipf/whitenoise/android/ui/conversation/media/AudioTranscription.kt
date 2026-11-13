@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
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

/**
 * One tap transcribes the received recording on this device and shows the text inline beneath the
 * voice row; a second tap hides it and cancels pending work. Transcripts belong to the mounted
 * attachment and never enter the message store or composer.
 */
@Composable
internal fun ReceivedAudioTranscription(file: File?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var ui by remember(file) { mutableStateOf<AudioTranscriptUi?>(null) }
    var request by remember(file) { mutableStateOf<Job?>(null) }
    var listingFailed by remember { mutableStateOf(false) }
    val working = ui?.phase == AudioTranscriptPhase.WORKING

    DisposableEffect(lifecycle, file) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    request?.cancel()
                    ui = null
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

    AudioTranscriptInline(
        state = ui,
        enabled = file != null,
        listingError = if (listingFailed) stringResource(R.string.dictation_ostt_recommendation_open_failed) else null,
        onToggle = { show ->
            listingFailed = false
            if (show) {
                ui = AudioTranscriptUi(AudioTranscriptPhase.WORKING)
            } else {
                request?.cancel()
                ui = null
            }
        },
        onRetry = { ui = AudioTranscriptUi(AudioTranscriptPhase.WORKING) },
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

/**
 * Stateless inline presentation: a compact Transcribe toggle (spinner while working) and, when on,
 * the generated transcript or failure guidance directly beneath it inside the bubble.
 */
@Composable
internal fun AudioTranscriptInline(
    state: AudioTranscriptUi?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onGetProvider: () -> Unit,
    modifier: Modifier = Modifier,
    listingError: String? = null,
) {
    Column(modifier = modifier) {
        AudioTranscribeToggle(state, enabled, onToggle)
        if (state != null && state.phase != AudioTranscriptPhase.WORKING) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier
                        .padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag(AUDIO_TRANSCRIPT_TEST_TAG),
            ) {
                AudioTranscriptResult(state, listingError, onRetry, onGetProvider)
            }
        }
    }
}

@Composable
private fun AudioTranscribeToggle(
    state: AudioTranscriptUi?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val on = state != null
    val working = state?.phase == AudioTranscriptPhase.WORKING
    val accent = if (on) MaterialTheme.colorScheme.primary else LocalContentColor.current
    val workingDescription = stringResource(R.string.audio_transcription_working)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
                .semantics { if (working) stateDescription = workingDescription }
                .heightIn(min = 32.dp)
                .padding(horizontal = 8.dp)
                .testTag(AUDIO_TRANSCRIBE_TEST_TAG),
    ) {
        val tint = if (enabled) accent else accent.copy(alpha = 0.38f)
        if (working) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = tint, modifier = Modifier.size(16.dp))
        } else {
            Icon(
                painterResource(R.drawable.ic_subtitles),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(stringResource(R.string.transcribe_audio), style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
private fun AudioTranscriptResult(
    state: AudioTranscriptUi,
    listingError: String?,
    onRetry: () -> Unit,
    onGetProvider: () -> Unit,
) {
    val secondary = LocalContentColor.current.copy(alpha = 0.7f)
    when (state.phase) {
        AudioTranscriptPhase.COMPLETE -> {
            SelectionContainer { Text(state.text, style = MaterialTheme.typography.bodyMedium) }
            Text(
                stringResource(R.string.audio_transcription_generated),
                style = MaterialTheme.typography.labelSmall,
                color = secondary,
            )
        }
        AudioTranscriptPhase.FAILED -> {
            Text(
                stringResource(transcriptionFailureText(state.failure)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            val needsProvider =
                state.failure == AttachmentSpeechFailure.PROVIDER_MISSING ||
                    state.failure == AttachmentSpeechFailure.PROVIDER_OUTDATED
            if (needsProvider) {
                TextButton(onClick = onGetProvider) {
                    Text(stringResource(R.string.dictation_ostt_recommendation_title))
                }
            } else if (state.failure != AttachmentSpeechFailure.ANDROID_VERSION) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
        AudioTranscriptPhase.WORKING -> Unit
    }
    if (listingError != null) {
        Text(listingError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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

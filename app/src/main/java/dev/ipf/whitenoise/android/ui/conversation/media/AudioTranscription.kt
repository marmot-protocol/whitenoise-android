@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.AttachmentSpeechException
import dev.ipf.whitenoise.android.audio.AttachmentSpeechFailure
import dev.ipf.whitenoise.android.audio.transcribeAttachment
import dev.ipf.whitenoise.android.media.holdCacheFileInUse
import dev.ipf.whitenoise.android.media.releaseCacheFileInUse
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.settings.openOfflineSpeechToTextListing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.io.File
import kotlin.coroutines.coroutineContext

internal enum class AudioTranscriptPhase { CONFIRM, WORKING, COMPLETE, FAILED }

internal data class AudioTranscriptUi(
    val phase: AudioTranscriptPhase,
    val text: String = "",
    val failure: AttachmentSpeechFailure = AttachmentSpeechFailure.RECOGNITION_FAILED,
)

/** Belongs to the mounted attachment; transcripts never enter the message store or composer. */
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

    TextButton(
        onClick = {
            listingFailed = false
            ui = AudioTranscriptUi(AudioTranscriptPhase.CONFIRM)
        },
        enabled = file != null,
        modifier = Modifier.testTag("audio.transcribe"),
    ) {
        Text(stringResource(R.string.transcribe_audio))
    }
    ui?.let { state ->
        AudioTranscriptDialog(
            state = state,
            listingError =
                if (listingFailed) stringResource(R.string.dictation_ostt_recommendation_open_failed) else null,
            onStart = { ui = AudioTranscriptUi(AudioTranscriptPhase.WORKING) },
            onDismiss = {
                request?.cancel()
                ui = null
            },
            onGetProvider = {
                listingFailed = !openOfflineSpeechToTextListing(context)
            },
        )
    }
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

@Composable
internal fun AudioTranscriptDialog(
    state: AudioTranscriptUi,
    listingError: String? = null,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    onGetProvider: () -> Unit,
) {
    val cancellable = state.phase == AudioTranscriptPhase.WORKING || state.phase == AudioTranscriptPhase.CONFIRM
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.audio_transcription_title)) },
        text = { AudioTranscriptBody(state, listingError) },
        confirmButton = { AudioTranscriptConfirm(state, onStart, onGetProvider) },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (cancellable) R.string.cancel else R.string.close))
            }
        },
    )
}

@Composable
private fun AudioTranscriptBody(
    state: AudioTranscriptUi,
    listingError: String?,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fadingVerticalScroll(rememberScrollState()).testTag("audio.transcription.body"),
    ) {
        when (state.phase) {
            AudioTranscriptPhase.CONFIRM -> Text(stringResource(R.string.audio_transcription_disclosure))
            AudioTranscriptPhase.WORKING -> {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.audio_transcription_working))
            }
            AudioTranscriptPhase.COMPLETE -> {
                Text(
                    stringResource(R.string.audio_transcription_generated),
                    style = MaterialTheme.typography.labelMedium,
                )
                SelectionContainer { Text(state.text) }
            }
            AudioTranscriptPhase.FAILED -> Text(stringResource(transcriptionFailureText(state.failure)))
        }
        if (listingError != null) {
            Text(listingError)
        }
    }
}

@Composable
private fun AudioTranscriptConfirm(
    state: AudioTranscriptUi,
    onStart: () -> Unit,
    onGetProvider: () -> Unit,
) {
    when (state.phase) {
        AudioTranscriptPhase.CONFIRM ->
            TextButton(onClick = onStart) {
                Text(stringResource(R.string.transcribe_audio))
            }
        AudioTranscriptPhase.FAILED -> {
            val needsProvider =
                state.failure == AttachmentSpeechFailure.PROVIDER_MISSING ||
                    state.failure == AttachmentSpeechFailure.PROVIDER_OUTDATED
            if (needsProvider) {
                TextButton(onClick = onGetProvider) {
                    Text(stringResource(R.string.dictation_ostt_recommendation_title))
                }
            } else if (state.failure != AttachmentSpeechFailure.ANDROID_VERSION) {
                TextButton(onClick = onStart) { Text(stringResource(R.string.retry)) }
            }
        }
        AudioTranscriptPhase.COMPLETE, AudioTranscriptPhase.WORKING -> Unit
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

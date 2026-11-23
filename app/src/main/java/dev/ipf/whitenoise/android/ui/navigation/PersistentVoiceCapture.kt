package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceNoteKey
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.formatRecordingDuration
import dev.ipf.whitenoise.android.ui.conversation.composer.persistentDictationChrome

internal fun persistentVoiceRecorder(
    appState: WhiteNoiseAppState,
    visibleGroupId: String?,
    navigationStable: Boolean,
): VoiceRecordingController? {
    if (!navigationStable || appState.appLock.screenVisible) return null
    val visible = appState.activeAccountRef?.let { account -> visibleGroupId?.let { VoiceNoteKey(account, it) } }
    return appState.voiceNotes.persistentRecorder(visible)
}

/** Keeps recording controls within reach while showing no chat or account details. */
@Composable
@Suppress("FunctionNaming")
internal fun PersistentVoiceCapture(
    recorder: VoiceRecordingController,
    padStatusBars: Boolean,
) {
    val paused = recorder.isPaused
    val status = stringResource(if (paused) R.string.voice_recording_paused else R.string.voice_row_recording)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (padStatusBars) Modifier.statusBarsPadding() else Modifier)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(
            modifier =
                Modifier
                    .testTag("persistent-voice-capture")
                    .persistentDictationChrome(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.outlineVariant,
                        status,
                        chromeVisibility = { 1f },
                        movingChromeSize = { null },
                    ).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                formatRecordingDuration(recorder.elapsedMs),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = { if (paused) recorder.resume() else recorder.pauseCapture() }) {
                Icon(
                    painterResource(if (paused) R.drawable.ic_play_arrow else R.drawable.ic_pause),
                    contentDescription =
                        stringResource(if (paused) R.string.voice_recording_resume else R.string.voice_recording_pause),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = { recorder.cancel() }) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.voice_message_cancel),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            IconButton(onClick = { recorder.stop() }) {
                Icon(
                    painterResource(R.drawable.ic_stop),
                    contentDescription = stringResource(R.string.voice_recording_stop),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

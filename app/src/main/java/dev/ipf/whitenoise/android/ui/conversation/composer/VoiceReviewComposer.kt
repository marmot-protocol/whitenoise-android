package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.AudioWaveformExtractor
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.ui.conversation.media.VoiceWaveform
import dev.ipf.whitenoise.android.ui.conversation.media.formatVoiceTime
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

/** Projects the current review onto the existing shared composer, using real native playback and decoded samples. */
@Composable
@Suppress("FunctionNaming")
internal fun VoiceReviewComposer(
    review: VoiceRecordingReview,
    clip: VoiceReviewClip,
    recorder: VoiceRecordingController?,
) {
    val playback by VoicePlaybackController.state.collectAsState()
    val waveform by produceState<FloatArray?>(null, clip.key) { value = AudioWaveformExtractor.decode(clip.file) }
    val ownsPlayback = playback.key == clip.key
    VoiceReviewComposerContent(
        durationMs = clip.durationMs,
        positionMs = if (ownsPlayback) playback.positionMs else 0,
        playing = ownsPlayback && playback.isPlaying,
        sending = review.isSending,
        waveform = waveform,
        onPlay = { review.togglePlayback(clip) },
        onDiscard = { review.discard(clip) },
        onRecordAgain = {
            review.recordAgain(clip) {
                if (recorder?.start() == true) recorder.lock()
            }
        },
        onSend = { review.send(clip) },
    )
}

/** The prototype's 48dp playback row and 48dp review action row; all actions use the captured clip owner. */
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun VoiceReviewComposerContent(
    durationMs: Long,
    positionMs: Int,
    playing: Boolean,
    waveform: FloatArray?,
    onPlay: () -> Unit,
    onDiscard: () -> Unit,
    onRecordAgain: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    sending: Boolean = false,
) {
    val reviewTitle = stringResource(R.string.voice_review_title)
    val discardLabel = stringResource(R.string.discard)
    val sendingLabel = stringResource(R.string.sending)
    val progress = (positionMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    val playAction: @Composable () -> Unit = {
        IconButton(onClick = onPlay, enabled = !sending, modifier = Modifier.size(48.dp)) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                border = amoledOutlineBorder(),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                        stringResource(if (playing) R.string.voice_message_pause else R.string.voice_message_play),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
    val discardAction: @Composable () -> Unit = {
        IconButton(onClick = onDiscard, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_close), discardLabel, modifier = Modifier.size(24.dp))
        }
    }
    val recordAgainAction: @Composable () -> Unit = {
        IconButton(onClick = onRecordAgain, enabled = !sending, modifier = Modifier.size(48.dp)) {
            Icon(
                painterResource(R.drawable.ic_mic),
                stringResource(R.string.voice_record_again),
                modifier = Modifier.size(24.dp),
            )
        }
    }
    val sendAction: @Composable () -> Unit = {
        IconButton(onClick = onSend, enabled = !sending, modifier = Modifier.size(48.dp)) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                border = amoledOutlineBorder(),
                color =
                    if (isAmoledSurfaceTheme()) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                contentColor =
                    if (isAmoledSurfaceTheme()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_upward),
                        stringResource(R.string.send),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .semantics {
                paneTitle = reviewTitle
                stateDescription =
                    if (sending) {
                        sendingLabel
                    } else {
                        formatVoiceTime(durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                    }
                customActions =
                    listOf(
                        CustomAccessibilityAction(discardLabel) {
                            onDiscard()
                            true
                        },
                    )
            }.testTag("conversation.voice.review"),
    ) {
        if (maxHeight < 96.dp) {
            Row(
                Modifier.align(Alignment.Center).fillMaxWidth().height(48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                playAction()
                discardAction()
                recordAgainAction()
                sendAction()
            }
        } else {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                playAction()
                Box(Modifier.weight(1f).height(32.dp), contentAlignment = Alignment.Center) {
                    if (waveform != null && waveform.isNotEmpty()) {
                        VoiceWaveform(
                            bars = waveform,
                            progress = progress,
                            playedColor = MaterialTheme.colorScheme.primary,
                            remainingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Text(
                    formatVoiceTime((durationMs - positionMs).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()),
                    // Hug the digits: an LTR run inside an RTL row is otherwise laid out across the whole
                    // remaining width to be aligned, which reads as visual overflow at large type.
                    modifier = Modifier.padding(horizontal = 12.dp).width(IntrinsicSize.Max),
                    maxLines = 1,
                    softWrap = false,
                    style =
                        MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            textDirection = TextDirection.Ltr,
                        ),
                )
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                discardAction()
                recordAgainAction()
                sendAction()
            }
        }
    }
}

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.AudioWaveformExtractor
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.ui.common.boundedIntrinsicWidth
import dev.ipf.whitenoise.android.ui.conversation.media.VoiceWaveform
import dev.ipf.whitenoise.android.ui.conversation.media.formatVoiceTime

/** Waveform and time alpha while Send prepares, matching Material's disabled content. */
private const val VOICE_REVIEW_SENDING_ALPHA = 0.38f

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
    var confirmDiscard by remember(clip.key) { mutableStateOf(false) }
    VoiceReviewComposerContent(
        durationMs = clip.durationMs,
        positionMs = if (ownsPlayback) playback.positionMs else 0,
        playing = ownsPlayback && playback.isPlaying,
        sending = review.isSending,
        waveform = waveform,
        onPlay = { review.togglePlayback(clip) },
        onDiscard = {
            if (clip.durationMs >= VOICE_DISCARD_CONFIRM_MS) confirmDiscard = true else review.discardByUser(clip)
        },
        onRecordAgain = {
            review.recordAgain(clip) {
                if (recorder?.start() == true) recorder.lock()
            }
        },
        onSend = { review.send(clip) },
        // Seeking needs the preview loaded, so the waveform becomes a scrubber once this take has played.
        onSeek = if (ownsPlayback) ({ fraction -> review.seek(clip, fraction) }) else null,
    )
    if (confirmDiscard) {
        VoiceDiscardConfirmation(
            onConfirm = {
                confirmDiscard = false
                review.discardByUser(clip)
            },
            onDismiss = { confirmDiscard = false },
        )
    }
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
    onSeek: ((Float) -> Unit)? = null,
) {
    val reviewTitle = stringResource(R.string.voice_review_title)
    val discardLabel = stringResource(R.string.discard)
    val sendingLabel = stringResource(R.string.sending)
    val progress = (positionMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    val enter by rememberVoiceEnterProgress()
    val playAction: @Composable () -> Unit = { VoiceReviewPlayAction(playing, sending, onPlay) }
    val discardAction: @Composable () -> Unit = { VoiceReviewDiscardAction(sending, onDiscard) }
    val recordAgainAction: @Composable () -> Unit = { VoiceReviewRecordAgainAction(sending, onRecordAgain) }
    val sendAction: @Composable () -> Unit = { VoiceReviewSendAction(sending, onSend) }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .voiceEnterLayer { enter }
            .semantics {
                paneTitle = reviewTitle
                stateDescription =
                    if (sending) {
                        sendingLabel
                    } else {
                        formatVoiceTime(durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                    }
                // While Send prepares there is nothing to discard: the custom action would be a dead end.
                if (!sending) {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(discardLabel) {
                                onDiscard()
                                true
                            },
                        )
                }
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
                VoiceReviewTrack(
                    waveform = waveform,
                    progress = progress,
                    sending = sending,
                    grow = { enter },
                    onSeek = onSeek,
                )
                VoiceReviewTime(remainingMs(durationMs, positionMs), sending)
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

/** The seekable waveform; bars grow from the centre line as review opens and dim while Send prepares. */
@Composable
@Suppress("FunctionNaming")
private fun androidx.compose.foundation.layout.RowScope.VoiceReviewTrack(
    waveform: FloatArray?,
    progress: Float,
    sending: Boolean,
    grow: () -> Float,
    onSeek: ((Float) -> Unit)?,
) {
    val alpha by animateFloatAsState(
        targetValue = if (sending) VOICE_REVIEW_SENDING_ALPHA else 1f,
        animationSpec = voiceTween(open = false),
        label = "voice-review-sending-alpha",
    )
    Box(
        Modifier
            .weight(1f)
            .height(32.dp)
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.Center,
    ) {
        if (waveform != null && waveform.isNotEmpty()) {
            VoiceWaveform(
                bars = waveform,
                progress = progress,
                playedColor = MaterialTheme.colorScheme.primary,
                remainingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .testTag("conversation.voice.review.waveform")
                        .graphicsLayer { scaleY = grow() },
                onSeek = onSeek.takeUnless { sending },
            )
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Remaining time as LTR digits, or "Sending" in the reading direction while Send prepares, on one line. */
@Composable
@Suppress("FunctionNaming")
private fun VoiceReviewTime(
    remainingMs: Int,
    sending: Boolean,
) {
    val label = MaterialTheme.typography.labelLarge
    Text(
        if (sending) stringResource(R.string.sending) else formatVoiceTime(remainingMs),
        // Hug the digits: an LTR run inside an RTL row is otherwise laid out across the whole
        // remaining width to be aligned, which reads as visual overflow at large type.
        modifier = Modifier.padding(horizontal = 12.dp).boundedIntrinsicWidth(IntrinsicSize.Max),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        style =
            if (sending) {
                label
            } else {
                label.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)
            },
    )
}

private fun remainingMs(
    durationMs: Long,
    positionMs: Int,
): Int = (durationMs - positionMs).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

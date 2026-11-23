package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

/*
 * The review's four actions. Each one is disabled while Send prepares (`enabled = !sending`), so a press during
 * that window can neither revoke the send nor start a second one.
 */

/** Plays or pauses the private take; the glyph cross-fades between the two. */
@Composable
@Suppress("FunctionNaming")
internal fun VoiceReviewPlayAction(
    playing: Boolean,
    sending: Boolean,
    onPlay: () -> Unit,
) {
    IconButton(onClick = onPlay, enabled = !sending, modifier = Modifier.size(48.dp)) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            border = amoledOutlineBorder(),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Crossfade(targetState = playing, animationSpec = voiceTween(open = false), label = "voice-review-play") {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(if (it) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                        stringResource(if (it) R.string.voice_message_pause else R.string.voice_message_play),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** Deletes the take. A long take asks first; the caller decides through [onDiscard]. */
@Composable
@Suppress("FunctionNaming")
internal fun VoiceReviewDiscardAction(
    sending: Boolean,
    onDiscard: () -> Unit,
) {
    IconButton(
        onClick = onDiscard,
        enabled = !sending,
        modifier =
            Modifier
                .size(48.dp)
                .testTag("conversation.voice.review.discard")
                .explainOnLongPress(
                    composerExplanation(
                        R.string.discard,
                        R.string.explanation_conversation_voice_discard,
                    ),
                ),
    ) {
        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.discard), modifier = Modifier.size(24.dp))
    }
}

/** Replaces the take with a fresh locked recording. */
@Composable
@Suppress("FunctionNaming")
internal fun VoiceReviewRecordAgainAction(
    sending: Boolean,
    onRecordAgain: () -> Unit,
) {
    IconButton(
        onClick = onRecordAgain,
        enabled = !sending,
        modifier =
            Modifier
                .size(48.dp)
                .explainOnLongPress(
                    composerExplanation(
                        R.string.voice_record_again,
                        R.string.explanation_conversation_voice_record_again,
                    ),
                ),
    ) {
        Icon(
            painterResource(R.drawable.ic_voice_note),
            stringResource(R.string.voice_record_again),
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Sends the take. While Send prepares, the arrow becomes a ring inside the same disc. */
@Composable
@Suppress("FunctionNaming")
internal fun VoiceReviewSendAction(
    sending: Boolean,
    onSend: () -> Unit,
) {
    val amoled = isAmoledSurfaceTheme()
    IconButton(
        onClick = onSend,
        enabled = !sending,
        modifier = Modifier.size(48.dp).testTag("conversation.voice.review.send"),
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            border = amoledOutlineBorder(),
            color = if (amoled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
            contentColor = if (amoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
        ) {
            Crossfade(targetState = sending, animationSpec = voiceTween(open = false), label = "voice-review-send") {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (it) {
                        val sendingLabel = stringResource(R.string.sending)
                        VoiceSendingIndicator(
                            size = 18.dp,
                            strokeWidth = 2.dp,
                            modifier =
                                Modifier
                                    .testTag("conversation.voice.review.sending")
                                    .semantics { contentDescription = sendingLabel },
                        )
                    } else {
                        Icon(
                            painterResource(R.drawable.ic_arrow_upward),
                            stringResource(R.string.send),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

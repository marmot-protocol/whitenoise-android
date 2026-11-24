package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.ui.common.AccountActionColors
import dev.ipf.whitenoise.android.ui.common.SlotIconButton
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress

/** A locked take's trailing row: delete the take, or stop it into review. Both stay enabled throughout. */
@Composable
@Suppress("FunctionNaming")
internal fun VoiceLockedActions(
    controller: VoiceRecordingController,
    actionTargetWidth: Dp,
    actionColors: AccountActionColors,
) {
    ComposerToolbarTargets {
        SlotIconButton(
            onClick = { controller.cancel() },
            modifier =
                Modifier
                    .width(
                        actionTargetWidth,
                    ).height(ComposerActionRowHeight)
                    .explainOnLongPress(
                        composerExplanation(
                            R.string.voice_message_cancel,
                            R.string.explanation_conversation_voice_delete,
                        ),
                    ),
        ) {
            Icon(
                painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.voice_message_cancel),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
    ComposerActionDisc(
        onClick = { controller.stop() },
        width = actionTargetWidth,
        containerColor = actionColors.container,
        contentColor = actionColors.content,
        description = stringResource(R.string.voice_recording_stop),
        icon = R.drawable.ic_stop,
        modifier =
            Modifier.explainOnLongPress(
                composerExplanation(
                    R.string.voice_recording_stop,
                    R.string.explanation_conversation_voice_stop,
                ),
            ),
    )
}

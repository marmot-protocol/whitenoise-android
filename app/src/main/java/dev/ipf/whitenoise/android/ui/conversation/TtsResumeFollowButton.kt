package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.MinimumTouchTarget
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.conversation.composer.composerExplanation
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.GpsFixed
import dev.ipf.whitenoise.android.ui.navigation.FloatingControlDisc

/** Scrolls back to the passage being read aloud after the reader moved away, as the shared floating disc. */
@Suppress("FunctionNaming")
@Composable
internal fun TtsResumeFollowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resumeFollowLabel = stringResource(R.string.tts_resume_follow)

    FloatingControlDisc(
        icon = rememberVectorPainter(Icons.Default.GpsFixed),
        contentDescription = resumeFollowLabel,
        onClick = onClick,
        modifier = modifier,
        // Resume following always took a full 48 dp slot in the stack, so it keeps it and does not move.
        slot = MinimumTouchTarget,
        targetModifier =
            Modifier.explainOnLongPress(
                composerExplanation(
                    R.string.tts_resume_follow,
                    R.string.explanation_conversation_tts_resume_follow,
                ),
            ),
    )
}

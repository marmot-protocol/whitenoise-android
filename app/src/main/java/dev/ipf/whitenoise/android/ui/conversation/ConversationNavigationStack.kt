@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.navigation.FloatingControlGap
import dev.ipf.whitenoise.android.ui.navigation.FloatingControlVisibility
import dev.ipf.whitenoise.android.ui.navigation.floatingDictationCapsuleClearance

/**
 * The transcript's bottom-end navigation controls — Resume read-aloud follow, the mention chip and
 * Jump to newest. They sit [FloatingControlGap] above the composer and, when the dictation capsule floats
 * over this composer, the same gap above that capsule, so the stack reads jump controls, capsule, composer.
 */
@Composable
internal fun BoxScope.ConversationNavigationStack(
    composerOverlap: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = composerOverlap)
                .padding(horizontal = 12.dp)
                .padding(bottom = FloatingControlGap)
                .floatingDictationCapsuleClearance(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(FloatingControlGap),
        content = content,
    )
}

/** One transcript navigation control on the shared floating control motion. */
@Composable
internal fun ColumnScope.ConversationNavigationControl(
    visible: Boolean,
    // Read here, not by the caller, so lifecycle changes recompose only this control.
    motionEnabled: Boolean = rememberConversationMotionEnabled(),
    content: @Composable () -> Unit,
) {
    FloatingControlVisibility(visible = visible, motionEnabled = motionEnabled, content = content)
}

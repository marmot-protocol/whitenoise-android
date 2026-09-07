package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.ipf.whitenoise.android.state.ConversationController

/**
 * Keeps the streaming-debug refresh outside the giant conversation composition.
 *
 * The 8055436 release preview observed this remembered Function2 block reading a
 * Boolean. A dedicated composition group isolates that cast without claiming an
 * unproven compiler or register-allocation cause.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ConversationStreamingDebugRefreshEffect(
    controller: ConversationController,
    streamingDebugEnabled: Boolean,
) {
    LaunchedEffect(controller, streamingDebugEnabled) {
        controller.refreshStreamingDebugPresentation()
    }
}

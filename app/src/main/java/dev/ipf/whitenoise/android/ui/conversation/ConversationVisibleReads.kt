package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isConversationReadVisible
import dev.ipf.whitenoise.android.state.reportVisibleMessage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/** Retries a settled read when its retained screen becomes the visible Android host again. */
@Composable
internal fun ObserveConversationVisibleReads(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    lifecycleOwner: LifecycleOwner?,
    readAnchor: () -> String?,
) {
    val resumed =
        lifecycleOwner
            ?.lifecycle
            ?.currentStateFlow
            ?.collectAsState()
            ?.value == Lifecycle.State.RESUMED
    val currentResumed by rememberUpdatedState(resumed)
    val currentReadAnchor by rememberUpdatedState(readAnchor)
    LaunchedEffect(controller, lifecycleOwner) {
        snapshotFlow {
            val visible =
                appState.isConversationReadVisible(controller.boundAccountRef.orEmpty(), controller.group.groupIdHex)
            if (currentResumed && visible) {
                currentReadAnchor()?.let { it to controller.latestChatListRow?.manuallyMarkedUnread }
            } else {
                null
            }
        }.distinctUntilChanged()
            .filterNotNull()
            .collect { (messageId, _) ->
                if (messageId.isNotBlank()) {
                    controller.markReadUpTo(messageId)
                    controller.reportVisibleMessage(messageId)
                }
            }
    }
}

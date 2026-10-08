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
import dev.ipf.whitenoise.android.state.ConversationTimelineSubscriptionHandle
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isConversationReadVisible
import dev.ipf.whitenoise.android.state.reportVisibleMessage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * Submits settled anchors only from a resumed, unlocked screen that owns the visible account and group.
 * Visibility reentry and native manual-reminder changes retry reads without scrolling.
 * Reports each changed visible message ID once per ready subscription, independently of manual-reminder transitions.
 */
@Composable
internal fun observeConversationVisibleReads(
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
        var lastReportedMessageId: String? = null
        var lastReportedSubscription: ConversationTimelineSubscriptionHandle? = null
        snapshotFlow {
            val visible =
                appState.isConversationReadVisible(controller.boundAccountRef.orEmpty(), controller.group.groupIdHex)
            if (currentResumed && visible) {
                currentReadAnchor()?.let {
                    Triple(it, controller.latestChatListRow?.manuallyMarkedUnread, controller.window.readySubscription)
                }
            } else {
                null
            }
        }.distinctUntilChanged()
            .filterNotNull()
            .collect { (messageId, _, subscription) ->
                if (messageId.isNotBlank()) {
                    controller.markReadUpTo(messageId)
                    if (
                        (messageId != lastReportedMessageId || subscription !== lastReportedSubscription) &&
                        controller.reportVisibleMessage(messageId, subscription)
                    ) {
                        lastReportedMessageId = messageId
                        lastReportedSubscription = subscription
                    }
                }
            }
    }
}

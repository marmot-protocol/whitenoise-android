package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/** Shows the durable last-admin block or offers to verify membership after it clears. */
@Suppress("FunctionNaming")
@Composable
internal fun BlockedLeaveCancelNotice(
    enabled: Boolean,
    refreshingMembership: Boolean = false,
    onCancel: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = Modifier.fillMaxWidth().testTag("chat_info.cancel_blocked_leave"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val hint =
                if (refreshingMembership) R.string.group_leave_refresh_hint else R.string.group_leave_blocked_hint
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
            val action = if (refreshingMembership) R.string.group_leave_refresh_action else R.string.group_cancel_leave
            TextButton(onClick = onCancel, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(action))
            }
        }
    }
}

/**
 * The conversation's composer slot while this account's leave is blocked: the same explanation and Cancel leave as
 * Chat Info, so a durable block never leaves the chat with an empty bar. Cancel waits while another group change is
 * running, and the controller itself reports a native failure, so this adds no message of its own.
 */
@Suppress("FunctionNaming")
@Composable
internal fun BlockedLeaveComposerNotice(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("conversation.blocked_leave"),
    ) {
        BlockedLeaveCancelNotice(
            enabled = controller.canCancelBlockedLeave && !controller.mutationInFlight,
            refreshingMembership = controller.blockedLeaveNeedsReadback,
            onCancel = { appState.launchMutation { controller.cancelBlockedLeave() } },
        )
    }
}

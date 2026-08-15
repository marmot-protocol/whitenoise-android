package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import dev.ipf.whitenoise.android.state.GroupRosterLoadState

internal const val CONVERSATION_MEMBERSHIP_PENDING_TAG = "conversation-membership-pending"
internal const val CONVERSATION_MEMBERSHIP_RETRY_TAG = "conversation-membership-retry"

/**
 * Honest bottom-bar state while the local authoritative roster is unresolved.
 * It occupies the composer's normal footprint, keeps drafts untouched, and
 * turns a failed read into an explicit retry instead of an indefinite blank
 * strip.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ConversationMembershipPendingBar(
    rosterState: GroupRosterLoadState,
    onRetry: () -> Unit,
) {
    val failed =
        rosterState == GroupRosterLoadState.FAILED ||
            rosterState == GroupRosterLoadState.INCONSISTENT
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .testTag(CONVERSATION_MEMBERSHIP_PENDING_TAG),
    ) {
        ConversationMembershipPendingContent(failed, onRetry)
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ConversationMembershipPendingContent(
    failed: Boolean,
    onRetry: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (failed) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
        Text(
            text =
                stringResource(
                    if (failed) {
                        R.string.conversation_access_unavailable
                    } else {
                        R.string.conversation_access_checking
                    },
                ),
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (failed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            modifier = Modifier.weight(1f),
        )
        if (failed) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier.testTag(CONVERSATION_MEMBERSHIP_RETRY_TAG),
            ) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

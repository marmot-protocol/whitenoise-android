package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.OptimisticGroupRosterMutation
import dev.ipf.whitenoise.android.state.PendingGroupMembershipActivity
import dev.ipf.whitenoise.android.ui.EmojiLabel

/** Local request feedback, deliberately separate from MDK's authoritative group-system rows. */
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
@Composable
internal fun PendingGroupMembershipRow(
    activity: PendingGroupMembershipActivity,
    displayName: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val label =
        when (val mutation = activity.mutation) {
            is OptimisticGroupRosterMutation.Invite ->
                stringResource(R.string.invite_pending, mutation.memberRefs.joinToString(", ") { displayName(it) })
            is OptimisticGroupRosterMutation.Remove ->
                stringResource(R.string.member_removal_pending, displayName(mutation.memberIdHex))
            is OptimisticGroupRosterMutation.SetAdmin -> return
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EmojiLabel(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

package dev.ipf.whitenoise.android.ui.group

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.GroupRosterLoadState
import dev.ipf.whitenoise.android.ui.chats.newchat.QuickActionButton
import dev.ipf.whitenoise.android.ui.theme.Dimens

/**
 * Renders authoritative roster progress or recovery. [retryContentColor] lets
 * a host meet contrast against its own surface without changing other roster
 * screens that retain the Material text-button default.
 */
@Composable
@Suppress("FunctionNaming")
internal fun GroupRosterLoadStatus(
    state: GroupRosterLoadState,
    onRetry: () -> Unit,
    retryContentColor: Color = MaterialTheme.colorScheme.primary,
) {
    when (state) {
        GroupRosterLoadState.LOADING ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.members),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        GroupRosterLoadState.FAILED,
        GroupRosterLoadState.INCONSISTENT,
        ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    stringResource(R.string.couldnt_load_conversation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(contentColor = retryContentColor),
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
        GroupRosterLoadState.READY -> Unit
    }
}

/**
 * Presentation gate for member administration. The authoritative roster is
 * still required at the commit boundary; this only decides when the action may
 * present enabled. A warm seed — the chat-list snapshot or member-snapshot
 * cache proving the active account is a member, with the group record already
 * naming it an admin — enables the action on the first frame while the roster
 * round-trip is still in flight. A cold open with no membership signal keeps
 * the loading gate, and a FAILED or INCONSISTENT roster never enables it.
 */
internal fun memberAdministrationPresentable(
    rosterState: GroupRosterLoadState,
    seededSelfMember: Boolean,
): Boolean =
    when (rosterState) {
        GroupRosterLoadState.READY -> true
        GroupRosterLoadState.LOADING -> seededSelfMember
        GroupRosterLoadState.FAILED,
        GroupRosterLoadState.INCONSISTENT,
        -> false
    }

/**
 * Reports how long the details screen took from open to an enabled member
 * administration action, tagged with the roster state at that moment, so a
 * regression back to multi-second blocking on the roster round-trip shows up
 * in debug logs instead of passing silently.
 */
internal fun reportMemberAdministrationEnabledLatency(
    elapsedMs: Long,
    rosterState: GroupRosterLoadState,
) {
    if (!BuildConfig.DEBUG) return
    Log.i("DMGroupDetails", "administration-enabled +${elapsedMs}ms roster=$rosterState")
}

/** The Add member quick action, enabled per [memberAdministrationPresentable] and the mutation lock. */
@Composable
@Suppress("FunctionNaming")
internal fun GroupDetailsAddMemberAction(
    visible: Boolean,
    rosterState: GroupRosterLoadState,
    mutationsBlocked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    seededSelfMember: Boolean = false,
) {
    if (!visible) return
    QuickActionButton(
        icon = Icons.Default.PersonAdd,
        label = stringResource(R.string.quick_action_add),
        onClick = onClick,
        enabled = memberAdministrationPresentable(rosterState, seededSelfMember) && !mutationsBlocked,
        modifier = modifier,
    )
}

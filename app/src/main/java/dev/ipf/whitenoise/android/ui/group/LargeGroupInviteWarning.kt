package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import java.util.Locale

internal const val LARGE_GROUP_INVITE_WARNING_THRESHOLD = 50
internal const val LARGE_GROUP_INVITE_WARNING_TAG = "large-group-invite-warning"
internal const val LARGE_GROUP_INVITE_CONFIRMATION_TAG = "large-group-invite-confirmation"

internal data class LargeGroupInviteProjection(
    val memberCount: Int,
) {
    val shouldWarn: Boolean
        get() = memberCount >= LARGE_GROUP_INVITE_WARNING_THRESHOLD
}

/**
 * Calculates the size an invite would produce from the authoritative roster,
 * the active account, optimistic pending invites, and the staged recipients.
 * A non-authoritative roster deliberately produces no count so the UI cannot
 * present stale membership as fact.
 */
internal fun largeGroupInviteProjection(
    rosterReady: Boolean,
    authoritativeMemberIds: Iterable<String>,
    activeAccountIdHex: String?,
    pendingInviteMemberIds: Iterable<String>,
    stagedRecipientIds: Iterable<String>,
): LargeGroupInviteProjection? {
    if (!rosterReady) return null

    val uniqueMembers = LinkedHashSet<String>()

    fun addMember(memberIdHex: String?) {
        memberIdHex
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotEmpty)
            ?.let(uniqueMembers::add)
    }

    authoritativeMemberIds.forEach(::addMember)
    addMember(activeAccountIdHex)
    pendingInviteMemberIds.forEach(::addMember)
    stagedRecipientIds.forEach(::addMember)
    return LargeGroupInviteProjection(memberCount = uniqueMembers.size)
}

/** Persistent disclosure shown in the Add Members picker once the projected group reaches the threshold. */
@Composable
@Suppress("FunctionNaming")
internal fun LargeGroupInviteWarningBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .testTag(LARGE_GROUP_INVITE_WARNING_TAG),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        border = amoledSurfaceBorderStroke(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Default.WarningAmber, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.large_group_invite_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.large_group_invite_warning_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Explicit, non-destructive confirmation required immediately before dispatching a large-group invite. */
@Composable
@Suppress("FunctionNaming")
internal fun LargeGroupInviteConfirmationDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(LARGE_GROUP_INVITE_CONFIRMATION_TAG),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.WarningAmber, contentDescription = null) },
        title = { Text(stringResource(R.string.large_group_invite_confirm_title)) },
        text = {
            Text(stringResource(R.string.large_group_invite_confirm_message))
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.group_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

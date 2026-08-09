package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.PillShape

@Composable
@Suppress("FunctionNaming")
internal fun GroupMemberMutationStatus(
    isAdmin: Boolean,
    inProgress: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isAdmin) {
            Surface(
                shape = PillShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    stringResource(R.string.admin),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = Dimens.spaceSm,
                            vertical = Dimens.spaceXxs,
                        ),
                )
            }
        }
        if (inProgress) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun PendingGroupInviteRow(
    title: String,
    subtitle: String,
    avatarSeed: String,
    avatarUrl: String?,
    onClick: () -> Unit,
) {
    ContactRow(
        title = title,
        subtitle = subtitle,
        avatarSeed = avatarSeed,
        avatarUrl = avatarUrl,
        onClick = onClick,
        trailing = {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        },
    )
}

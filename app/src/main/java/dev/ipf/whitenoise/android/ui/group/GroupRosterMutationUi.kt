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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.newchat.PersonRow
import dev.ipf.whitenoise.android.ui.common.animatePublicKeyExpansion
import dev.ipf.whitenoise.android.ui.common.isCanonicalNpub
import dev.ipf.whitenoise.android.ui.settings.publicKeyDisplayGroups
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

/** A member who was invited but has not joined; tapping copies their key and reveals the full pending line. */
@Composable
@Suppress("FunctionNaming")
internal fun PendingGroupInviteRow(
    title: String,
    subtitle: String,
    avatarSeed: String,
    avatarUrl: String?,
    onClick: () -> Unit,
    npub: String = "",
) {
    var expanded by rememberSaveable(npub) { mutableStateOf(false) }
    PersonRow(
        segmented = false,
        title = title,
        subtitle = subtitle.takeUnless { expanded },
        avatarSeed = avatarSeed,
        avatarUrl = avatarUrl,
        onClick = {
            onClick()
            expanded = isCanonicalNpub(npub)
        },
        secondaryContent = {
            if (expanded) {
                Text(
                    stringResource(R.string.invite_pending, publicKeyDisplayGroups(npub).joinToString(" ")),
                    modifier = Modifier.animatePublicKeyExpansion(expanded),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )
            }
        },
        trailing = {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        },
        avatarDomain = AvatarImageLoader.publicProfiles(),
    )
}

/** Shared presentation/admission ownership, with the same ordered account and runtime fences. */
internal fun ConversationController.isCurrentGroupMutationOwner(
    appState: WhiteNoiseAppState,
    reviewedRuntime: Int,
): Boolean =
    when {
        boundAccountRef == null || boundAccountRef != appState.activeAccountRef -> false
        reviewedRuntime != appState.runtimeGeneration -> false
        appState.signOutInProgress || appState.wipeInProgress -> false
        else -> true
    }

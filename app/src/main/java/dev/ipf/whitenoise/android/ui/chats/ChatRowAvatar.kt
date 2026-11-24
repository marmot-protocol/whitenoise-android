package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.FollowedPersonBadge
import dev.ipf.whitenoise.android.ui.common.LocalFollowedPeople
import dev.ipf.whitenoise.android.ui.common.followedPersonBadgeSize

/**
 * A chat row's avatar with its pinned marker and, for a direct chat whose peer the active account follows
 * ([openableDmAvatarAccount]), the followed-person badge. The circular clip shapes only the profile ripple, so
 * neither marker is cut by it; when both are shown the pin moves to the top corner.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun ChatRowAvatar(
    appState: WhiteNoiseAppState,
    item: ChatListItem,
    title: String,
    accountRef: String?,
    openableDmAvatarAccount: String?,
    profileTapEnabled: Boolean,
    interactionsEnabled: Boolean,
    pinned: Boolean,
    onOpenProfile: (String) -> Unit,
) {
    // Follows belong to the active account; a row listed for another account (the forward picker) gets no badge.
    val followedAccount = openableDmAvatarAccount?.takeIf { accountRef == appState.activeAccountRef }
    ChatRowAvatarMarkers(followedAccount, pinned) {
        Box(
            modifier =
                if (profileTapEnabled && openableDmAvatarAccount != null) {
                    // The row offers Open profile to the screen reader; a separate unlabelled stop would not.
                    Modifier
                        .clip(CircleShape)
                        .clearAndSetSemantics {}
                        .clickable(enabled = interactionsEnabled, role = Role.Button) {
                            onOpenProfile(appState.npub(openableDmAvatarAccount))
                        }
                } else {
                    Modifier
                },
        ) {
            ChatListItemAvatar(item, appState, accountRef, title, CHAT_ROW_AVATAR_SIZE)
        }
    }
}

/**
 * Places the followed badge for [followedAccount] and the [pinned] marker around a chat row's [avatar], outside any
 * clip the avatar applies. The followed badge keeps the bottom corner it has on every person avatar; a pin shown with
 * it moves to the top corner.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ChatRowAvatarMarkers(
    followedAccount: String?,
    pinned: Boolean,
    avatar: @Composable () -> Unit,
) {
    val followed = LocalFollowedPeople.current.contains(followedAccount)
    Box {
        avatar()
        if (followed) {
            // Announced with the row, like the pin beside it.
            val description = stringResource(R.string.user_search_you_follow)
            FollowedPersonBadge(
                Modifier.align(Alignment.BottomEnd).semantics { contentDescription = description },
                followedPersonBadgeSize(CHAT_ROW_AVATAR_SIZE),
            )
        }
        if (pinned) {
            Surface(
                modifier = Modifier.align(if (followed) Alignment.TopEnd else Alignment.BottomEnd).size(20.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box(contentAlignment = Alignment.Center) { PinnedBadge() }
            }
        }
    }
}

internal val CHAT_ROW_AVATAR_SIZE = 52.dp

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isPeerSourced
import dev.ipf.whitenoise.android.state.privateContactAvatarSource
import dev.ipf.whitenoise.android.ui.common.FollowedPersonBadge
import dev.ipf.whitenoise.android.ui.common.GroupAvatar
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
    avatarAccount: String?,
    openableDmAvatarAccount: String?,
    profileTapEnabled: Boolean,
    interactionsEnabled: Boolean,
    pinned: Boolean,
    onOpenProfile: (String) -> Unit,
) {
    ChatRowAvatarMarkers(openableDmAvatarAccount, pinned) {
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
            GroupAvatar(
                appState = appState,
                group = item.group,
                title = title,
                seed = item.selectedAvatarSeed ?: avatarAccount ?: item.group.groupIdHex,
                size = CHAT_ROW_AVATAR_SIZE,
                // A group without its own image shows its monogram, never a
                // member's photo; only direct chats borrow the peer's picture.
                // Only the account the projector names (the peer of an unnamed pair, an inviter)
                // lends its picture; MDK's selected avatar for a named group is ignored, as the
                // conversation header does.
                accountRef = accountRef,
                fallbackPictureUrl =
                    avatarAccount?.let {
                        appState.privateContactAvatarSource(it, accountRef)
                            ?: item.selectedAvatarUrl
                            ?: appState.avatarUrl(it)
                    },
                firstFrameAvatar = item.firstFrameAvatar,
                durableAvatar = item.selectedAvatarAsset,
                durableAvatarIsPersonPicture = item.selectedPresentation?.avatarSource?.isPeerSourced() == true,
                retainCompatibility = true,
            )
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
            FollowedPersonBadge(Modifier.align(Alignment.BottomEnd), followedPersonBadgeSize(CHAT_ROW_AVATAR_SIZE))
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

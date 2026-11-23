package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isPeerSourced
import dev.ipf.whitenoise.android.state.privateContactAvatarSource
import dev.ipf.whitenoise.android.ui.common.GroupAvatar

/** The picture a chat row draws for [item], shared by every surface that lists chats. */
@Suppress("FunctionNaming")
@Composable
internal fun ChatListItemAvatar(
    item: ChatListItem,
    appState: WhiteNoiseAppState,
    accountRef: String?,
    title: String,
    size: Dp,
) {
    val avatarAccount = chatListItemAvatarAccount(item)
    GroupAvatar(
        appState = appState,
        group = item.group,
        title = title,
        seed = item.selectedAvatarSeed ?: avatarAccount ?: item.group.groupIdHex,
        size = size,
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

@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.newchat.PersonRow
import dev.ipf.whitenoise.android.ui.chats.newchat.SelectionIndicator
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.common.rememberChatListGroupAvatar
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardTargetAvatarAccount
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardTargetMembersPreview

/** One share destination; a DM's stored peer picture animates like any other profile picture. */
@Composable
internal fun ShareTargetRow(
    index: Int,
    count: Int,
    item: ChatListItem,
    title: String,
    selected: Boolean,
    selectedAccountIdHex: String?,
    ownerAccountRef: String?,
    appState: WhiteNoiseAppState,
    onToggle: (String) -> Unit,
) {
    val groupId = item.group.groupIdHex
    val avatarAccount = forwardTargetAvatarAccount(item)
    val avatar =
        rememberChatListGroupAvatar(
            appState,
            item,
            ownerAccountRef,
            avatarAccount?.let { appState.contactAvatarSource(it, ownerAccountRef) },
        )
    val memberIds =
        remember(item, selectedAccountIdHex) {
            item.memberSnapshot
                ?.members
                .orEmpty()
                .map { it.memberIdHex }
                .filter { it.isNotBlank() && it != selectedAccountIdHex }
                .distinct()
        }
    val memberRevisions = memberIds.map(appState::profileAccountRevisionForCompose)
    val membersPreview =
        remember(item, ownerAccountRef, memberRevisions) {
            forwardTargetMembersPreview(item, selectedAccountIdHex) { memberIdHex ->
                appState.contactDisplayNameCached(ownerAccountRef, memberIdHex)
            }
        }
    PersonRow(
        index = index,
        count = count,
        title = title,
        subtitle = membersPreview,
        avatarSeed = avatarAccount ?: item.group.groupIdHex,
        avatarUrl = avatar.pictureUrl,
        avatarImage = avatar.image,
        avatarAnimationKey = avatar.animationKey,
        groupAnimation = avatar.groupAnimation,
        avatarDomain = avatar.pictureDomain,
        modifier = Modifier.semantics { this.selected = selected },
        onClick = { onToggle(groupId) },
        trailing = { SelectionIndicator(selected = selected) },
    )
}

/** Nothing to share to yet, or nothing matching the search. */
@Composable
internal fun ShareEmptyState(noChats: Boolean) {
    if (noChats) {
        WhiteNoiseEmptyState(stringResource(R.string.share_no_chats))
    } else {
        WhiteNoiseEmptyState(stringResource(R.string.no_matches), stringResource(R.string.share_no_matches))
    }
}

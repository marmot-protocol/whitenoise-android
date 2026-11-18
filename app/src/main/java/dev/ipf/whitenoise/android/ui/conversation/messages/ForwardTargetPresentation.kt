package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.nativeFolderMembership
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * Chat-picker sheet for forwarding a message into one or more other chats
 * (issue #390). Multi-select, searchable, recent-first. Confirming fans the
 * message out to every selected chat as an independent fresh send (see
 * [WhiteNoiseAppState.startForwardMessages]) — each target is re-encrypted under its own
 * group state, with no source-group key reuse and no original-sender / source
 * attribution carried across the boundary.
 *
 * The picker deliberately omits the chat the message came from
 * ([originGroupIdHex]): forwarding a message back into its own conversation is
 * never the intent and would just duplicate it.
 */
internal fun forwardTargetAvatarAccount(item: ChatListItem): String? =
    GroupProjector.avatarAccount(
        group = item.group,
        otherMemberAccount = item.presentationOtherMemberAccount,
        memberCount = item.presentationMemberCount,
    )

internal fun forwardTargetMembersPreview(
    item: ChatListItem,
    activeAccountIdHex: String?,
    memberTitle: (String) -> String,
): String? {
    if (forwardTargetAvatarAccount(item) != null) return null
    return item.memberSnapshot
        ?.members
        ?.filterNot { it.memberIdHex.equals(activeAccountIdHex, ignoreCase = true) }
        ?.map { memberTitle(it.memberIdHex) }
        ?.filter { it.isNotBlank() }
        ?.take(6)
        ?.joinToString(", ")
        ?.takeIf { it.isNotBlank() }
}

/** Resolves all saved folders against the same bounded, eligible account-owned destinations. */
internal fun destinationFolderMembershipRows(
    appState: WhiteNoiseAppState,
    targets: List<ChatListItem>,
    groupTitleCopy: GroupTitleCopy,
    ownerAccountRef: String? = appState.activeAccountRef,
    ownerAccountIdHex: String? = appState.activeAccount?.accountIdHex,
): List<Pair<ChatFolder, List<String>>> {
    val accountRef = ownerAccountRef ?: return emptyList()
    val store = appState.chatFolderPreferences
    val eligibleIds =
        targets
            .map {
                it.group.groupIdHex
                    .trim()
                    .lowercase(Locale.ROOT)
            }.filter { it.isNotEmpty() }
            .distinct()
    val mutedGroupIds = targets.filter(ChatListItem::engineMuted).mapTo(hashSetOf()) { it.group.groupIdHex }
    return store
        .foldersFor(accountRef)
        .map { folder ->
            val native = appState.nativeFolderMembership(accountRef, folder.id)?.idSet
            val memberIds =
                native ?: chatFolderChatIds(
                    items = targets,
                    manualChatIds = store.membershipFor(accountRef, folder.id),
                    excludedChatIds = store.excludedChats(accountRef, folder.id),
                    rule = store.folderRule(accountRef, folder.id),
                    activeAccountIdHex = ownerAccountIdHex,
                    isMuted = { groupIdHex ->
                        groupIdHex in mutedGroupIds
                    },
                    displayTitle = { forwardTargetDisplayTitle(it, appState, accountRef, groupTitleCopy) },
                ).mapTo(hashSetOf()) { it.trim().lowercase(Locale.ROOT) }
            folder to eligibleIds.filter(memberIds::contains)
        }
}

/**
 * Chat title resolved against the account that owns the chat list, so a
 * non-active destination account's rows never borrow the active account's
 * contact-name caches.
 */
internal fun forwardTargetDisplayTitle(
    item: ChatListItem,
    appState: WhiteNoiseAppState,
    ownerAccountRef: String?,
    copy: GroupTitleCopy,
): String =
    when {
        ownerAccountRef == null || ownerAccountRef == appState.activeAccountRef ->
            chatListItemDisplayTitle(item, appState, copy)
        item.sanitizedNamedTitle != null -> item.sanitizedNamedTitle.orEmpty()
        else ->
            GroupProjector.displayTitle(
                group = item.group,
                otherMemberAccount = item.presentationOtherMemberAccount,
                memberCount = item.presentationMemberCount,
                memberTitle = { appState.contactDisplayNameCached(ownerAccountRef, it) },
                copy = copy,
                conversationKind = item.projection?.conversationKind,
                soleSelfMember = item.presentationActiveAccountIsSoleMember,
            )
    }

package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.MessageSearchConstraints
import dev.ipf.whitenoise.android.core.canonicalChatListGroupId
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import java.time.ZoneId
import java.util.Locale

/** Two optional chat-id scopes (folder pill, folder filters) combine by intersection, null meaning unscoped. */
internal fun intersectChatScopes(
    first: Set<String>?,
    second: Set<String>?,
): Set<String>? =
    when {
        first == null -> second
        second == null -> first
        else ->
            first
                .mapTo(mutableSetOf(), ::canonicalChatListGroupId)
                .intersect(second.mapTo(mutableSetOf(), ::canonicalChatListGroupId))
    }

/** The prototype's chat-type and named-chat scope over the chat list; both empty leaves the list untouched. */
internal fun applyGlobalSearchChatScope(
    source: List<ChatListItem>,
    chatTypes: Set<GlobalSearchChatType>,
    chatIds: Set<String>,
): List<ChatListItem> {
    if (chatTypes.isEmpty() && chatIds.isEmpty()) return source
    val canonicalChatIds = chatIds.mapTo(mutableSetOf(), ::canonicalChatListGroupId)
    return source.filter { item ->
        (chatTypes.isEmpty() || item.globalSearchChatType() in chatTypes) &&
            (canonicalChatIds.isEmpty() || canonicalChatListGroupId(item.group.groupIdHex) in canonicalChatIds)
    }
}

/** The prototype chat type of a row: a direct chat or a group. */
internal fun ChatListItem.globalSearchChatType(): GlobalSearchChatType =
    if (GroupProjector.isDm(projection?.conversationKind, presentationMemberCount, group.name)) {
        GlobalSearchChatType.DIRECT
    } else {
        GlobalSearchChatType.GROUPS
    }

/** Restricts a scoped list to the given canonical chat ids; null keeps the list. */
internal fun restrictToChatIds(
    source: List<ChatListItem>,
    chatIds: Set<String>?,
): List<ChatListItem> =
    if (chatIds == null) {
        source
    } else {
        val canonical = chatIds.mapTo(mutableSetOf(), ::canonicalChatListGroupId)
        source.filter { canonicalChatListGroupId(it.group.groupIdHex) in canonical }
    }

/** Sender, date and content filters as message-search constraints; null when none is active. */
internal fun messageSearchConstraintsFor(
    state: GlobalSearchState,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): MessageSearchConstraints? {
    if (!state.messageFiltersActive) return null
    return MessageSearchConstraints(
        senderIds = state.senderFilters.mapTo(mutableSetOf()) { it.stableId.lowercase(Locale.ROOT) },
        dateBounds = runCatching { state.dateFilterSelection.resolveEpochBounds(nowMillis, zoneId) }.getOrNull(),
        contentKinds = state.contentFilterSelection.selectedKinds,
    )
}

/** Picker choices: the account's folders, the chats in the current scope and everyone who writes in them. */
internal fun globalSearchFilterOptions(
    appState: WhiteNoiseAppState,
    folders: List<GlobalSearchFolderOption>,
    scopedChats: List<ChatListItem>,
    titleCopy: GroupTitleCopy,
): GlobalSearchFilterOptions =
    GlobalSearchFilterOptions(
        folders = folders,
        chats =
            scopedChats.map { item ->
                val peer =
                    GroupProjector.avatarAccount(
                        item.group,
                        item.presentationOtherMemberAccount,
                        item.presentationMemberCount,
                    )
                WhiteNoisePickerItem(
                    id = canonicalChatListGroupId(item.group.groupIdHex),
                    title = chatListItemDisplayTitle(item, appState, titleCopy),
                    avatarSeed = item.selectedAvatarSeed ?: peer ?: item.group.groupIdHex,
                    avatarUrl = peer?.let { item.selectedAvatarUrl ?: appState.avatarUrl(it) },
                )
            },
        senders =
            scopedChats
                .flatMap { it.memberSnapshot?.members.orEmpty() }
                // memberIdHex is the Nostr pubkey the timeline's `sender` carries; `account` is a local label.
                .map { member -> member.memberIdHex.lowercase(Locale.ROOT) }
                .filter { it.isNotBlank() }
                .distinct()
                .map { hex ->
                    WhiteNoisePickerItem(
                        id = hex,
                        title = appState.chatMemberTitle(hex),
                        avatarSeed = hex,
                        avatarUrl = appState.avatarUrl(hex),
                    )
                }.sortedBy { it.title.lowercase(Locale.ROOT) },
    )

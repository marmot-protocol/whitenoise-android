package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.MessageSearchConstraints
import dev.ipf.whitenoise.android.core.canonicalChatListGroupId
import dev.ipf.whitenoise.android.core.chatListItemTitle
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

/** All search consumers share this final intersection, including an explicitly empty folder. */
internal fun globalSearchScopedChats(
    source: List<ChatListItem>,
    state: GlobalSearchState,
    folderChatIds: Set<String>?,
): List<ChatListItem> =
    applyGlobalSearchChatScope(
        restrictToChatIds(source, folderChatIds),
        state.chatTypeFilters,
        state.chatFilters.mapTo(mutableSetOf()) { it.stableId },
    )

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
    scope: GlobalSearchFilterScope,
): GlobalSearchFilterOptions =
    with(scope) {
        GlobalSearchFilterOptions(
            folders = folders,
            chats =
                chatChoices.map { item ->
                    val peer =
                        GroupProjector.avatarAccount(
                            item.group,
                            item.presentationOtherMemberAccount,
                            item.presentationMemberCount,
                        )
                    WhiteNoisePickerItem(
                        id = canonicalChatListGroupId(item.group.groupIdHex),
                        title =
                            chatListItemTitle(
                                item,
                                { appState.contactNicknameFor(accountRef, it) },
                                { appState.contactDisplayNameCached(accountRef, it) },
                                titleCopy,
                            ),
                        avatarSeed = item.selectedAvatarSeed ?: peer ?: item.group.groupIdHex,
                        avatarUrl =
                            peer?.let { appState.contactAvatarSource(it, accountRef) }
                                ?: item.selectedAvatarUrl,
                    )
                },
            senders =
                globalSearchSenderIds(senderChats, selfId)
                    .map { hex ->
                        WhiteNoisePickerItem(
                            id = hex,
                            title =
                                if (hex.equals(selfId, ignoreCase = true)) {
                                    selfLabel
                                } else {
                                    appState.contactDisplayNameCached(accountRef, hex)
                                },
                            avatarSeed = hex,
                            avatarUrl = appState.contactAvatarSource(hex, accountRef),
                        )
                    }.sortedWith(
                        compareBy<WhiteNoisePickerItem> { !it.id.equals(selfId, ignoreCase = true) }
                            .thenBy { it.title.lowercase(Locale.ROOT) }
                            .thenBy { it.id },
                    ),
            membersPending = senderChats.any { it.memberSnapshot == null },
        )
    }

/** Membership keys, never account labels, identify sender filters; the current identity is always selectable. */
internal fun globalSearchSenderIds(
    chats: List<ChatListItem>,
    selfId: String?,
): Set<String> =
    (
        chats
            .asSequence()
            .flatMap {
                it.memberSnapshot
                    ?.members
                    .orEmpty()
                    .asSequence()
            }.map { it.memberIdHex } +
            sequenceOf(selfId).filterNotNull()
    ).map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
        .toSet()

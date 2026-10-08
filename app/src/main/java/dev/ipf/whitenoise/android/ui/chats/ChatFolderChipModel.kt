package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.SmartFolderCodec
import dev.ipf.whitenoise.android.state.SystemFolderKind
import dev.ipf.whitenoise.android.state.chatFolderSource
import dev.ipf.whitenoise.android.state.references
import dev.ipf.whitenoise.android.state.returnChatListToTop

/** Restore the newest loaded windows before the prototype disables native paging and anchor reports. */
internal suspend fun ChatsController.returnSmartFolderWindowsToTop() {
    returnChatListToTop(ChatListViewFfi.CHATS)
    returnChatListToTop(ChatListViewFfi.ARCHIVED)
}

/**
 * One configured folder chip. The unchanged Chats configuration can also represent
 * the unfiltered root, avoiding a duplicate All chats chip until Chats changes.
 * [customLabel] is the stored folder name; when empty and [systemKind] is
 * set, the chip renders that default's localized label instead.
 */
internal data class ChatFolderChipModel(
    val folderId: String,
    val systemKind: SystemFolderKind?,
    val customLabel: String,
    val trailingCount: Int,
    val pending: Boolean = false,
    val unfilteredHome: Boolean = false,
    val unfilteredScope: Boolean = unfilteredHome,
)

/**
 * Derives the visible chip row from the folder store: the user's configured
 * order, hiding empty folders unless their settings opt in to keeping them visible.
 * The selected folder stays represented while empty so its filter remains
 * visible and explicit. Membership, source list, and the unread badge all
 * come from each folder's own rule (via [membershipOf], which must evaluate
 * against the matching source), so an edited default behaves exactly like a
 * custom folder.
 */
internal fun chatFolderChipModels(
    folders: List<ChatFolder>,
    activeItems: List<ChatListItem>,
    archivedItems: List<ChatListItem>,
    activeAccountIdHex: String?,
    ruleOf: (folderId: String) -> ChatFolderRule?,
    membershipOf: (folderId: String) -> Set<String>,
    pendingFolderIds: Set<String> = emptySet(),
    selectedFolderId: String? = null,
    excludedOf: (String) -> Set<String> = { emptySet() },
): List<ChatFolderChipModel> =
    folders
        .sortedBy { it.order }
        .mapNotNull { folder ->
            val source = chatFolderSource(ruleOf(folder.id), activeItems, archivedItems)
            val ids = membershipOf(folder.id)
            // One pass per folder: the chip needs only "does anything match"
            // and the matched unread count, so neither the intermediate member
            // list nor a per-row lowercase copy of the group id is needed.
            var memberCount = 0
            var unreadCount = 0
            source.forEach { item ->
                if (item.foldedId in ids) {
                    memberCount++
                    if (item.effectiveHasUnread(activeAccountIdHex)) unreadCount++
                }
            }
            val pending = folder.id in pendingFolderIds
            val hiddenWhenEmpty = memberCount == 0 && !folder.showWhenEmpty
            if (hiddenWhenEmpty && folder.id != selectedFolderId && !pending) {
                null
            } else {
                ChatFolderChipModel(
                    folderId = folder.id,
                    systemKind = folder.systemKind,
                    customLabel = folder.name,
                    trailingCount = unreadCount,
                    pending = pending,
                    unfilteredHome =
                        folder.systemKind == SystemFolderKind.CHATS &&
                            ruleOf(folder.id) == ChatFolderRule(includeAll = true, includeMuted = true) &&
                            folder.sort == dev.ipf.whitenoise.android.state.ChatFolderSort.RECENT &&
                            excludedOf(folder.id).isEmpty(),
                    unfilteredScope =
                        folder.systemKind == SystemFolderKind.CHATS &&
                            ruleOf(folder.id) == ChatFolderRule(includeAll = true, includeMuted = true) &&
                            excludedOf(folder.id).isEmpty(),
                )
            }
        }

/** An unresolved roster is not an authoritative empty member-rule answer. */
internal fun memberBasedFolderPending(
    rule: ChatFolderRule?,
    items: Iterable<ChatListItem>,
): Boolean {
    val participants =
        if (rule?.smartFilter != null) {
            SmartFolderCodec
                .decode(rule.smartFilter)
                ?.takeIf(SmartFolderCodec::valid)
                ?.references(FolderField.PARTICIPANTS) == true
        } else {
            rule?.includeMemberPubkeys?.isNotEmpty() == true
        }
    return participants && items.any { it.memberSnapshot == null }
}

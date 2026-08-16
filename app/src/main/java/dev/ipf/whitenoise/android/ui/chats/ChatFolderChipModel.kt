package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.SystemFolderKind
import java.util.Locale

/**
 * One renderable chat-list chip. `All` is not modeled here: it is the
 * permanent reset state the row always renders first, not a real folder.
 * [customLabel] is the stored folder name; when empty and [systemKind] is
 * set, the chip renders that default's localized label instead.
 */
internal data class ChatFolderChipModel(
    val folderId: String,
    val systemKind: SystemFolderKind?,
    val customLabel: String,
    val trailingCount: Int,
    val pending: Boolean = false,
)

/**
 * Derives the visible chip row from the folder store: the user's configured
 * order, and only non-selected folders that currently match ≥1 chat — the
 * hide-when-empty rule the Archived chip pioneered, applied to every folder.
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
): List<ChatFolderChipModel> =
    folders
        .sortedBy { it.order }
        .mapNotNull { folder ->
            val source = if (ruleOf(folder.id)?.archivedOnly == true) archivedItems else activeItems
            val ids = membershipOf(folder.id)
            val members = source.filter { it.group.groupIdHex.lowercase(Locale.ROOT) in ids }
            val pending = folder.id in pendingFolderIds
            if (members.isEmpty() && folder.id != selectedFolderId && !pending) {
                null
            } else {
                ChatFolderChipModel(
                    folderId = folder.id,
                    systemKind = folder.systemKind,
                    customLabel = folder.name,
                    trailingCount = members.count { it.effectiveHasUnread(activeAccountIdHex) },
                    pending = pending,
                )
            }
        }

/** An unresolved roster is not an authoritative empty member-rule answer. */
internal fun memberBasedFolderPending(
    rule: ChatFolderRule?,
    items: Iterable<ChatListItem>,
): Boolean =
    rule?.includeMemberPubkeys?.isNotEmpty() == true &&
        items.any { it.memberSnapshot == null }

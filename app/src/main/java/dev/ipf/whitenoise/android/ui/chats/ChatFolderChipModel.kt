package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.SystemFolderKind

/**
 * One renderable chat-list chip. `All` is not modeled here: it is the
 * permanent reset state the row always renders first, not a real folder.
 */
internal data class ChatFolderChipModel(
    val folderId: String,
    val systemKind: SystemFolderKind?,
    val customLabel: String,
    val trailingCount: Int,
)

/**
 * Derives the visible chip row from the folder store: the user's configured
 * order, and only folders that currently match ≥1 chat — the hide-when-empty
 * rule the Archived chip already had, generalized to every folder. Unread and
 * Archived keep their existing badge counts.
 */
internal fun chatFolderChipModels(
    folders: List<ChatFolder>,
    activeItems: List<ChatListItem>,
    archivedItems: List<ChatListItem>,
    membershipOf: (folderId: String) -> Set<String>,
): List<ChatFolderChipModel> =
    folders
        .sortedBy { it.order }
        .mapNotNull { folder ->
            when (folder.systemKind) {
                SystemFolderKind.UNREAD -> {
                    val unread = activeItems.count { it.hasUnread }
                    if (unread == 0) {
                        null
                    } else {
                        ChatFolderChipModel(folder.id, folder.systemKind, "", trailingCount = unread)
                    }
                }
                SystemFolderKind.ARCHIVED -> {
                    if (archivedItems.isEmpty()) {
                        null
                    } else {
                        ChatFolderChipModel(
                            folder.id,
                            folder.systemKind,
                            "",
                            trailingCount = archivedItems.count { it.hasUnread },
                        )
                    }
                }
                SystemFolderKind.GROUPS -> {
                    val groups = activeItems.count { !it.isDm() }
                    if (groups == 0) null else ChatFolderChipModel(folder.id, folder.systemKind, "", trailingCount = 0)
                }
                null -> {
                    val membership = membershipOf(folder.id)
                    val matches = activeItems.count { it.group.groupIdHex.lowercase() in membership }
                    if (matches == 0) {
                        null
                    } else {
                        ChatFolderChipModel(folder.id, null, folder.name, trailingCount = 0)
                    }
                }
            }
        }

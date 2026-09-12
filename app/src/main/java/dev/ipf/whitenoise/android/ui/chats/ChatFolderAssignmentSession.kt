package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.state.ChatFolderPreferences

/** A caller-scoped multi-folder draft; only explicitly touched manual membership is committed. */
internal class ChatFolderAssignmentSession(
    private val account: String,
    targets: List<String>,
    private val store: ChatFolderPreferences,
    private val draft: ChatFolderAssignmentDraft,
    private val runtime: Int,
    private val current: () -> Boolean,
) {
    private val targetIds = targets.map { it.trim().lowercase() }.filter(String::isNotEmpty).distinct()
    private var active = true
    val intents get() = draft.intents

    /** A closing or replaced picker cannot revive writes through an already captured callback. */
    fun isCurrent(): Boolean {
        val sameTargets = targetIds.isNotEmpty() && draft.matches(account, runtime, targetIds)
        return active && current() && sameTargets
    }

    /** Captures one desired manual-membership state; rule-derived membership is not edited. */
    fun choose(
        folderId: String,
        include: Boolean,
    ) {
        if (isCurrent() && store.foldersFor(account).any { it.id == folderId }) intents[folderId] = include
    }

    /** Saves touched intents against current folder existence, preserving unrelated/manual and rule state. */
    @Suppress("ReturnCount") // Each native write needs an immediate owner/existence/readback failure exit.
    fun save(): Boolean {
        if (!isCurrent()) return false
        val desired = intents.toMap()
        if (desired.keys.any { id -> store.foldersFor(account).none { it.id == id } }) return false
        for ((folder, include) in desired) {
            for (chat in targetIds) {
                if (!isCurrent() || store.foldersFor(account).none { it.id == folder }) return false
                store.setChatInFolder(account, folder, chat, include)
                if ((chat in store.membershipFor(account, folder)) != include) return false
            }
        }
        active = false
        return true
    }

    /** Leaves before invoking the caller, so same-frame Save/selection replays cannot write. */
    fun leave(action: () -> Unit) {
        if (!isCurrent()) return
        active = false
        action()
    }

    /** Disposes presentation without modifying any persisted memberships. */
    fun dispose() {
        active = false
    }
}

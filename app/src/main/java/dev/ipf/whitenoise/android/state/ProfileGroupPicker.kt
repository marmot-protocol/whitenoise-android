package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.GroupProjector

internal fun sharedChatListItemsWith(
    items: Iterable<ChatListItem>,
    targetAccountIdHex: String,
    activeAccountIdHex: String?,
): List<ChatListItem> {
    val active = activeAccountIdHex?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val target = targetAccountIdHex.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
    return items
        .filter { item ->
            val snapshot = item.memberSnapshot ?: return@filter false
            snapshot.containsAccount(active) && snapshot.containsAccount(target)
        }.distinctBy { it.group.groupIdHex.lowercase() }
}

internal fun profileAddableGroupItems(
    items: Iterable<ChatListItem>,
    targetAccountIdHex: String,
    activeAccountIdHex: String?,
): List<ChatListItem> =
    profileAddableGroupsState(
        items = items,
        targetAccountIdHex = targetAccountIdHex,
        activeAccountIdHex = activeAccountIdHex,
    ).groups

internal enum class ProfileGroupPickerLoadState {
    READY,
    LOADING,
    FAILED,
}

internal data class ProfileGroupPickerState(
    val groups: List<ChatListItem>,
    val pendingGroupIds: Set<String>,
    val loadState: ProfileGroupPickerLoadState,
) {
    companion object {
        fun empty(): ProfileGroupPickerState =
            ProfileGroupPickerState(
                groups = emptyList(),
                pendingGroupIds = emptySet(),
                loadState = ProfileGroupPickerLoadState.READY,
            )
    }
}

internal fun profileAddableGroupsState(
    items: Iterable<ChatListItem>,
    targetAccountIdHex: String,
    activeAccountIdHex: String?,
    failedGroupIds: Set<String> = emptySet(),
): ProfileGroupPickerState {
    val (active, target) =
        profileGroupPickerAccounts(activeAccountIdHex, targetAccountIdHex)
            ?: return ProfileGroupPickerState.empty()
    return profileGroupPickerState(
        candidates = profileManagedGroupCandidates(items, active),
        failedGroupIds = failedGroupIds,
    ) { _, snapshot ->
        snapshot.containsAccount(active) && !snapshot.containsAccount(target)
    }
}

internal fun profilePromotableGroupsState(
    items: Iterable<ChatListItem>,
    targetAccountIdHex: String,
    activeAccountIdHex: String?,
    failedGroupIds: Set<String> = emptySet(),
): ProfileGroupPickerState {
    val (active, target) =
        profileGroupPickerAccounts(activeAccountIdHex, targetAccountIdHex)
            ?: return ProfileGroupPickerState.empty()
    return profileGroupPickerState(
        candidates = profileManagedGroupCandidates(items, active),
        failedGroupIds = failedGroupIds,
    ) { item, snapshot ->
        snapshot.containsAccount(active) &&
            snapshot.containsAccount(target) &&
            !GroupProjector.isAdminRef(item.group, target)
    }
}

private fun profileGroupPickerAccounts(
    activeAccountIdHex: String?,
    targetAccountIdHex: String,
): Pair<String, String>? {
    val active = activeAccountIdHex?.trim().orEmpty()
    val target = targetAccountIdHex.trim()
    return if (active.isEmpty() || target.isEmpty() || active.equals(target, ignoreCase = true)) {
        null
    } else {
        active to target
    }
}

private fun profileManagedGroupCandidates(
    items: Iterable<ChatListItem>,
    activeAccountIdHex: String,
): List<ChatListItem> =
    items
        .filter { item ->
            !item.group.pendingConfirmation &&
                !item.removedFromGroup(activeAccountIdHex) &&
                !item.isDm() &&
                GroupProjector.isAdminRef(item.group, activeAccountIdHex)
        }.distinctBy { it.group.groupIdHex.lowercase() }

private fun profileGroupPickerState(
    candidates: List<ChatListItem>,
    failedGroupIds: Set<String>,
    eligible: (ChatListItem, GroupMemberSnapshot) -> Boolean,
): ProfileGroupPickerState {
    val groups = candidates.filter { item -> item.memberSnapshot?.let { eligible(item, it) } == true }
    val pendingGroupIds =
        candidates
            .filter { it.memberSnapshot == null }
            .mapTo(linkedSetOf()) { it.group.groupIdHex }
    val normalizedFailures = failedGroupIds.mapTo(mutableSetOf()) { it.lowercase() }
    val loadState =
        when {
            pendingGroupIds.any { it.lowercase() in normalizedFailures } -> ProfileGroupPickerLoadState.FAILED
            pendingGroupIds.isNotEmpty() -> ProfileGroupPickerLoadState.LOADING
            else -> ProfileGroupPickerLoadState.READY
        }
    return ProfileGroupPickerState(groups, pendingGroupIds, loadState)
}

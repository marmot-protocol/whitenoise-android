package dev.ipf.whitenoise.android.ui.conversation.messages

import java.util.Locale

internal fun forwardSelectionAfterFolderToggle(
    selected: List<String>,
    memberIds: List<String>,
): List<String> {
    val normalizedSelection = selected.mapNotNull(::normalizedForwardGroupId).distinct()
    val normalizedMemberIds = memberIds.mapNotNull(::normalizedForwardGroupId).distinct()
    val allMembersSelected =
        normalizedMemberIds.isNotEmpty() &&
            normalizedMemberIds.all { it in normalizedSelection }
    val nextSelection =
        if (allMembersSelected) {
            normalizedSelection.filterNot { it in normalizedMemberIds }
        } else {
            normalizedSelection + normalizedMemberIds
        }
    return nextSelection.distinct()
}

internal fun forwardRecipientGroupIds(
    selected: List<String>,
    originGroupIdHex: String,
): List<String> {
    val normalizedOrigin = normalizedForwardGroupId(originGroupIdHex)
    return selected
        .mapNotNull(::normalizedForwardGroupId)
        .filterNot { it == normalizedOrigin }
        .distinct()
}

private fun normalizedForwardGroupId(groupIdHex: String): String? =
    groupIdHex
        .trim()
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotEmpty() }

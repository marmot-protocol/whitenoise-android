package dev.ipf.whitenoise.android.ui.conversation.messages

internal fun forwardSelectionAfterFolderToggle(
    selected: List<String>,
    memberIds: List<String>,
): List<String> {
    val normalizedMemberIds = memberIds.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    val allMembersSelected =
        normalizedMemberIds.isNotEmpty() &&
            normalizedMemberIds.all { memberId ->
                selected.any { it.equals(memberId, ignoreCase = true) }
            }
    val nextSelection =
        if (allMembersSelected) {
            selected.filterNot { selectedId ->
                normalizedMemberIds.any { it.equals(selectedId, ignoreCase = true) }
            }
        } else {
            selected + normalizedMemberIds
        }
    return nextSelection.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
}

internal fun forwardRecipientGroupIds(
    selected: List<String>,
    originGroupIdHex: String,
): List<String> =
    selected
        .filter { it.isNotBlank() && !it.equals(originGroupIdHex, ignoreCase = true) }
        .distinctBy { it.lowercase() }

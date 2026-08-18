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

internal fun toggleForwardTargetSelection(
    selected: List<String>,
    groupIdHex: String,
): List<String> {
    val normalizedSelection = selected.mapNotNull(::normalizedForwardGroupId).distinct()
    val normalizedGroupId = normalizedForwardGroupId(groupIdHex) ?: return normalizedSelection
    return if (normalizedGroupId in normalizedSelection) {
        normalizedSelection - normalizedGroupId
    } else {
        normalizedSelection + normalizedGroupId
    }
}

/**
 * Hands the immutable selection to the app-owned forwarding coordinator and
 * closes the picker as soon as that coordinator accepts the work. Network and
 * media work must never be owned by the picker composition.
 */
internal fun confirmForwardTargets(
    targets: List<String>,
    start: (List<String>) -> Boolean,
    dismiss: () -> Unit,
): Boolean {
    if (!start(targets)) return false
    dismiss()
    return true
}

private fun normalizedForwardGroupId(groupIdHex: String): String? =
    groupIdHex
        .trim()
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotEmpty() }

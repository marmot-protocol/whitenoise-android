package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelectedChatPreviewFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.OutgoingMessageIndicator
import dev.ipf.whitenoise.android.state.SmartFolderFilter

internal fun folderTruth(value: Boolean?): FolderTruth =
    when (value) {
        true -> FolderTruth.TRUE
        false -> FolderTruth.FALSE
        null -> FolderTruth.UNKNOWN
    }

internal fun smartFolderMatches(
    filter: SmartFolderFilter,
    item: ChatListItem,
    displayTitle: (ChatListItem) -> String,
): FolderTruth =
    when (filter) {
        is SmartFolderFilter.Group -> {
            // Even NOT(empty) remains manual-only: clearing a group cannot accidentally include everything.
            if (filter.children.isEmpty()) {
                FolderTruth.UNKNOWN
            } else {
                val children = filter.children.map { smartFolderMatches(it, item, displayTitle) }
                val truth =
                    when {
                        filter.all && FolderTruth.FALSE in children -> FolderTruth.FALSE
                        !filter.all && FolderTruth.TRUE in children -> FolderTruth.TRUE
                        FolderTruth.UNKNOWN in children -> FolderTruth.UNKNOWN
                        filter.all -> FolderTruth.TRUE
                        else -> FolderTruth.FALSE
                    }
                if (filter.not) truth.inverted() else truth
            }
        }
        is SmartFolderFilter.Condition -> {
            val truth = smartFolderCondition(filter, item, displayTitle)
            if (filter.not) truth.inverted() else truth
        }
    }

private fun smartFolderCondition(
    condition: SmartFolderFilter.Condition,
    item: ChatListItem,
    displayTitle: (ChatListItem) -> String,
): FolderTruth {
    val value: Boolean? =
        when (condition.field) {
            FolderField.UNREAD -> folderUnread(item)
            FolderField.MENTIONS -> folderMentions(item)
            FolderField.MUTED -> item.projection?.muted
            FolderField.PINNED -> item.projection?.pinned
            FolderField.ARCHIVED -> item.projection?.archived
            FolderField.ACCEPTED -> folderAccepted(item)
            FolderField.DRAFT -> folderDraft(item)
            // The SDK has no account-wide outbox summary. Absence is not inferred from a latest-message tick.
            FolderField.PENDING_SEND -> folderPendingSend(item)
            FolderField.TYPE -> folderKind(condition.mode, item)
            FolderField.TITLE ->
                localeInvariantFold(displayTitle(item)).contains(localeInvariantFold(condition.values.single()))
            FolderField.PARTICIPANTS -> folderParticipants(condition, item)
        }
    val truth = folderTruth(value)
    return if (condition.mode == FolderMode.NONE) truth.inverted() else truth
}

private fun folderUnread(item: ChatListItem): Boolean? =
    item.projection?.let {
        it.hasUnread ||
            it.manuallyMarkedUnread ||
            it.unreadCount > 0uL
    }

private fun folderMentions(item: ChatListItem): Boolean? =
    item.projection?.let {
        it.unreadMention ||
            it.unreadMentionCount > 0uL
    }

private fun folderPendingSend(item: ChatListItem): Boolean? =
    if (item.projectedDeliveryIndicator() == OutgoingMessageIndicator.Sending) {
        true
    } else {
        null
    }

private fun folderAccepted(item: ChatListItem): Boolean? =
    item.projection?.takeUnless { item.inviteConfirmationUnresolved }?.let {
        !item.removed &&
            !it.pendingConfirmation &&
            it.selfMembership == SelfMembershipFfi.MEMBER &&
            !it.leaveRequestPending &&
            !it.disbanding &&
            it.lifecycleState != GroupLifecycleStateFfi.DISBANDED &&
            it.lifecycleState != GroupLifecycleStateFfi.UNRECOVERABLE
    }

private fun folderDraft(item: ChatListItem): Boolean? =
    when (val preview = item.selectedPreview) {
        null -> null
        is SelectedChatPreviewFfi.Draft ->
            preview.draft.text.isNotBlank() ||
                preview.draft.attachmentCount > 0uL ||
                preview.draft.attachmentKind != null
        else -> false
    }

private fun folderKind(
    mode: FolderMode,
    item: ChatListItem,
): Boolean? =
    item.projection
        ?.conversationKind
        ?.takeUnless {
            it == ChatConversationKindFfi.UNKNOWN
        }?.let {
            if (mode == FolderMode.DIRECT) it == ChatConversationKindFfi.DIRECT else it == ChatConversationKindFfi.GROUP
        }

private fun folderParticipants(
    condition: SmartFolderFilter.Condition,
    item: ChatListItem,
): Boolean? {
    val roster = item.memberSnapshot
    // Never copy the full roster for every condition; lookup only the selected keys.
    val ids = roster?.foldedMemberIds.orEmpty()
    val peer = item.otherMemberAccount?.let(::localeInvariantFold)
    val complete = folderRosterComplete(item, peer)
    val found = condition.values.count { it in ids || it == peer }
    return when (condition.mode) {
        FolderMode.ANY_OF ->
            if (found > 0) {
                true
            } else if (complete) {
                false
            } else {
                null
            }
        FolderMode.ALL_OF ->
            if (found == condition.values.size) {
                true
            } else if (complete) {
                false
            } else {
                null
            }
        FolderMode.EXCLUDES ->
            if (found > 0) {
                false
            } else if (complete) {
                true
            } else {
                null
            }
        else -> null
    }
}

/** A DM roster may enumerate only self; an unresolved counterpart cannot prove absence. */
private fun folderRosterComplete(
    item: ChatListItem,
    peer: String?,
): Boolean =
    item.memberSnapshot?.members?.isNotEmpty() == true &&
        when (item.projection?.conversationKind) {
            ChatConversationKindFfi.GROUP -> true
            ChatConversationKindFfi.DIRECT -> peer != null
            else -> false
        }

package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.ChatListRowActionsFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelectedChatPreviewFfi
import dev.ipf.whitenoise.android.core.MediaPreviewFallback

/**
 * A synchronous view of existing controller inputs, never another row/profile cache.
 * Retained rows select their MDK presentation from the live window; off-window rows carry their own.
 */
@Suppress("LongParameterList")
internal class ChatListRowProjector(
    private val activeAccountIdHex: String?,
    private val groups: Map<String, AppGroupRecordFfi>,
    private val members: Map<String, List<AppGroupMemberRecordFfi>>,
    private val presentationMembers: (String) -> ChatListMemberPresentation?,
    private val tokens: Map<String, MarkdownDocumentFfi>,
    private val mediaFallbacks: Map<String, MediaPreviewFallback>,
    private val removed: Set<String>,
    private val activitySequences: Map<String, ULong>,
    private val optimisticPreviews: Map<String, OptimisticChatListPreviewState>,
    private val archiveActions: ChatListArchiveActions,
    private val localGroupNames: LocalGroupNameAuthority,
    private val presentations: Map<String, ConversationPresentationFfi>,
    private val avatars: Map<String, AvatarAssetFfi>,
    private val previews: Map<String, SelectedChatPreviewFfi>,
    private val actions: Map<String, ChatListRowActionsFfi>,
) {
    /** Uses the retained window's selected values and its optimistic send overlay. */
    fun retained(authoritativeRow: ChatListRowFfi): ChatListItem {
        val key = authoritativeRow.groupIdHex.lowercase()
        return project(
            authoritativeRow,
            presentations[key],
            avatars[key],
            previews[key],
            actions[key],
            optimisticPreviews[key],
        )
    }

    /** Uses the keyed MDK result without inserting it into the bounded window or borrowing its overlay. */
    fun presented(presented: PresentedChatRowFfi): ChatListItem =
        project(
            presented.row,
            localGroupNames.reconciledPresentation(presented.row, presented.presentation),
            presented.avatarAsset,
            presented.preview,
            presented.actions,
            null,
        )

    private fun project(
        authoritativeRow: ChatListRowFfi,
        presentation: ConversationPresentationFfi?,
        avatar: AvatarAssetFfi?,
        preview: SelectedChatPreviewFfi?,
        actions: ChatListRowActionsFfi?,
        optimistic: OptimisticChatListPreviewState?,
    ): ChatListItem {
        val row = archiveActions.projectRow(authoritativeRow)
        return chatListItemFromProjection(
            row = row,
            selectedPresentation = presentation,
            selectedAvatarAsset = avatar,
            selectedPreview = preview,
            actions = actions,
            group = archiveActions.projectGroup(row.groupIdHex, groups[row.groupIdHex]),
            activeAccountIdHex = activeAccountIdHex,
            members = members[row.groupIdHex],
            presentationMembers = presentationMembers(row.groupIdHex),
            previewTokens = chatRowPreviewTokens(row, tokens),
            resolvedMediaPreviewFallback = row.lastMessage?.messageIdHex?.let { mediaFallbacks[it] },
            removed = row.groupIdHex in removed,
            activitySequence = activitySequences[row.groupIdHex.lowercase()] ?: 0uL,
            awaitingSendPreview = optimistic?.awaitingSendPreview() == true,
            hasOptimisticSendPreview = optimistic?.hasOptimisticSendPreview() == true,
        )
    }
}

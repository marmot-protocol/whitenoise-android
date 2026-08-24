package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.whitenoise.android.core.GroupProjector

/**
 * Transcript-only presentation mode. A two-member MLS group uses the compact
 * direct-message treatment from the first frame when an opening snapshot is
 * available, then follows the freshly verified roster without changing group
 * semantics or membership-sensitive gates.
 */
val ConversationController.usesDirectTranscriptChrome: Boolean
    get() {
        val stableMemberCount =
            if (membersVerified) {
                memberCount
            } else {
                initialMemberSnapshot?.memberCount
            }
        return GroupProjector.usesDirectTranscriptChrome(
            isDirectConversation = latestChatListRow?.conversationKind == ChatConversationKindFfi.DIRECT,
            memberCount = stableMemberCount ?: 0,
            memberCountStable = stableMemberCount != null,
        )
    }

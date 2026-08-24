package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.whitenoise.android.core.GroupProjector

/**
 * Transcript-only presentation mode. A verified two-member MLS group uses the
 * compact direct-message bubble treatment without changing its semantics.
 */
val ConversationController.usesDirectTranscriptChrome: Boolean
    get() =
        GroupProjector.usesDirectTranscriptChrome(
            isDirectConversation = latestChatListRow?.conversationKind == ChatConversationKindFfi.DIRECT,
            memberCount = memberCount,
            membersVerified = membersVerified,
        )

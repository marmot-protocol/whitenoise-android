package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.GroupProjector

/** Whether this controller has account-owned evidence for its transcript chrome. */
internal val ConversationController.hasKnownTranscriptPresentation: Boolean
    get() = isDm || initialMemberSnapshot != null || membersVerified

/** A cold roster failure needs recovery; a failed refresh must retain already-trusted chrome. */
internal val ConversationController.transcriptPresentationNeedsRetry: Boolean
    get() =
        !hasKnownTranscriptPresentation &&
            (memberRosterState == GroupRosterLoadState.FAILED || memberRosterState == GroupRosterLoadState.INCONSISTENT)

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
            isDirectConversation = isDm,
            memberCount = stableMemberCount ?: 0,
            memberCountStable = stableMemberCount != null,
        )
    }

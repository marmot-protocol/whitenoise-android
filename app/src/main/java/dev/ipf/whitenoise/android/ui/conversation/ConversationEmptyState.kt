package dev.ipf.whitenoise.android.ui.conversation

/**
 * What an empty conversation should say about itself.
 *
 * An empty timeline does not prove that anything expired: the conversation may never have carried a
 * message, every message may be hidden locally, or nothing displayable may remain for another
 * reason. So the retention wording explains the policy that is in force and never claims history
 * was lost (#2674).
 */
internal sealed interface ConversationEmptyState {
    /** No retention policy is in force, so the ordinary wording applies. */
    data object NoMessages : ConversationEmptyState

    /** Messages in this conversation disappear after [retentionSeconds]. */
    data class DisappearingMessages(
        val retentionSeconds: Long,
    ) : ConversationEmptyState
}

/**
 * Chooses the empty-state wording from the conversation's own retention policy.
 *
 * A non-positive or absent duration is retention off, matching how the rest of the app reads the
 * engine's "no policy" value, so a zero can never be presented as an instant timer.
 */
internal fun conversationEmptyState(retentionSeconds: ULong?): ConversationEmptyState =
    retentionSeconds
        ?.takeIf { it > 0uL }
        ?.let { ConversationEmptyState.DisappearingMessages(it.toLong()) }
        ?: ConversationEmptyState.NoMessages

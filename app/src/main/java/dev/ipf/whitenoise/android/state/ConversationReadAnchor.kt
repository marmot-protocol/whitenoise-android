package dev.ipf.whitenoise.android.state

private val OPTIMISTIC_TIMELINE_MESSAGE_ID =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * Monotonic read-anchor advance. Returns the candidate row's id (the row at
 * [candidateIndex]) only when it is strictly deeper than the current anchor's
 * live position — or when there is no current anchor, or the current anchor
 * has fallen out of the loaded window. Otherwise returns [currentAnchorId]
 * unchanged, so scrolling up can never move the read pointer backwards.
 */
internal fun nextReadAnchor(
    timeline: List<TimelineMessage>,
    currentAnchorId: String?,
    candidateIndex: Int,
): String? {
    val candidate = timeline.getOrNull(candidateIndex)
    val candidateId = candidate?.record?.messageIdHex
    // Synthetic streaming-debug rows carry a non-hex id and never mark read;
    // don't let one become the read anchor or it would pin the pointer off a
    // real message until the next chat row advances it.
    if (candidateId.isNullOrBlank() || candidateId.startsWith(ConversationController.STREAM_DEBUG_ID_PREFIX)) {
        return currentAnchorId
    }
    val anchorIdx = timeline.indexOfFirst { it.record.messageIdHex == currentAnchorId }
    return if (currentAnchorId == null || anchorIdx < 0 || candidateIndex > anchorIdx) candidateId else currentAnchorId
}

/**
 * Advances the conversation's UI read anchor without losing the durable
 * watermark when the screen is recreated. A restored history viewport can be
 * older than the persisted anchor, so a missing anchor is rebased only when
 * [canRebaseMissingAnchor] confirms that the loaded window reaches the live
 * end of the conversation.
 */
internal fun advanceConversationReadAnchor(
    timeline: List<TimelineMessage>,
    currentUiAnchorId: String?,
    durableAnchorId: String?,
    candidateIndex: Int,
    canRebaseMissingAnchor: Boolean = false,
): String? {
    val baseline = currentUiAnchorId ?: durableAnchorId
    if (!baseline.isNullOrBlank() && timeline.none { it.record.messageIdHex == baseline }) {
        // A local send first renders with a UUID, then keeps the same list slot
        // while convergence replaces it with the confirmed 64-hex id. Rebase
        // that transient UI-only anchor through the durable watermark instead
        // of preserving a UUID that can never be found or marked read.
        return when {
            currentUiAnchorId != null && isOptimisticMessageId(currentUiAnchorId) ->
                nextReadAnchor(
                    timeline = timeline,
                    currentAnchorId = durableAnchorId,
                    candidateIndex = candidateIndex,
                )
            canRebaseMissingAnchor ->
                nextReadAnchor(
                    timeline = timeline,
                    currentAnchorId = null,
                    candidateIndex = candidateIndex,
                )
            else -> baseline
        }
    }
    return nextReadAnchor(
        timeline = timeline,
        currentAnchorId = baseline,
        candidateIndex = candidateIndex,
    )
}

/** Returns whether [messageId] is the temporary UUID used before send convergence. */
private fun isOptimisticMessageId(messageId: String): Boolean = OPTIMISTIC_TIMELINE_MESSAGE_ID.matches(messageId)

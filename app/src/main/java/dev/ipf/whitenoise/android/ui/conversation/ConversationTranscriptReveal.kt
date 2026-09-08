package dev.ipf.whitenoise.android.ui.conversation

/**
 * Keeps a notification-routed semantic-group transcript neutral until its
 * account-owned controller can classify sender chrome from a direct projection,
 * an opening roster snapshot, or a freshly verified roster.
 */
internal fun conversationTranscriptReadyToReveal(
    initialPresentationCommitted: Boolean,
    notificationOpenRequestId: Long,
    transcriptPresentationKnown: Boolean,
): Boolean =
    initialPresentationCommitted &&
        (notificationOpenRequestId == 0L || transcriptPresentationKnown)

/** Accepts an empty conversation only after its authoritative timeline and route state settle. */
internal fun conversationAuthoritativeEmptyPresentationReady(
    authoritativeEmptyTimeline: Boolean,
    routePresentationSettled: Boolean,
    inviteAcceptanceResolutionPending: Boolean,
): Boolean =
    authoritativeEmptyTimeline &&
        routePresentationSettled &&
        !inviteAcceptanceResolutionPending

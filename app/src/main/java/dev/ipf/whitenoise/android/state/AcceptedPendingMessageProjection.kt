package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.SendAcceptDispositionFfi

/**
 * MDK assigns an app-event id before reporting [SendAcceptDispositionFfi.ACCEPTED_PENDING].
 * Use that id to settle retained media exactly; a content/timestamp heuristic cannot safely
 * distinguish multiple queued albums.
 */
internal fun acceptedPendingMediaOptimisticIdForProjection(
    projectedMessageIdHex: String,
    acceptedPendingMessageIdsByOptimisticId: Map<String, String>,
): String? =
    acceptedPendingMessageIdsByOptimisticId
        .entries
        .singleOrNull { (_, acceptedMessageIdHex) -> acceptedMessageIdHex == projectedMessageIdHex }
        ?.key

/**
 * Accepted-pending text keeps its local temporary id until its projection is
 * visible, while MDK already knows the canonical app-event id. Pairing on that
 * canonical id prevents identical queued text sends from stealing each other's
 * projections.
 */
internal fun acceptedPendingTextOptimisticIdForProjection(
    projectedMessageIdHex: String,
    acceptedPendingOptimisticIdsByMessageId: Map<String, String>,
): String? = acceptedPendingOptimisticIdsByMessageId[projectedMessageIdHex]

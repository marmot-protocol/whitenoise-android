package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.TimelineProjector

internal fun optimisticMessageIdForProjection(
    optimisticMessages: Collection<TimelineMessage>,
    projected: AppMessageRecordFfi,
    allowDelayedProjection: Boolean = false,
): String? {
    // Bridge fast-path: `performMediaUpload` inserts an optimistic entry whose
    // `record.messageIdHex` is the confirmed event id returned by
    // `sendMediaAttachments`. That id equals the projection's id once the relay
    // echo arrives, so this pairing is exact — no need to fall back to the
    // shape-heuristic below, which would otherwise pick up a sibling pending
    // optimistic with the same `_media_pending` shape (the multi-document send
    // case where N bubbles share direction/sender/kind/recordedAt).
    optimisticMessages
        .firstOrNull { it.record.messageIdHex == projected.messageIdHex }
        ?.let { return it.record.messageIdHex }
    val projectedIsMedia = projected.tags.any { it.values.firstOrNull() == "imeta" }
    // When the projection is for a media send and multiple `_media_pending`
    // optimistics are in flight, the shape heuristic below can't safely pick
    // which sibling to reconcile — they all carry the same direction/kind/
    // sender/recordedAt and `optimisticMessages` is a `SnapshotStateMap`
    // with no insertion-order guarantee. Refuse the match and let
    // `performMediaUpload`'s bridge insert (keyed at `msg:$confirmedId`)
    // resolve the projection on the next publish pass via the fast-path
    // above. distinctBy in `publishTimelineFromIndexes` collapses the
    // transient duplicate (bridge vs. projected) deterministically.
    if (projectedIsMedia) {
        val pendingMediaCount =
            optimisticMessages.count {
                isSendableOptimisticStatus(it.status, allowDelayedProjection) &&
                    it.record.tags.any { tag -> tag.values.firstOrNull() == "_media_pending" }
            }
        if (pendingMediaCount > 1) return null
    }
    // MarmotKit 0.10.1 commits a pending row for a send before the call returns its id, so two identical
    // texts in flight can both be candidates. `optimisticMessages` is a snapshot map with no insertion
    // order; pairing oldest-first by the monotonic timeline order keeps the match deterministic.
    return optimisticMessages
        .sortedBy { it.timelineOrder }
        .firstOrNull { optimistic ->
            if (!isSendableOptimisticStatus(optimistic.status, allowDelayedProjection)) return@firstOrNull false
            if (optimistic.record.direction != projected.direction) return@firstOrNull false
            if (optimistic.record.groupIdHex != projected.groupIdHex) return@firstOrNull false
            if (!optimistic.record.sender.equals(projected.sender, ignoreCase = true)) return@firstOrNull false
            if (optimistic.record.kind != projected.kind) return@firstOrNull false
            val timestampsOk =
                timestampsAreNear(optimistic.record.recordedAt, projected.recordedAt) ||
                    (allowDelayedProjection && projected.recordedAt >= optimistic.record.recordedAt)
            if (!timestampsOk) return@firstOrNull false

            // Media-pending optimistic ↔ media projection: the pending bubble
            // carries plaintext = "📎 filename" + the "_media_pending" sentinel
            // tag, while the projection carries plaintext = caption (often
            // empty) + the real imeta tag. Plain field-by-field equality won't
            // match. The sentinel tag on the optimistic side plus an imeta tag
            // on the projection side, combined with the identity fields above,
            // uniquely identifies the pending → confirmed pair.
            val optimisticIsMediaPending = optimistic.record.tags.any { it.values.firstOrNull() == "_media_pending" }
            if (optimisticIsMediaPending && projectedIsMedia) return@firstOrNull true

            // Standard match for text sends: plaintext equal and the same reply
            // identity. The optimistic record is built from the typed text and
            // carries only its reply tags; everything else on the projected copy
            // is engine-derived on publish (NIP-27 `p` mentions, expiry, provenance
            // and whatever a later engine adds). Requiring those to agree left the
            // two copies unmatched whenever the echo landed before the send call
            // returned, a double bubble until the confirmed id arrived.
            optimistic.record.plaintext == projected.plaintext &&
                replyIdentityTags(optimistic.record.tags) == replyIdentityTags(projected.tags)
        }?.record
        ?.messageIdHex
}

/** The tags that name what a text message replies to; the only tags the optimistic copy carries. */
private fun replyIdentityTags(tags: List<MessageTagFfi>): List<MessageTagFfi> =
    tags.filter { tag ->
        val name = tag.values.firstOrNull()
        name == MessageProjector.EventRefTag || name == MessageProjector.QuoteRefTag
    }

internal fun isSendableOptimisticStatus(
    status: MessageStatus,
    allowFailed: Boolean,
): Boolean =
    status == MessageStatus.Pending ||
        status == MessageStatus.Sent ||
        (allowFailed && status == MessageStatus.Failed)

/**
 * The retained Failed optimistic send that [projected], a retracted MDK row, is the engine copy of, or null.
 * Matching uses identity, plaintext and [sameRenderableSendTags], so an emoji send still matches.
 */
internal fun failedOptimisticMessageIdForInvalidatedProjection(
    optimisticMessages: Collection<TimelineMessage>,
    projected: AppMessageRecordFfi,
): String? =
    optimisticMessages
        .firstOrNull { optimistic ->
            if (optimistic.status != MessageStatus.Failed) return@firstOrNull false
            if (optimistic.record.direction != projected.direction) return@firstOrNull false
            if (optimistic.record.groupIdHex != projected.groupIdHex) return@firstOrNull false
            if (!optimistic.record.sender.equals(projected.sender, ignoreCase = true)) return@firstOrNull false
            if (optimistic.record.kind != projected.kind) return@firstOrNull false
            optimistic.record.plaintext == projected.plaintext &&
                sameRenderableSendTags(optimistic.record.tags, projected.tags)
        }?.record
        ?.messageIdHex

internal fun invalidatedProjectionIdsMatchingMessage(
    timelineRecords: Map<String, TimelineMessageRecordFfi>,
    message: AppMessageRecordFfi,
): List<String> =
    timelineRecords.values
        .filter { projected ->
            projected.invalidationStatus != null &&
                messagesHaveSameRenderableSendShape(
                    left = TimelineProjector.toAppMessageRecord(projected),
                    right = message,
                )
        }.map { it.messageIdHex }

internal fun unpublishedProjectionIdsMatchingMessage(
    timelineRecords: Map<String, TimelineMessageRecordFfi>,
    message: AppMessageRecordFfi,
    activeAccountIdHex: String?,
): List<String> =
    timelineRecords.values
        .filter { projected ->
            !projected.deleted &&
                projected.invalidationStatus == null &&
                projected.sourceMessageIdHex == null &&
                messagesHaveSameRenderableSendShape(
                    left = TimelineProjector.toAppMessageRecord(projected),
                    right = message,
                ) &&
                MessageProjector.isMine(TimelineProjector.toAppMessageRecord(projected), activeAccountIdHex)
        }.map { it.messageIdHex }

/** Whether two records are copies of one send: same direction, chat, sender, kind, text and renderable tags. */
private fun messagesHaveSameRenderableSendShape(
    left: AppMessageRecordFfi,
    right: AppMessageRecordFfi,
): Boolean {
    if (left.direction != right.direction) return false
    if (left.groupIdHex != right.groupIdHex) return false
    if (!left.sender.equals(right.sender, ignoreCase = true)) return false
    if (left.kind != right.kind) return false
    return left.plaintext == right.plaintext && sameRenderableSendTags(left.tags, right.tags)
}

/**
 * Whether two copies of one send carry the same tags that matter. Engine-derived `p` mentions never
 * count. A custom-emoji send is a media message, so MDK's copy adds `imeta` and `emoji` rows the
 * typed optimistic copy never has, and after a re-upload their URLs differ too. When either side
 * carries emoji tags only the reply identity is compared.
 */
internal fun sameRenderableSendTags(
    left: List<MessageTagFfi>,
    right: List<MessageTagFfi>,
): Boolean {
    val hasEmoji = (left + right).any { it.values.firstOrNull() == "emoji" }
    return if (hasEmoji) {
        replyIdentityTags(left) == replyIdentityTags(right)
    } else {
        left.filterNot { it.values.firstOrNull() == "p" } == right.filterNot { it.values.firstOrNull() == "p" }
    }
}

/**
 * Find a projected timeline row that matches [optimistic] and is committed locally
 * but not yet published (`sourceMessageIdHex == null`). Used when retrying a failed
 * optimistic send so we wait for native publication instead of minting a duplicate.
 */
internal fun committedButUnpublishedProjectionForOptimistic(
    timelineRecords: Map<String, TimelineMessageRecordFfi>,
    optimistic: AppMessageRecordFfi,
    activeAccountIdHex: String?,
): TimelineMessageRecordFfi? {
    val optimisticIsMediaPending = optimistic.tags.any { it.values.firstOrNull() == "_media_pending" }
    return timelineRecords.values.firstOrNull { projected ->
        if (projected.deleted || projected.sourceMessageIdHex != null) return@firstOrNull false
        if (projected.direction != "sent") return@firstOrNull false
        val projectedAction = TimelineProjector.toAppMessageRecord(projected)
        if (!MessageProjector.isMine(projectedAction, activeAccountIdHex)) return@firstOrNull false
        if (optimistic.groupIdHex != projectedAction.groupIdHex) return@firstOrNull false
        projected.clientToken?.let { return@firstOrNull it == optimistic.messageIdHex }
        val projectedIsMedia = projectedAction.tags.any { it.values.firstOrNull() == "imeta" }
        if (optimisticIsMediaPending && projectedIsMedia) {
            timestampsAreNear(optimistic.recordedAt, projectedAction.recordedAt)
        } else {
            optimistic.plaintext == projectedAction.plaintext &&
                (
                    optimistic.tags == projectedAction.tags ||
                        emojiSendTagsMatch(optimistic.tags, projectedAction.tags)
                ) &&
                timestampsAreNear(optimistic.recordedAt, projectedAction.recordedAt)
        }
    }
}

/** Whether [projected] is the MDK copy of an emoji send whose typed copy [optimistic] has the same reply identity. */
private fun emojiSendTagsMatch(
    optimistic: List<MessageTagFfi>,
    projected: List<MessageTagFfi>,
): Boolean =
    projected.any { it.values.firstOrNull() == "emoji" } &&
        replyIdentityTags(optimistic) == replyIdentityTags(projected)

private fun timestampsAreNear(
    left: ULong,
    right: ULong,
): Boolean = if (left >= right) left - right <= 1uL else right - left <= 1uL

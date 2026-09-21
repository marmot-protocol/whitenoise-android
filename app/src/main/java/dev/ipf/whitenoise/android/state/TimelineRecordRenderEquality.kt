package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi

/**
 * Whether two timeline records would render the same bubble. Ephemeral observation and ordering
 * timestamps are ignored, while every user-visible projection participates.
 */
internal fun timelineRecordsRenderEqual(
    a: TimelineMessageRecordFfi,
    b: TimelineMessageRecordFfi,
): Boolean = timelineRecordEnvelopeEqual(a, b) && timelineRecordContentEqual(a, b)

/** Compares identity and routing fields that can change a bubble's ownership or reconciliation. */
private fun timelineRecordEnvelopeEqual(
    a: TimelineMessageRecordFfi,
    b: TimelineMessageRecordFfi,
): Boolean =
    a.messageIdHex == b.messageIdHex &&
        a.sourceMessageIdHex == b.sourceMessageIdHex &&
        a.clientToken == b.clientToken &&
        a.direction == b.direction &&
        a.groupIdHex == b.groupIdHex &&
        a.sender == b.sender &&
        a.kind == b.kind

/** Compares the projected content and state that determine the bubble's visible presentation. */
private fun timelineRecordContentEqual(
    a: TimelineMessageRecordFfi,
    b: TimelineMessageRecordFfi,
): Boolean =
    a.plaintext == b.plaintext &&
        markdownDocumentsRenderEqual(a.contentTokens, b.contentTokens) &&
        a.tags == b.tags &&
        a.replyToMessageIdHex == b.replyToMessageIdHex &&
        a.replyPreview == b.replyPreview &&
        a.mediaJson == b.mediaJson &&
        a.media == b.media &&
        a.agentTextStreamJson == b.agentTextStreamJson &&
        a.deleted == b.deleted &&
        a.deletedByMessageIdHex == b.deletedByMessageIdHex &&
        a.invalidationStatus == b.invalidationStatus &&
        a.retentionSeconds == b.retentionSeconds &&
        a.retentionExpiresAt == b.retentionExpiresAt &&
        a.reactions == b.reactions

/** Compares parsed Markdown while preserving byte-array content semantics for blank-line metadata. */
private fun markdownDocumentsRenderEqual(
    a: MarkdownDocumentFfi,
    b: MarkdownDocumentFfi,
): Boolean =
    a.truncated == b.truncated &&
        a.blocks == b.blocks &&
        a.blankLinesBefore.contentEquals(b.blankLinesBefore)

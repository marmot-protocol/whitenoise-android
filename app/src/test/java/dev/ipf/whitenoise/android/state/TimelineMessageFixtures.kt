package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi

/** A row the engine projected: canonical, with an id that outlives the window. */
internal fun projectedTimelineMessage(record: AppMessageRecordFfi): TimelineMessage {
    val projected = projectedRecordFor(record)
    return localTimelineMessage(record).copy(projected = projected)
}

/** An optimistic send or stream-debug row: local only, synthetic id. */
internal fun localTimelineMessage(record: AppMessageRecordFfi): TimelineMessage =
    TimelineMessage(
        id = "msg:${record.messageIdHex}",
        record = record,
        status = MessageStatus.Received,
        timelineOrder = record.recordedAt,
    )

internal fun timelineAppMessage(
    id: String,
    recordedAt: ULong = 1uL,
): AppMessageRecordFfi =
    AppMessageRecordFfi(
        messageIdHex = id,
        direction = "received",
        groupIdHex = "g",
        sender = "s",
        plaintext = "Text $id.",
        contentTokens = emptyMarkdown(),
        kind = 9uL,
        tags = emptyList(),
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = recordedAt,
        receivedAt = recordedAt,
    )

private fun projectedRecordFor(record: AppMessageRecordFfi): TimelineMessageRecordFfi =
    TimelineMessageRecordFfi(
        messageIdHex = record.messageIdHex,
        sourceMessageIdHex = record.messageIdHex,
        direction = record.direction,
        groupIdHex = record.groupIdHex,
        sender = record.sender,
        plaintext = record.plaintext,
        contentTokens = emptyMarkdown(),
        kind = record.kind,
        tags = emptyList(),
        timelineAt = record.recordedAt,
        receivedAt = record.receivedAt,
        replyToMessageIdHex = null,
        replyPreview = null,
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        groupSystem = null,
        reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        deleted = false,
        deletedByMessageIdHex = null,
        invalidationStatus = null,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
    )

private fun emptyMarkdown(): MarkdownDocumentFfi =
    MarkdownDocumentFfi(
        truncated = false,
        blocks = emptyList(),
        blankLinesBefore = ByteArray(0),
    )

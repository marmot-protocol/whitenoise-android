package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.isSentFromThisDevice

/**
 * The delivery status a projected row shows. A [streaming] row is still arriving, and a row that is not [mine] was
 * received. Of the account's own rows, only this installation's send can still be waiting on delivery: a row from
 * another of its devices or from imported history is already delivered, whatever its source event says.
 */
internal fun projectedMessageStatus(
    streaming: Boolean,
    mine: Boolean,
    actionRecord: AppMessageRecordFfi,
    record: TimelineMessageRecordFfi,
): MessageStatus =
    when {
        streaming -> MessageStatus.Streaming
        !mine -> MessageStatus.Received
        actionRecord.isSentFromThisDevice() && record.sourceMessageIdHex == null && !record.recipientConfirmed ->
            MessageStatus.Pending
        else -> MessageStatus.Sent
    }

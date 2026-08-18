package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.retentionIndicatorVisible
import dev.ipf.whitenoise.android.state.MessageStatus

internal fun AppMessageRecordFfi.retentionIndicatorInput(
    controllerKey: Any,
    accountRef: String?,
    deleted: Boolean,
    retentionAtSendSeconds: ULong? = null,
): RetentionIndicatorInput? =
    retentionIndicatorInput(
        controllerKey = controllerKey,
        accountRef = accountRef,
        groupIdHex = groupIdHex,
        messageIdHex = messageIdHex,
        sourceEpoch = sourceEpoch,
        // A non-null projected value, including an explicit zero, is authoritative.
        // The send-time snapshot only fills the projection handoff window.
        durationSeconds = retentionSeconds ?: retentionAtSendSeconds,
        expiresAtEpochSeconds = retentionExpiresAt,
        deleted = deleted,
    )

internal fun retentionIndicatorInput(
    controllerKey: Any,
    accountRef: String?,
    groupIdHex: String,
    messageIdHex: String,
    sourceEpoch: ULong?,
    durationSeconds: ULong?,
    expiresAtEpochSeconds: ULong?,
    deleted: Boolean,
): RetentionIndicatorInput? {
    val duration = durationSeconds?.takeIf { retentionIndicatorVisible(it) }
    return if (accountRef == null || duration == null || deleted) {
        null
    } else {
        RetentionIndicatorInput(
            controllerKey = controllerKey,
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            messageIdHex = messageIdHex,
            sourceEpoch = sourceEpoch,
            durationSeconds = duration,
            expiresAtEpochSeconds = expiresAtEpochSeconds,
        )
    }
}

/**
 * Keeps the retention indicator visible while authoritative input crosses a projection boundary:
 * either a retained record awaiting account binding or an unconfirmed send in a retained group.
 */
internal fun shouldReserveRetentionIndicatorSpace(
    input: RetentionIndicatorInput?,
    projectedRetentionSeconds: ULong?,
    mine: Boolean,
    status: MessageStatus,
    groupRetentionSeconds: ULong,
): Boolean {
    if (input != null) return false
    val unconfirmedOutgoing =
        mine &&
            (status == MessageStatus.Pending || status == MessageStatus.Failed) &&
            groupRetentionSeconds > 0uL
    return retentionIndicatorVisible(projectedRetentionSeconds) || unconfirmedOutgoing
}

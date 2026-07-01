package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.marmotkit.RuntimeProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import dev.ipf.whitenoise.android.core.MessageProjector

/**
 * Builds local notification updates from projection events that the runtime
 * surfaces on the event firehose but may not surface on the notification stream.
 * The event itself remains Marmot's source of truth; this only adapts the live
 * projection update into the same Android notification path used by runtime
 * `NotificationUpdateFfi` values.
 */
internal fun groupSystemActivityNotificationUpdates(update: RuntimeProjectionUpdateFfi): List<NotificationUpdateFfi> {
    val projection = update.update
    if (projection.chatListTrigger != ChatListUpdateTriggerFfi.MEMBERSHIP_CHANGED) return emptyList()
    val groupName = projection.chatListRow?.groupName
    return projection
        .activityCandidateRecords()
        .mapNotNull { record ->
            notificationUpdateForGroupSystemActivity(
                accountRef = update.accountLabel,
                accountIdHex = update.accountIdHex,
                groupName = groupName,
                record = record,
            )
        }
}

private fun dev.ipf.marmotkit.TimelineProjectionUpdateFfi.activityCandidateRecords(): List<TimelineMessageRecordFfi> =
    (changes.mapNotNull { change -> (change as? TimelineMessageChangeFfi.Upsert)?.message } + messages)
        .distinctBy { it.messageIdHex }
        .filter(::isMembershipOrAdminSystemRecord)

private fun isMembershipOrAdminSystemRecord(record: TimelineMessageRecordFfi): Boolean =
    MessageProjector.isGroupSystemKind(record.kind) &&
        GroupSystemEvents
            .resolve(record.plaintext, record.groupSystem)
            ?.let(GroupSystemEvents::isMembershipOrAdminActivity) == true

private fun notificationUpdateForGroupSystemActivity(
    accountRef: String,
    accountIdHex: String,
    groupName: String?,
    record: TimelineMessageRecordFfi,
): NotificationUpdateFfi? {
    val event = GroupSystemEvents.resolve(record.plaintext, record.groupSystem) ?: return null
    val actorHex = GroupSystemEvents.actorHex(event, record.sender)
    if (!GroupSystemEvents.isSelfTargetedMembershipOrAdminActivity(accountIdHex, event, actorHex)) return null
    val groupIdHex = record.groupIdHex.takeIf { it.isNotBlank() } ?: return null
    val messageIdHex = record.messageIdHex.takeIf { it.isNotBlank() } ?: return null
    return NotificationUpdateFfi(
        notificationKey = "group-system:$accountRef:$groupIdHex:$messageIdHex",
        conversationKey = "conversation:$accountRef:$groupIdHex",
        trigger = NotificationTriggerFfi.NEW_MESSAGE,
        accountRef = accountRef,
        accountIdHex = accountIdHex,
        groupIdHex = groupIdHex,
        groupName = groupName,
        isDm = false,
        isMention = false,
        messageIdHex = messageIdHex,
        sender = NotificationUserFfi(accountIdHex = actorHex.orEmpty(), displayName = null, pictureUrl = null),
        receiver = NotificationUserFfi(accountIdHex = accountIdHex, displayName = null, pictureUrl = null),
        previewText = GroupSystemEvents.previewText(record.plaintext, structured = record.groupSystem),
        reactionEmoji = null,
        reactedToPreview = null,
        timestampMs = timelineSecondsToMillis(record.timelineAt),
        isFromSelf = false,
    )
}

private fun timelineSecondsToMillis(timelineAt: ULong): Long {
    val maxSafeSeconds = Long.MAX_VALUE.toULong() / 1000uL
    val seconds = if (timelineAt > maxSafeSeconds) maxSafeSeconds else timelineAt
    return seconds.toLong() * 1000L
}

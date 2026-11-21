package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

internal val WeekdaysOnly =
    setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

/** Epoch milliseconds of a local wall-clock time in [zone]. */
internal fun localMs(
    text: String,
    zone: ZoneId,
): Long =
    LocalDateTime
        .parse(text)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

internal fun profileSchedule(
    start: String,
    end: String,
    days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    enabled: Boolean = true,
): NotificationProfileSchedule =
    NotificationProfileSchedule(
        enabled = enabled,
        startMinuteOfDay = minuteOf(start),
        endMinuteOfDay = minuteOf(end),
        days = days,
    )

internal fun testProfile(
    id: String,
    schedule: NotificationProfileSchedule? = null,
    allowedChats: Set<NotificationProfileChat> = emptySet(),
    allowMentions: Boolean = true,
    name: String = id,
): NotificationProfile =
    NotificationProfile(
        id = id,
        name = name,
        allowedChats = allowedChats,
        allowMentions = allowMentions,
        schedule = schedule,
        createdAtMs = 0L,
    )

/** A synthetic MDK update with no runtime dependency. */
@Suppress("LongParameterList")
internal fun profileTestUpdate(
    accountRef: String = "account-a",
    groupIdHex: String = "group-a",
    trigger: NotificationTriggerFfi = NotificationTriggerFfi.NEW_MESSAGE,
    isDm: Boolean = false,
    isMention: Boolean = false,
    isFromSelf: Boolean = false,
    reactionEmoji: String? = null,
    trafficClass: NotificationTrafficClassFfi = NotificationTrafficClassFfi.STANDARD,
    sender: String = "sender",
): NotificationUpdateFfi =
    NotificationUpdateFfi(
        notificationKey = "key",
        conversationKey = "conversation",
        trigger = trigger,
        trafficClass = trafficClass,
        accountRef = accountRef,
        accountIdHex = accountRef,
        groupIdHex = groupIdHex,
        groupName = "Group",
        isDm = isDm,
        isMention = isMention,
        messageIdHex = "message",
        sender = NotificationUserFfi(sender, "Sender", null),
        receiver = NotificationUserFfi("self", "Self", null),
        previewText = "Hello",
        reactionEmoji = reactionEmoji,
        reactedToPreview = null,
        timestampMs = 1_000L,
        isFromSelf = isFromSelf,
        sticker = null,
    )

private fun minuteOf(text: String): Int {
    val (hour, minute) = text.split(':').map(String::toInt)
    return hour * 60 + minute
}

package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Days to look ahead for a schedule's next start. One week covers every weekday set; the second week covers
 * a start skipped because its window collapsed in a DST gap.
 */
private const val SCHEDULE_LOOKAHEAD_DAYS = 15L

/**
 * The profile in force at [nowMs] in [zone]. A live manual choice wins; a live pause hides schedules;
 * otherwise the open scheduled window that started last wins, ties going to the earlier listed profile.
 */
internal fun activeNotificationProfile(
    state: NotificationProfilesState,
    nowMs: Long,
    zone: ZoneId,
): ActiveNotificationProfile? {
    val manual = state.manual
    val on = (manual as? NotificationProfileManual.On)?.takeIf { it.untilMs == null || nowMs < it.untilMs }
    val manualProfile = on?.let { choice -> state.profiles.firstOrNull { it.id == choice.profileId } }
    val paused = manual is NotificationProfileManual.Paused && nowMs < manual.untilMs
    return when {
        manualProfile != null -> ActiveNotificationProfile(manualProfile, NotificationProfileSource.MANUAL, on.untilMs)
        paused -> null
        else -> scheduledNotificationProfile(state, nowMs, zone)
    }
}

/** The open scheduled window that started last; ties go to the earlier listed profile. */
private fun scheduledNotificationProfile(
    state: NotificationProfilesState,
    nowMs: Long,
    zone: ZoneId,
): ActiveNotificationProfile? {
    val open =
        state.profiles.mapNotNull { profile ->
            profile.schedule?.let { scheduleWindowContaining(it, nowMs, zone) }?.let { profile to it }
        }
    // maxByOrNull keeps the first of equal keys, so list order breaks ties.
    return open.maxByOrNull { it.second.startMs }?.let { (profile, window) ->
        ActiveNotificationProfile(profile, NotificationProfileSource.SCHEDULE, window.endMs)
    }
}

/**
 * Whether an active profile keeps [update] from being posted. Own messages are left to the existing policy;
 * allowed chats and, when enabled, direct mentions in new messages pass. Everything else is held back,
 * including reactions, invites, membership and admin events.
 */
internal fun notificationProfileSuppresses(
    active: NotificationProfile?,
    update: NotificationUpdateFfi,
): Boolean {
    if (active == null || update.isFromSelf) return false
    val chat = NotificationProfileChat(update.accountRef, update.groupIdHex.lowercase(Locale.ROOT))
    val mention =
        update.trigger == NotificationTriggerFfi.NEW_MESSAGE &&
            update.isMention &&
            !LocalNotificationFormatter.isReaction(update)
    return chat !in active.allowedChats && !(active.allowMentions && mention)
}

/** The next instant after [nowMs] at which [activeNotificationProfile] may change, or null if none is scheduled. */
internal fun nextNotificationProfileTransitionMs(
    state: NotificationProfilesState,
    nowMs: Long,
    zone: ZoneId,
): Long? {
    val candidates = mutableListOf<Long>()
    when (val manual = state.manual) {
        is NotificationProfileManual.On -> manual.untilMs?.let(candidates::add)
        is NotificationProfileManual.Paused -> candidates += manual.untilMs
        null -> Unit
    }
    state.profiles.mapNotNull { it.schedule }.filter { it.enabled && it.isValid }.forEach { schedule ->
        scheduleWindowContaining(schedule, nowMs, zone)?.let { candidates += it.endMs }
        nextScheduleStartMs(schedule, nowMs, zone)?.let(candidates::add)
    }
    return candidates.filter { it > nowMs }.minOrNull()
}

/** The occurrence of [schedule] containing [nowMs], or null when it is off, invalid or outside every window. */
internal fun scheduleWindowContaining(
    schedule: NotificationProfileSchedule,
    nowMs: Long,
    zone: ZoneId,
): NotificationProfileWindow? {
    if (!schedule.enabled || !schedule.isValid) return null
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return listOf(today, today.minusDays(1))
        .mapNotNull { day -> scheduleWindowStartingOn(schedule, day, zone) }
        .filter { nowMs >= it.startMs && nowMs < it.endMs }
        .maxByOrNull { it.startMs }
}

/** The next local occurrence of [minuteOfDay] strictly after [nowMs]. */
internal fun nextLocalTimeMs(
    minuteOfDay: Int,
    nowMs: Long,
    zone: ZoneId,
): Long {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val time = localTime(minuteOfDay)
    val candidate = ZonedDateTime.of(today, time, zone).toInstant().toEpochMilli()
    return if (candidate > nowMs) {
        candidate
    } else {
        ZonedDateTime.of(today.plusDays(1), time, zone).toInstant().toEpochMilli()
    }
}

/** The window of [schedule] that starts on [day], or null when [day] is not selected or the window collapses. */
private fun scheduleWindowStartingOn(
    schedule: NotificationProfileSchedule,
    day: LocalDate,
    zone: ZoneId,
): NotificationProfileWindow? {
    if (day.dayOfWeek !in schedule.days) return null
    // ZonedDateTime.of moves a time in a DST gap forward by the gap and takes the earlier offset in an overlap.
    val start = ZonedDateTime.of(day, localTime(schedule.startMinuteOfDay), zone).toInstant().toEpochMilli()
    val endDay = if (schedule.endsNextDay) day.plusDays(1) else day
    val end = ZonedDateTime.of(endDay, localTime(schedule.endMinuteOfDay), zone).toInstant().toEpochMilli()
    return if (end > start) NotificationProfileWindow(start, end) else null
}

/** The first start of [schedule] strictly after [nowMs] within the next two weeks. */
private fun nextScheduleStartMs(
    schedule: NotificationProfileSchedule,
    nowMs: Long,
    zone: ZoneId,
): Long? {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return (0L..SCHEDULE_LOOKAHEAD_DAYS)
        .asSequence()
        .mapNotNull { offset -> scheduleWindowStartingOn(schedule, today.plusDays(offset), zone) }
        .map { it.startMs }
        .firstOrNull { it > nowMs }
}

private fun localTime(minuteOfDay: Int): LocalTime {
    val minutes = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1).toLong()
    return LocalTime.MIDNIGHT.plusMinutes(minutes)
}

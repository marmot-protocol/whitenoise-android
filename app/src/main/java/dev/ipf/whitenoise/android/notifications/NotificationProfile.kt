package dev.ipf.whitenoise.android.notifications

import java.time.DayOfWeek

/** Most profiles one device keeps; the list screen hides its add action at this count. */
internal const val MAX_NOTIFICATION_PROFILES = 10

/** Longest profile name, counted in Unicode code points so emoji are not split. */
internal const val MAX_NOTIFICATION_PROFILE_NAME_CODE_POINTS = 40

/** Minutes in one local day; schedule times are minute-of-day values below it. */
internal const val MINUTES_PER_DAY = 24 * 60

/** One chat a profile still lets through, scoped to the local account that belongs to it. */
internal data class NotificationProfileChat(
    val accountRef: String,
    /** Lower-case group id. */
    val groupIdHex: String,
)

/**
 * A repeating local-time window. [days] are the days on which the window starts; an [endMinuteOfDay] at or
 * before [startMinuteOfDay] ends on the following day.
 */
internal data class NotificationProfileSchedule(
    val enabled: Boolean,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val days: Set<DayOfWeek>,
) {
    /** True when the window crosses midnight and ends on the day after it starts. */
    val endsNextDay: Boolean get() = endMinuteOfDay <= startMinuteOfDay

    /** A schedule the evaluator can run: distinct in-range times and at least one day. */
    val isValid: Boolean
        get() =
            startMinuteOfDay in 0 until MINUTES_PER_DAY &&
                endMinuteOfDay in 0 until MINUTES_PER_DAY &&
                startMinuteOfDay != endMinuteOfDay &&
                days.isNotEmpty()

    companion object {
        /** 22:00 to 07:00 every day: the window offered when a user first turns a schedule on. */
        val Default =
            NotificationProfileSchedule(
                enabled = true,
                startMinuteOfDay = 22 * 60,
                endMinuteOfDay = 7 * 60,
                days = DayOfWeek.entries.toSet(),
            )
    }
}

/**
 * A device-wide notification profile. While it is active, only [allowedChats] (and mentions when
 * [allowMentions] is on) may notify; everything else is not posted.
 */
internal data class NotificationProfile(
    val id: String,
    val name: String,
    val allowedChats: Set<NotificationProfileChat>,
    val allowMentions: Boolean,
    val schedule: NotificationProfileSchedule?,
    val createdAtMs: Long,
)

/** One occurrence of a schedule as the half-open interval [[startMs], [endMs]). */
internal data class NotificationProfileWindow(
    val startMs: Long,
    val endMs: Long,
)

/** The user's latest manual choice, which outranks schedules until it expires. */
internal sealed interface NotificationProfileManual {
    /** The user turned [profileId] on; a null [untilMs] keeps it on until it is turned off. */
    data class On(
        val profileId: String,
        val untilMs: Long?,
    ) : NotificationProfileManual

    /** The user turned profiles off inside a scheduled window; schedules stay quiet until [untilMs]. */
    data class Paused(
        val untilMs: Long,
    ) : NotificationProfileManual
}

/**
 * Everything the store persists: profiles in display order and the latest manual choice. [engaged] records
 * that the user has saved or turned on a profile, which seeding never does; [presetsSeeded] marks that the
 * starter profiles were added, so deleting them never brings them back.
 */
internal data class NotificationProfilesState(
    val profiles: List<NotificationProfile> = emptyList(),
    val manual: NotificationProfileManual? = null,
    val engaged: Boolean = false,
    val presetsSeeded: Boolean = false,
)

/** Why a profile is active now. */
internal enum class NotificationProfileSource { MANUAL, SCHEDULE }

/** The profile in force at one instant, and when that ends if known. */
internal data class ActiveNotificationProfile(
    val profile: NotificationProfile,
    val source: NotificationProfileSource,
    val endsAtMs: Long?,
)

/** Trims [name] and caps it at [MAX_NOTIFICATION_PROFILE_NAME_CODE_POINTS] code points. */
internal fun normalizedNotificationProfileName(name: String): String {
    val trimmed = name.trim()
    val count = trimmed.codePointCount(0, trimmed.length)
    if (count <= MAX_NOTIFICATION_PROFILE_NAME_CODE_POINTS) return trimmed
    return trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_NOTIFICATION_PROFILE_NAME_CODE_POINTS)).trimEnd()
}

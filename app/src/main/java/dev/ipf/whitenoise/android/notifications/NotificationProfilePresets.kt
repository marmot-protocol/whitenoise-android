package dev.ipf.whitenoise.android.notifications

import android.content.Context
import dev.ipf.whitenoise.android.R
import java.time.DayOfWeek

private const val SLEEP_START = 22 * 60
private const val SLEEP_END = 7 * 60
private const val WORK_START = 9 * 60
private const val WORK_END = 17 * 60

/** Localized names for the starter profiles, read once when they are created. */
internal data class NotificationProfilePresetNames(
    val sleep: String,
    val work: String,
    val focus: String,
) {
    companion object {
        fun from(context: Context): NotificationProfilePresetNames =
            NotificationProfilePresetNames(
                sleep = context.getString(R.string.notification_profile_preset_sleep),
                work = context.getString(R.string.notification_profile_preset_work),
                focus = context.getString(R.string.notification_profile_preset_focus),
            )
    }
}

/**
 * The profiles a new install starts with. They are ordinary profiles: editable, deletable and stored like
 * any other. Their schedules are saved switched off, so nothing is held back until the user turns one on.
 */
internal fun notificationProfilePresets(
    names: NotificationProfilePresetNames,
    createdAtMs: Long,
): List<NotificationProfile> =
    listOf(
        NotificationProfile(
            id = "preset-sleep",
            name = names.sleep,
            allowedChats = emptySet(),
            allowMentions = false,
            schedule = NotificationProfileSchedule(false, SLEEP_START, SLEEP_END, DayOfWeek.entries.toSet()),
            createdAtMs = createdAtMs,
            allowRepeatedMessages = true,
            emoji = "🌙",
        ),
        NotificationProfile(
            id = "preset-work",
            name = names.work,
            allowedChats = emptySet(),
            allowMentions = true,
            schedule =
                NotificationProfileSchedule(
                    enabled = false,
                    startMinuteOfDay = WORK_START,
                    endMinuteOfDay = WORK_END,
                    days = DayOfWeek.entries.filterTo(HashSet()) { it != DayOfWeek.SATURDAY && it != DayOfWeek.SUNDAY },
                ),
            createdAtMs = createdAtMs,
            allowAllDirectMessages = true,
            emoji = "💼",
        ),
        NotificationProfile(
            id = "preset-focus",
            name = names.focus,
            allowedChats = emptySet(),
            allowMentions = true,
            schedule = null,
            createdAtMs = createdAtMs,
            allowRepeatedMessages = true,
            emoji = "🎯",
        ),
    ).map { it.copy(name = normalizedNotificationProfileName(it.name)) }

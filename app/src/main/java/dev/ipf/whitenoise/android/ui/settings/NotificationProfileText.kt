package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.notifications.ActiveNotificationProfile
import dev.ipf.whitenoise.android.notifications.NotificationProfile
import dev.ipf.whitenoise.android.notifications.NotificationProfilePreferences
import dev.ipf.whitenoise.android.notifications.NotificationProfileSchedule
import dev.ipf.whitenoise.android.notifications.activeNotificationProfile
import dev.ipf.whitenoise.android.notifications.nextNotificationProfileTransitionMs
import dev.ipf.whitenoise.android.ui.common.currentDateTimeDisplay
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** A fixed day in UTC that formats a minute of the day without any daylight-saving shift. */
private const val CLOCK_REFERENCE_EPOCH_SECONDS = 86_400L
private const val SECONDS_PER_MINUTE = 60L
private const val MILLIS_PER_SECOND = 1_000L

/** Longest wait between re-evaluations, so a clock or time-zone change shows within a minute. */
private const val NOTIFICATION_PROFILE_TICK_MS = 60_000L

/**
 * The profile in force now, re-evaluated at its next transition (at most a minute apart) while composed.
 * Nothing ticks while no profile exists.
 */
@Composable
internal fun rememberActiveNotificationProfile(store: NotificationProfilePreferences): ActiveNotificationProfile? {
    val state by store.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        now = System.currentTimeMillis()
        while (state.profiles.isNotEmpty()) {
            val next = nextNotificationProfileTransitionMs(state, now, ZoneId.systemDefault())
            val wait = ((next ?: Long.MAX_VALUE) - now).coerceIn(1L, NOTIFICATION_PROFILE_TICK_MS)
            delay(wait)
            now = System.currentTimeMillis()
        }
    }
    return remember(state, now) { activeNotificationProfile(state, now, ZoneId.systemDefault()) }
}

internal val NotificationProfileWeekdays =
    setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
internal val NotificationProfileWeekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/** The seven days in [locale]'s week order, starting on its first day of the week. */
internal fun notificationProfileWeek(locale: Locale): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return DayOfWeek.entries.indices.map { first.plus(it.toLong()) }
}

/** Clock text for [minuteOfDay] in the user's 12- or 24-hour choice. */
internal fun formatNotificationProfileClock(
    minuteOfDay: Int,
    locale: Locale,
    use24Hour: Boolean?,
): String =
    IdentityFormatter.clockTime(
        (CLOCK_REFERENCE_EPOCH_SECONDS + minuteOfDay * SECONDS_PER_MINUTE).toULong(),
        locale,
        ZoneOffset.UTC,
        force24Hour = use24Hour,
    )

/** Clock text for a minute of the day, following the user's 12- or 24-hour choice. */
@Composable
internal fun notificationProfileClock(minuteOfDay: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    val use24Hour = currentDateTimeDisplay().use24Hour
    return remember(minuteOfDay, locale, use24Hour) { formatNotificationProfileClock(minuteOfDay, locale, use24Hour) }
}

/** Local clock text for an absolute instant, such as the end of a manual profile. */
@Composable
internal fun notificationProfileInstantClock(
    epochMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val locale = LocalConfiguration.current.locales[0]
    val use24Hour = currentDateTimeDisplay().use24Hour
    return remember(epochMs, zone, locale, use24Hour) {
        IdentityFormatter.clockTime((epochMs / MILLIS_PER_SECOND).toULong(), locale, zone, force24Hour = use24Hour)
    }
}

/** "Every day", "Weekdays", "Weekends", or the chosen days' short names in week order. */
@Composable
internal fun notificationProfileDaysLabel(days: Set<DayOfWeek>): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (days) {
        DayOfWeek.entries.toSet() -> stringResource(R.string.notification_profile_days_every_day)
        NotificationProfileWeekdays -> stringResource(R.string.notification_profile_days_weekdays)
        NotificationProfileWeekend -> stringResource(R.string.notification_profile_days_weekends)
        else ->
            notificationProfileWeek(locale)
                .filter { it in days }
                .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
    }
}

/** "Weekdays, 22:00–07:00" for a schedule. */
@Composable
internal fun notificationProfileScheduleSummary(schedule: NotificationProfileSchedule): String =
    stringResource(
        R.string.notification_profile_schedule_summary,
        notificationProfileDaysLabel(schedule.days),
        notificationProfileClock(schedule.startMinuteOfDay),
        notificationProfileClock(schedule.endMinuteOfDay),
    )

/** "On until 07:00" or "On" while [profile] is active, else its schedule or "No schedule". */
@Composable
internal fun notificationProfileStatus(
    profile: NotificationProfile,
    active: ActiveNotificationProfile?,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val schedule = profile.schedule?.takeIf { it.enabled && it.isValid }
    return when {
        active?.profile?.id == profile.id ->
            active.endsAtMs?.let {
                stringResource(R.string.notification_profile_on_until, notificationProfileInstantClock(it, zone))
            }
                ?: stringResource(R.string.notification_profile_on)
        schedule != null -> notificationProfileScheduleSummary(schedule)
        else -> stringResource(R.string.notification_profile_no_schedule)
    }
}

/** "Sleep is on until 07:00" or "Sleep is on". */
@Composable
internal fun notificationProfileActiveLabel(
    active: ActiveNotificationProfile,
    zone: ZoneId = ZoneId.systemDefault(),
): String =
    active.endsAtMs?.let {
        stringResource(
            R.string.notification_profile_banner_on_until,
            active.profile.name,
            notificationProfileInstantClock(it, zone),
        )
    } ?: stringResource(R.string.notification_profile_banner_on, active.profile.name)

/** What still notifies while [profile] is on: allowed chats, plus mentions when the profile lets them through. */
@Composable
internal fun notificationProfileActiveDetail(profile: NotificationProfile): String =
    stringResource(
        if (profile.allowMentions) {
            R.string.notification_profile_banner_detail_mentions
        } else {
            R.string.notification_profile_banner_detail
        },
    )

package dev.ipf.whitenoise.android.ui.group

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R

/** Human label for a disappearing timer in seconds. */
@Composable
internal fun disappearingMessagesLabel(secs: Long): String =
    when (val spec = disappearingLabelSpec(secs)) {
        DisappearingLabelSpec.Off -> stringResource(R.string.disappearing_off)
        is DisappearingLabelSpec.Preset -> stringResource(spec.resId)
        is DisappearingLabelSpec.Seconds -> disappearingCountLabel(R.plurals.disappearing_seconds_count, spec.count)
        is DisappearingLabelSpec.Minutes -> disappearingCountLabel(R.plurals.disappearing_minutes_count, spec.count)
        is DisappearingLabelSpec.Hours -> disappearingCountLabel(R.plurals.disappearing_hours_count, spec.count)
        is DisappearingLabelSpec.Days -> disappearingCountLabel(R.plurals.disappearing_days_count, spec.count)
        is DisappearingLabelSpec.Weeks -> disappearingCountLabel(R.plurals.disappearing_weeks_count, spec.count)
        is DisappearingLabelSpec.Months -> disappearingCountLabel(R.plurals.disappearing_months_count, spec.count)
        is DisappearingLabelSpec.Years -> disappearingCountLabel(R.plurals.disappearing_years_count, spec.count)
    }

/** Label for a protocol retention value. Past `Long.MAX_VALUE` it reads as the longest duration, never negative. */
@Composable
internal fun disappearingMessagesLabel(secs: ULong): String {
    val clamped = secs.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
    return disappearingMessagesLabel(clamped)
}

/** Formats the exact [count]; only the plural form is chosen from a count clamped to the `Int` range. */
@Composable
private fun disappearingCountLabel(
    @PluralsRes id: Int,
    count: Long,
): String = pluralStringResource(id, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), count)

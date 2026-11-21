package dev.ipf.whitenoise.android.ui.group

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
        is DisappearingLabelSpec.Seconds ->
            stringResource(R.string.disappearing_seconds_format, spec.count)
        is DisappearingLabelSpec.Minutes ->
            stringResource(R.string.disappearing_minutes_format, spec.count)
        is DisappearingLabelSpec.Hours ->
            stringResource(R.string.disappearing_hours_format, spec.count)
        is DisappearingLabelSpec.Days ->
            stringResource(R.string.disappearing_days_format, spec.count)
        is DisappearingLabelSpec.Weeks ->
            pluralStringResource(R.plurals.disappearing_weeks_count, spec.count.toInt(), spec.count.toInt())
        is DisappearingLabelSpec.Months ->
            pluralStringResource(R.plurals.disappearing_months_count, spec.count.toInt(), spec.count.toInt())
        is DisappearingLabelSpec.Years ->
            pluralStringResource(R.plurals.disappearing_years_count, spec.count.toInt(), spec.count.toInt())
    }

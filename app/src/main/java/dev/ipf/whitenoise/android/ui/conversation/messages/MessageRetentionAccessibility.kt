package dev.ipf.whitenoise.android.ui.conversation.messages

import android.icu.text.ListFormatter
import android.icu.text.RelativeDateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

internal fun formatRetentionExpiryState(
    locale: Locale,
    remainingMillis: Long,
    expiryLabel: String,
): String =
    ListFormatter.getInstance(locale).format(
        formatRetentionRemaining(locale, remainingMillis),
        expiryLabel,
    )

internal fun formatRetentionRemaining(
    locale: Locale,
    remainingMillis: Long,
): String {
    val formatter = RelativeDateTimeFormatter.getInstance(locale)
    val boundedMillis = remainingMillis.coerceAtLeast(0L)
    val (count, unit) =
        when {
            boundedMillis >= MILLIS_PER_DAY ->
                roundedUpDuration(boundedMillis, MILLIS_PER_DAY) to
                    RelativeDateTimeFormatter.RelativeUnit.DAYS
            boundedMillis >= MILLIS_PER_HOUR ->
                roundedUpDuration(boundedMillis, MILLIS_PER_HOUR) to
                    RelativeDateTimeFormatter.RelativeUnit.HOURS
            boundedMillis >= MILLIS_PER_MINUTE ->
                roundedUpDuration(boundedMillis, MILLIS_PER_MINUTE) to
                    RelativeDateTimeFormatter.RelativeUnit.MINUTES
            else ->
                roundedUpDuration(boundedMillis, MILLIS_PER_SECOND) to
                    RelativeDateTimeFormatter.RelativeUnit.SECONDS
        }
    return formatter.format(
        count,
        RelativeDateTimeFormatter.Direction.NEXT,
        unit,
    )
}

private fun roundedUpDuration(
    durationMillis: Long,
    unitMillis: Long,
): Double = ceil(durationMillis.toDouble() / unitMillis.toDouble())

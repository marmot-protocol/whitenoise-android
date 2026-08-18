package dev.ipf.whitenoise.android.ui.conversation.messages

import android.icu.text.ListFormatter
import android.icu.text.RelativeDateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

internal fun formatRetentionExpiryState(
    locale: Locale,
    remainingMillis: Long,
    expiryLabel: String,
    expiredLabel: String,
): String =
    ListFormatter.getInstance(locale).format(
        if (remainingMillis <= 0L) expiredLabel else formatRetentionRemaining(locale, remainingMillis),
        expiryLabel,
    )

internal fun formatRetentionRemaining(
    locale: Locale,
    remainingMillis: Long,
): String {
    require(remainingMillis > 0L) { "Relative retention time requires a positive duration" }
    val formatter = RelativeDateTimeFormatter.getInstance(locale)
    val (count, unit) =
        when {
            remainingMillis >= MILLIS_PER_DAY ->
                roundedUpDuration(remainingMillis, MILLIS_PER_DAY) to
                    RelativeDateTimeFormatter.RelativeUnit.DAYS
            remainingMillis >= MILLIS_PER_HOUR ->
                roundedUpDuration(remainingMillis, MILLIS_PER_HOUR) to
                    RelativeDateTimeFormatter.RelativeUnit.HOURS
            remainingMillis >= MILLIS_PER_MINUTE ->
                roundedUpDuration(remainingMillis, MILLIS_PER_MINUTE) to
                    RelativeDateTimeFormatter.RelativeUnit.MINUTES
            else ->
                roundedUpDuration(remainingMillis, MILLIS_PER_SECOND) to
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

package dev.ipf.whitenoise.android.ui

import android.icu.text.RelativeDateTimeFormatter
import java.time.Instant
import java.util.Locale

internal fun markdownTimestampLabel(
    seconds: Long,
    style: Char,
    now: Long = Instant.now().epochSecond,
): String {
    if (style != 'R') return markdownTimestampAbsolute(seconds, style)
    val amount = timestampRelativeAmount(seconds, now)
    val unit =
        when (amount.unit) {
            'y' -> RelativeDateTimeFormatter.RelativeUnit.YEARS
            'M' -> RelativeDateTimeFormatter.RelativeUnit.MONTHS
            'd' -> RelativeDateTimeFormatter.RelativeUnit.DAYS
            'h' -> RelativeDateTimeFormatter.RelativeUnit.HOURS
            'm' -> RelativeDateTimeFormatter.RelativeUnit.MINUTES
            else -> RelativeDateTimeFormatter.RelativeUnit.SECONDS
        }
    return RelativeDateTimeFormatter.getInstance(Locale.getDefault()).format(
        amount.count.toDouble(),
        if (amount.future) RelativeDateTimeFormatter.Direction.NEXT else RelativeDateTimeFormatter.Direction.LAST,
        unit,
    )
}

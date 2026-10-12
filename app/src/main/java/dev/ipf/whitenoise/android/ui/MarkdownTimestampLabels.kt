package dev.ipf.whitenoise.android.ui

import android.icu.text.RelativeDateTimeFormatter
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownTimestampStyleFfi
import java.time.Instant
import java.util.Locale

/** The local label for [seconds] in [style]: an absolute local date/time, or an ICU relative phrase for `R`. */
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

/** The canonical single-letter style code used in `<t:seconds:style>` tokens. */
internal fun MarkdownTimestampStyleFfi.code(): Char =
    when (this) {
        MarkdownTimestampStyleFfi.SHORT_TIME -> 't'
        MarkdownTimestampStyleFfi.LONG_TIME -> 'T'
        MarkdownTimestampStyleFfi.SHORT_DATE -> 'd'
        MarkdownTimestampStyleFfi.LONG_DATE -> 'D'
        MarkdownTimestampStyleFfi.SHORT_DATE_TIME -> 'f'
        MarkdownTimestampStyleFfi.LONG_DATE_TIME -> 'F'
        MarkdownTimestampStyleFfi.COMPACT_DATE_TIME -> 's'
        MarkdownTimestampStyleFfi.COMPACT_DATE_TIME_SECONDS -> 'S'
        MarkdownTimestampStyleFfi.RELATIVE -> 'R'
    }

/** The device-local label for this timestamp, as shown in bubbles, previews and read-aloud. */
internal fun MarkdownInlineFfi.Timestamp.localLabel(): String = markdownTimestampLabel(unixSeconds, style.code())

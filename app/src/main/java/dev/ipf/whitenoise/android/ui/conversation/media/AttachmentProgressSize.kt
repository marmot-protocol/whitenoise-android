package dev.ipf.whitenoise.android.ui.conversation.media

import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

private val ProgressSizeUnits = listOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
private val ProgressSizeStep = 1024.toBigDecimal()

/** Formats the full unsigned native byte range with localized decimals, rounding down to avoid overstating progress. */
internal fun formatAttachmentProgressSize(
    bytes: ULong,
    locale: Locale,
): String {
    var amount = bytes.toString().toBigDecimal()
    var unit = 0
    while (amount >= ProgressSizeStep && unit < ProgressSizeUnits.lastIndex) {
        amount = amount.divide(ProgressSizeStep)
        unit++
    }
    val format =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = if (unit == 0) 0 else 1
            maximumFractionDigits = minimumFractionDigits
            roundingMode = RoundingMode.DOWN
        }
    return "${format.format(amount)} ${ProgressSizeUnits[unit]}"
}

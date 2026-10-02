package dev.ipf.whitenoise.android.ui.chats

import android.content.res.Resources
import android.icu.number.NumberFormatter
import android.icu.text.PluralRules
import android.os.Build
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.unreadBadgeLabel
import java.math.BigDecimal

private const val MAX_EXACT_DOUBLE_INTEGER = 9_007_199_254_740_992uL
private const val LARGE_COUNT_MODULUS = 1_000_000_000uL
private const val LARGE_COUNT_BASE = 1_000_000_000_000uL

/** Visual pill count, exact through 999 and `999+` beyond; accessibility still receives the uncapped native total. */
internal fun chatsPillVisibleCount(count: ULong): String? = if (count == 0uL) null else unreadBadgeLabel(count)

/** Formats the full native ULong with its locale plural category, including values above Int.MAX_VALUE. */
internal fun chatsPillAccessibleDescription(
    resources: Resources,
    label: String,
    count: ULong,
): String {
    if (count == 0uL) return label
    val locale = resources.configuration.locales[0]
    val formatter = NumberFormatter.withLocale(locale)
    val rules = PluralRules.forLocale(locale)
    val fullNumber = formatter.format(BigDecimal(count.toString()))
    val category =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rules.select(fullNumber)
        } else {
            rules.select(api30PluralRepresentative(count))
        }
    val representative =
        ((0..200) + listOf(1_000, 1_000_000, 1_000_000_000, Int.MAX_VALUE))
            .firstOrNull { rules.select(it.toDouble()) == category }
            ?: Int.MAX_VALUE
    val unread =
        resources.getQuantityString(
            R.plurals.chat_pill_unread_messages_count,
            representative,
            fullNumber.toString(),
        )
    return "$label, $unread"
}

/** API 30 ICU accepts doubles only; preserve exact small values and large values' final nine digits. */
private fun api30PluralRepresentative(count: ULong): Double =
    if (count <= MAX_EXACT_DOUBLE_INTEGER) {
        count.toDouble()
    } else {
        (LARGE_COUNT_BASE + count % LARGE_COUNT_MODULUS).toDouble()
    }

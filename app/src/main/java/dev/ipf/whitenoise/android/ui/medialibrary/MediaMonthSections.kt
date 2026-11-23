package dev.ipf.whitenoise.android.ui.medialibrary

import dev.ipf.whitenoise.android.core.IdentityFormatter
import java.time.ZoneId
import java.util.Calendar

// Month bucketing keyed off the local-time calendar so the separators match
// what the user sees on each message. `recordedAt` is epoch SECONDS.
internal fun monthKeyForMedia(
    recordedAtSeconds: ULong,
    zone: ZoneId = mediaMonthGroupingZone,
): Int {
    val date = IdentityFormatter.clampedLocalDate(recordedAtSeconds, zone)
    // monthLabel() decodes the month with Calendar.MONTH (0-based).
    return date.year * MONTH_KEY_YEAR_SCALE + (date.monthValue - 1)
}

// Group already-newest-first items by calendar month, preserving order so
// section headers read newest → oldest. Runs during tile projection on a
// background dispatcher — composition only renders the pre-built sections.

/** Groups items into month sections by their recorded time, newest first. */
internal fun <T> groupIntoMonthSections(
    items: List<T>,
    recordedAtOf: (T) -> ULong,
): List<MediaMonthSection<T>> {
    if (items.isEmpty()) return emptyList()
    val sections = LinkedHashMap<Int, ArrayList<T>>()
    for (item in items) {
        val key = monthKeyForMedia(recordedAtOf(item))
        sections.getOrPut(key) { ArrayList() }.add(item)
    }
    return sections.map { (key, bucket) -> MediaMonthSection(key, bucket) }
}

private val mediaMonthGroupingZone: ZoneId = ZoneId.systemDefault()

// Localized month/year header for a [monthKeyForMedia] key.
internal fun monthLabel(key: Int): String {
    val year = key / MONTH_KEY_YEAR_SCALE
    val month = key % MONTH_KEY_YEAR_SCALE
    val cal = Calendar.getInstance()
    cal.set(Calendar.YEAR, year)
    cal.set(Calendar.MONTH, month)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    val monthName =
        cal.getDisplayName(Calendar.MONTH, Calendar.LONG, java.util.Locale.getDefault())
            ?: (month + 1).toString()
    return "$monthName $year"
}

/** A month key is `year * 100 + zero-based month`. */
private const val MONTH_KEY_YEAR_SCALE = 100

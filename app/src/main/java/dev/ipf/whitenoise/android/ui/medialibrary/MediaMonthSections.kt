package dev.ipf.whitenoise.android.ui.medialibrary

import android.text.format.DateFormat
import dev.ipf.whitenoise.android.core.IdentityFormatter
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale

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

/** Local calendar day of a recorded time, in the same zone as the month buckets. */
internal fun mediaDayForMedia(
    recordedAtSeconds: ULong,
    zone: ZoneId = mediaMonthGroupingZone,
): LocalDate = IdentityFormatter.clampedLocalDate(recordedAtSeconds, zone)

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

/** Month and year in the locale's own order, such as "November 2023" or "2023年11月". */
internal fun monthLabel(
    key: Int,
    locale: Locale = Locale.getDefault(),
): String {
    // Buckets are Gregorian months: override only the calendar, keeping choices such as -u-nu-latn digits.
    val gregorian =
        Locale
            .Builder()
            .setLocale(locale)
            .setUnicodeLocaleKeyword("ca", "gregory")
            .build()
    val calendar = Calendar.getInstance(gregorian)
    calendar.clear()
    calendar.set(key / MONTH_KEY_YEAR_SCALE, key % MONTH_KEY_YEAR_SCALE, 1)
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(gregorian, "LLLLy"), gregorian).format(calendar.time)
}

/** A month key is `year * 100 + zero-based month`. */
private const val MONTH_KEY_YEAR_SCALE = 100

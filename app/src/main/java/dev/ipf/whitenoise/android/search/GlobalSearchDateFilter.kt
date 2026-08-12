package dev.ipf.whitenoise.android.search

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class GlobalSearchEpochBounds(
    val startEpochMillisInclusive: Long,
    val endEpochMillisExclusive: Long,
)

sealed interface GlobalSearchDateFilterSelection {
    data object AnyTime : GlobalSearchDateFilterSelection

    data object Today : GlobalSearchDateFilterSelection

    data object Last7Days : GlobalSearchDateFilterSelection

    data object Last30Days : GlobalSearchDateFilterSelection

    data class Custom(
        val from: LocalDate,
        val to: LocalDate,
        val zoneId: ZoneId,
    ) : GlobalSearchDateFilterSelection

    fun resolveEpochBounds(
        nowMillis: Long,
        zoneId: ZoneId,
    ): GlobalSearchEpochBounds? =
        when (this) {
            AnyTime -> null
            Today -> presetBounds(nowMillis, zoneId, daysInclusive = 1)
            Last7Days -> presetBounds(nowMillis, zoneId, daysInclusive = 7)
            Last30Days -> presetBounds(nowMillis, zoneId, daysInclusive = 30)
            is Custom -> {
                require(!from.isAfter(to)) { "Global search custom date range is reversed" }
                GlobalSearchEpochBounds(
                    startEpochMillisInclusive = civilDateStartEpochMillis(from, this.zoneId),
                    endEpochMillisExclusive = civilDateEndEpochMillisExclusive(to, this.zoneId),
                )
            }
        }

    fun validateCustomRange(): GlobalSearchCustomRangeValidation =
        when (this) {
            is Custom ->
                if (from.isAfter(to)) {
                    GlobalSearchCustomRangeValidation.Reversed
                } else {
                    GlobalSearchCustomRangeValidation.Valid
                }
            else -> GlobalSearchCustomRangeValidation.NotCustom
        }

    private fun presetBounds(
        nowMillis: Long,
        zoneId: ZoneId,
        daysInclusive: Int,
    ): GlobalSearchEpochBounds {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val from = today.minusDays((daysInclusive - 1).toLong())
        return GlobalSearchEpochBounds(
            startEpochMillisInclusive = civilDateStartEpochMillis(from, zoneId),
            endEpochMillisExclusive = civilDateEndEpochMillisExclusive(today, zoneId),
        )
    }
}

enum class GlobalSearchCustomRangeValidation {
    Valid,
    Reversed,
    NotCustom,
    ;

    val canApply: Boolean
        get() = this == Valid
}

/** Material date pickers surface UTC-midnight millis for a civil date, not a zone-local instant. */
@Suppress("MaxLineLength")
fun civilDateFromPickerUtcMillis(utcTimeMillis: Long): LocalDate = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()

fun civilDateStartEpochMillis(
    date: LocalDate,
    zoneId: ZoneId,
): Long = date.atStartOfDay(zoneId).toInstant().toEpochMilli()

fun civilDateEndEpochMillisExclusive(
    date: LocalDate,
    zoneId: ZoneId,
): Long =
    date
        .plusDays(1)
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()

fun pickerUtcMillisForCivilDate(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

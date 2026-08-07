package dev.ipf.whitenoise.android.ui.group

import dev.ipf.whitenoise.android.R

internal const val DISAPPEARING_SECONDS_PER_MONTH = 2_592_000L
internal const val DISAPPEARING_SECONDS_PER_YEAR = 31_536_000L

private const val DISAPPEARING_SECONDS_PER_WEEK = 604_800L
private const val DISAPPEARING_SECONDS_PER_DAY = 86_400L
private const val DISAPPEARING_SECONDS_PER_HOUR = 3_600L
private const val DISAPPEARING_SECONDS_PER_MINUTE = 60L
private const val DISAPPEARING_MAX_PICKER_SECONDS = 59L

// Preset retention windows, longest → shortest.
internal val disappearingPresetSecs =
    listOf(
        0L,
        7_776_000L,
        2_419_200L,
        604_800L,
        86_400L,
        28_800L,
        3_600L,
        300L,
        30L,
    )

internal data class DisappearingUnit(
    val labelRes: Int,
    val seconds: Long,
    val max: Int,
)

internal val disappearingCustomUnits =
    listOf(
        DisappearingUnit(R.string.disappearing_unit_seconds, 1L, 59),
        DisappearingUnit(R.string.disappearing_unit_minutes, DISAPPEARING_SECONDS_PER_MINUTE, 59),
        DisappearingUnit(R.string.disappearing_unit_hours, DISAPPEARING_SECONDS_PER_HOUR, 23),
        DisappearingUnit(R.string.disappearing_unit_days, DISAPPEARING_SECONDS_PER_DAY, 6),
        DisappearingUnit(R.string.disappearing_unit_weeks, DISAPPEARING_SECONDS_PER_WEEK, 4),
        DisappearingUnit(R.string.disappearing_unit_months, DISAPPEARING_SECONDS_PER_MONTH, 12),
        DisappearingUnit(R.string.disappearing_unit_years, DISAPPEARING_SECONDS_PER_YEAR, 10),
    )

internal data class DisappearingCustomPickerState(
    val unitIndex: Int,
    val value: Int,
)

internal sealed class DisappearingLabelSpec {
    data object Off : DisappearingLabelSpec()

    data class Preset(
        val resId: Int,
    ) : DisappearingLabelSpec()

    data class Seconds(
        val count: Long,
    ) : DisappearingLabelSpec()

    data class Minutes(
        val count: Long,
    ) : DisappearingLabelSpec()

    data class Hours(
        val count: Long,
    ) : DisappearingLabelSpec()

    data class Days(
        val count: Long,
    ) : DisappearingLabelSpec()

    data class Weeks(
        val count: Long,
    ) : DisappearingLabelSpec()

    data class Months(
        val count: Long,
    ) : DisappearingLabelSpec()

    data class Years(
        val count: Long,
    ) : DisappearingLabelSpec()
}

private val disappearingPresetLabelRes =
    mapOf(
        0L to R.string.disappearing_off,
        7_776_000L to R.string.disappearing_90_days,
        2_419_200L to R.string.disappearing_4_weeks,
        604_800L to R.string.disappearing_1_week,
        86_400L to R.string.disappearing_1_day,
        28_800L to R.string.disappearing_8_hours,
        3_600L to R.string.disappearing_1_hour,
        300L to R.string.disappearing_5_minutes,
        30L to R.string.disappearing_30_seconds,
    )

internal fun disappearingCustomSeconds(
    value: Int,
    unitIndex: Int,
): Long {
    val unit = disappearingCustomUnits[unitIndex]
    return disappearingCustomSecondsFromParts(value.toLong(), unit.seconds)
}

internal fun disappearingCustomSecondsFromParts(
    value: Long,
    secondsPerUnit: Long,
): Long = Math.multiplyExact(value, secondsPerUnit)

internal fun clampDisappearingCustomValue(
    value: Int,
    unitIndex: Int,
): Int = value.coerceIn(1, disappearingCustomUnits[unitIndex].max)

internal fun disappearingCustomPickerStateForSeconds(secs: Long): DisappearingCustomPickerState {
    require(secs > 0)
    return pickCustomPickerState(secs, respectMax = true)
        ?: pickCustomPickerState(secs, respectMax = false)
        ?: DisappearingCustomPickerState(0, secs.coerceIn(1L, DISAPPEARING_MAX_PICKER_SECONDS).toInt())
}

private fun disappearingPickerCountToInt(count: Long): Int? = count.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()

private fun pickCustomPickerState(
    secs: Long,
    respectMax: Boolean,
): DisappearingCustomPickerState? =
    disappearingCustomUnits.indices.reversed().firstNotNullOfOrNull { index ->
        val unit = disappearingCustomUnits[index]
        val count = secs / unit.seconds
        val fitsUnit =
            secs % unit.seconds == 0L &&
                count >= 1L &&
                (!respectMax || count <= unit.max.toLong())
        val pickerValue = disappearingPickerCountToInt(count) ?: return@firstNotNullOfOrNull null
        if (fitsUnit) DisappearingCustomPickerState(index, pickerValue) else null
    }

private fun disappearingLabelCountFitsPlural(
    secs: Long,
    secondsPerUnit: Long,
): Boolean =
    secs > 0L &&
        secs % secondsPerUnit == 0L &&
        secs / secondsPerUnit <= Int.MAX_VALUE.toLong()

internal fun disappearingLabelSpec(secs: Long): DisappearingLabelSpec =
    when {
        secs == 0L -> DisappearingLabelSpec.Off
        disappearingPresetLabelRes[secs] != null ->
            DisappearingLabelSpec.Preset(requireNotNull(disappearingPresetLabelRes[secs]))
        disappearingLabelCountFitsPlural(secs, DISAPPEARING_SECONDS_PER_YEAR) ->
            DisappearingLabelSpec.Years(secs / DISAPPEARING_SECONDS_PER_YEAR)
        disappearingLabelCountFitsPlural(secs, DISAPPEARING_SECONDS_PER_MONTH) ->
            DisappearingLabelSpec.Months(secs / DISAPPEARING_SECONDS_PER_MONTH)
        disappearingLabelCountFitsPlural(secs, DISAPPEARING_SECONDS_PER_WEEK) ->
            DisappearingLabelSpec.Weeks(secs / DISAPPEARING_SECONDS_PER_WEEK)
        secs % DISAPPEARING_SECONDS_PER_DAY == 0L ->
            DisappearingLabelSpec.Days(secs / DISAPPEARING_SECONDS_PER_DAY)
        secs % DISAPPEARING_SECONDS_PER_HOUR == 0L ->
            DisappearingLabelSpec.Hours(secs / DISAPPEARING_SECONDS_PER_HOUR)
        secs % DISAPPEARING_SECONDS_PER_MINUTE == 0L ->
            DisappearingLabelSpec.Minutes(secs / DISAPPEARING_SECONDS_PER_MINUTE)
        else -> DisappearingLabelSpec.Seconds(secs)
    }

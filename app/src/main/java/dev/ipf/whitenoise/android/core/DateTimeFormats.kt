package dev.ipf.whitenoise.android.core

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.LocaleManagerCompat
import java.util.IllformedLocaleException
import java.util.Locale

/**
 * The user's clock choice, independent of the app language (#3602).
 * [Automatic] follows Android's 12/24-hour switch when it is set, otherwise the regional default.
 */
enum class TimeFormatPreference(
    val preferenceValue: String,
) {
    Automatic("automatic"),
    TwelveHour("12_hour"),
    TwentyFourHour("24_hour"),
    ;

    /** The clock system to render, or null for the formatting locale's default. */
    fun use24Hour(system24Hour: Boolean?): Boolean? =
        when (this) {
            Automatic -> system24Hour
            TwelveHour -> false
            TwentyFourHour -> true
        }

    companion object {
        fun fromPreference(value: String?) = entries.find { it.preferenceValue == value } ?: Automatic
    }
}

/**
 * The user's numeric date order, independent of the app language (#3602).
 * [Automatic] keeps the regional date formats. An explicit choice renders every absolute
 * day as digits in that order; weekday and month headings stay localized.
 */
enum class DateFormatPreference(
    val preferenceValue: String,
    internal val pattern: String?,
    internal val withoutYearPattern: String?,
) {
    Automatic("automatic", null, null),
    YearMonthDay("yyyy_mm_dd", "yyyy-MM-dd", "MM-dd"),
    DayMonthYearSlash("dd_mm_yyyy_slash", "dd/MM/yyyy", "dd/MM"),
    DayMonthYearDot("dd_mm_yyyy_dot", "dd.MM.yyyy", "dd.MM."),
    MonthDayYear("mm_dd_yyyy", "MM/dd/yyyy", "MM/dd"),
    ;

    companion object {
        fun fromPreference(value: String?) = entries.find { it.preferenceValue == value } ?: Automatic
    }
}

/**
 * Resolved display choices handed to [IdentityFormatter]; a null [use24Hour] keeps the locale default.
 * [region] is the phone region [IdentityFormatter] formats in; it is carried here so labels
 * remembered against this value refresh when the phone region changes.
 */
@Immutable
data class DateTimeDisplay(
    val use24Hour: Boolean? = null,
    val dateFormat: DateFormatPreference = DateFormatPreference.Automatic,
    val region: String? = null,
) {
    companion object {
        val LocaleDefault = DateTimeDisplay()
    }
}

/**
 * The single owner of date/time display inputs. The app state loads and persists the two
 * preferences; [refreshSystem] reads Android's 12/24-hour switch and the phone region. Snapshot
 * state, so composition reading [display] recomposes on a change; plain text builders read it too.
 */
object DateTimeFormatState {
    var timeFormat by mutableStateOf(TimeFormatPreference.Automatic)
    var dateFormat by mutableStateOf(DateFormatPreference.Automatic)

    /** Android's explicit 12/24-hour switch; null when unset, so the regional default applies. */
    var system24Hour by mutableStateOf<Boolean?>(null)

    /** The phone's region (e.g. "GB"), applied to an app language that names none. */
    var deviceRegion by mutableStateOf<String?>(null)

    val display: DateTimeDisplay
        get() = DateTimeDisplay(timeFormat.use24Hour(system24Hour), dateFormat, deviceRegion)

    /** Reads the platform inputs; call at start-up, on configuration changes and on resume. */
    fun refreshSystem(context: Context) {
        deviceRegion = LocaleManagerCompat.getSystemLocales(context)[0]?.country?.takeIf { it.isNotEmpty() }
        system24Hour =
            when (Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)) {
                "24" -> true
                "12" -> false
                else -> null
            }
    }

    /**
     * The locale dates and times are formatted in: the app language with the phone region when
     * the language has none, so English on a British phone reads `06/11/2026, 15:04` (#3602).
     */
    fun formattingLocale(locale: Locale): Locale = regionalLocale(locale, deviceRegion)

    internal fun regionalLocale(
        locale: Locale,
        region: String?,
    ): Locale {
        if (region.isNullOrEmpty() || locale.country.isNotEmpty() || locale.language.isEmpty()) return locale
        return try {
            Locale
                .Builder()
                .setLocale(locale)
                .setRegion(region)
                .build()
        } catch (_: IllformedLocaleException) {
            locale
        }
    }
}

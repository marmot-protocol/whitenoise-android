package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.DateFormatPreference
import dev.ipf.whitenoise.android.core.DateTimeDisplay
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.TimeFormatPreference
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDialogChoiceRow
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Example moment for every date and time label in Settings: a day past 12 shows the field order
 * unambiguously and an afternoon hour shows the clock system.
 */
private val SampleDateTime = LocalDateTime.of(2026, 12, 31, 15, 4)

/** [display] applied to the example moment, as the compact date-and-time labels render it. */
internal fun dateTimeFormatSample(
    locale: Locale,
    display: DateTimeDisplay,
    dateStyle: FormatStyle? = FormatStyle.SHORT,
    timeStyle: FormatStyle? = FormatStyle.SHORT,
    zone: ZoneId = ZoneId.systemDefault(),
) = IdentityFormatter.dateTime(SampleDateTime.atZone(zone).toInstant(), locale, zone, display, dateStyle, timeStyle)

/**
 * Time and date choices on one screen, each labelled with the example it produces. Choices apply
 * immediately; Reset returns both to Automatic (app language plus phone region).
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun DateTimeFormatDialog(
    timeFormat: TimeFormatPreference,
    dateFormat: DateFormatPreference,
    system24Hour: Boolean?,
    onSelectTime: (TimeFormatPreference) -> Unit,
    onSelectDate: (DateFormatPreference) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val automatic = timeFormat == TimeFormatPreference.Automatic && dateFormat == DateFormatPreference.Automatic
    WhiteNoiseAlertDialog(
        modifier = Modifier.testTag("date_time_format.dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.date_time_format)) },
        text = {
            Column(
                Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TimeFormatChoices(locale, timeFormat, system24Hour, onSelectTime)
                DateFormatChoices(locale, dateFormat, onSelectDate)
                Text(
                    stringResource(R.string.date_time_format_help),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            TextButton(
                enabled = !automatic,
                onClick = {
                    onSelectTime(TimeFormatPreference.Automatic)
                    onSelectDate(DateFormatPreference.Automatic)
                },
                modifier = Modifier.testTag("date_time_format.reset"),
            ) {
                Text(stringResource(R.string.reset))
            }
        },
    )
}

@Composable
@Suppress("FunctionNaming")
private fun TimeFormatChoices(
    locale: Locale,
    selected: TimeFormatPreference,
    system24Hour: Boolean?,
    onSelect: (TimeFormatPreference) -> Unit,
) {
    DateTimeGroupLabel(stringResource(R.string.date_time_format_time))
    Column(Modifier.selectableGroup()) {
        TimeFormatPreference.entries.forEach { format ->
            val display = DateTimeDisplay(use24Hour = format.use24Hour(system24Hour))
            WhiteNoiseDialogChoiceRow(
                title = stringResource(format.labelRes),
                subtitle = dateTimeFormatSample(locale, display, dateStyle = null),
                selected = selected == format,
                highlightSelected = false,
                onClick = { if (selected != format) onSelect(format) },
                modifier = Modifier.testTag("date_time_format.time.${format.preferenceValue}"),
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun DateFormatChoices(
    locale: Locale,
    selected: DateFormatPreference,
    onSelect: (DateFormatPreference) -> Unit,
) {
    DateTimeGroupLabel(stringResource(R.string.date_time_format_date))
    Column(Modifier.selectableGroup()) {
        DateFormatPreference.entries.forEach { format ->
            val sample = dateTimeFormatSample(locale, DateTimeDisplay(dateFormat = format), timeStyle = null)
            val isAutomatic = format == DateFormatPreference.Automatic
            WhiteNoiseDialogChoiceRow(
                // Explicit orders are named by their own example; only Automatic needs words.
                title = if (isAutomatic) stringResource(R.string.date_time_format_automatic) else sample,
                subtitle = sample.takeIf { isAutomatic },
                selected = selected == format,
                highlightSelected = false,
                onClick = { if (selected != format) onSelect(format) },
                modifier = Modifier.testTag("date_time_format.date.${format.preferenceValue}"),
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun DateTimeGroupLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

internal val TimeFormatPreference.labelRes: Int
    get() =
        when (this) {
            TimeFormatPreference.Automatic -> R.string.date_time_format_automatic
            TimeFormatPreference.TwelveHour -> R.string.time_format_12_hour
            TimeFormatPreference.TwentyFourHour -> R.string.time_format_24_hour
        }

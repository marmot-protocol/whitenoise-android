package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerFormatter
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.search.civilDateFromPickerUtcMillis
import dev.ipf.whitenoise.android.search.pickerUtcMillisForCivilDate
import java.time.LocalDate

/**
 * The app's one calendar for choosing a civil date, shared by search's date filter and Shared Media's
 * Jump to date. [availableDays] limits the choice to those days and years; null allows any date. A
 * choice that the current [availableDays] no longer contains cannot be confirmed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun CivilDatePickerDialog(
    title: String,
    initialDate: LocalDate?,
    confirmLabel: String,
    confirmTag: String,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
    availableDays: Set<LocalDate>? = null,
) {
    var chosen by remember { mutableStateOf(initialDate?.takeIf { availableDays == null || it in availableDays }) }
    val days by rememberUpdatedState(availableDays)
    val allows = { utcMillis: Long -> days?.contains(civilDateFromPickerUtcMillis(utcMillis)) != false }
    val formatter = remember { DatePickerDefaults.dateFormatter() }
    val yearRange =
        availableDays?.takeIf { it.isNotEmpty() }?.let { loaded -> loaded.minOf { it.year }..loaded.maxOf { it.year } }
            ?: DatePickerDefaults.YearRange
    AnimatedDatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val choice = chosen?.takeIf { day -> days?.contains(day) != false }
            TextButton(
                enabled = choice != null && acceptsInput,
                modifier = Modifier.testTag(confirmTag),
                onClick = { choice?.let { close { onConfirm(it) } } },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        // Material caps the dialog's height; scrolling keeps the whole month grid readable at large text sizes.
        Column(Modifier.fadingVerticalScroll(rememberScrollState())) {
            // Material keeps a picker's year range for its lifetime, so a newly loaded year starts a new picker.
            key(yearRange) {
                val state =
                    rememberDatePickerState(
                        initialSelectedDateMillis = chosen?.let(::pickerUtcMillisForCivilDate),
                        yearRange = yearRange,
                        selectableDates =
                            object : SelectableDates {
                                override fun isSelectableDate(utcTimeMillis: Long) = allows(utcTimeMillis)

                                override fun isSelectableYear(year: Int) = days?.any { it.year == year } != false
                            },
                    )
                LaunchedEffect(state) {
                    snapshotFlow { state.selectedDateMillis }
                        .collect { chosen = it?.let(::civilDateFromPickerUtcMillis) }
                }
                DatePicker(
                    state = state,
                    dateFormatter = formatter,
                    title = {
                        Text(
                            text = title,
                            modifier =
                                Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp).semantics { heading() },
                        )
                    },
                    headline = { CivilDateHeadline(state, formatter) },
                )
            }
        }
    }
}

/** Material's headline stays on one line and clips at large text sizes; a chosen date wraps here instead. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
private fun CivilDateHeadline(
    state: DatePickerState,
    formatter: DatePickerFormatter,
) {
    val locale = LocalConfiguration.current.locales[0]
    val padding = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp)
    val millis = state.selectedDateMillis
    val date = formatter.formatDate(millis, locale)
    if (date == null) {
        DatePickerDefaults.DatePickerHeadline(millis, state.displayMode, formatter, padding)
        return
    }
    val spoken = formatter.formatDate(millis, locale, forContentDescription = true) ?: date
    Text(
        text = date,
        modifier =
            padding.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = spoken
            },
    )
}

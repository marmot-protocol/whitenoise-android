package dev.ipf.whitenoise.android.ui.search

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal const val GLOBAL_SEARCH_DATE_FILTER_SECTION_TAG = "global-search-date-filter-section"

@Suppress("FunctionNaming")
@Composable
internal fun GlobalSearchDateFilterSection(
    selection: GlobalSearchDateFilterSelection,
    onOpenDateDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onOpenDateDialog,
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(GLOBAL_SEARCH_DATE_FILTER_SECTION_TAG),
    ) {
        Text(globalSearchDateFilterLabel(selection))
    }
}

@Composable
internal fun globalSearchDateFilterLabel(selection: GlobalSearchDateFilterSelection): String =
    when (selection) {
        GlobalSearchDateFilterSelection.AnyTime -> stringResource(R.string.global_search_date_any_time)
        GlobalSearchDateFilterSelection.Today -> stringResource(R.string.global_search_date_today)
        GlobalSearchDateFilterSelection.Last7Days -> stringResource(R.string.global_search_date_last_7_days)
        GlobalSearchDateFilterSelection.Last30Days -> stringResource(R.string.global_search_date_last_30_days)
        is GlobalSearchDateFilterSelection.Custom ->
            globalSearchCustomDateRangeLabel(selection.from, selection.to)
    }

@Composable
internal fun globalSearchCustomDateRangeLabel(
    from: LocalDate,
    to: LocalDate,
): String {
    val locale = LocalConfiguration.current.locales[0]
    val dateFormatter =
        remember(locale) {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        }
    val fromLabel = remember(from, locale) { from.format(dateFormatter) }
    val toLabel = remember(to, locale) { to.format(dateFormatter) }
    return stringResource(
        R.string.global_search_date_custom_summary,
        fromLabel,
        toLabel,
    )
}

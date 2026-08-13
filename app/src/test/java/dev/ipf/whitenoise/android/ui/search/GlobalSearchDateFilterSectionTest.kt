package dev.ipf.whitenoise.android.ui.search

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class GlobalSearchDateFilterSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun customSelectionShowsInclusiveRangeOnSectionButton() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 3)
        val custom =
            GlobalSearchDateFilterSelection.Custom(
                from = from,
                to = to,
                zoneId = ZoneId.of("UTC"),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    GlobalSearchDateFilterSection(
                        selection = custom,
                        onOpenDateDialog = {},
                    )
                }
            }
        }

        val formatter =
            DateTimeFormatter
                .ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.US)
        val expectedLabel =
            context.getString(
                R.string.global_search_date_custom_summary,
                from.format(formatter),
                to.format(formatter),
            )
        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_FILTER_SECTION_TAG).assertExists()
        composeRule.onNodeWithText(expectedLabel).assertExists()
    }
}

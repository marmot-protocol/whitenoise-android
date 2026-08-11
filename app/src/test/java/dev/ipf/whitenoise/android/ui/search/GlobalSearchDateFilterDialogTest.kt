package dev.ipf.whitenoise.android.ui.search

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class GlobalSearchDateFilterDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun dialogShowsEveryRequestedPresetChoice() {
        render()

        listOf(
            R.string.global_search_date_any_time,
            R.string.global_search_date_today,
            R.string.global_search_date_last_7_days,
            R.string.global_search_date_last_30_days,
            R.string.global_search_date_custom,
        ).forEach { label ->
            composeRule.onNodeWithText(context.getString(label)).assertExists()
        }
    }

    @Test
    fun presetSelectionExposesSelectedStateForAccessibility() {
        render(selection = GlobalSearchDateFilterSelection.Today)

        composeRule
            .onNodeWithTag(globalSearchDatePresetTag(GlobalSearchDateFilterSelection.Today))
            .assertIsSelected()
    }

    @Test
    fun customPresetWalksFromAndToPickersUsingOneZoneSnapshot() {
        val snapshotZone = ZoneId.of("America/Los_Angeles")
        var zoneSnapshots = 0
        var customStage by mutableStateOf<GlobalSearchDateCustomStage?>(null)
        var applied: GlobalSearchDateFilterSelection? = null
        renderInteractiveDateFilter(
            customStage = { customStage },
            zoneId = {
                zoneSnapshots += 1
                snapshotZone
            },
            onCustomStageChange = { customStage = it },
            onApply = { applied = it },
        )

        val customTag =
            globalSearchDatePresetTag(
                GlobalSearchDateFilterSelection.Custom(
                    from = LocalDate.EPOCH,
                    to = LocalDate.EPOCH,
                    zoneId = ZoneId.of("UTC"),
                ),
            )
        composeRule.onNodeWithTag(customTag).performClick()
        composeRule.runOnIdle {
            assertEquals(GlobalSearchDateCustomStage.FromDate(snapshotZone), customStage)
        }
        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_FROM_CONFIRM_TAG).performClick()
        composeRule.runOnIdle {
            assertEquals(
                GlobalSearchDateCustomStage.ToDate(
                    from = LocalDate.of(2026, 8, 11),
                    snapshottedZoneId = snapshotZone,
                ),
                customStage,
            )
        }
        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_TO_CONFIRM_TAG).performClick()
        composeRule.runOnIdle {
            assertEquals(
                GlobalSearchDateCustomStage.Review(
                    from = LocalDate.of(2026, 8, 11),
                    to = LocalDate.of(2026, 8, 11),
                    snapshottedZoneId = snapshotZone,
                ),
                customStage,
            )
        }
        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_APPLY_TAG).performClick()

        composeRule.runOnIdle {
            assertEquals(1, zoneSnapshots)
            assertEquals(
                GlobalSearchDateFilterSelection.Custom(
                    from = LocalDate.of(2026, 8, 11),
                    to = LocalDate.of(2026, 8, 11),
                    zoneId = snapshotZone,
                ),
                applied,
            )
        }
    }

    @Test
    fun reversedCustomRangeShowsLocalizedCorrectionAndBlocksApply() {
        var applied: GlobalSearchDateFilterSelection? = null
        render(
            customStage =
                GlobalSearchDateCustomStage.Review(
                    from = LocalDate.of(2026, 8, 12),
                    to = LocalDate.of(2026, 8, 10),
                    snapshottedZoneId = ZoneId.of("UTC"),
                ),
            onApply = { applied = it },
        )

        composeRule
            .onNodeWithText(context.getString(R.string.global_search_date_custom_reversed_error))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_APPLY_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_APPLY_TAG).performClick()

        composeRule.runOnIdle { assertNull(applied) }
    }

    @Test
    fun validCustomRangeAppliesInclusiveSelectionWithSnapshottedZone() {
        var applied: GlobalSearchDateFilterSelection? = null
        val from = LocalDate.of(2026, 8, 1)
        val to = LocalDate.of(2026, 8, 3)
        val zone = ZoneId.of("America/Los_Angeles")
        render(
            customStage =
                GlobalSearchDateCustomStage.Review(
                    from = from,
                    to = to,
                    snapshottedZoneId = zone,
                ),
            onApply = { applied = it },
        )

        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_APPLY_TAG).performClick()

        composeRule.runOnIdle {
            assertEquals(
                GlobalSearchDateFilterSelection.Custom(from = from, to = to, zoneId = zone),
                applied,
            )
        }
    }

    @Test
    fun customRangeReviewShowsLocalizedDatesNotIsoStrings() {
        val from = LocalDate.of(2026, 8, 1)
        val to = LocalDate.of(2026, 8, 3)
        val formatter =
            DateTimeFormatter
                .ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.US)
        val expectedSummary =
            context.getString(
                R.string.global_search_date_custom_summary,
                from.format(formatter),
                to.format(formatter),
            )

        render(
            customStage =
                GlobalSearchDateCustomStage.Review(
                    from = from,
                    to = to,
                    snapshottedZoneId = ZoneId.of("UTC"),
                ),
        )

        composeRule.onNodeWithText(expectedSummary).assertIsDisplayed()
        composeRule.onNodeWithText(from.toString()).assertDoesNotExist()
    }

    private fun renderInteractiveDateFilter(
        customStage: () -> GlobalSearchDateCustomStage?,
        zoneId: () -> ZoneId,
        onCustomStageChange: (GlobalSearchDateCustomStage?) -> Unit,
        onApply: (GlobalSearchDateFilterSelection) -> Unit,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    GlobalSearchDateFilterDialog(
                        selection = GlobalSearchDateFilterSelection.AnyTime,
                        customStage = customStage(),
                        onDismiss = {},
                        onApply = onApply,
                        nowMillis = { FIXED_NOW_MILLIS },
                        zoneId = zoneId,
                        onCustomStageChange = onCustomStageChange,
                    )
                }
            }
        }
    }

    private fun render(
        selection: GlobalSearchDateFilterSelection = GlobalSearchDateFilterSelection.AnyTime,
        customStage: GlobalSearchDateCustomStage? = null,
        zoneId: () -> ZoneId = { ZoneId.of("UTC") },
        onApply: (GlobalSearchDateFilterSelection) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    GlobalSearchDateFilterDialog(
                        selection = selection,
                        customStage = customStage,
                        onDismiss = {},
                        onApply = onApply,
                        nowMillis = { FIXED_NOW_MILLIS },
                        zoneId = zoneId,
                        onCustomStageChange = {},
                    )
                }
            }
        }
    }

    private companion object {
        private const val FIXED_NOW_MILLIS = 1_786_462_800_000L
    }
}

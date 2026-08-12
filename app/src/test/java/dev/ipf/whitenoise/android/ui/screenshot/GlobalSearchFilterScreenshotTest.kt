package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_SEARCH_FILTER_SHEET_TAG
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchState
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTypedFilterSheet
import dev.ipf.whitenoise.android.ui.search.GLOBAL_SEARCH_CONTENT_FILTER_TAG
import dev.ipf.whitenoise.android.ui.search.GLOBAL_SEARCH_DATE_DIALOG_TAG
import dev.ipf.whitenoise.android.ui.search.GlobalSearchContentFilterChips
import dev.ipf.whitenoise.android.ui.search.GlobalSearchDateCustomStage
import dev.ipf.whitenoise.android.ui.search.GlobalSearchDateFilterDialog
import dev.ipf.whitenoise.android.ui.search.globalSearchContentChipTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class GlobalSearchFilterScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dateFilterPresetsDark() {
        renderDateDialog(selection = GlobalSearchDateFilterSelection.Last7Days)
        composeRule
            .onNodeWithTag(GLOBAL_SEARCH_DATE_DIALOG_TAG)
            .captureRoboImage("src/test/snapshots/global_search_date_filter_presets_dark.png")
    }

    @Test
    fun customRangeReversedErrorDark() {
        renderDateDialog(
            customStage =
                GlobalSearchDateCustomStage.Review(
                    from = LocalDate.of(2026, 8, 12),
                    to = LocalDate.of(2026, 8, 10),
                    snapshottedZoneId = ZoneId.of("America/New_York"),
                ),
        )
        composeRule
            .onNodeWithTag(GLOBAL_SEARCH_DATE_DIALOG_TAG)
            .captureRoboImage("src/test/snapshots/global_search_date_filter_reversed_error_dark.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun contentFilterChipsRtlLargeFontLight() {
        renderContentChips(
            selection =
                GlobalSearchContentFilterSelection(
                    selectedKinds = GlobalSearchContentKind.entries.toSet(),
                ),
            rtl = true,
            fontScale = 2f,
            darkTheme = false,
        )
        composeRule
            .onNodeWithTag(globalSearchContentChipTag(GlobalSearchContentKind.ANY_ATTACHMENT))
            .performScrollTo()
        composeRule
            .onNodeWithTag(GLOBAL_SEARCH_CONTENT_FILTER_TAG)
            .captureRoboImage("src/test/snapshots/global_search_content_filter_rtl_large_font_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun customRangeReversedErrorRtlLargeFontLight() {
        renderDateDialog(
            customStage =
                GlobalSearchDateCustomStage.Review(
                    from = LocalDate.of(2026, 8, 12),
                    to = LocalDate.of(2026, 8, 10),
                    snapshottedZoneId = ZoneId.of("America/New_York"),
                ),
            rtl = true,
            fontScale = 2f,
            darkTheme = false,
        )
        composeRule
            .onNodeWithTag(GLOBAL_SEARCH_DATE_DIALOG_TAG)
            .captureRoboImage("src/test/snapshots/global_search_date_filter_reversed_error_rtl_large_font_light.png")
    }

    @Test
    fun typedFilterSheetSelectedDark() {
        renderTypedFilterSheet(
            state =
                GlobalSearchState(
                    isOpen = true,
                    filterSheetOpen = true,
                    dateFilterSelection = GlobalSearchDateFilterSelection.Last7Days,
                    contentFilterSelection =
                        GlobalSearchContentFilterSelection(
                            selectedKinds =
                                setOf(
                                    GlobalSearchContentKind.TEXT,
                                    GlobalSearchContentKind.IMAGES_VIDEO,
                                ),
                        ),
                ),
        )
        composeRule
            .onNodeWithTag(CHAT_LIST_SEARCH_FILTER_SHEET_TAG)
            .performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(CHAT_LIST_SEARCH_FILTER_SHEET_TAG)
            .captureRoboImage("src/test/snapshots/global_search_typed_filter_sheet_selected_dark.png")
    }

    @Test
    fun contentFilterChipsSelectedDark() {
        renderContentChips(
            selection =
                GlobalSearchContentFilterSelection(
                    selectedKinds =
                        setOf(
                            GlobalSearchContentKind.TEXT,
                            GlobalSearchContentKind.IMAGES_VIDEO,
                            GlobalSearchContentKind.ANY_ATTACHMENT,
                        ),
                ),
        )
        composeRule
            .onNodeWithTag(GLOBAL_SEARCH_CONTENT_FILTER_TAG)
            .captureRoboImage("src/test/snapshots/global_search_content_filter_selected_dark.png")
    }

    private fun renderDateDialog(
        selection: GlobalSearchDateFilterSelection = GlobalSearchDateFilterSelection.AnyTime,
        customStage: GlobalSearchDateCustomStage? = null,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        darkTheme: Boolean = true,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                        ) {
                            GlobalSearchDateFilterDialog(
                                selection = selection,
                                customStage = customStage,
                                onDismiss = {},
                                onApply = {},
                                onCustomStageChange = {},
                                nowMillis = { FIXED_NOW_MILLIS },
                                zoneId = { ZoneId.of("America/New_York") },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun renderTypedFilterSheet(
        state: GlobalSearchState,
        darkTheme: Boolean = true,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        GlobalSearchTypedFilterSheet(
                            state = state,
                            onStateChange = { _ -> },
                            nowMillis = { FIXED_NOW_MILLIS },
                            zoneId = { ZoneId.of("America/New_York") },
                        )
                    }
                }
            }
        }
    }

    private fun renderContentChips(
        selection: GlobalSearchContentFilterSelection,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        darkTheme: Boolean = true,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            GlobalSearchContentFilterChips(
                                selection = selection,
                                onSelectionChange = {},
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        private const val FIXED_NOW_MILLIS = 1_786_462_800_000L
    }
}

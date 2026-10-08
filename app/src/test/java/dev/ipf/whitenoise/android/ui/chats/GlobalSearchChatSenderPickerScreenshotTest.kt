package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captures the real multi-select sheet with pending and unavailable identities. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GlobalSearchChatSenderPickerScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun pendingLight() = capture("pending_light", pending = true)

    @Test fun unavailableDark() = capture("unavailable_dark", dark = true)

    @Test fun unavailableAmoled() = capture("unavailable_amoled", dark = true, amoled = true)

    @Test
    @Config(sdk = [36], qualifiers = "ar-w320dp-h780dp-mdpi")
    fun unavailableLargeRtl() = capture("unavailable_large_rtl", largeRtl = true)

    private fun capture(
        name: String,
        pending: Boolean = false,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        val state =
            GlobalSearchState(
                isOpen = true,
                openFilterCategory = GlobalSearchFilterCategory.Sender,
                senderFilters = setOf(GlobalSearchSenderFilter("selected", if (largeRtl) "ليلى" else "Alice")),
            )
        val options =
            GlobalSearchFilterOptions(
                senders = listOf(WhiteNoisePickerItem("self", if (largeRtl) "أنت" else "You")),
                membersPending = pending,
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface { GlobalSearchFilterPicker(state, options, {}) }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sheet.surface").captureRoboImage(
            "src/test/snapshots/global_search_picker_$name.png",
        )
    }
}

package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.ChatFolderSortOrder
import dev.ipf.whitenoise.android.ui.settings.FolderSortDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The actual folder-order picker across themes and a large RTL layout. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class FolderSortDialogScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Default light surfaces. */
    @Test fun light() = capture("folder_sort_light", dark = false)

    /** Dark text and selected-state contrast. */
    @Test fun dark() = capture("folder_sort_dark", dark = true)

    /** AMOLED retains the dialog's outline and visible radio choices. */
    @Test fun amoled() = capture("folder_sort_amoled", dark = true, amoled = true)

    /** Long choice labels stay accessible when the dialog must scroll at 200 percent text. */
    @Test fun largeRtl() = capture("folder_sort_rtl_large", dark = true, rtl = true)

    /** Captures production dialog rendering with deterministic selection and no preference mutation. */
    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    FolderSortDialog(ChatFolderSortOrder.NAME, {}, {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}

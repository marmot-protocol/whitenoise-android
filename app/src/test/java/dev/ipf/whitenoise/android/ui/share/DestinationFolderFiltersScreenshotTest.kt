package dev.ipf.whitenoise.android.ui.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Share and forward destination filters reuse the borderless folder pills in every theme (#4225). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h120dp-mdpi")
class DestinationFolderFiltersScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Screenshot: light theme with a folder selected. */
    @Test fun light() = capture("destination_folder_filters_light")

    /** Screenshot: dark theme with a folder selected. */
    @Test fun dark() = capture("destination_folder_filters_dark", dark = true)

    /** Screenshot: AMOLED with a folder selected; no pill carries an outline. */
    @Test fun amoled() = capture("destination_folder_filters_amoled", dark = true, amoled = true)

    /** Screenshot: AMOLED with All selected. */
    @Test fun amoledAll() = capture("destination_folder_filters_amoled_all", dark = true, amoled = true, all = true)

    /** Renders the filter strip with one explicit recipient and two folders. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        all: Boolean = false,
    ) {
        val rows =
            listOf(
                ChatFolder("work", "Work", "", 0, null) to listOf("a"),
                ChatFolder("family", "Family", "", 1, null) to listOf("b"),
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                val state = remember { DestinationFolderFilterState().also { if (!all) it.selectFolder("work") } }
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxWidth().padding(vertical = Dimens.spaceMd)) {
                        DestinationFolderFilters(rows, state, listOf("a"), {})
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}

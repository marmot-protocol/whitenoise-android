package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.group.GroupDetailsHeader
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baseline for the group-details header leaf (no picture URL, so the
 * seeded palette avatar keeps the shot deterministic).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class GroupDetailsHeaderScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun groupDetailsHeaderLight() {
        render(darkTheme = false)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/group_details_header_light.png")
    }

    @Test
    fun groupDetailsHeaderDark() {
        render(darkTheme = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/group_details_header_dark.png")
    }

    private fun render(darkTheme: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                    GroupDetailsHeader(
                        title = "Weekend hikers",
                        subtitle = "8 members",
                        description = "Trail plans and photos.",
                        seed = "stable-screenshot-seed",
                        pictureUrl = null,
                        archived = false,
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "group-details-header"
    }
}

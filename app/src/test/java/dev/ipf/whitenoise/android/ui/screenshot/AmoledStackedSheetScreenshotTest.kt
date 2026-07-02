package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.theme.amoledModalSheetSurface
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression guard for issue #801. In AMOLED, a bottom sheet (profile / QR /
 * npub) opens over the Settings screen's bordered section cards. The sheet must
 * occlude the underneath card's grey stroke and rounded corner where the two
 * overlap, and paint its own clean stroke — not let the card beneath bleed
 * through the sheet's rounded top corners.
 *
 * The two surfaces are stacked in a single window here (a real
 * `ModalBottomSheet` renders in its own window, which Roborazzi can't easily
 * compose against a host screen) so the top-edge overlap is captured as a
 * stable pixel baseline.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AmoledStackedSheetScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun amoledSheetOccludesUnderneathSurfaceStroke() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                StackedSheetOverSettingsCard()
            }
        }
        composeRule
            .onRoot()
            .captureRoboImage("src/test/snapshots/amoled_stacked_sheet_over_settings.png")
    }
}

@Composable
private fun StackedSheetOverSettingsCard() {
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Box(modifier = Modifier.size(width = 200.dp, height = 200.dp).background(Color.Black)) {
        // Underneath: a Settings section card with the AMOLED grey stroke and
        // rounded corners, positioned so its lower half is overlapped by the
        // sheet's top edge.
        Surface(
            color = Color.Black,
            shape = RoundedCornerShape(12.dp),
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(horizontal = 12.dp)
                    .amoledSurfaceBorder(RoundedCornerShape(12.dp)),
        ) {
            Text("underneath", modifier = Modifier.padding(8.dp), color = Color.White)
        }
        // On top: the bottom-sheet surface pinned to the lower half, using the
        // shared AMOLED sheet modifier. Its opaque fill + own stroke must fully
        // cover the card beneath in the overlap region.
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(110.dp)
                    .amoledModalSheetSurface(sheetShape),
        ) {
            Text("bottom sheet", modifier = Modifier.padding(16.dp), color = Color.White)
        }
    }
}

package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pixel evidence for the visibly distinct purple PR Preview launcher mark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w128dp-h128dp-mdpi")
class PreviewLauncherScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun previewLauncherMark() {
        composeRule.setContent {
            Box(
                modifier =
                    Modifier
                        .size(128.dp)
                        .background(colorResource(R.color.preview_launcher_background)),
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onRoot().captureRoboImage("src/test/snapshots/preview_launcher_mark.png")
    }
}

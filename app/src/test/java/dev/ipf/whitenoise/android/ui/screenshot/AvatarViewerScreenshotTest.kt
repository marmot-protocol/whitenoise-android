package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.common.VIEWER_MIN_SCALE
import dev.ipf.whitenoise.android.ui.profile.AvatarViewerFrame
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline for the full-screen avatar viewer's default loading frame: permanent
 * black chrome with close and overflow menu, centered spinner — no network or
 * image decode.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class AvatarViewerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun avatarViewerDefaultFrame() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                AvatarViewerFrame(
                    scale = VIEWER_MIN_SCALE,
                    dismissThresholdPx = 96f,
                    onDismiss = {},
                    menuOpen = false,
                    onMenuOpenChange = {},
                    saveEnabled = false,
                    editActionLabel = null,
                    onEditPicture = null,
                    onSave = {},
                    snackbarHostState = remember { SnackbarHostState() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/avatar_viewer_default_frame.png")
    }
}

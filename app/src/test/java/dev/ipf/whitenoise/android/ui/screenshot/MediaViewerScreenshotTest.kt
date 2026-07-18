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
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerFrame
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline for the full-screen media viewer's default loading frame: permanent
 * black chrome with close/save/share controls, page counter, caption scrim, and
 * a centered spinner — no live controller or attachment decode.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaViewerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mediaViewerDefaultFrame() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                MediaViewerFrame(
                    pageIndex = 0,
                    pageCount = 3,
                    senderLabel = "Alex",
                    recordedAtLabel = "Jul 16, 2026, 3:45 PM",
                    onDismiss = {},
                    onSave = {},
                    onShare = {},
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
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_default_frame.png")
    }
}

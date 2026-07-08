package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.media.PendingFilePill
import dev.ipf.whitenoise.android.ui.conversation.media.VoiceWaveform
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baseline for media-bubble chrome that renders without a live
 * conversation controller: the pending file pill and the voice waveform.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaBubbleChromeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mediaBubbleChromeLight() {
        render(darkTheme = false)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/media_bubble_chrome_light.png")
    }

    @Test
    fun mediaBubbleChromeDark() {
        render(darkTheme = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/media_bubble_chrome_dark.png")
    }

    private fun render(darkTheme: Boolean) {
        val bars = FloatArray(24) { i -> 0.2f + 0.8f * ((i * 7) % 10) / 10f }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface {
                    Column(modifier = Modifier.width(360.dp).padding(8.dp).testTag(TAG)) {
                        PendingFilePill(
                            fileName = "trip-notes.pdf",
                            mediaType = "application/pdf",
                            sizeBytes = 48_213L,
                            failed = false,
                            statusLabel = "Uploading",
                        )
                        PendingFilePill(
                            fileName = "trail-map.gpx",
                            mediaType = "application/gpx+xml",
                            sizeBytes = 9_004L,
                            failed = true,
                            statusLabel = "Failed",
                            onRetry = {},
                        )
                        VoiceWaveform(
                            bars = bars,
                            progress = 0.4f,
                            playedColor = MaterialTheme.colorScheme.primary,
                            remainingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(240.dp).height(32.dp),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "media-bubble-chrome"
    }
}

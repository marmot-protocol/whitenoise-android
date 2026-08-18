package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.media.MasonryImageLayout
import dev.ipf.whitenoise.android.ui.conversation.messages.VisualMediaFooterFrame
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaAlbumFooterScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun outgoingThreeItemAlbumFooterOccupiesLastTile() {
        val tileColors = listOf(Color(0xFF526A91), Color(0xFF627C4B), Color(0xFF8E5B67))
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    Column(Modifier.padding(24.dp).testTag(TAG)) {
                        VisualMediaFooterFrame(
                            showFooter = true,
                            timeText = "10:42",
                            showStatus = true,
                            status = MessageStatus.Sent,
                            showRetention = false,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.width(312.dp),
                            ) {
                                MasonryImageLayout(visibleCount = 3) { index, tileModifier ->
                                    Box(
                                        modifier = tileModifier.background(tileColors[index]),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = (index + 1).toString(),
                                            color = Color.White,
                                            style = MaterialTheme.typography.headlineMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/media_album_footer_three_items.png")
    }

    private companion object {
        const val TAG = "media-album-footer"
    }
}

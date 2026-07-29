package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerAttachmentPaneBottomCorners
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerAttachmentSheetPane
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
class ComposerAttachmentPaneScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun attachmentPaneCornersLight() {
        render(darkTheme = false, amoled = false)
        capture("composer_attachment_pane_corners_light.png")
    }

    @Test
    fun attachmentPaneCornersDark() {
        render(darkTheme = true, amoled = false)
        capture("composer_attachment_pane_corners_dark.png")
    }

    @Test
    fun attachmentPaneCornersAmoled() {
        render(darkTheme = true, amoled = true)
        capture("composer_attachment_pane_corners_amoled.png")
    }

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier =
                            Modifier
                                .width(360.dp)
                                .padding(vertical = 16.dp)
                                .testTag(TAG),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Square display", modifier = Modifier.padding(horizontal = 16.dp))
                        AttachmentPaneFixture(
                            corners = ComposerAttachmentPaneBottomCorners(start = 0.dp, end = 0.dp),
                        )
                        Text("Rounded display", modifier = Modifier.padding(horizontal = 16.dp))
                        AttachmentPaneFixture(
                            corners = ComposerAttachmentPaneBottomCorners(start = 36.dp, end = 24.dp),
                        )
                    }
                }
            }
        }
    }

    private fun capture(fileName: String) {
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/$fileName")
    }

    private companion object {
        const val TAG = "composer-attachment-pane-corners"
    }
}

@androidx.compose.runtime.Composable
private fun AttachmentPaneFixture(corners: ComposerAttachmentPaneBottomCorners) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        ComposerAttachmentSheetPane(
            alpha = 1f,
            minimumHeight = 0.dp,
            onPickRecentMedia = null,
            onPickFromGallery = null,
            onCaptureFromCamera = null,
            onPickDocument = null,
            onShareLocation = null,
            onShareUser = null,
            onShareContact = null,
            onComingSoon = {},
            bottomCornersOverride = corners,
        )
    }
}

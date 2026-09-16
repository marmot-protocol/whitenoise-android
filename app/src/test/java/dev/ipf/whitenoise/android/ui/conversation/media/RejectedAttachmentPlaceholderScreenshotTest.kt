package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MediaAttachmentRejectionKindFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual regression coverage for attachments MarmotKit rejected while parsing a message. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h240dp-mdpi")
class RejectedAttachmentPlaceholderScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Both explanations render in the light theme. */
    @Test
    fun rejectedAttachmentPlaceholdersLight() {
        composeRule.setContent { WhiteNoiseTheme(darkTheme = false) { PlaceholderPair() } }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/rejected_attachment_placeholders_light.png")
    }

    /** Both explanations render in the dark theme. */
    @Test
    fun rejectedAttachmentPlaceholdersDark() {
        composeRule.setContent { WhiteNoiseTheme(darkTheme = true) { PlaceholderPair() } }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/rejected_attachment_placeholders_dark.png")
    }

    /** Every structural rejection shares the malformed explanation; only unsupported formats differ. */
    @Test
    fun rejectionKindsMapToTwoExplanations() {
        val malformed =
            listOf(
                MediaAttachmentRejectionKindFfi.INVALID_STRUCTURE,
                MediaAttachmentRejectionKindFfi.MISSING_FIELD,
                MediaAttachmentRejectionKindFfi.DUPLICATE_FIELD,
                MediaAttachmentRejectionKindFfi.MALFORMED_FIELD,
            ).map(::rejectedAttachmentDetail).toSet()
        assertEquals(1, malformed.size)
        assertEquals(
            false,
            rejectedAttachmentDetail(MediaAttachmentRejectionKindFfi.UNSUPPORTED_FORMAT) in malformed,
        )
    }
}

/** Renders one placeholder per explanation so a single baseline covers both strings. */
@Composable
private fun PlaceholderPair() {
    Surface {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RejectedAttachmentPlaceholder(kind = MediaAttachmentRejectionKindFfi.UNSUPPORTED_FORMAT)
            RejectedAttachmentPlaceholder(kind = MediaAttachmentRejectionKindFfi.MALFORMED_FIELD)
        }
    }
}

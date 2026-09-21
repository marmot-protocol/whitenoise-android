package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.WhiteNoiseUrls
import dev.ipf.whitenoise.android.share.QrShareCardRenderer
import dev.ipf.whitenoise.android.share.QrShareCardSpec
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w480dp-h540dp-mdpi")
class QrShareCardScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Profile export with a long name and fallback avatar. */
    @Test
    fun profileCardLight() {
        capture(
            fileName = "profile_share_card_long_name_light.png",
            spec =
                QrShareCardSpec(
                    headline = context.getString(R.string.profile_share_card_headline),
                    qrPayload = "marmot://profile/npub1${"q".repeat(58)}?from=qr",
                    displayName = "Ada Lovelace with a deliberately long profile name",
                ),
            dark = false,
            fontScale = 1f,
        )
    }

    /** The fixed invite export remains light and unclipped under dark theme and 200 percent text. */
    @Test
    fun inviteCardDarkLargeText() {
        capture(
            fileName = "invite_share_card_dark_large_text.png",
            spec =
                QrShareCardSpec(
                    headline = context.getString(R.string.invite_to_white_noise),
                    qrPayload = WhiteNoiseUrls.DOWNLOAD,
                ),
            dark = true,
            fontScale = 2f,
        )
    }

    /** Renders the fixed bitmap inside a controlled Compose surface before recording its baseline. */
    private fun capture(
        fileName: String,
        spec: QrShareCardSpec,
        dark: Boolean,
        fontScale: Float,
    ) {
        val bitmap = QrShareCardRenderer.render(spec).asImageBitmap()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = context.getString(R.string.share_profile_picture),
                        modifier = Modifier.size(480.dp, 540.dp).testTag(TAG),
                    )
                }
            }
        }
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$fileName")
    }

    private companion object {
        const val TAG = "qr-share-card"
    }
}

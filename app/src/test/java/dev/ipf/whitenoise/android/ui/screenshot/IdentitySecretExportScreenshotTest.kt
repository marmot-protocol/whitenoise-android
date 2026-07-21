package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.IDENTITY_SECRET_EXPORT_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.settings.IdentitySecretExportContent
import dev.ipf.whitenoise.android.ui.settings.IdentitySecretExportState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class IdentitySecretExportScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun maskedDefaultLight() = capture("identity_secret_export_masked_light", dark = false, amoled = false)

    @Test
    fun maskedDefaultDark() = capture("identity_secret_export_masked_dark", dark = true, amoled = false)

    @Test
    fun maskedDefaultAmoled() = capture("identity_secret_export_masked_amoled", dark = true, amoled = true)

    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                Surface(modifier = Modifier.width(328.dp).padding(16.dp)) {
                    IdentitySecretExportContent(
                        state = IdentitySecretExportState(confirmationVisible = true),
                        secret = "nsec1never-rendered-in-the-masked-default",
                        onToggleReveal = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(IDENTITY_SECRET_EXPORT_CONTENT_TAG)
            .captureRoboImage("src/test/snapshots/$name.png")
    }
}

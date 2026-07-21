package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountKeyPackageFfi
import dev.ipf.whitenoise.android.ui.settings.KEY_PACKAGES_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.settings.KeyPackagesContent
import dev.ipf.whitenoise.android.ui.settings.keyPackagesState
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
class KeyPackagesScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun keyPackagesScreenDefaultDark() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KeyPackagesContent(
                        state =
                            keyPackagesState(
                                hasActiveAccount = true,
                                loaded = true,
                                loading = false,
                                working = false,
                                packageCount = 0,
                            ),
                        packages = emptyList<AccountKeyPackageFfi>(),
                        onBack = {},
                        onRefresh = {},
                        onRepublish = {},
                        onPublishNew = {},
                        onDelete = {},
                        onCopied = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(KEY_PACKAGES_CONTENT_TAG)
            .captureRoboImage("src/test/snapshots/key_packages_screen_default_dark.png")
    }
}

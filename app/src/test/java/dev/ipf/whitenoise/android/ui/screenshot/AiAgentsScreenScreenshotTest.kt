package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.AI_AGENTS_SCREEN_TAG
import dev.ipf.whitenoise.android.ui.settings.AiAgentsContent
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
class AiAgentsScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val previewNpub = "npub1" + "a".repeat(58)

    @Test
    fun aiAgentsScreenLight() = capture("ai_agents_screen_light", dark = false, amoled = false)

    @Test
    fun aiAgentsScreenDark() = capture("ai_agents_screen_dark", dark = true, amoled = false)

    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AiAgentsContent(
                        npub = previewNpub,
                        snackbarHostState = SnackbarHostState(),
                        onCopyPrompt = {},
                        onOpenConnectorDocs = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(AI_AGENTS_SCREEN_TAG)
            .captureRoboImage("src/test/snapshots/$name.png")
    }
}

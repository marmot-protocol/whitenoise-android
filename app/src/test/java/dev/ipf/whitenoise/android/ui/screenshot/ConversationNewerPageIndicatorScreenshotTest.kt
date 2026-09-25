package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.ConversationNewerPageIndicator
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pins the newer-page spinner that sits beside the jump button while a forward page is in flight. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationNewerPageIndicatorScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The jump button's 42 dp footprint around a 34 dp secondary-container disc with a 16 dp spinner. */
    @Test
    fun newerPageIndicatorMatchesTheJumpButtonChrome() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.size(96.dp).testTag(ROOT_TAG)) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ConversationNewerPageIndicator()
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/conversation_newer_page_indicator_dark.png")
    }

    private companion object {
        const val ROOT_TAG = "conversation-newer-page-indicator-root"
    }
}

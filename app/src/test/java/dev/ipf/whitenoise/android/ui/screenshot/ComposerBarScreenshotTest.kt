package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baseline for the composer bar leaf in its idle (empty) and drafted
 * states. App state and the voice controller stay null, so only the pure
 * input surface is pinned.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ComposerBarScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composerBarIdleLight() {
        render(darkTheme = false, draft = "")
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_bar_idle_light.png")
    }

    @Test
    fun composerBarIdleDark() {
        render(darkTheme = true, draft = "")
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_bar_idle_dark.png")
    }

    @Test
    fun composerBarDraftLight() {
        render(darkTheme = false, draft = "Draft message text")
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_bar_draft_light.png")
    }

    private fun render(
        darkTheme: Boolean,
        draft: String,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> },
                        initialDraft = draft,
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "composer-bar"
    }
}
